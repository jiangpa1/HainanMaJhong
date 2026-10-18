package hainanMahjong.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import hainanMahjong.exception.BusinessException;
import hainanMahjong.mapper.GameSessionsMapper;
import hainanMahjong.mapper.RecordsV2Mapper;
import hainanMahjong.pojo.GameSessions;
import hainanMahjong.vo.recordvo.*;
import hainanMahjong.vo.recordvo.RecordsV2VO.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RecordsV2Service {
    private RecordProjection projection;
    private RecordDetailCache detailCache;
    @org.springframework.beans.factory.annotation.Value("${records.p2.cache.enabled:true}")
    private boolean cacheEnabled = true;
    @org.springframework.beans.factory.annotation.Autowired
    public void setP2(RecordProjection projection, RecordDetailCache detailCache) {
        this.projection=projection; this.detailCache=detailCache;
    }
    private final GameSessionsMapper sessions;
    private final RecordsV2Mapper queries;
    private final RecordsServiceImpl legacy;
    private final ObjectMapper json = new ObjectMapper();
    private final Object[] aggregateLocks = java.util.stream.IntStream.range(0,64).mapToObj(i -> new Object()).toArray();
    // 只保存数值统计和最高番描述，不保存个人昵称/权限。每次读之前必须 authorized。
    // 上限 512 场，60 秒固定寿命；新版本只聚合新增小局，冷启动/回读旧版本仍由 SQL 聚合。
    private record Aggregate(int cutoff, long revision, long createdAt, List<Stats> players) {}
    private final Map<Long, Aggregate> aggregates = Collections.synchronizedMap(
            new LinkedHashMap<Long, Aggregate>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Long, Aggregate> entry) { return size() > 512; }
            });

    public RecordsV2Service(GameSessionsMapper sessions, RecordsV2Mapper queries, RecordsServiceImpl legacy) {
        this.sessions = sessions;
        this.queries = queries;
        this.legacy = legacy;
    }

    public Map<String, Object> list(long userId, int pageNum, int pageSize, Long readThroughSessionId) {
        range(pageNum, 1, 100000, "pageNum");
        range(pageSize, 1, 20, "pageSize");
        boolean projected = projection != null && projection.reads();
        long latest = projected ? projection.latestSession(userId) : queries.latestSession(userId);
        long cutoff = readThroughSessionId == null ? latest : readThroughSessionId;
        if (cutoff < 0 || cutoff > latest) throw bad("readThroughSessionId 不合法");
        long total = projected ? projection.count(userId, cutoff) : queries.sessionCount(userId, cutoff);
        List<RecordsListVO> rows = projected ? projection.sessions(userId, cutoff, pageSize, (long) (pageNum - 1) * pageSize)
                : queries.sessions(userId, cutoff, pageSize, (long) (pageNum - 1) * pageSize);
        Map<Long, List<Long>> participantIds = new HashMap<>();
        for (RecordsListVO row : rows) {
            GameSessions metadata = new GameSessions(); metadata.setPlayerIds(row.getPlayerIds());
            List<Long> ids = playerIds(metadata);
            if (!ids.contains(userId)) throw new BusinessException(403, "只能查看自己参与的战绩");
            participantIds.put(row.getSessionId(), ids);
        }
        // 保持旧列表的批量聚合方式，避免分页 20 场引入逐场查询/最高番查询的 N+1 开销。
        Map<Long, Map<Long, Long>> totals = new HashMap<>();
        List<Long> sessionIds = rows.stream().map(RecordsListVO::getSessionId).toList();
        if (projected) totals = projection.totals(sessionIds);
        else if (!rows.isEmpty()) for (PlayerTotalVO player : sessions.selectTotals(sessionIds))
            totals.computeIfAbsent(player.getSessionId(), k -> new HashMap<>()).put(player.getUserId(), (long) player.getTotalScore());
        Map<Long, String> names = legacy.nicknames(participantIds.values().stream().flatMap(List::stream).toList());
        for (RecordsListVO row : rows) {
            Map<Long, Long> scores = totals.getOrDefault(row.getSessionId(), Map.of());
            long mine = scores.getOrDefault(userId, 0L);
            row.setTotalScore(Math.toIntExact(mine));
            row.setRank(1 + (int) scores.entrySet().stream().filter(p -> p.getKey() != userId && p.getValue() > mine).count());
            row.setPlayers(participantIds.get(row.getSessionId()).stream().map(id -> names.computeIfAbsent(id, legacy::nickname)).toList());
        }
        return Map.of("records", rows, "total", total, "pageNum", pageNum, "pageSize", pageSize,
                "pages", (total + pageSize - 1) / pageSize, "readThroughSessionId", cutoff);
    }

    public Overview byRoom(String roomId, long userId) {
        if (roomId == null || !roomId.matches("[0-9]{6}")) throw bad("roomId 应为六位数字");
        Long id = sessions.selectSessionIdByRoomId(roomId);
        if (id == null) throw new BusinessException(404, "战绩不存在");
        return overview(id, userId, null);
    }

    public Overview overview(long sessionId, long userId, Integer readThroughRoundNum) {
        GameSessions session = authorized(sessionId, userId);
        int latest = queries.latestRound(sessionId);
        int cutoff = cutoff(readThroughRoundNum, latest);
        List<Stats> players = stats(session, cutoff, new HashMap<>());
        Stats mine = players.stream().filter(p -> p.getUserId() == userId).findFirst().orElseThrow();
        Overview out = new Overview();
        out.setSessionId(sessionId);
        out.setRoomId(session.getRoomId());
        out.setStatus(session.getStatus());
        out.setLatestRoundNum(latest);
        out.setReadThroughRoundNum(cutoff);
        out.setVersion(version(sessionId, cutoff));
        out.setPlayers(players);
        out.setTotalScore(mine.getTotalScore());
        out.setRank(mine.getRank());
        out.setWinCount(mine.getWinCount());
        out.setHighestFanValue(mine.getHighestFanValue());
        out.setHighestFan(mine.getHighestFan() == null ? "无" : mine.getHighestFan());
        return out;
    }

    /** afterRoundNum=上页最后局号；增量请求不传 cutoff，捕获新的截止点后继续按相同截止点翻页。 */
    public RoundPage rounds(long sessionId, long userId, int afterRoundNum, int pageSize, Integer readThroughRoundNum) {
        authorized(sessionId, userId);
        range(pageSize, 1, 50, "pageSize");
        range(afterRoundNum, 0, Integer.MAX_VALUE, "afterRoundNum");
        int cutoff = cutoff(readThroughRoundNum, queries.latestRound(sessionId));
        if (afterRoundNum > cutoff) throw bad("afterRoundNum 不能超过读取截止局号");
        List<RoundKey> keys = queries.keys(sessionId, afterRoundNum, cutoff, pageSize + 1);
        boolean hasMore = keys.size() > pageSize;
        List<Long> ids = keys.stream().limit(pageSize).map(RoundKey::getId).toList();
        List<RoundSummary> rounds = new ArrayList<>();
        if (!ids.isEmpty()) {
            rounds = queries.summaries(sessionId, ids);
            Map<Long, List<Score>> scores = new HashMap<>();
            List<Score> allScores = queries.scores(ids);
            Map<Long, String> names = legacy.nicknames(allScores.stream().map(Score::getUserId).toList());
            for (Score score : allScores) {
                scores.computeIfAbsent(score.getRoundId(), k -> new ArrayList<>()).add(score);
            }
            if (rounds.size() != ids.size()) throw new BusinessException(500, "小局数据不完整");
            for (RoundSummary round : rounds) {
                List<Score> players = scores.getOrDefault(round.getRoundId(), List.of());
                complete(players.stream().map(Score::getUserId).toList(), players.stream().map(Score::getSeat).toList());
                for (Score score : players) {
                    score.setNickname(names.computeIfAbsent(score.getUserId(), legacy::nickname));
                    score.setMe(score.getUserId() == userId);
                }
                round.setParticipants(players);
            }
        }
        RoundPage out = new RoundPage();
        out.setSessionId(sessionId);
        out.setReadThroughRoundNum(cutoff);
        out.setVersion(version(sessionId, cutoff));
        out.setRounds(rounds);
        out.setHasMore(hasMore);
        out.setPageSize(pageSize);
        out.setNextAfterRoundNum(rounds.isEmpty() ? afterRoundNum : rounds.get(rounds.size() - 1).getRoundNum());
        return out;
    }

    public Map<String, Object> detail(long sessionId, long userId, int roundNum, Integer readThroughRoundNum) {
        authorized(sessionId, userId);
        range(roundNum, 1, Integer.MAX_VALUE, "roundNum");
        int cutoff = cutoff(readThroughRoundNum, queries.latestRound(sessionId));
        if (roundNum > cutoff) throw new BusinessException(404, "该小局不在读取范围内");
        long revision = projection != null && projection.writes() ? projection.revision(sessionId) : 0;
        List<RoundPlayerRowVO> rows = detailCache != null && cacheEnabled && revision > 0
                ? detailCache.get(new RecordDetailCache.Key(sessionId,roundNum,revision), () -> queries.detail(sessionId,roundNum,cutoff))
                : queries.detail(sessionId, roundNum, cutoff);
        if (rows.isEmpty()) throw new BusinessException(404, "小局不存在");
        complete(rows.stream().map(RoundPlayerRowVO::getUserId).toList(), rows.stream().map(RoundPlayerRowVO::getSeat).toList());
        return Map.of("sessionId", sessionId, "readThroughRoundNum", cutoff, "version", version(sessionId, cutoff),
                "round", legacy.buildRounds(userId, rows, legacy.nicknames(rows.stream().map(RoundPlayerRowVO::getUserId).toList())).get(0));
    }

    private GameSessions authorized(long sessionId, long userId) {
        if (sessionId <= 0) throw bad("sessionId 必须为正数");
        GameSessions session = sessions.selectById(sessionId);
        if (session == null) throw new BusinessException(404, "战绩不存在");
        if (!playerIds(session).contains(userId)) throw new BusinessException(403, "只能查看自己参与的战绩");
        return session;
    }

    private List<Long> playerIds(GameSessions session) {
        try {
            List<?> raw = json.readValue(session.getPlayerIds(), List.class);
            List<Long> ids = raw.stream().map(id -> ((Number) id).longValue()).toList();
            if (ids.size() != 4 || new HashSet<>(ids).size() != 4) throw new IllegalArgumentException();
            return ids;
        } catch (Exception e) {
            throw new BusinessException(500, "会话参与者数据不完整");
        }
    }

    private List<Stats> stats(GameSessions session, int cutoff, Map<Long, String> names) {
        Map<Long, Stats> totals = new HashMap<>();
        if (cutoff > 0) for (Stats stats : aggregate(session.getId(), cutoff)) totals.put(stats.getUserId(), stats);
        List<Stats> out = new ArrayList<>(4);
        List<Long> ids = playerIds(session);
        names.putAll(legacy.nicknames(ids));
        for (int seat = 0; seat < ids.size(); seat++) {
            long id = ids.get(seat);
            Stats stats = totals.getOrDefault(id, new Stats());
            if (stats.getHighestFan() == null) stats.setHighestFan("无");
            stats.setUserId(id);
            if (!totals.containsKey(id)) stats.setSeat(seat);
            stats.setNickname(names.computeIfAbsent(id, legacy::nickname));
            out.add(stats);
        }
        for (Stats stats : out) stats.setRank(1 + (int) out.stream().filter(p -> p.getTotalScore() > stats.getTotalScore()).count());
        return out;
    }

    private List<Stats> aggregate(long sessionId, int cutoff) {
        synchronized(aggregateLocks[Math.floorMod(Long.hashCode(sessionId),aggregateLocks.length)]) {
            return aggregateLocked(sessionId,cutoff);
        }
    }

    private List<Stats> aggregateLocked(long sessionId, int cutoff) {
        if (projection != null && projection.reads()) {
            List<Stats> stored = projection.stats(sessionId,cutoff);
            if (stored != null) {
                for (Stats s : stored) {
                    List<String> fans = legacy.parseFanTypes(s.getHighestFan());
                    s.setHighestFan(s.getHighestFanValue()>0 ? (fans.isEmpty()?"平胡":String.join("、",fans)) : "无");
                }
                return stored;
            }
        }
        long now = System.currentTimeMillis();
        long revision = projection != null && projection.writes() ? projection.revision(sessionId) : 0;
        Aggregate cached = aggregates.get(sessionId);
        boolean reusable = cacheEnabled && cached != null && cached.revision() == revision && now - cached.createdAt() < 60000 && cached.cutoff() <= cutoff;
        if (reusable && cached.cutoff() == cutoff) return cached.players().stream().map(this::copyStats).toList();
        Map<Long, Stats> merged = new HashMap<>();
        if (reusable) for (Stats stats : cached.players()) merged.put(stats.getUserId(), copyStats(stats));
        int after = reusable ? cached.cutoff() : 0;
        for (Stats delta : queries.stats(sessionId, after, cutoff)) {
            Stats total = merged.get(delta.getUserId());
            if (delta.getHighestFanValue() > 0 && (total == null || delta.getHighestFanValue() > total.getHighestFanValue())) {
                List<String> fans = legacy.parseFanTypes(queries.highestFan(sessionId, after, cutoff, delta.getUserId()));
                delta.setHighestFan(fans.isEmpty() ? "平胡" : String.join("、", fans));
            }
            if (total == null) merged.put(delta.getUserId(), copyStats(delta));
            else {
                total.setSeat(Math.max(total.getSeat(), delta.getSeat()));
                total.setTotalScore(total.getTotalScore() + delta.getTotalScore());
                total.setWinCount(total.getWinCount() + delta.getWinCount());
                if (delta.getHighestFanValue() > total.getHighestFanValue()) total.setHighestFan(delta.getHighestFan());
                total.setHighestFanValue(Math.max(total.getHighestFanValue(), delta.getHighestFanValue()));
            }
        }
        List<Stats> values = new ArrayList<>(merged.values());
        // 旧截止点不能覆盖较新缓存；并发请求即使竞争，也只缓存完整且不可变的截止点。
        synchronized (aggregates) {
            Aggregate current = aggregates.get(sessionId);
            int descriptionBytes = values.stream().mapToInt(s -> s.getHighestFan()==null?0:s.getHighestFan().length()*2).sum();
            if (cacheEnabled && descriptionBytes <= 8192 && (current == null || current.revision() != revision || now - current.createdAt() >= 60000 || current.cutoff() <= cutoff))
                aggregates.put(sessionId, new Aggregate(cutoff, revision, reusable ? cached.createdAt() : now,
                        values.stream().map(this::copyStats).toList()));
        }
        return values;
    }

    private Stats copyStats(Stats source) {
        Stats out = new Stats();
        out.setUserId(source.getUserId());
        out.setSeat(source.getSeat());
        out.setTotalScore(source.getTotalScore());
        out.setWinCount(source.getWinCount());
        out.setHighestFanValue(source.getHighestFanValue());
        out.setHighestFan(source.getHighestFan());
        return out;
    }

    private int cutoff(Integer value, int latest) {
        int cutoff = value == null ? latest : value;
        range(cutoff, 0, latest, "readThroughRoundNum");
        return cutoff;
    }

    private String version(long sessionId, int cutoff) {
        return sessionId + ":" + cutoff + (projection != null && projection.writes() ? ":" + projection.revision(sessionId) : "");
    }
    public void invalidate(long sessionId) {
        aggregates.remove(sessionId);
        if(detailCache != null) detailCache.invalidate(sessionId);
    }
    private void complete(List<Long> ids, List<Integer> seats) {
        if (ids.size() != 4 || ids.contains(null) || new HashSet<>(ids).size() != 4 || !new HashSet<>(seats).equals(Set.of(0,1,2,3)))
            throw new BusinessException(500, "小局四家分数不完整");
    }
    private void range(int value, int min, int max, String name) {
        if (value < min || value > max) throw bad(name + " 应在 " + min + "~" + max + " 之间");
    }
    private BusinessException bad(String message) { return new BusinessException(400, message); }
}
