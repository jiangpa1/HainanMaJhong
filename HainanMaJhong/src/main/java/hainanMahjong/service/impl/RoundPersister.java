package hainanMahjong.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import hainanMahjong.mapper.GameRoundsMapper;
import hainanMahjong.mapper.GameSessionsMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 对局落库：创建整场 session、保存每一把（含四家流水）。
 *
 * <p>从 {@code MysqlService} 迁出的写入部分，改走 MyBatis Mapper。</p>
 *
 * <p><b>为什么单独一个类</b>：{@code saveHand} 要跨三张表写（game_rounds /
 * game_round_scores / game_sessions），必须在一个事务里；把它留在 mapper 里就没有事务边界了。</p>
 *
 * <p><b>⚠️ 两个刻意的取舍</b>：</p>
 * <ul>
 *   <li><b>JSON 列不走实体</b>：{@code win_hand} / {@code fan_info} / {@code detail} 都是 JSON 列，
 *       这里<b>直接传 JSON 字符串</b>。走 {@code GameRounds}/{@code GameRoundScores} 实体会因为
 *       MyBatis-Plus 3.5.5 没有默认的 {@code Map → JSON} TypeHandler，把 {@code {"a":1}} 存成
 *       {@code {a=1}}，战绩页解析直接失败。</li>
 *   <li><b>0/1 而非 boolean</b>：{@code is_draw} / {@code is_winner} 传 0/1，
 *       与原 {@code MysqlService} 的写法一致，避免驱动层的转换差异。</li>
 * </ul>
 */
@Service
public class RoundPersister {

    private final GameSessionsMapper sessions;
    private final GameRoundsMapper rounds;
    private final ObjectMapper mapper = new ObjectMapper();

    public RoundPersister(GameSessionsMapper sessions, GameRoundsMapper rounds) {
        this.sessions = sessions;
        this.rounds = rounds;
    }

    /**
     * 创建整场对局，返回 session id。
     *
     * @param playerIds 四个座位的 userId（机器人是负数），序列化成 JSON 存进 {@code player_ids}
     */
    public long createSession(String roomId, long creatorId, List<Long> playerIds) {
        String json;
        try {
            json = mapper.writeValueAsString(playerIds);
        } catch (Exception e) {
            json = "[]";
        }
        GameSessionsMapper.SessionInsert row = new GameSessionsMapper.SessionInsert();
        sessions.insertSession(row, roomId, creatorId, json);
        return row.id == null ? -1L : row.id;
    }

    /** 玩家中途退出：标记该场为异常解散。 */
    public void disbandSession(long sessionId) {
        sessions.disbandSession(sessionId);
    }

    /**
     * 事务内保存一把对局及其四家流水（<b>幂等</b>）。
     *
     * <p>同一 {@code (session_id, round_num)} 已存在（服务重启后重放同把）时，
     * 复用其 round 并<b>覆盖</b>该把流水 —— 先清后写，避免撞唯一键 {@code uk_round_user}
     * 或残留半截数据；最后推进 {@code game_sessions.current_round}。</p>
     *
     * @param winHandJson  {@code win_hand} 的 JSON 字符串（可为 null）
     * @param fanInfoJson  {@code fan_info} 的 JSON 字符串（流局时通常是 {@code "{}"}）
     * @return 本把的 round id
     */
    @Transactional
    public long saveHand(long sessionId, int roundNum, boolean isDraw, int winType,
                         Long winnerId, Long loserId, Integer winTile,
                         String winHandJson, String fanInfoJson, int totalFan,
                         String dealerName, int windIdx,
                         List<GameRoundsMapper.ScoreRow> scores) {
        // ① 找/建本把
        Long roundId = rounds.findRoundId(sessionId, roundNum);
        if (roundId == null) {
            GameRoundsMapper.RoundInsert row = new GameRoundsMapper.RoundInsert();
            row.sessionId = sessionId;
            row.roundNum = roundNum;
            row.isDraw = isDraw ? 1 : 0;
            row.winType = winType;
            row.winnerId = winnerId;
            row.loserId = loserId;
            row.winTile = winTile;
            row.winHandJson = winHandJson;
            row.fanInfoJson = fanInfoJson;
            row.totalFan = totalFan;
            row.dealer = dealerName;
            row.wind = windIdx;
            rounds.insertRound(row);
            roundId = row.id;
        } else {
            // 续跑重放同把：清掉可能残留的半截流水，按最新结算整把重写
            rounds.deleteScoresByRound(roundId);
        }

        // ② 写四家流水
        if (scores != null) {
            for (GameRoundsMapper.ScoreRow sc : scores) {
                rounds.insertScore(roundId, sc.userId(), sc.seat(), sc.change(),
                        sc.winner() ? 1 : 0, sc.detail());
            }
        }

        // ③ 推进进度
        sessions.updateCurrentRound(sessionId, roundNum);
        return roundId;
    }
}
