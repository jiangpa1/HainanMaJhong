package hainanMahjong.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import hainanMahjong.mapper.GameRoundsMapper;
import hainanMahjong.mapper.GameSessionsMapper;
import hainanMahjong.pojo.GameSessions;
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
    private RoomCloseOutbox closeOutbox;
    @org.springframework.beans.factory.annotation.Autowired
    public void setCloseOutbox(RoomCloseOutbox closeOutbox) { this.closeOutbox = closeOutbox; }

    /** The event and session close commit or roll back together. Redis is touched after commit. */
    @Transactional
    public void closeWithOutbox(long sessionId, String roomCode, String generation) {
        disbandSession(sessionId);
        if (closeOutbox == null) throw new IllegalStateException("Room close outbox unavailable");
        closeOutbox.enqueue(sessionId, roomCode, generation);
    }

    private RecordProjection projection;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRecordProjection(RecordProjection projection) { this.projection = projection; }
    private RecordsV2Service records;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRecordsV2Service(RecordsV2Service records) { this.records = records; }
    private hainanMahjong.monitoring.CapacityMetrics metrics;
    @org.springframework.beans.factory.annotation.Autowired
    public void setCapacityMetrics(hainanMahjong.monitoring.CapacityMetrics metrics) { this.metrics = metrics; }

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
    @Transactional
    public long createSession(String roomId, long creatorId, List<Long> playerIds) {
        String json;
        try {
            json = mapper.writeValueAsString(playerIds);
        } catch (Exception e) {
            json = "[]";
        }
        GameSessionsMapper.SessionInsert row = new GameSessionsMapper.SessionInsert();
        sessions.insertSession(row, roomId, creatorId, json);
        if (row.id != null && projection != null && projection.writes()) projection.create(row.id, playerIds);
        return row.id == null ? -1L : row.id;
    }

    /** 玩家中途退出：标记该场为异常解散。 */
    @Transactional
    public void disbandSession(long sessionId) {
        // The conditional UPDATE locks the same session row as saveHand. Replays are safe.
        int changed = sessions.disbandSession(sessionId);
        if (changed == 0) {
            GameSessions existing = sessions.lockSession(sessionId);
            if (existing == null || existing.getEndTime() == null)
                throw new IllegalStateException("场次结束状态未写入 sessionId=" + sessionId);
        }
        if (records != null && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { records.invalidate(sessionId); }
                });
        }
    }

    public GameSessions sessionForRestore(long sessionId) {
        return sessions.selectById(sessionId);
    }

    public int lastCommittedRound(long sessionId) { return rounds.lastCommittedRound(sessionId); }

    /**
     * 事务内保存一把对局及其四家流水（<b>幂等</b>）。
     *
     * <p>同一 {@code (session_id, round_num)} 已存在（服务重启后重放同把）时，
     * 返回已提交的 round，不覆盖流水或重复计入。通过会话行锁串行结算与关闭，
     * 新结算的明细、四家流水和进度在一个事务内提交。</p>
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
        // 与关闭和重复结算串行，已结束的会话不能再接收旧回调。
        GameSessions session = sessions.lockSession(sessionId);
        if (session == null || session.getStatus() != 0 || session.getEndTime() != null) {
            throw new IllegalStateException("会话已结束，拒绝结算");
        }
        // ① 找/建本把
        Long roundId = rounds.findRoundId(sessionId, roundNum);
        if (roundId != null) return roundId; // 已提交的结算不再覆盖或重复计入
        if (scores == null || scores.size() != 4 || scores.stream().map(GameRoundsMapper.ScoreRow::userId).distinct().count() != 4
                || !scores.stream().map(GameRoundsMapper.ScoreRow::seat).collect(java.util.stream.Collectors.toSet()).equals(java.util.Set.of(0,1,2,3)))
            throw new IllegalArgumentException("结算必须包含四家唯一玩家和座位");
        if (projection != null && projection.writes()) {
            List<Long> ids = projection.players(session.getPlayerIds());
            for (GameRoundsMapper.ScoreRow score : scores)
                if (ids.get(score.seat()) != score.userId()) throw new IllegalArgumentException("结算成员与会话不一致");
            projection.ensure(session);
        }
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

        // ② 写四家流水
        if (scores != null) {
            for (GameRoundsMapper.ScoreRow sc : scores) {
                rounds.insertScore(roundId, sc.userId(), sc.seat(), sc.change(),
                        sc.winner() ? 1 : 0, sc.detail());
            }
        }

        // ③ 推进进度
        sessions.updateCurrentRound(sessionId, roundNum);
        if (projection != null && projection.writes()) {
            // Lower round numbers indicate a historical insertion; rebuild also restores fan tie ordering.
            if (roundNum < rounds.lastCommittedRound(sessionId)) projection.rebuild(session);
            else projection.append(sessionId, roundNum, totalFan, fanInfoJson, scores);
        }
        if (metrics != null && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { metrics.count("mahjong.game.rounds.committed"); }
                });
        }
        if (records != null && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { records.invalidate(sessionId); }
                });
        }
        return roundId;
    }
}
