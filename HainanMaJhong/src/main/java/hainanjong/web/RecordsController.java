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
        List<Map<String, Object>> allRows = mysql.sessionRoundsAll(sessionId);

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

        // 概览（当前用户视角）
        int winCount = 0;
        int highestFan = 0;
        String highestFanDesc = "";
        for (Map<String, Object> r : rawRounds) {
            boolean isWinner = toBool(r.get("isWinner"));
            int totalFan = r.get("totalFan") == null ? 0 : ((Number) r.get("totalFan")).intValue();
            List<String> fanTypes = parseFanTypes((String) r.get("fanInfo"));
            if (isWinner) {
                winCount++;
                if (totalFan > highestFan) {
                    highestFan = totalFan;
                    highestFanDesc = fanTypes.isEmpty() ? "平胡" : String.join("、", fanTypes);
                }
            }
        }

        // 按局分组：每局 = 全局四家（含各自杠/花明细）
        Map<Integer, List<Map<String, Object>>> byRound = new java.util.TreeMap<Integer, List<Map<String, Object>>>();
        for (Map<String, Object> row : allRows) {
            int n = ((Number) row.get("roundNum")).intValue();
            List<Map<String, Object>> list = byRound.get(n);
            if (list == null) {
                list = new ArrayList<Map<String, Object>>();
                byRound.put(n, list);
            }
            list.add(row);
        }

        List<Map<String, Object>> rounds = new ArrayList<Map<String, Object>>();
        for (Map.Entry<Integer, List<Map<String, Object>>> e : byRound.entrySet()) {
            List<Map<String, Object>> rows = e.getValue();
            Map<String, Object> first = rows.get(0);
            List<String> fanTypes = parseFanTypes((String) first.get("fanInfo"));
            Map<String, Object> winHandObj = parseWinHand((String) first.get("winHand"));
            Long winnerId = first.get("winnerId") == null ? null : ((Number) first.get("winnerId")).longValue();
            Long loserId = first.get("loserId") == null ? null : ((Number) first.get("loserId")).longValue();

            List<Map<String, Object>> participants = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> row : rows) {
                Long pid = ((Number) row.get("userId")).longValue();
                Map<String, Object> pp = new HashMap<String, Object>();
                pp.put("userId", pid);
                pp.put("nickname", nickname(pid));
                pp.put("seat", ((Number) row.get("seat")).intValue());
                pp.put("scoreChange", row.get("scoreChange") == null ? 0 : ((Number) row.get("scoreChange")).intValue());
                pp.put("isWinner", toBool(row.get("isWinner")));
                pp.put("isMe", pid == userId);
                pp.put("detail", parseDetailObj((String) row.get("detail")));
                participants.add(pp);
            }

            Map<String, Object> round = new HashMap<String, Object>();
            round.put("roundNum", e.getKey());
            round.put("isDraw", toBool(first.get("isDraw")));
            round.put("winType", first.get("winType") == null ? 2 : ((Number) first.get("winType")).intValue());
            round.put("winnerId", winnerId);
            round.put("winner", winnerId == null ? null : nickname(winnerId));
            round.put("loserId", loserId);
            round.put("winTile", first.get("winTile") == null ? null : ((Number) first.get("winTile")).intValue());
            round.put("fanTypes", fanTypes);
            round.put("winHand", winHandObj.get("hand"));
            round.put("winMelds", winHandObj.get("melds"));
            round.put("participants", participants);
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

    /** 解析每局 detail JSON：新版对象 {notes,flowers,gangs}；旧版字符串数组则并入 notes。 */
    private Map<String, Object> parseDetailObj(String json) {
        Map<String, Object> out = new HashMap<String, Object>();
        out.put("notes", new ArrayList<Object>());
        out.put("flowers", new ArrayList<Object>());
        out.put("gangs", new ArrayList<Object>());
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        String s = json.trim();
        try {
            if (s.startsWith("[")) {
                @SuppressWarnings("unchecked")
                List<Object> arr = mapper.readValue(s, List.class);
                out.put("notes", arr);
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> obj = mapper.readValue(s, Map.class);
                if (obj.get("notes") != null) {
                    out.put("notes", obj.get("notes"));
                }
                if (obj.get("flowers") != null) {
                    out.put("flowers", obj.get("flowers"));
                }
                if (obj.get("gangs") != null) {
                    out.put("gangs", obj.get("gangs"));
                }
            }
        } catch (Exception e) {
            // 忽略解析失败
        }
        return out;
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
