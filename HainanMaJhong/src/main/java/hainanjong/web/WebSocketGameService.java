package hainanjong.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import hainanjong.FanType;
import hainanjong.game.BotController;
import hainanjong.game.GameConfig;
import hainanjong.game.GameSnapshot;
import hainanjong.game.Meld;
import hainanjong.game.PlayerController;
import hainanjong.game.RoomManager;
import hainanjong.game.RoundResult;
import hainanjong.game.Seat;
import hainanjong.service.MysqlService;
import hainanjong.service.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 每个 WebSocket 连接对应一场对局：人类玩家坐东家，其余三家为机器人。
 *
 * <p>一场对局共 {@link #MATCH_HANDS} 把，四家初始金币为 0：胡牌 +1，未胡 -1。
 * 进行中的中间状态按房间号存 Redis（{@code room:{roomId} -> {version, data}}），
 * 前端刷新后带同一 roomId 重连，据此恢复而非重开新房；旧线程会被取代（supersede）停止。</p>
 */
@Service
public class WebSocketGameService {

    private static final Logger log = LoggerFactory.getLogger(WebSocketGameService.class);
    private static final int MATCH_HANDS = 16;
    private static final long DISCARD_TIMEOUT_MS = 30_000L; // 出牌超时：30 秒
    private static final long ACTION_TIMEOUT_MS = 15_000L;  // 吃碰杠胡/自摸响应超时：15 秒

    /** 被同一账号的新登录/新连接顶下线时，关闭旧 WebSocket 用的状态码（4001）。 */
    private static final CloseStatus CLOSE_REASSIGNED = new CloseStatus(4001, "re-logged-in elsewhere");

    private final ObjectMapper mapper = new ObjectMapper();
    private final MysqlService mysql;
    private final RedisService redis;
    private final Map<String, Game> gamesBySession = new ConcurrentHashMap<String, Game>();
    private final Map<String, Game> gamesByRoom = new ConcurrentHashMap<String, Game>();
    /** 同一账号当前的对局（key=人类玩家 userId，>0；游客不登记）。 */
    private final Map<Long, Game> gamesByUser = new ConcurrentHashMap<Long, Game>();
    private static final Map<String, Long> SEEN_VERSIONS = new ConcurrentHashMap<String, Long>();

    public WebSocketGameService(MysqlService mysql, RedisService redis) {
        this.mysql = mysql;
        this.redis = redis;
    }

    private static final class Game {
        final WebSocketSession session;
        final String roomId;
        final HumanPlayerController human;
        final Object lock;        // 发往该连接时的同步锁
        final long humanUserId;   // >0 为真实账号；0 为游客
        volatile boolean quit;        // 用户主动退出/被新登录顶下线
        volatile boolean superseded;  // 被同一房间的新连接取代
        volatile RoomManager room;
        Game(WebSocketSession session, String roomId, HumanPlayerController human,
             long humanUserId, Object lock) {
            this.session = session;
            this.roomId = roomId;
            this.human = human;
            this.humanUserId = humanUserId;
            this.lock = lock;
        }
    }

    /** Redis 中保存的进行中对局状态。 */
    public static class MatchState {
        public long sessionId;
        public int currentRound;
        public Map<String, Integer> coins = new LinkedHashMap<String, Integer>();
        public String dealer;
        public GameSnapshot snapshot; // 进行中某把的完整快照；两把之间为 null
    }

    /** Redis 中存储的房间状态：版本号 + 数据。 */
    public static class RoomState {
        public long version;
        public MatchState data;
    }

    public void startGame(final WebSocketSession session) {
        final Object lock = new Object();
        final HumanPlayerController human = new HumanPlayerController(
                msg -> send(session, lock, msg), DISCARD_TIMEOUT_MS, ACTION_TIMEOUT_MS);

        final long humanUserId = parseLong(session, "userId", 0L);

        // 确定房间号：前端带回来的 roomId 若在 Redis 有存档则沿用，否则开新房
        final String candidateRoomId = parseParam(session, "roomId");
        String existing = null;
        if (candidateRoomId != null && !candidateRoomId.isEmpty()) {
            try {
                existing = redis.getRoomState(candidateRoomId);
            } catch (Exception e) {
                // Redis 不可用则视为无存档
            }
        }
        final String roomId = (existing != null) ? candidateRoomId : ("room-" + System.currentTimeMillis());

        final Game game = new Game(session, roomId, human, humanUserId, lock);

        // —— 同一账号互斥：新连接上线时，若旧端仍在同一账号的对局中，
        //    强制旧端下线并废弃其正在进行的牌局；重连同一房间的恢复场景除外 ——
        if (humanUserId > 0) {
            Game prior = gamesByUser.get(humanUserId);
            boolean resumeSameRoom = candidateRoomId != null && candidateRoomId.equals(prior != null ? prior.roomId : null);
            if (prior != null && prior != game && !resumeSameRoom && isLive(prior)) {
                log.info("账号 {} 在其他设备上线，强制下线其房间 {} 的对局", humanUserId, prior.roomId);
                gamesByUser.remove(humanUserId, prior);
                forceQuit(prior, "该账号已在其他设备登录，本局已结束");
            }
            gamesByUser.put(humanUserId, game);
        }

        // 取代同一房间的旧线程（僵尸线程），避免它继续写 Redis
        Game old = gamesByRoom.get(roomId);
        if (old != null && old != game) {
            old.superseded = true;
            if (old.room != null) {
                old.room.cancel();
            }
        }
        gamesBySession.put(session.getId(), game);
        gamesByRoom.put(roomId, game);

        Map<Seat, PlayerController> controllers = new EnumMap<Seat, PlayerController>(Seat.class);
        controllers.put(Seat.EAST, human);
        int i = 1;
        for (Seat s : Seat.values()) {
            if (s != Seat.EAST) {
                controllers.put(s, new BotController(i++));
            }
        }

        final WebGameListener listener = new WebGameListener(msg -> send(session, lock, msg), Seat.EAST);

        final Map<Seat, Long> playerIds = new EnumMap<Seat, Long>(Seat.class);
        playerIds.put(Seat.EAST, humanUserId);
        playerIds.put(Seat.SOUTH, -1L);
        playerIds.put(Seat.WEST, -2L);
        playerIds.put(Seat.NORTH, -3L);

        Map<String, Object> welcome = new HashMap<String, Object>();
        welcome.put("type", "welcome");
        welcome.put("seat", "EAST");
        welcome.put("roomId", roomId);
        send(session, lock, welcome);

        Map<String, Object> match = new HashMap<String, Object>();
        match.put("type", "match");
        match.put("total", MATCH_HANDS);
        send(session, lock, match);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                RoomState loaded = loadRoomState(roomId);
                MatchState saved = loaded == null ? null : loaded.data;
                AtomicLong version = new AtomicLong(loaded == null ? 0L : loaded.version);
                long sessionId;
                int startRound;
                Map<Seat, Integer> coins = new EnumMap<Seat, Integer>(Seat.class);
                Seat dealer;
                GameSnapshot resumeSnapshot = null;

                if (saved != null && saved.sessionId > 0
                        && saved.currentRound >= 1 && saved.currentRound <= MATCH_HANDS) {
                    sessionId = saved.sessionId;
                    startRound = saved.currentRound;
                    dealer = seatOf(saved.dealer);
                    for (Seat s : Seat.values()) {
                        Integer c = saved.coins == null ? null : saved.coins.get(s.name());
                        coins.put(s, c == null ? 0 : c);
                    }
                    resumeSnapshot = saved.snapshot;
                    log.info("恢复对局 room={}，session={}，从第 {} 把继续{}", roomId, sessionId, startRound,
                            resumeSnapshot != null ? "（含中途快照）" : "");
                } else {
                    sessionId = -1L;
                    try {
                        List<Long> idList = new ArrayList<Long>();
                        for (Seat s : Seat.values()) {
                            idList.add(playerIds.get(s));
                        }
                        sessionId = mysql.createSession(roomId, humanUserId, mapper.writeValueAsString(idList));
                    } catch (Exception e) {
                        log.warn("创建 game_sessions 失败：{}", e.getMessage());
                    }
                    startRound = 1;
                    dealer = Seat.EAST;
                    for (Seat s : Seat.values()) {
                        coins.put(s, 0);
                    }
                }

                boolean completed = false;
                try {
                    for (int hand = startRound; hand <= MATCH_HANDS; hand++) {
                        if (!session.isOpen() || game.quit || game.superseded) {
                            break;
                        }
                        human.reset();

                        Map<String, Object> hs = new HashMap<String, Object>();
                        hs.put("type", "hand_start");
                        hs.put("hand", hand);
                        hs.put("total", MATCH_HANDS);
                        hs.put("dealer", dealer.name());
                        hs.put("coins", coinView(coins));
                        send(session, lock, hs);

                        GameConfig config = new GameConfig(true, dealer, DISCARD_TIMEOUT_MS, ACTION_TIMEOUT_MS,
                                System.currentTimeMillis() + hand, true, 0);
                        RoomManager room = new RoomManager(config, controllers, listener);
                        listener.setRoom(room);
                        final long sid = sessionId;
                        final int h = hand;
                        final Seat d = dealer;
                        room.setCheckpoint(snap -> {
                            try {
                                long v = version.incrementAndGet();
                                saveMatchState(roomId, v, sid, h, coins, d, snap);
                            } catch (Exception e) {
                                log.warn("保存 Redis 对局快照失败：{}", e.getMessage());
                            }
                        });
                        game.room = room;
                        if (resumeSnapshot != null) {
                            room.resume(resumeSnapshot);
                            resumeSnapshot = null;
                        } else {
                            room.start();
                        }
                        game.room = null;
                        if (game.quit || game.superseded || room.isCancelled()) {
                            break;
                        }
                        RoundResult r = room.getResult();

                        if (r.isDraw) {
                            for (Seat s : Seat.values()) {
                                coins.put(s, coins.get(s) - 1);
                            }
                        } else {
                            coins.put(r.winner, coins.get(r.winner) + 1);
                            for (Seat s : Seat.values()) {
                                if (s != r.winner) {
                                    coins.put(s, coins.get(s) - 1);
                                }
                            }
                        }

                        if (sessionId > 0) {
                            try {
                                persistRound(sessionId, hand, r, playerIds);
                                mysql.updateSessionRound(sessionId, hand);
                            } catch (Exception e) {
                                log.warn("保存对局数据失败：{}", e.getMessage());
                            }
                        }

                        Map<String, Object> cu = new HashMap<String, Object>();
                        cu.put("type", "coins");
                        cu.put("hand", hand);
                        cu.put("coins", coinView(coins));
                        send(session, lock, cu);

                        dealer = dealer.next();

                        try {
                            long v = version.incrementAndGet();
                            saveMatchState(roomId, v, sessionId, hand + 1, coins, dealer, null);
                        } catch (Exception e) {
                            log.warn("保存 Redis 对局状态失败：{}", e.getMessage());
                        }

                        if (hand == MATCH_HANDS) {
                            completed = true;
                        }
                    }

                    if (game.superseded) {
                        // 被同一房间的新连接取代：不碰 MySQL / Redis
                        log.info("房间 {} 已被新连接取代，旧线程退出", roomId);
                    } else if (game.quit) {
                        if (sessionId > 0) {
                            try {
                                mysql.disbandSession(sessionId);
                            } catch (Exception e) {
                                log.warn("解散 game_sessions 失败：{}", e.getMessage());
                            }
                        }
                        try {
                            redis.deleteRoomState(roomId);
                        } catch (Exception e) {
                            log.warn("清除 Redis 状态失败：{}", e.getMessage());
                        }
                    } else if (completed) {
                        if (sessionId > 0) {
                            try {
                                mysql.finishSession(sessionId);
                            } catch (Exception e) {
                                log.warn("结束 game_sessions 失败：{}", e.getMessage());
                            }
                        }
                        try {
                            redis.deleteRoomState(roomId);
                        } catch (Exception e) {
                            log.warn("清除 Redis 状态失败：{}", e.getMessage());
                        }
                        Map<String, Object> end = new HashMap<String, Object>();
                        end.put("type", "match_end");
                        end.put("coins", coinView(coins));
                        send(session, lock, end);
                    } else {
                        // 断线：保留 Redis 状态用于重连恢复，不做清理
                        log.info("房间 {} 断线，保留 Redis 状态等待恢复", roomId);
                    }
                } catch (Throwable ex) {
                    log.warn("对局异常终止：{}", ex.getMessage());
                } finally {
                    gamesBySession.remove(session.getId());
                    gamesByRoom.remove(roomId, game);
                    gamesByUser.remove(game.humanUserId, game);
                }
            }
        }, "game-" + session.getId());
        t.setDaemon(true);
        t.start();
    }

    /** 保存一把对局及其四家流水。 */
    private void persistRound(long sessionId, int roundNum, RoundResult r, Map<Seat, Long> playerIds) throws Exception {
        boolean isDraw = r.isDraw;
        int winType = isDraw ? 2 : (r.selfDraw ? 0 : 1);
        Long winnerId = (isDraw || r.winner == null) ? null : playerIds.get(r.winner);
        Long loserId = (isDraw || r.selfDraw || r.from == null) ? null : playerIds.get(r.from);
        Integer winTile = (r.winTile >= 0) ? r.winTile : null;

        String fanInfo = "{}";
        int totalFan = 0;
        if (r.hu != null && !r.hu.fanTypes.isEmpty()) {
            Map<String, Integer> fanMap = new LinkedHashMap<String, Integer>();
            for (FanType f : r.hu.fanTypes) {
                fanMap.put(f.name(), 1);
            }
            fanInfo = mapper.writeValueAsString(fanMap);
            totalFan = r.hu.fanTypes.size();
        }

        String winHandJson = null;
        if (!isDraw && r.winHand != null) {
            Map<String, Object> wh = new HashMap<String, Object>();
            wh.put("hand", r.winHand);
            List<Map<String, Object>> melds = new ArrayList<Map<String, Object>>();
            if (r.winMelds != null) {
                for (Meld m : r.winMelds) {
                    Map<String, Object> mm = new HashMap<String, Object>();
                    mm.put("type", m.type.name());
                    List<Integer> tiles = new ArrayList<Integer>();
                    for (int t : m.tiles) {
                        tiles.add(t);
                    }
                    mm.put("tiles", tiles);
                    melds.add(mm);
                }
            }
            wh.put("melds", melds);
            winHandJson = mapper.writeValueAsString(wh);
        }

        long roundId = mysql.saveRound(sessionId, roundNum, isDraw, winType,
                winnerId, loserId, winTile, winHandJson, fanInfo, totalFan);
        if (roundId > 0) {
            for (Seat s : Seat.values()) {
                boolean isWinner = !isDraw && r.winner == s;
                int change = isWinner ? 1 : -1;
                mysql.saveRoundScore(roundId, playerIds.get(s), s.ordinal(), change, isWinner);
            }
        }
    }

    private void saveMatchState(String roomId, long version, long sessionId, int round, Map<Seat, Integer> coins,
                                Seat dealer, GameSnapshot snapshot) throws Exception {
        MatchState data = new MatchState();
        data.sessionId = sessionId;
        data.currentRound = round;
        data.dealer = dealer == null ? null : dealer.name();
        for (Seat seat : Seat.values()) {
            data.coins.put(seat.name(), coins.get(seat));
        }
        data.snapshot = snapshot;

        RoomState rs = new RoomState();
        rs.version = version;
        rs.data = data;
        redis.saveRoomState(roomId, mapper.writeValueAsString(rs));
    }

    private RoomState loadRoomState(String roomId) {
        try {
            String json = redis.getRoomState(roomId);
            if (json == null || json.isEmpty()) {
                return null;
            }
            RoomState rs = mapper.readValue(json, RoomState.class);
            if (rs == null || rs.version <= 0 || rs.data == null) {
                log.warn("Redis 房间状态无效（version={}），拒绝恢复", rs == null ? 0 : rs.version);
                return null;
            }
            Long prev = SEEN_VERSIONS.get(roomId);
            if (prev != null && rs.version < prev) {
                log.error("Redis 版本回退（当前 {} < 已见 {}），拒绝恢复", rs.version, prev);
                return null;
            }
            SEEN_VERSIONS.put(roomId, rs.version);
            return rs;
        } catch (Exception e) {
            log.warn("读取 Redis 房间状态失败：{}", e.getMessage());
            return null;
        }
    }

    private static Seat seatOf(String name) {
        if (name == null) {
            return Seat.EAST;
        }
        try {
            return Seat.valueOf(name);
        } catch (Exception e) {
            return Seat.EAST;
        }
    }

    private static Map<String, Integer> coinView(Map<Seat, Integer> coins) {
        Map<String, Integer> view = new LinkedHashMap<String, Integer>();
        for (Seat s : Seat.values()) {
            view.put(s.name(), coins.get(s));
        }
        return view;
    }

    private static String parseParam(WebSocketSession session, String name) {
        try {
            String q = session.getUri() == null ? null : session.getUri().getQuery();
            if (q != null) {
                for (String kv : q.split("&")) {
                    if (kv.startsWith(name + "=")) {
                        return kv.substring(name.length() + 1);
                    }
                }
            }
        } catch (Exception e) {
            // 忽略
        }
        return null;
    }

    private static long parseLong(WebSocketSession session, String name, long def) {
        String v = parseParam(session, name);
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public void onMessage(WebSocketSession session, String payload) {
        Game g = gamesBySession.get(session.getId());
        if (g == null) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = mapper.readValue(payload, Map.class);
            String type = (String) msg.get("type");
            int reqId = msg.get("reqId") == null ? -1 : ((Number) msg.get("reqId")).intValue();
            if ("discard".equals(type)) {
                g.human.discard(reqId, ((Number) msg.get("tile")).intValue());
            } else if ("act".equals(type)) {
                g.human.act(reqId, ((Number) msg.get("index")).intValue());
            } else if ("pass".equals(type)) {
                g.human.pass(reqId);
            } else if ("quit".equals(type)) {
                g.quit = true;
                if (g.room != null) {
                    g.room.cancel();
                }
            }
        } catch (Exception e) {
            log.warn("无法解析客户端消息：{}", e.getMessage());
        }
    }

    public void onClose(WebSocketSession session) {
        // 只摘掉 session 映射；不断线程，断线后靠超时/重连 supersede 处理
        gamesBySession.remove(session.getId());
    }

    // ==================== 账号互斥 / 强制下线 ====================

    /** 该对局是否仍处于“旧端在线正常游玩”的状态。 */
    private static boolean isLive(Game g) {
        return g.session.isOpen() && !g.quit && !g.superseded;
    }

    /**
     * 强制结束 {@code g} 的对局：解除房间线程阻塞、通知旧端并断开其连接。
     * 旧线程据此走“quit”清理分支：解散 game_sessions、清除 Redis 房间状态（本局丢弃）。
     */
    private void forceQuit(Game g, String message) {
        g.quit = true;
        RoomManager rm = g.room;
        if (rm != null) {
            rm.cancel();
        }
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("type", "kicked");
        m.put("message", message);
        try {
            String json = mapper.writeValueAsString(m);
            synchronized (g.lock) {
                if (g.session.isOpen()) {
                    g.session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            log.debug("通知旧端下线失败：{}", e.getMessage());
        }
        try {
            if (g.session.isOpen()) {
                g.session.close(CLOSE_REASSIGNED);
            }
        } catch (Exception e) {
            log.debug("关闭旧连接失败：{}", e.getMessage());
        }
    }

    /**
     * 登录时调用：若该账号当前正有一个“在线进行中”的对局，
     * 强制其下线并废弃该牌局，返回是否真的踢了一个。
     */
    public boolean kickUser(long userId) {
        if (userId <= 0) {
            return false;
        }
        Game g = gamesByUser.get(userId);
        if (g == null || !isLive(g)) {
            return false;
        }
        gamesByUser.remove(userId, g);
        forceQuit(g, "该账号已在其他设备登录，本局已结束");
        return true;
    }

    private void send(WebSocketSession session, Object lock, Map<String, Object> msg) {
        try {
            String json = mapper.writeValueAsString(msg);
            synchronized (lock) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            log.warn("发送消息失败：{}", e.getMessage());
        }
    }
}
