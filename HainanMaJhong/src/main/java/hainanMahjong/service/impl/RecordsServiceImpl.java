package hainanMahjong.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import hainanMahjong.engine.model.FanType;
import hainanMahjong.common.PageResult;
import hainanMahjong.dto.PageQueryDTO;
import hainanMahjong.mapper.GameRoundsMapper;
import hainanMahjong.mapper.GameSessionsMapper;
import hainanMahjong.mapper.UserMapper;
import hainanMahjong.pojo.User;
import hainanMahjong.service.RecordsService;
import hainanMahjong.vo.recordvo.PlayerTotalVO;
import hainanMahjong.vo.recordvo.RoundPlayerRowVO;
import hainanMahjong.vo.recordvo.RecordDetailItemVO;
import hainanMahjong.vo.recordvo.RecordDetailVO;
import hainanMahjong.vo.recordvo.RecordMeldVO;
import hainanMahjong.vo.recordvo.RecordOverviewVO;
import hainanMahjong.vo.recordvo.RecordParticipantVO;
import hainanMahjong.vo.recordvo.RecordRoundVO;
import hainanMahjong.vo.recordvo.RecordsListVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 战绩查询：
 * <ul>
 *   <li>{@link #selectRecordsList}：房间列表（分页）+ 我在各场的总分与名次；</li>
 *   <li>{@link #selectDetail}：某一场的完整明细（概览 + 每一把 + 本把四家）。</li>
 * </ul>
 *
 * <p>三个 SQL 都在 Mapper 里；本类的职责是"把扁平行装配成前端要的视图"。</p>
 */
@Service
@Slf4j
public class RecordsServiceImpl implements RecordsService {

    private final GameSessionsMapper gameSessionsMapper;
    private final GameRoundsMapper gameRoundsMapper;
    private final UserMapper userMapper;
    private final ObjectMapper mapper = new ObjectMapper();

    public RecordsServiceImpl(GameSessionsMapper gameSessionsMapper,
                              GameRoundsMapper gameRoundsMapper,
                              UserMapper userMapper) {
        this.gameSessionsMapper = gameSessionsMapper;
        this.gameRoundsMapper = gameRoundsMapper;
        this.userMapper = userMapper;
    }

    // ==================== 列表页 ====================

    @Override
    public PageResult<RecordsListVO> selectRecordsList(long userId, PageQueryDTO dto) {
        // ① 分页查房间列表（插件改 SQL 加 LIMIT，并回填 total）
        Page<RecordsListVO> page = new Page<>(dto.getPageNum(), dto.getPageSize());
        IPage<RecordsListVO> result = gameSessionsMapper.listSessionsForUser(page, userId);
        List<RecordsListVO> records = result.getRecords();

        // ② 空页直接返回（避免 IN () 这种非法 SQL）
        if (!records.isEmpty()) {
            List<Long> sessionIds = new ArrayList<>(records.size());
            for (RecordsListVO r : records) {
                sessionIds.add(r.getSessionId());
            }
            // 昵称缓存：这一页里同一个人会出现很多次，一次请求只查一次库
            Map<Long, String> nicks = new HashMap<>();
            fillPlayers(records, nicks);
            fillScoreAndRank(userId, records, sessionIds);
        }

        PageResult<RecordsListVO> pr = new PageResult<>();
        pr.setTotal(result.getTotal());
        pr.setPageNum(result.getCurrent());
        pr.setPageSize(result.getSize());
        pr.setPages(result.getPages());
        pr.setRecords(records);
        return pr;
    }

    /**
     * 把每场的 player_ids（JSON 数组，按座位 东→南→西→北）翻译成四个显示名。
     *
     * <p>名字来源用 {@code game_sessions.player_ids} 而不是 game_round_scores：
     * 前者是"这一场有哪四个人"的权威记录，写在对局开始那一刻；
     * 后者要有小局结算才有行，刚开的房间会一个名字都列不出来。</p>
     *
     * <p>解析失败（脏数据）就留空，不能让整个战绩页 500。</p>
     */
    private void fillPlayers(List<RecordsListVO> records, Map<Long, String> nicks) {
        for (RecordsListVO record : records) {
            String raw = record.getPlayerIds();
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            try {
                List<?> ids = mapper.readValue(raw, List.class);
                List<String> names = new ArrayList<>(ids.size());
                for (Object id : ids) {
                    if (!(id instanceof Number)) {
                        continue;
                    }
                    String n = nick(((Number) id).longValue(), nicks);
                    if (n != null && !n.isEmpty()) {
                        names.add(n);
                    }
                }
                record.setPlayers(names);
            } catch (Exception e) {
                log.warn("解析 player_ids 失败（session={}）：{}", record.getSessionId(), e.getMessage());
            }
        }
    }

    /** 一次查出这一页所有房间的玩家总分，再按各自的 sessionId 回填。 */
    private void fillScoreAndRank(long userId, List<RecordsListVO> records, List<Long> sessionIds) {
        List<PlayerTotalVO> rows = gameSessionsMapper.selectTotals(sessionIds);

        Map<Long, List<PlayerTotalVO>> bySession = new HashMap<>();
        for (PlayerTotalVO t : rows) {
            bySession.computeIfAbsent(t.getSessionId(), k -> new ArrayList<>()).add(t);
        }

        for (RecordsListVO record : records) {
            List<PlayerTotalVO> totals = bySession.getOrDefault(record.getSessionId(), Collections.emptyList());
            record.setTotalScore(myTotalOf(totals, userId));
            record.setRank(computeRank(totals, userId));
        }
    }

    /** 我在这一场的累计得分；不在场（异常数据）时为 0。 */
    private int myTotalOf(List<PlayerTotalVO> totals, long userId) {
        for (PlayerTotalVO t : totals) {
            if (Objects.equals(t.getUserId(), userId)) {
                return t.getTotalScore();
            }
        }
        return 0;
    }

    /** 本场名次 1~4：标准竞赛排名（并列同名、后续跳号）。 */
    private int computeRank(List<PlayerTotalVO> totals, long userId) {
        int myTotal = myTotalOf(totals, userId);
        int rank = 1;
        for (PlayerTotalVO t : totals) {
            if (!Objects.equals(t.getUserId(), userId) && t.getTotalScore() > myTotal) {
                rank++;
            }
        }
        return rank;
    }

    // ==================== 详情页 ====================

    @Override
    public RecordDetailVO selectByRoom(String roomId, Long userId, boolean needNotFoundFlag) {
        Long sessionId = gameSessionsMapper.selectSessionIdByRoomId(roomId);
        if (sessionId == null) {
            return notFoundDetail();
        }
        return selectDetail(sessionId, userId, false);
    }

    @Override
    public RecordDetailVO selectDetail(long sessionId, Long userId, boolean needNotFoundFlag) {
        // ① 房间码（顺带校验 session 是否存在：-1 是"未创建"的哨兵值）
        String roomId = sessionId > 0 ? gameSessionsMapper.selectRoomIdById(sessionId) : null;
        if (roomId == null && needNotFoundFlag) {
            return notFoundDetail();
        }

        // ② 本场每一把 × 四家（一次查询）
        List<RoundPlayerRowVO> rows = gameRoundsMapper.selectRoundsAll(sessionId);

        // ③ 本请求昵称缓存（同一 userId 只查一次库）
        Map<Long, String> nicks = new HashMap<>();

        RecordDetailVO vo = new RecordDetailVO();
        vo.setRoomId(roomId);
        vo.setOverview(buildOverview(userId, totalsOf(sessionId), rows, nicks));
        vo.setRounds(buildRounds(userId, rows, nicks));
        return vo;
    }

    /** 查不到房间时的返回（/byRoom 用）。 */
    private RecordDetailVO notFoundDetail() {
        RecordDetailVO vo = new RecordDetailVO();
        vo.setNotFound(true);
        vo.setOverview(emptyOverview());
        vo.setRounds(Collections.emptyList());
        return vo;
    }

    /** 单场四家总分（复用批量方法，只是传一个元素的列表）。 */
    private Collection<PlayerTotalVO> totalsOf(long sessionId) {
        Map<Long, PlayerTotalVO> out = new HashMap<>();
        for (PlayerTotalVO t : gameSessionsMapper.selectTotals(Collections.singletonList(sessionId))) {
            out.put(t.getUserId(), t);
        }
        return out.values();
    }

    private RecordOverviewVO buildOverview(long userId, Collection<PlayerTotalVO> totalsList,
                                           List<RoundPlayerRowVO> rows, Map<Long, String> nicks) {
        // 玩家总分（补 nickname）
        List<PlayerTotalVO> players = new ArrayList<>(totalsList.size());
        for (PlayerTotalVO t : totalsList) {
            t.setNickname(nick(t.getUserId(), nicks));
            players.add(t);
        }

        // 我胡了几次 + 我最高番（一次遍历同时算出）
        int winCount = 0;
        int highestFan = 0;
        String highestFanDesc = "";
        for (RoundPlayerRowVO r : rows) {
            if (!Objects.equals(r.getUserId(), userId) || !r.isWinner()) {
                continue;
            }
            winCount++;
            int totalFan = r.getTotalFan() == null ? 0 : r.getTotalFan();
            if (totalFan > highestFan) {
                highestFan = totalFan;
                List<String> fanTypes = parseFanTypes(r.getFanInfo());
                highestFanDesc = fanTypes.isEmpty() ? "平胡" : String.join("、", fanTypes);
            }
        }

        RecordOverviewVO ov = new RecordOverviewVO();
        ov.setWinCount(winCount);
        ov.setHighestFan(highestFanDesc.isEmpty() ? "无" : highestFanDesc);
        ov.setPlayers(players);
        return ov;
    }

    /** 查不到房间时的空概览。 */
    private RecordOverviewVO emptyOverview() {
        RecordOverviewVO ov = new RecordOverviewVO();
        ov.setWinCount(0);
        ov.setHighestFan("无");
        ov.setPlayers(Collections.emptyList());
        return ov;
    }

    /**
     * 每一把：先按 roundNum 分组，再逐把组装。
     *
     * <p>★ 关键：局级字段在同一把的多行里是重复的，所以取 {@code group.get(0)}；
     * 人级字段每行不同，逐行转成 participant。</p>
     */
    private List<RecordRoundVO> buildRounds(long userId, List<RoundPlayerRowVO> rows,
                                            Map<Long, String> nicks) {
        // ① 分组（TreeMap 保证局数升序）
        Map<Integer, List<RoundPlayerRowVO>> byRound = new TreeMap<>();
        for (RoundPlayerRowVO row : rows) {
            byRound.computeIfAbsent(row.getRoundNum(), k -> new ArrayList<>()).add(row);
        }

        List<RecordRoundVO> rounds = new ArrayList<>(byRound.size());
        for (Map.Entry<Integer, List<RoundPlayerRowVO>> e : byRound.entrySet()) {
            List<RoundPlayerRowVO> group = e.getValue();
            RoundPlayerRowVO first = group.get(0);

            RecordRoundVO round = new RecordRoundVO();
            round.setRoundNum(e.getKey());
            round.setDraw(first.isDraw());
            round.setWinType(first.getWinType() == null ? 2 : first.getWinType());
            round.setWinnerId(first.getWinnerId());
            round.setLoserId(first.getLoserId());
            round.setWinTile(first.getWinTile());
            round.setFanTypes(parseFanTypes(first.getFanInfo()));
            round.setDealerSeat(first.getDealerName());
            round.setWindIdx(first.getWindIdx());

            Map<String, Object> wh = parseWinHand(first.getWinHand());
            round.setWinHand(toIntList(wh.get("hand")));
            round.setWinMelds(toMeldVOList(wh.get("melds")));

            // ② 人级：每行一位玩家
            List<RecordParticipantVO> ps = new ArrayList<>(group.size());
            for (RoundPlayerRowVO row : group) {
                RecordParticipantVO p = new RecordParticipantVO();
                p.setUserId(row.getUserId());
                p.setNickname(nick(row.getUserId(), nicks));
                p.setSeat(row.getSeat());
                p.setScoreChange(row.getScoreChange());
                p.setWinner(row.isWinner());
                p.setMe(Objects.equals(row.getUserId(), userId));
                p.setDetail(parseDetailObj(row.getDetail()));
                ps.add(p);
            }
            round.setParticipants(ps);

            // ③ 昵称优先从本把 participants 里取（数据已在内存，省查库）
            round.setWinner(nickByIdIn(ps, first.getWinnerId(), nicks));
            round.setLoser(nickByIdIn(ps, first.getLoserId(), nicks));
            round.setDealerNickname(nickBySeat(ps, first.getDealerName()));

            rounds.add(round);
        }
        return rounds;
    }

    // ==================== 辅助：昵称 ====================

    /** 带缓存的昵称查询：同一 userId 本次请求只查一次库。 */
    private String nick(Long userId, Map<Long, String> cache) {
        if (userId == null) {
            return null;
        }
        return cache.computeIfAbsent(userId, this::nickname);
    }

    /**
     * 昵称解析：机器人用固定名、游客用「游客」、真实用户查 user 表。
     *
     * <p>⚠️ {@code selectById} 可能返回 <b>null</b>，必须判空。历史上这里直接
     * {@code selectById(id).getNickname()}，一旦某个 userId 在 user 表里没有对应行
     * （账号被删、或是某种正数 id 的机器人），整个战绩明细就 NPE → 500，
     * 而且报错栈只指向 Tomcat 的错误页，看不出是哪一步。
     * 查不到就退化成「玩家{id}」，不要把整页拖垮。</p>
     */
    private String nickname(Long userId) {
        if (userId < 0) {
            return switch (userId.intValue()) {
                case -1 -> "电脑·南";
                case -2 -> "电脑·西";
                case -3 -> "电脑·北";
                default -> "电脑";
            };
        }
        if (userId == 0) {
            return "游客";
        }
        User u = userMapper.selectById(userId);
        if (u == null) {
            return "玩家" + userId;
        }
        String name = u.getNickname();
        if (name == null || name.isEmpty()) {
            name = u.getUsername();
        }
        return name == null || name.isEmpty() ? "玩家" + userId : name;
    }

    /** 从本把参与者里按 userId 取昵称；取不到再回源。 */
    private String nickByIdIn(List<RecordParticipantVO> ps, Long id, Map<Long, String> nicks) {
        if (id == null) {
            return null;
        }
        for (RecordParticipantVO p : ps) {
            if (Objects.equals(p.getUserId(), id)) {
                return p.getNickname();
            }
        }
        return nick(id, nicks);
    }

    /** 座位名（EAST/SOUTH/WEST/NORTH）→ 该座位玩家昵称。 */
    private String nickBySeat(List<RecordParticipantVO> ps, String seatName) {
        if (seatName == null) {
            return null;
        }
        int ord = SEAT_ORDER.indexOf(seatName);
        if (ord < 0) {
            return null;
        }
        for (RecordParticipantVO p : ps) {
            if (p.getSeat() == ord) {
                return p.getNickname();
            }
        }
        return null;
    }

    private static final List<String> SEAT_ORDER = List.of("EAST", "SOUTH", "WEST", "NORTH");

    // ==================== 辅助：JSON 解析 ====================

    /** {@code fan_info} JSON（{@code {番型枚举名: 倍数}}）→ 中文番型列表。 */
    private List<String> parseFanTypes(String json) {
        List<String> names = new ArrayList<>();
        if (json == null || json.isEmpty() || "{}".equals(json)) {
            return names;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = mapper.readValue(json, Map.class);
            for (String key : map.keySet()) {
                try {
                    names.add(FanType.valueOf(key).display);
                } catch (Exception e) {
                    names.add(key);
                }
            }
        } catch (Exception e) {
            // 解析失败按"无番"处理
        }
        return names;
    }

    /** {@code win_hand} JSON（{@code {hand:[...], melds:[{type,tiles}]}}）→ Map。 */
    private Map<String, Object> parseWinHand(String json) {
        Map<String, Object> result = new HashMap<>();
        result.put("hand", new ArrayList<Integer>());
        result.put("melds", new ArrayList<Map<String, Object>>());
        if (json == null || json.isEmpty()) {
            return result;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = mapper.readValue(json, Map.class);
            if (obj.get("hand") != null) {
                result.put("hand", obj.get("hand"));
            }
            if (obj.get("melds") != null) {
                result.put("melds", obj.get("melds"));
            }
        } catch (Exception e) {
            // 解析失败返回空
        }
        return result;
    }

    /** {@code detail} JSON（{@code {notes:[], flowers:[], gangs:[{type,tiles}]}}）→ VO。 */
    private RecordDetailItemVO parseDetailObj(String json) {
        RecordDetailItemVO vo = new RecordDetailItemVO();
        vo.setNotes(new ArrayList<>());
        vo.setFlowers(new ArrayList<>());
        vo.setGangs(new ArrayList<>());
        if (json == null || json.trim().isEmpty()) {
            return vo;
        }
        try {
            String s = json.trim();
            if (s.startsWith("[")) {
                // 旧格式：直接是字符串数组（并入 notes）
                @SuppressWarnings("unchecked")
                List<String> arr = mapper.readValue(s, List.class);
                vo.setNotes(arr);
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> obj = mapper.readValue(s, Map.class);
                if (obj.get("notes") != null) {
                    vo.setNotes(toStringList(obj.get("notes")));
                }
                if (obj.get("flowers") != null) {
                    vo.setFlowers(toStringList(obj.get("flowers")));
                }
                if (obj.get("gangs") != null) {
                    vo.setGangs(toMeldVOList(obj.get("gangs")));
                }
            }
        } catch (Exception e) {
            // 解析失败返回空明细
        }
        return vo;
    }

    /** 任意 JSON 数组 → {@code List<Integer>}（非数字元素跳过）。 */
    private List<Integer> toIntList(Object raw) {
        List<Integer> out = new ArrayList<>();
        if (!(raw instanceof List<?> arr)) {
            return out;
        }
        for (Object o : arr) {
            if (o instanceof Number n) {
                out.add(n.intValue());
            }
        }
        return out;
    }

    /** 任意 JSON 数组 → {@code List<String>}。 */
    private List<String> toStringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (!(raw instanceof List<?> arr)) {
            return out;
        }
        for (Object o : arr) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    /** 副露数组（{@code [{type,tiles}]}）→ {@code List<RecordMeldVO>}。 */
    private List<RecordMeldVO> toMeldVOList(Object raw) {
        List<RecordMeldVO> out = new ArrayList<>();
        if (!(raw instanceof List<?> arr)) {
            return out;
        }
        for (Object o : arr) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            RecordMeldVO vo = new RecordMeldVO();
            vo.setType(m.get("type") == null ? null : String.valueOf(m.get("type")));
            vo.setTiles(toIntList(m.get("tiles")));
            out.add(vo);
        }
        return out;
    }
}
