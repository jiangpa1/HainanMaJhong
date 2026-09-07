package hainanjong.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import hainanjong.FanType;
import hainanjong.service.MysqlService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 战绩查询接口。
 *
 * <ul>
 *   <li>{@code GET /api/records?userId=}：房间总战绩列表。</li>
 *   <li>{@code GET /api/records/byRoom?roomId=&userId=}：按房间号查某一房间的明细（局内用）。</li>
 *   <li>{@code GET /api/records/{sessionId}?userId=}：按 session 查某房间明细。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/records")
public class RecordsController {

    private final MysqlService mysql;
    private final ObjectMapper mapper = new ObjectMapper();

    public RecordsController(MysqlService mysql) {
        this.mysql = mysql;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam long userId) {
        List<Map<String, Object>> sessions = mysql.listSessionsForUser(userId);
        List<Map<String, Object>> records = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> s : sessions) {
            long sessionId = ((Number) s.get("sessionId")).longValue();
            Map<Long, int[]> totals = mysql.sessionPlayerTotals(sessionId);
            int myTotal = totals.containsKey(userId) ? totals.get(userId)[1] : 0;
            int rank = computeRank(totals, userId);

            Map<String, Object> rec = new HashMap<String, Object>();
            rec.put("sessionId", sessionId);
            rec.put("roomId", s.get("roomId"));
            rec.put("startTime", s.get("startTime"));
            rec.put("endTime", s.get("endTime"));
            rec.put("status", s.get("status"));
            rec.put("totalScore", myTotal);
            rec.put("rank", rank);
            records.add(rec);
        }
        Map<String, Object> resp = new HashMap<String, Object>();
        resp.put("records", records);
        return resp;
    }

    /** 按房间号查询（局内「战绩查询」用）：查当前房间从开始到现在的各小局。 */
    @GetMapping("/byRoom")
    public Map<String, Object> byRoom(@RequestParam String roomId, @RequestParam long userId) {
        Long sessionId = mysql.getSessionIdByRoomId(roomId);
        if (sessionId == null) {
            Map<String, Object> resp = new HashMap<String, Object>();
            resp.put("roomId", roomId);
            resp.put("notFound", true);
            resp.put("overview", emptyOverview());
            resp.put("rounds", new ArrayList<Object>());
            return resp;
        }
        return buildDetail(sessionId, userId);
    }

    @GetMapping("/{sessionId}")
    public Map<String, Object> detail(@PathVariable long sessionId, @RequestParam long userId) {
        return buildDetail(sessionId, userId);
    }

    private Map<String, Object> buildDetail(long sessionId, long userId) {
        Map<Long, int[]> totals = mysql.sessionPlayerTotals(sessionId);
        List<Map<String, Object>> rawRounds = mysql.sessionRounds(sessionId, userId);

        // 玩家总分明细
        List<Map<String, Object>> players = new ArrayList<Map<String, Object>>();
        for (Map.Entry<Long, int[]> e : totals.entrySet()) {
            Map<String, Object> p = new HashMap<String, Object>();
            p.put("userId", e.getKey());
            p.put("seat", e.getValue()[0]);
            p.put("total", e.getValue()[1]);
            p.put("isMe", e.getKey() == userId);
            p.put("nickname", nickname(e.getKey()));
            players.add(p);
        }

        int winCount = 0;
        int highestFan = 0;
        String highestFanDesc = "";
        List<Map<String, Object>> rounds = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : rawRounds) {
            int roundNum = ((Number) r.get("roundNum")).intValue();
            int winType = r.get("winType") == null ? 2 : ((Number) r.get("winType")).intValue();
            boolean isDraw = toBool(r.get("isDraw"));
            boolean isWinner = toBool(r.get("isWinner"));
            int scoreChange = r.get("scoreChange") == null ? 0 : ((Number) r.get("scoreChange")).intValue();
            Integer winTile = r.get("winTile") == null ? null : ((Number) r.get("winTile")).intValue();
            int totalFan = r.get("totalFan") == null ? 0 : ((Number) r.get("totalFan")).intValue();
            List<String> fanTypes = parseFanTypes((String) r.get("fanInfo"));
            Long winnerId = r.get("winnerId") == null ? null : ((Number) r.get("winnerId")).longValue();
            String winner = winnerId == null ? null : nickname(winnerId);
            Long loserId = r.get("loserId") == null ? null : ((Number) r.get("loserId")).longValue();
            boolean isLoser = loserId != null && loserId == userId;
            Map<String, Object> winHandObj = parseWinHand((String) r.get("winHand"));

            if (isWinner) {
                winCount++;
                if (totalFan > highestFan) {
                    highestFan = totalFan;
                    highestFanDesc = fanTypes.isEmpty() ? "平胡" : String.join("、", fanTypes);
                }
            }

            Map<String, Object> round = new HashMap<String, Object>();
            round.put("roundNum", roundNum);
            round.put("winType", winType);
            round.put("isDraw", isDraw);
            round.put("isWinner", isWinner);
            round.put("isLoser", isLoser);
            round.put("scoreChange", scoreChange);
            round.put("winTile", winTile);
            round.put("fanTypes", fanTypes);
            round.put("winner", winner);
            round.put("winHand", winHandObj.get("hand"));
            round.put("winMelds", winHandObj.get("melds"));
            rounds.add(round);
        }

        Map<String, Object> overview = new HashMap<String, Object>();
        overview.put("winCount", winCount);
        overview.put("highestFan", highestFanDesc.isEmpty() ? "无" : highestFanDesc);
        overview.put("players", players);

        Map<String, Object> resp = new HashMap<String, Object>();
        resp.put("roomId", mysql.getSessionRoomId(sessionId));
        resp.put("overview", overview);
        resp.put("rounds", rounds);
        return resp;
    }

    private Map<String, Object> emptyOverview() {
        Map<String, Object> ov = new HashMap<String, Object>();
        ov.put("winCount", 0);
        ov.put("highestFan", "无");
        ov.put("players", new ArrayList<Object>());
        return ov;
    }

    private int computeRank(Map<Long, int[]> totals, long userId) {
        int myTotal = totals.containsKey(userId) ? totals.get(userId)[1] : Integer.MIN_VALUE;
        int rank = 1;
        for (Map.Entry<Long, int[]> e : totals.entrySet()) {
            if (e.getKey() != userId && e.getValue()[1] > myTotal) {
                rank++;
            }
        }
        return rank;
    }

    /** 解析玩家昵称：机器人用固定名，游客用「游客」，真实用户查 user 表。 */
    private String nickname(long userId) {
        if (userId < 0) {
            switch ((int) userId) {
                case -1: return "电脑·南";
                case -2: return "电脑·西";
                case -3: return "电脑·北";
                default: return "电脑";
            }
        }
        if (userId == 0) {
            return "游客";
        }
        String name = mysql.findNickname(userId);
        if (name == null || name.isEmpty()) {
            name = mysql.getUsername(userId);
        }
        return name == null ? "玩家" + userId : name;
    }

    private List<Integer> parseIntList(String json) {
        List<Integer> list = new ArrayList<Integer>();
        if (json == null || json.isEmpty()) {
            return list;
        }
        try {
            @SuppressWarnings("unchecked")
            List<Object> arr = mapper.readValue(json, List.class);
            for (Object o : arr) {
                list.add(((Number) o).intValue());
            }
        } catch (Exception e) {
            // 忽略解析失败
        }
        return list;
    }

    /** 解析胡牌手牌 JSON：{hand:[...], melds:[{type,tiles}]}。 */
    private Map<String, Object> parseWinHand(String json) {
        Map<String, Object> result = new HashMap<String, Object>();
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
            // 忽略解析失败
        }
        return result;
    }

    /** 兼容 MySQL BOOLEAN(TINYINT(1)) 经 JDBC 映射为 Boolean 或 Number 的两种情况。 */
    private static boolean toBool(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue() != 0;
        }
        String s = String.valueOf(v);
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    private List<String> parseFanTypes(String json) {
        List<String> names = new ArrayList<String>();
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
            // 忽略解析失败
        }
        return names;
    }
}
