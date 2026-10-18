package hainanMahjong.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import hainanMahjong.pojo.GameSessions;
import hainanMahjong.mapper.GameRoundsMapper.ScoreRow;
import hainanMahjong.vo.recordvo.RecordsListVO;
import hainanMahjong.vo.recordvo.RecordsV2VO.Stats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Derived data only. All writes run under the caller's session lock and transaction. */
@Service
public class RecordProjection {
    public static final String PLAYERS_DDL = "CREATE TABLE IF NOT EXISTS game_session_players ("
            + "session_id BIGINT NOT NULL,user_id BIGINT NOT NULL,seat TINYINT NOT NULL,"
            + "total_score BIGINT NOT NULL DEFAULT 0,win_count INT NOT NULL DEFAULT 0,"
            + "highest_fan_value INT NOT NULL DEFAULT 0,highest_fan_json JSON,"
            + "PRIMARY KEY(session_id,user_id),UNIQUE KEY uk_session_seat(session_id,seat),"
            + "KEY idx_player_session(user_id,session_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    public static final String VERSION_DDL = "CREATE TABLE IF NOT EXISTS game_record_versions ("
            + "session_id BIGINT PRIMARY KEY,through_round INT NOT NULL,revision BIGINT NOT NULL DEFAULT 1) ENGINE=InnoDB";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    @Value("${records.p2.read-enabled:false}") private boolean readEnabled;
    @Value("${records.p2.write-enabled:true}") private boolean writeEnabled = true;

    public RecordProjection(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public boolean reads() {
        // Session zero is reserved for the operator's full verification gate.
        // An unverified migration must never silently hide historical sessions.
        return readEnabled && writeEnabled && jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_record_versions WHERE session_id=0",Integer.class)==1;
    }
    public boolean writes() { return writeEnabled; }
    public long revision(long id) {
        Long revision=jdbc.query("SELECT revision FROM game_record_versions WHERE session_id=?",rs->rs.next()?rs.getLong(1):null,id);
        return revision==null?0:revision;
    }

    public List<Long> players(String raw) {
        try {
            List<?> values = json.readValue(raw, List.class);
            List<Long> ids = new ArrayList<>();
            for (Object value : values) {
                if (!(value instanceof Number) || value instanceof Double || value instanceof Float)
                    throw new IllegalArgumentException("invalid player id");
                ids.add(((Number) value).longValue());
            }
            if (ids.size() != 4 || new HashSet<>(ids).size() != 4) throw new IllegalArgumentException("four players required");
            return ids;
        } catch (Exception e) { throw new IllegalStateException("Invalid session participants", e); }
    }

    public void create(long id, List<Long> ids) {
        players(writeJson(ids));
        for (int seat = 0; seat < 4; seat++)
            jdbc.update("INSERT INTO game_session_players(session_id,user_id,seat) VALUES(?,?,?)", id, ids.get(seat), seat);
        jdbc.update("INSERT INTO game_record_versions(session_id,through_round) VALUES(?,0)", id);
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException(e); }
    }

    public void ensure(GameSessions session) {
        Integer through=jdbc.query("SELECT through_round FROM game_record_versions WHERE session_id=?",
                rs->rs.next()?rs.getInt(1):null,session.getId());
        int latest=jdbc.queryForObject("SELECT COALESCE(MAX(round_num),0) FROM game_rounds WHERE session_id=?",Integer.class,session.getId());
        if (through == null || through != latest)
            rebuild(session);
    }

    /** Rebuild, including corrections, never additive replay. Holds game_sessions FOR UPDATE. */
    public void rebuild(GameSessions session) {
        List<Long> ids = players(session.getPlayerIds());
        validateDetails(session,ids);
        Long previous = jdbc.query("SELECT revision FROM game_record_versions WHERE session_id=?",
                rs -> rs.next() ? rs.getLong(1) : null, session.getId());
        jdbc.update("DELETE FROM game_session_players WHERE session_id=?", session.getId());
        for (int seat = 0; seat < 4; seat++) {
            long user = ids.get(seat);
            Map<String,Object> total = jdbc.queryForMap("SELECT COALESCE(SUM(s.score_change),0) score,"
                    + "COALESCE(SUM(s.is_winner=1),0) wins,COALESCE(MAX(CASE WHEN s.is_winner=1 THEN r.total_fan ELSE 0 END),0) fan "
                    + "FROM game_rounds r JOIN game_round_scores s ON s.round_id=r.id WHERE r.session_id=? AND s.user_id=?", session.getId(),user);
            String fan = jdbc.query("SELECT r.fan_info FROM game_rounds r JOIN game_round_scores s ON s.round_id=r.id "
                    + "WHERE r.session_id=? AND s.user_id=? AND s.is_winner=1 AND r.total_fan>0 "
                    + "ORDER BY r.total_fan DESC,r.round_num ASC LIMIT 1", rs -> rs.next() ? rs.getString(1) : null,session.getId(),user);
            jdbc.update("INSERT INTO game_session_players(session_id,user_id,seat,total_score,win_count,highest_fan_value,highest_fan_json) VALUES(?,?,?,?,?,?,?)",
                    session.getId(),user,seat,total.get("score"),total.get("wins"),total.get("fan"),fan);
        }
        int latest = jdbc.queryForObject("SELECT COALESCE(MAX(round_num),0) FROM game_rounds WHERE session_id=?", Integer.class,session.getId());
        jdbc.update("INSERT INTO game_record_versions(session_id,through_round,revision) VALUES(?,?,?) "
                + "ON DUPLICATE KEY UPDATE through_round=VALUES(through_round),revision=VALUES(revision)",session.getId(),latest,previous==null?1:previous+1);
        verify(session);
    }

    private void validateDetails(GameSessions session,List<Long> ids) {
        List<Map<String,Object>> invalid = jdbc.queryForList("SELECT r.id FROM game_rounds r LEFT JOIN game_round_scores s ON s.round_id=r.id "
                + "WHERE r.session_id=? GROUP BY r.id HAVING COUNT(s.id)<>4 OR COUNT(DISTINCT s.user_id)<>4 "
                + "OR COUNT(DISTINCT s.seat)<>4 OR MIN(s.seat)<>0 OR MAX(s.seat)<>3 LIMIT 1", session.getId());
        if (!invalid.isEmpty()) throw new IllegalStateException("Incomplete round scores: " + session.getId());
        for (Map<String,Object> row : jdbc.queryForList("SELECT DISTINCT s.user_id,s.seat FROM game_rounds r "
                + "JOIN game_round_scores s ON s.round_id=r.id WHERE r.session_id=?", session.getId())) {
            int seat = ((Number) row.get("seat")).intValue();
            if (!Objects.equals(ids.get(seat), ((Number) row.get("user_id")).longValue()))
                throw new IllegalStateException("Participant seat mismatch: " + session.getId());
        }
    }

    public void append(long sessionId, int roundNum, int fanValue, String fanJson, List<ScoreRow> scores) {
        for (ScoreRow score : scores) {
            int fan = score.winner() ? Math.max(0,fanValue) : 0;
            int changed = jdbc.update("UPDATE game_session_players SET total_score=total_score+?,win_count=win_count+?,"
                    + "highest_fan_json=CASE WHEN ? > highest_fan_value THEN ? ELSE highest_fan_json END,"
                    + "highest_fan_value=GREATEST(highest_fan_value,?) WHERE session_id=? AND user_id=? AND seat=?",
                    score.change(),score.winner()?1:0,fan,fanJson,fan,sessionId,score.userId(),score.seat());
            if (changed != 1) throw new IllegalStateException("Projection participant missing");
        }
        jdbc.update("UPDATE game_record_versions SET through_round=GREATEST(through_round,?),revision=revision+1 WHERE session_id=?",roundNum,sessionId);
    }

    public List<Stats> stats(long sessionId, int cutoff) {
        Integer through = jdbc.query("SELECT through_round FROM game_record_versions WHERE session_id=?",
                rs -> rs.next()?rs.getInt(1):null,sessionId);
        if (through == null || through != cutoff) return null; // frozen historical cutoff uses authoritative SQL
        List<Stats> out = jdbc.query("SELECT * FROM game_session_players WHERE session_id=? ORDER BY seat", (rs,n) -> {
            Stats s = new Stats(); s.setUserId(rs.getLong("user_id")); s.setSeat(rs.getInt("seat"));
            s.setTotalScore(rs.getLong("total_score")); s.setWinCount(rs.getInt("win_count"));
            s.setHighestFanValue(rs.getInt("highest_fan_value")); s.setHighestFan(rs.getString("highest_fan_json")); return s;
        },sessionId);
        return out.size()==4?out:null;
    }

    public Map<Long,Map<Long,Long>> totals(List<Long> ids) {
        Map<Long,Map<Long,Long>> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        String marks = String.join(",",Collections.nCopies(ids.size(),"?"));
        jdbc.query("SELECT session_id,user_id,total_score FROM game_session_players WHERE session_id IN ("+marks+")",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.computeIfAbsent(rs.getLong(1),k->new HashMap<>()).put(rs.getLong(2),rs.getLong(3)),ids.toArray());
        for(long id:ids) if(out.getOrDefault(id,Map.of()).size()!=4)
            throw new IllegalStateException("Incomplete projection: "+id);
        return out;
    }

    public long latestSession(long user) { return jdbc.queryForObject("SELECT COALESCE(MAX(session_id),0) FROM game_session_players WHERE user_id=?",Long.class,user); }
    public long count(long user,long cutoff) { return jdbc.queryForObject("SELECT COUNT(*) FROM game_session_players WHERE user_id=? AND session_id<=?",Long.class,user,cutoff); }
    public List<RecordsListVO> sessions(long user,long cutoff,int size,long offset) {
        return jdbc.query("SELECT g.id,g.room_id,g.player_ids,g.status,g.start_time,g.end_time FROM game_session_players p "
                + "JOIN game_sessions g ON g.id=p.session_id WHERE p.user_id=? AND p.session_id<=? ORDER BY p.session_id DESC LIMIT ? OFFSET ?",(rs,n)-> {
            RecordsListVO r = new RecordsListVO(); r.setSessionId(rs.getLong(1)); r.setRoomId(rs.getString(2));
            r.setPlayerIds(rs.getString(3)); r.setStatus(rs.getInt(4));
            if(rs.getTimestamp(5)!=null) r.setStartTime(rs.getTimestamp(5).toLocalDateTime());
            if(rs.getTimestamp(6)!=null) r.setEndTime(rs.getTimestamp(6).toLocalDateTime()); return r;
        },user,cutoff,size,offset);
    }

    /** Compare under the same session lock, including fan JSON and seats, not just sum conservation. */
    public void verify(GameSessions session) {
        List<Long> ids = players(session.getPlayerIds());
        validateDetails(session,ids);
        int latest = jdbc.queryForObject("SELECT COALESCE(MAX(round_num),0) FROM game_rounds WHERE session_id=?",Integer.class,session.getId());
        List<Stats> stored = stats(session.getId(),latest);
        if(stored==null) throw new IllegalStateException("Projection version missing");
        for(Stats s:stored) {
            if(!ids.get(s.getSeat()).equals(s.getUserId())) throw new IllegalStateException("Seat mismatch");
            Map<String,Object> actual = jdbc.queryForMap("SELECT COALESCE(SUM(s.score_change),0) score,COALESCE(SUM(s.is_winner=1),0) wins,"
                    + "COALESCE(MAX(CASE WHEN s.is_winner=1 THEN r.total_fan ELSE 0 END),0) fan FROM game_rounds r "
                    + "JOIN game_round_scores s ON s.round_id=r.id WHERE r.session_id=? AND s.user_id=?",session.getId(),s.getUserId());
            String fan = jdbc.query("SELECT r.fan_info FROM game_rounds r JOIN game_round_scores s ON s.round_id=r.id WHERE r.session_id=? "
                    + "AND s.user_id=? AND s.is_winner=1 AND r.total_fan>0 ORDER BY r.total_fan DESC,r.round_num ASC LIMIT 1",
                    rs->rs.next()?rs.getString(1):null,session.getId(),s.getUserId());
            if(s.getTotalScore()!=((Number)actual.get("score")).longValue() || s.getWinCount()!=((Number)actual.get("wins")).intValue()
                    || s.getHighestFanValue()!=((Number)actual.get("fan")).intValue() || !Objects.equals(s.getHighestFan(),fan))
                throw new IllegalStateException("Projection mismatch: session="+session.getId()+" user="+s.getUserId());
        }
    }
}
