package hainanjong.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import hainanjong.FanType;
import hainanjong.HuResult;
import hainanjong.game.Action;
import hainanjong.game.BotController;
import hainanjong.game.GameConfig;
import hainanjong.game.GameListener;
import hainanjong.game.GameSnapshot;
import hainanjong.game.Meld;
import hainanjong.game.Player;
import hainanjong.game.PlayerController;
import hainanjong.game.Responder;
import hainanjong.game.RoomManager;
import hainanjong.game.RoundResult;
import hainanjong.game.Seat;
import hainanjong.rules.DealerFlow;
import hainanjong.rules.HainanConfig;
import hainanjong.rules.HainanFan;
import hainanjong.rules.HainanScore;
import hainanjong.service.MysqlService;
import hainanjong.service.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多人联机房间：等待房 + 4 真人 16 局。
 *
 * <p>v2 关键规则：</p>
 * <ul>
 *   <li><b>对局中任何人（含房主）离开/刷新都不结束游戏</b>：座位保留、由机器人托管，
 *       该玩家回到大厅会看到“返回房间”按钮，可随时回座位；一个 16 局块结束仍有人未归才解散。</li>
 *   <li><b>多人局也把快照写进 Redis</b>（以房间码为键）：块内每把每次出牌前落一次全量快照；
 *       服务重启/崩溃后，只要有玩家凭房间码回来（或 /game 连回），就从 Redis 重建房间并继续把剩余局数打完。</li>
 *   <li>每 16 把 = 一条 MySQL game_sessions；房主可在块末发起下一轮。</li>
 * </ul>
 */
@Service
public class MultiPlayerRoomService {

    private static final Logger log = LoggerFactory.getLogger(MultiPlayerRoomService.class);
    private static final int MAX_MEMBERS = 4;
    private static final int MATCH_HANDS = 16;
    private static final long DISCARD_TIMEOUT_MS = 30_000L;
    private static final long ACTION_TIMEOUT_MS = 15_000L;
    private static final long BIND_GRACE_MS = 40_000L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final MysqlService mysql;
    private final RedisService redis;
    private final WebSocketGameService solo;
    private final SecureRandom random = new SecureRandom();

    private final Map<String, Room> roomsByCode = new ConcurrentHashMap<String, Room>();
    private final Map<Long, String> codeByUser = new ConcurrentHashMap<Long, String>();
    private final Map<String, Long> userOfSession = new ConcurrentHashMap<String, Long>();
    private final Map<String, Long> gameUserOfSession = new ConcurrentHashMap<String, Long>();
    private final Map<String, String> gameCodeOfSession = new ConcurrentHashMap<String, String>();
    private final Map<String, Seat> gameSeatOfSession = new ConcurrentHashMap<String, Seat>();

    public MultiPlayerRoomService(MysqlService mysql, RedisService redis, WebSocketGameService solo) {
        this.mysql = mysql;
        this.redis = redis;
        this.solo = solo;
    }

    public enum State { WAITING, PLAYING, BETWEEN }

    public static class Member {
        public final Seat seat;
        public final long userId;
        public final String nickname;
        public volatile WebSocketSession roomWs;
        public volatile WebSocketSession gameWs;
        public volatile boolean offline;
        public final HumanPlayerController human;
        public final SwitchingController controller;

        Member(Seat seat, long userId, String nickname, WebSocketSession roomWs) {
            this.seat = seat;
            this.userId = userId;
            this.nickname = nickname;
            this.roomWs = roomWs;
            this.human = new HumanPlayerController(null, DISCARD_TIMEOUT_MS, ACTION_TIMEOUT_MS);
            this.controller = new SwitchingController(human);
            this.offline = false;
        }
    }

    public static class SwitchingController implements PlayerController {
        private volatile PlayerController delegate;
        SwitchingController(PlayerController initial) { this.delegate = initial; }
        public void setDelegate(PlayerController d) { this.delegate = d; }
        @Override public void onDiscardTurn(Seat seat, List<Integer> hand, int drawnTile, Responder r) {
            delegate.onDiscardTurn(seat, hand, drawnTile, r);
        }
        @Override public void onActionChance(Seat seat, int tile, List<Action> options, Responder r) {
            delegate.onActionChance(seat, tile, options, r);
        }
        @Override public void onDrawChance(Seat seat, int drawnTile, List<Action> options, Responder r) {
            delegate.onDrawChance(seat, drawnTile, options, r);
        }
        @Override public boolean isAutoReport() {
            return delegate.isAutoReport();
        }
        @Override public void prepareDiscard(boolean canReport) {
            delegate.prepareDiscard(canReport);
        }
    }

    public static class Room {
        public final String code;
        public final long hostUserId;
        public volatile State state = State.WAITING;
        public final Map<Seat, Member> members = new EnumMap<Seat, Member>(Seat.class);
        public int blockNo = 0;
        public long sessionId = -1L;
        public final Map<Seat, Integer> coins = new EnumMap<Seat, Integer>(Seat.class);
        public volatile RoomManager active;
        public volatile int currentHand;
        public volatile Seat currentDealer;
        public volatile GameSnapshot currentSnap;
        public volatile boolean stop;
        public Thread blockThread;
        public volatile EngineStart resumeSeed; // 从 Redis 重建房间后，用于续跑本块
        public HainanConfig cfg;                 // 房间可设项（创建房间弹窗）
        public volatile DealerFlow flow;         // 当前块的庄/令/底分状态（含重建）

        Room(String code, long hostUserId) {
            this.code = code;
            this.hostUserId = hostUserId;
        }
    }

    /** 一块的起始参数（新建 = 默认；从 Redis 恢复 = 带已打到把数、流状态与快照）。 */
    private static final class EngineStart {
        HainanConfig cfg;
        DealerFlow flow;       // 非空则用其继续；空则新开（随机首庄）
        long sessionId = -1L;
        Map<Seat, Integer> coins = new EnumMap<Seat, Integer>(Seat.class);
        GameSnapshot resume = null;
    }

    /** 新开一局/一轮：随机首庄 + 全新流状态。 */
    private DealerFlow newFlow(Room room) {
        HainanConfig cfg = room.cfg == null ? HainanConfig.defaultConfig() : room.cfg;
        Seat first = Seat.values()[random.nextInt(4)];
        return new DealerFlow(cfg, first);
    }

    // ==================== Redis 快照结构 ====================

    public static class MemberInfo {
        public String seat;
        public long userId;
        public String nickname;
    }

    public static class RedisRoom {
        public long hostUserId;
        public String state;            // WAITING / PLAYING / BETWEEN
        public int blockNo;
        public long sessionId;
        public int currentHand;
        public String dealer;
        public Map<String, Integer> coins = new LinkedHashMap<String, Integer>();
        public List<MemberInfo> members = new ArrayList<MemberInfo>();
        public GameSnapshot snapshot;   // 可空
        public HainanConfig cfg;         // 可空
        public String firstDealer;       // 庄流（海南）：首局庄
        public int bottom;               // 当前庄底分
        public int windIdx;              // 令 0..3
        public int flowHandNo;           // 下把编号
        public int firstDealerBegins;    // 首局庄已开始坐庄次数
        public int handsPlayed;
    }

    // ==================== /room 入口 ====================

    public void open(WebSocketSession session) {
        long userId = parseLong(session, "userId", 0L);
        if (userId > 0) {
            userOfSession.put(session.getId(), userId);
        }
    }

    public void onMessage(WebSocketSession session, String payload) {
        Long userId = userOfSession.get(session.getId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = mapper.readValue(payload, Map.class);
            String type = (String) msg.get("type");
            if ("createRoom".equals(type)) {
                createRoom(session, userId == null ? 0L : userId, msg.get("config"));
            } else if ("joinRoom".equals(type)) {
                String code = msg.get("code") == null ? "" : String.valueOf(msg.get("code")).trim();
                joinRoom(session, userId == null ? 0L : userId, code);
            } else if ("leaveRoom".equals(type)) {
                leave(userId == null ? 0L : userId);
            } else {
                sendJson(session, denied("未知指令: " + type));
            }
        } catch (Exception e) {
            log.warn("解析 /room 消息失败：{}", e.getMessage());
        }
    }

    public void onClose(WebSocketSession session) {
        Long userId = userOfSession.remove(session.getId());
        if (userId == null) {
            return;
        }
        String code = codeByUser.get(userId);
        if (code == null) {
            return;
        }
        Room room = roomsByCode.get(code);
        if (room == null) {
            codeByUser.remove(userId, code);
            return;
        }
        synchronized (room) {
            Member m = memberOf(room, userId);
            if (m == null || m.roomWs != session) {
                return;
            }
            m.roomWs = null;
            if (room.state != State.PLAYING) {
                removeMember(room, userId, true);
            }
        }
    }

    // ==================== /game 入口 ====================

    public void openGame(WebSocketSession session) {
        long userId = parseLong(session, "userId", 0L);
        String code = parseParam(session, "code");
        Seat seat = parseSeat(session);
        if (userId <= 0 || code == null || seat == null) {
            closeQuietly(session);
            return;
        }
        gameUserOfSession.put(session.getId(), userId);
        gameCodeOfSession.put(session.getId(), code);
        gameSeatOfSession.put(session.getId(), seat);
        bindGameSeat(code, seat, userId, session);
    }

    public void onGameMessage(WebSocketSession session, String payload) {
        Long userId = gameUserOfSession.get(session.getId());
        String code = gameCodeOfSession.get(session.getId());
        if (userId == null || code == null) {
            return;
        }
        Room room = roomsByCode.get(code);
        if (room == null) {
            return;
        }
        synchronized (room) {
            Member m = memberOf(room, userId);
            if (m == null) {
                return;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> msg = mapper.readValue(payload, Map.class);
                String type = (String) msg.get("type");
                int reqId = msg.get("reqId") == null ? -1 : ((Number) msg.get("reqId")).intValue();
                if ("discard".equals(type)) {
                    m.human.discard(reqId, ((Number) msg.get("tile")).intValue());
                } else if ("act".equals(type)) {
                    m.human.act(reqId, ((Number) msg.get("index")).intValue());
                } else if ("pass".equals(type)) {
                    m.human.pass(reqId);
                } else if ("baoting".equals(type)) {
                    m.human.baoTing(reqId);
                } else if ("rematch".equals(type)) {
                    onRematch(room, userId);
                } else if ("leaveRoom".equals(type) || "quit".equals(type)) {
                    handleMemberLeft(room, m);
                }
            } catch (Exception e) {
                log.warn("解析 /game 消息失败：{}", e.getMessage());
            }
        }
    }

    public void onGameClose(WebSocketSession session) {
        Long userId = gameUserOfSession.remove(session.getId());
        String code = gameCodeOfSession.remove(session.getId());
        gameSeatOfSession.remove(session.getId());
        if (userId == null || code == null) {
            return;
        }
        Room room = roomsByCode.get(code);
        if (room == null) {
            return;
        }
        synchronized (room) {
            Member m = memberOf(room, userId);
            if (m == null || m.gameWs != session) {
                return;
            }
            m.gameWs = null;
            handleMemberLeft(room, m);
        }
    }

    /** 对局中任何人（含房主）离开/刷新：不断房，机器人托管该座，房间/座位保留以便“返回房间”。 */
    private void handleMemberLeft(Room room, Member m) {
        if (room.state == State.PLAYING) {
            m.offline = true;
            m.controller.setDelegate(new BotController());
            log.info("房间 {} 座位 {} 玩家 {} 中途离开，机器人托管(可返回)", room.code, m.seat, m.userId);
            return;
        }
        disband(room, "有成员离开，房间解散");
    }

    private void bindGameSeat(String code, Seat seat, long userId, WebSocketSession session) {
        Room room = roomsByCode.get(code);
        if (room == null) {
            room = resurrect(code); // 服务重启后凭 Redis 快照重建
        }
        if (room == null) {
            sendJson(session, denied("房间不存在或已解散"));
            return;
        }
        synchronized (room) {
            Member m = room.members.get(seat);
            if (m == null || m.userId != userId) {
                sendJson(session, denied("座位不匹配，请重进房间"));
                return;
            }
            m.gameWs = session;
            m.offline = false;
            m.human.attach(msg -> sendJson(session, msg));
            m.controller.setDelegate(m.human);
            m.human.resendPending();
            codeByUser.put(userId, code);

            Map<String, Object> welcome = new HashMap<String, Object>();
            welcome.put("type", "welcome");
            welcome.put("seat", seat.name());
            welcome.put("roomId", code);
            List<Map<String, Object>> players = new ArrayList<Map<String, Object>>();
            for (Seat s : Seat.values()) {
                Member mm = room.members.get(s);
                if (mm == null) continue;
                Map<String, Object> p = new HashMap<String, Object>();
                p.put("userId", mm.userId);
                p.put("nickname", mm.nickname);
                p.put("seat", s.name());
                players.add(p);
            }
            welcome.put("players", players);
            sendJson(session, welcome);

            Map<String, Object> match = new HashMap<String, Object>();
            match.put("type", "match");
            match.put("total", -1); // 打满四风，不固定把数
            sendJson(session, match);

            if (room.state == State.PLAYING && room.active != null) {
                Map<String, Object> hs = new HashMap<String, Object>();
                hs.put("type", "hand_start");
                hs.put("hand", room.currentHand);
                hs.put("total", -1);
                hs.put("dealer", room.currentDealer == null ? Seat.EAST.name() : room.currentDealer.name());
                if (room.flow != null) {
                    hs.put("wind", room.flow.windIdx());
                    hs.put("windName", DealerFlow.windName(room.flow.windIdx()));
                    hs.put("bottom", room.flow.bottom);
                }
                hs.put("coins", coinView(room));
                sendJson(session, hs);
                pushSeatState(session, room, m.seat);
            }
            // 重启后第一次有人连回：若 Redis 说本块正在打，则续跑本块
            if (room.state == State.PLAYING && room.blockThread == null && room.resumeSeed != null) {
                EngineStart seed = room.resumeSeed;
                room.resumeSeed = null;
                startEngine(room, false, seed);
            }
            log.info("座位 {} 玩家 {} 连入对局 {}", seat, userId, code);
        }
    }

    // ==================== 创建 / 加入 / 离开（等待房） ====================

    private void createRoom(WebSocketSession session, long userId, Object cfgRaw) {
        if (userId <= 0) {
            sendJson(session, denied("请先登录后再创建房间"));
            return;
        }
        String busy = busyRoomCode(userId);
        if (busy != null) {
            sendJson(session, denied("你仍在对局房间 " + busy + " 中，请先返回房间或等对局结束"));
            return;
        }
        solo.kickUser(userId);
        String code;
        Room room;
        synchronized (roomsByCode) {
            do {
                code = String.valueOf(100000 + random.nextInt(900000));
            } while (roomsByCode.containsKey(code));
            room = new Room(code, userId);
            room.cfg = parseConfig(cfgRaw);
            room.members.put(Seat.EAST, new Member(Seat.EAST, userId, nicknameOf(userId), session));
            roomsByCode.put(code, room);
        }
        codeByUser.put(userId, code);
        userOfSession.put(session.getId(), userId);
        sendRoomInfo(room, session);
        log.info("用户 {} 创建房间 {}（海南配置 enabled={} 底分={}）", userId, code,
                room.cfg.enabled, room.cfg.basePoint);
    }

    /** 创建房间配置：前端传入 JSON 对象 → HainanConfig；缺省用默认。 */
    private HainanConfig parseConfig(Object raw) {
        if (raw == null) {
            return HainanConfig.defaultConfig();
        }
        try {
            return mapper.convertValue(raw, HainanConfig.class);
        } catch (Exception e) {
            log.warn("解析房间配置失败，使用默认：{}", e.getMessage());
            return HainanConfig.defaultConfig();
        }
    }

    private void joinRoom(WebSocketSession session, long userId, String code) {
        if (userId <= 0) {
            sendJson(session, denied("请先登录后再加入房间"));
            return;
        }
        if (!isSixDigit(code)) {
            sendJson(session, denied("房间码应为 6 位数字"));
            return;
        }
        Room room = roomsByCode.get(code);
        if (room == null) {
            room = resurrect(code); // 服务重启后，若 Redis 有 BETWEEN/等待态存档则重建
        }
        if (room == null) {
            sendJson(session, denied("房间不存在或已解散"));
            return;
        }
        synchronized (room) {
            Member existing = memberOf(room, userId);
            if (existing != null) {
                solo.kickUser(userId);
                existing.roomWs = session;
                userOfSession.put(session.getId(), userId);
                codeByUser.put(userId, code);
                sendRoomInfo(room, session);
                return;
            }
            if (room.state == State.PLAYING) {
                sendJson(session, denied("对局进行中，请直接返回牌局"));
                return;
            }
            if (memberCount(room) >= MAX_MEMBERS) {
                sendJson(session, denied("房间已满(4人)"));
                return;
            }
            String busy = busyRoomCode(userId);
            if (busy != null) {
                sendJson(session, denied("你仍在对局房间 " + busy + " 中，请先返回房间或等对局结束"));
                return;
            }
            solo.kickUser(userId);

            Seat seat = nextFreeSeat(room);
            if (seat == null) {
                sendJson(session, denied("房间已满(4人)"));
                return;
            }
            Member m = new Member(seat, userId, nicknameOf(userId), session);
            room.members.put(seat, m);
            codeByUser.put(userId, code);
            userOfSession.put(session.getId(), userId);
            sendRoomInfo(room, null);
            if (room.state == State.WAITING && memberCount(room) == MAX_MEMBERS) {
                startMatch(room);
            }
            log.info("用户 {} 加入房间 {} 座位 {}", userId, code, seat);
        }
    }

    public void leave(long userId) {
        if (userId <= 0) {
            return;
        }
        String code = codeByUser.get(userId);
        if (code == null) {
            return;
        }
        Room room = roomsByCode.get(code);
        if (room == null) {
            codeByUser.remove(userId, code);
            return;
        }
        synchronized (room) {
            Member m = memberOf(room, userId);
            if (m != null) {
                handleMemberLeft(room, m);
            }
        }
    }

    /** 若用户已被某个“进行中”房间占座，返回其房间码（创建/加入新房前拦截）。 */
    private String busyRoomCode(long userId) {
        String code = codeByUser.get(userId);
        if (code == null) {
            return null;
        }
        Room room = roomsByCode.get(code);
        if (room == null) {
            return null;
        }
        if (room.state == State.PLAYING || room.state == State.BETWEEN) {
            return code;
        }
        return null;
    }

    // ==================== 大厅“返回房间” ====================

    /** 登录用户是否有一个可返回的房间（中途离开但房间还在）。 */
    public Map<String, Object> pendingReturn(long userId) {
        Map<String, Object> out = new HashMap<String, Object>();
        out.put("exists", false);
        if (userId <= 0) {
            return out;
        }
        String code = codeByUser.get(userId);
        if (code == null) {
            return out;
        }
        Room room = roomsByCode.get(code);
        if (room == null) {
            return out;
        }
        synchronized (room) {
            Member m = memberOf(room, userId);
            if (m == null) {
                return out;
            }
            out.put("exists", true);
            out.put("code", code);
            out.put("state", room.state.name());
            out.put("seat", m.seat.name());
            out.put("hostUserId", room.hostUserId);
        }
        return out;
    }

    // ==================== 开局 / 引擎 ====================

    private void startMatch(Room room) {
        room.state = State.PLAYING;
        room.stop = false;
        Map<String, Object> start = new HashMap<String, Object>();
        start.put("type", "start");
        start.put("code", room.code);
        for (Seat s : Seat.values()) {
            Member m = room.members.get(s);
            if (m == null) continue;
            start.put("seat", s.name());
            sendJson(m.roomWs, start);
        }
        log.info("房间 {} 满 4 人，自动开局", room.code);
        room.blockThread = new Thread(() -> runBlock(room), "room-bind-" + room.code);
        room.blockThread.setDaemon(true);
        room.blockThread.start();
    }

    private void runBlock(Room room) {
        long deadline = System.currentTimeMillis() + BIND_GRACE_MS;
        while (System.currentTimeMillis() < deadline && room.state == State.PLAYING && !room.stop) {
            if (allBound(room)) break;
            try { Thread.sleep(200); } catch (InterruptedException e) { return; }
        }
        if (room.state != State.PLAYING || room.stop) {
            return;
        }
        // 宽限期内没连上的座位（含房主）一律机器人托管，游戏照常开始、座位保留
        for (Seat s : Seat.values()) {
            Member m = room.members.get(s);
            if (m != null && !bound(m)) {
                m.offline = true;
                m.controller.setDelegate(new BotController());
                log.info("房间 {} 座位 {} 玩家未连入，机器人托管本块", room.code, s);
            }
        }
        runEngine(room, new EngineStart());
    }

    /** 房主再来一轮（块与块之间）。 */
    private void onRematch(Room room, long userId) {
        if (userId != room.hostUserId || room.state != State.BETWEEN) {
            return;
        }
        for (Member m : new ArrayList<Member>(room.members.values())) {
            if (!bound(m)) {
                disband(room, "有成员离开，房间解散");
                return;
            }
        }
        room.state = State.PLAYING;
        log.info("房主 {} 在房间 {} 开启新一轮", userId, room.code);
        startEngine(room, false, new EngineStart());
    }

    private void startEngine(Room room, boolean waitBind, EngineStart seed) {
        room.blockThread = new Thread(() -> runEngine(room, seed), "room-engine-" + room.code);
        room.blockThread.setDaemon(true);
        room.blockThread.start();
    }

    /** 跑完一块（16 把，或从 Redis 恢复的某把续到 16 把）。 */
    private void runEngine(Room room, EngineStart seed) {
        room.stop = false;
        room.blockNo++;
        room.resumeSeed = null;
        if (seed.coins.isEmpty()) {
            for (Seat s : Seat.values()) {
                seed.coins.put(s, 0);
            }
        }
        for (Seat s : Seat.values()) {
            room.coins.put(s, seed.coins.get(s) == null ? 0 : seed.coins.get(s));
        }

        long sessionId = seed.sessionId;
        if (sessionId <= 0) {
            List<Long> ids = new ArrayList<Long>();
            for (Seat s : Seat.values()) {
                Member m = room.members.get(s);
                ids.add(m == null ? -1L : m.userId);
            }
            try {
                sessionId = mysql.createSession(room.code, room.hostUserId, mapper.writeValueAsString(ids));
            } catch (Exception e) {
                log.warn("创建 game_sessions 失败：{}", e.getMessage());
            }
        }
        room.sessionId = sessionId;
        final long sid = sessionId;

        HainanConfig cfg = seed.cfg != null ? seed.cfg : (room.cfg != null ? room.cfg : HainanConfig.defaultConfig());
        room.cfg = cfg;
        DealerFlow flow = seed.flow;
        boolean resumeNext = seed.resume != null;
        if (flow == null) {
            flow = newFlow(room);
        }
        room.flow = flow;
        saveRoom(room, null);

        try {
            while (!room.stop && room.state == State.PLAYING && !flow.finished) {
                int hand = flow.handNo;
                Seat dealer = flow.dealer;
                room.currentHand = hand;
                room.currentDealer = dealer;
                Map<String, Object> hs = new HashMap<String, Object>();
                hs.put("type", "hand_start");
                hs.put("hand", hand);
                hs.put("total", -1); // 打满四风，不固定总把数
                hs.put("dealer", dealer.name());
                hs.put("wind", flow.windIdx());
                hs.put("windName", DealerFlow.windName(flow.windIdx()));
                hs.put("bottom", flow.bottom);
                hs.put("coins", coinView(room));
                broadcastGame(room, hs);

                Map<Seat, PlayerController> controllers = new EnumMap<Seat, PlayerController>(Seat.class);
                for (Seat s : Seat.values()) {
                    Member m = room.members.get(s);
                    controllers.put(s, m == null ? new BotController() : m.controller);
                }
                GameConfig config = new GameConfig(true, dealer, DISCARD_TIMEOUT_MS, ACTION_TIMEOUT_MS,
                        System.currentTimeMillis() + hand, true, 0, cfg.enabled, flow.windIdx());
                RoomListener listener = new RoomListener(room);
                RoomManager rm = new RoomManager(config, controllers, listener);
                listener.setRoom(rm);
                room.active = rm;
                final int fhand = hand;
                rm.setCheckpoint(snap -> {
                    room.currentSnap = snap;
                    room.currentHand = fhand;
                    saveRoom(room, snap);
                });
                if (resumeNext) {
                    rm.resume(seed.resume);
                    resumeNext = false;
                } else {
                    rm.start();
                }
                room.active = null;
                room.currentSnap = null;
                if (room.stop || room.state != State.PLAYING) {
                    break;
                }
                RoundResult r = rm.getResult();

                Map<Seat, List<Integer>> flMap = new EnumMap<Seat, List<Integer>>(Seat.class);
                Map<Seat, List<Meld>> mMap = new EnumMap<Seat, List<Meld>>(Seat.class);
                for (Seat s : Seat.values()) {
                    Player pp = rm.getPlayer(s);
                    flMap.put(s, pp == null ? new ArrayList<Integer>() : new ArrayList<Integer>(pp.flowers));
                    mMap.put(s, pp == null ? new ArrayList<Meld>() : new ArrayList<Meld>(pp.melds));
                }
                HainanScore.Settlement st = HainanScore.settleRound(r, flow.dealer, flow.bottom, cfg,
                        flMap, mMap, rm.deckSize(), rm.genChainHit(), dealerFirstGangSeat(dealer, rm));
                Map<Seat, Integer> delta = st.delta;
                for (Seat s : Seat.values()) {
                    room.coins.put(s, room.coins.get(s) + delta.get(s));
                }
                Map<Seat, String> detailJsons = buildDetailJsons(st, flMap, mMap);
                if (sid > 0) {
                    try {
                        persistRound(sid, hand, r, room, delta, detailJsons);
                        mysql.updateSessionRound(sid, hand);
                    } catch (Exception e) {
                        log.warn("保存对局数据失败：{}", e.getMessage());
                    }
                }
                Map<String, Object> cu = new HashMap<String, Object>();
                cu.put("type", "coins");
                cu.put("hand", hand);
                cu.put("coins", coinView(room));
                broadcastGame(room, cu);

                flow.afterRound(r);
                room.flow = flow;
                saveRoom(room, null);
            }

            if (room.stop || room.state != State.PLAYING) {
                return;
            }
            boolean anyOffline = false;
            for (Member m : room.members.values()) {
                if (m != null && (!bound(m) || m.offline)) {
                    anyOffline = true;
                    break;
                }
            }
            if (anyOffline) {
                try {
                    if (sid > 0) {
                        mysql.disbandSession(sid);
                    }
                } catch (Exception e) {
                    log.warn("标记解散失败：{}", e.getMessage());
                }
                broadcastFinish(room, true, "有玩家中途未归，本轮结束后房间解散");
                stopAndClear(room);
                return;
            }
            try {
                if (sid > 0) {
                    mysql.finishSession(sid);
                }
            } catch (Exception e) {
                log.warn("结束对局失败：{}", e.getMessage());
            }
            room.state = State.BETWEEN;
            saveRoom(room, null);
            broadcastFinish(room, false, null);
        } catch (Throwable ex) {
            log.warn("对局线程异常：{}", ex.getMessage());
            disband(room, "对局异常终止");
        }
    }

    private void broadcastFinish(Room room, boolean disbandFlag, String reason) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("type", "match_finish");
        m.put("coins", coinView(room));
        m.put("disband", disbandFlag);
        if (reason != null) {
            m.put("reason", reason);
        }
        for (Member mem : room.members.values()) {
            if (mem == null) continue;
            m.put("isHost", mem.userId == room.hostUserId);
            sendJson(mem.gameWs, m);
        }
    }

    // ==================== Redis 快照存取 ====================

    private void saveRoom(Room room, GameSnapshot snap) {
        try {
            RedisRoom rs = new RedisRoom();
            rs.hostUserId = room.hostUserId;
            rs.state = room.state.name();
            rs.blockNo = room.blockNo;
            rs.sessionId = room.sessionId;
            rs.currentHand = room.currentHand;
            rs.dealer = room.currentDealer == null ? null : room.currentDealer.name();
            rs.cfg = room.cfg;
            DealerFlow f = room.flow;
            if (f != null) {
                rs.firstDealer = f.firstDealer().name();
                rs.bottom = f.bottom;
                rs.windIdx = f.windIdx();
                rs.flowHandNo = f.handNo;
                rs.firstDealerBegins = f.begins();
                rs.handsPlayed = f.handsPlayed();
            }
            for (Seat s : Seat.values()) {
                rs.coins.put(s.name(), room.coins.get(s) == null ? 0 : room.coins.get(s));
            }
            for (Member m : room.members.values()) {
                MemberInfo mi = new MemberInfo();
                mi.seat = m.seat.name();
                mi.userId = m.userId;
                mi.nickname = m.nickname;
                rs.members.add(mi);
            }
            rs.snapshot = snap;
            redis.saveRoomState(room.code, mapper.writeValueAsString(rs));
        } catch (Exception e) {
            log.warn("保存 Redis 多人房间失败({}): {}", room.code, e.getMessage());
        }
    }

    private Room resurrect(String code) {
        synchronized (roomsByCode) {
            Room existing = roomsByCode.get(code);
            if (existing != null) {
                return existing;
            }
            String json = null;
            try {
                json = redis.getRoomState(code);
            } catch (Exception e) {
                // Redis 不可用
            }
            if (json == null || json.isEmpty()) {
                return null;
            }
            try {
                RedisRoom rs = mapper.readValue(json, RedisRoom.class);
                Room room = new Room(code, rs.hostUserId);
                for (MemberInfo mi : rs.members) {
                    Seat s = Seat.valueOf(mi.seat);
                    room.members.put(s, new Member(s, mi.userId, mi.nickname, null));
                }
                room.state = State.valueOf(rs.state);
                room.blockNo = rs.blockNo;
                room.sessionId = rs.sessionId;
                room.currentHand = rs.currentHand;
                room.currentDealer = rs.dealer == null ? null : Seat.valueOf(rs.dealer);
                room.cfg = rs.cfg != null ? rs.cfg : HainanConfig.defaultConfig();
                for (String k : rs.coins.keySet()) {
                    room.coins.put(Seat.valueOf(k), rs.coins.get(k));
                }
                DealerFlow flow = null;
                if (rs.firstDealer != null) {
                    Seat first = Seat.valueOf(rs.firstDealer);
                    Seat cur = room.currentDealer == null ? first : room.currentDealer;
                    int handNo = rs.flowHandNo > 0 ? rs.flowHandNo : Math.max(1, rs.currentHand);
                    flow = DealerFlow.restore(room.cfg, first, cur,
                            rs.bottom > 0 ? rs.bottom : room.cfg.basePoint,
                            rs.windIdx, handNo,
                            rs.firstDealerBegins > 0 ? rs.firstDealerBegins : 1, rs.handsPlayed);
                }
                room.flow = flow;
                if (room.state == State.PLAYING) {
                    EngineStart seed = new EngineStart();
                    seed.sessionId = rs.sessionId;
                    seed.cfg = room.cfg;
                    seed.flow = flow;
                    seed.resume = rs.snapshot; // 可空：空则从下一把起继续
                    for (Seat s : Seat.values()) {
                        Integer c = room.coins.get(s);
                        seed.coins.put(s, c == null ? 0 : c);
                    }
                    room.resumeSeed = seed;
                }
                roomsByCode.put(code, room);
                for (Member m : room.members.values()) {
                    codeByUser.put(m.userId, code);
                }
                log.info("房间 {} 已从 Redis 快照重建(state={}, hand={})", code, room.state, room.currentHand);
                return room;
            } catch (Exception e) {
                log.warn("重建房间 {} 失败：{}", code, e.getMessage());
                return null;
            }
        }
    }

    // ==================== 牌局监听（4 座位广播 + turn） ====================

    private final class RoomListener implements GameListener {
        private final Room room;
        private volatile RoomManager rm;

        RoomListener(Room room) { this.room = room; }

        void setRoom(RoomManager rm) { this.rm = rm; }

        @Override public void onShuffle(int deckSize) {
            logAll("洗牌完成，共 " + deckSize + " 张");
        }

        @Override public void onDeal(Seat dealer) {
            Map<String, Object> m = new HashMap<String, Object>();
            m.put("type", "dealer");
            m.put("dealer", dealer.name());
            broadcastGame(room, m);
            for (Seat s : Seat.values()) {
                sendHand(s);
            }
            sendCounts();
        }

        @Override public void onDraw(Seat seat, int tile) {
            Map<String, Object> m = new HashMap<String, Object>();
            m.put("type", "draw");
            m.put("seat", seat.name());
            m.put("tile", tile);
            broadcastGame(room, m);
            sendCounts();
            sendHand(seat);
        }

        @Override public void onFlower(Seat seat, int tile) {
            Map<String, Object> m = new HashMap<String, Object>();
            m.put("type", "flower");
            m.put("seat", seat.name());
            m.put("tile", tile);
            broadcastGame(room, m);
            sendCounts();
            sendHand(seat);
        }

        @Override public void onDiscard(Seat seat, int tile) {
            Map<String, Object> m = new HashMap<String, Object>();
            m.put("type", "discard");
            m.put("seat", seat.name());
            m.put("tile", tile);
            broadcastGame(room, m);
            sendCounts();
            sendHand(seat);
        }

        @Override public void onMeld(Seat seat, Meld meld) {
            Map<String, Object> m = new HashMap<String, Object>();
            m.put("type", "meld");
            m.put("seat", seat.name());
            m.put("meld", serializeMeld(meld));
            broadcastGame(room, m);
            sendCounts();
            sendHand(seat);
        }

        @Override public void onHu(Seat seat, HuResult result, boolean selfDraw, int tile, Seat from) {
            Map<String, Object> m = new HashMap<String, Object>();
            m.put("type", "hu");
            m.put("seat", seat.name());
            m.put("selfDraw", selfDraw);
            m.put("tile", tile);
            m.put("from", from == null ? null : from.name());
            List<String> fans = new ArrayList<String>();
            if (rm != null) {
                Player p = rm.getPlayer(seat);
                if (p != null) {
                    for (FanType f : HainanScore.fansOfHand(p.hand, p.melds, p.flowers, tile)) {
                        fans.add(f.toString());
                    }
                }
            }
            if (fans.isEmpty()) {
                fans.add("平胡");
            }
            m.put("fans", fans);
            if (selfDraw && rm != null && rm.wasLastDrawKongFlower()) {
                m.put("ganKai", true);
            }
            broadcastGame(room, m);
        }

        @Override public void onTimeout(Seat seat, String action) {
            logAll(seat.cn + " " + action + " 超时，已自动处理");
        }

        @Override public void onRoundDraw() {
            logAll("流局");
        }

        @Override public void onTurnStart(Seat seat, String kind, long timeoutMs) {
            Map<String, Object> m = new HashMap<String, Object>();
            m.put("type", "turn");
            m.put("seat", seat == null ? null : seat.name());
            m.put("kind", kind);
            m.put("timeoutMs", timeoutMs);
            broadcastGame(room, m);
        }

        @Override public void onReport(Seat seat, int mode) {
            String name = mode == 1 ? "天听" : (mode == 2 ? "地听" : "报听");
            logAll(seat.cn + " 报听（" + name + "）");
            Map<String, Object> m = new HashMap<String, Object>();
            m.put("type", "report");
            m.put("seat", seat.name());
            m.put("mode", mode);
            broadcastGame(room, m);
        }

        @Override public void onResume() {
            // 从快照恢复一把：把当前全场状态推给所有在座端（服务重启/断线续跑用）
            if (rm == null) {
                return;
            }
            Map<String, List<Integer>> discards = new HashMap<String, List<Integer>>();
            Map<String, List<Map<String, Object>>> melds = new HashMap<String, List<Map<String, Object>>>();
            Map<String, Integer> counts = new HashMap<String, Integer>();
            for (Seat s : Seat.values()) {
                discards.put(s.name(), new ArrayList<Integer>(rm.getPlayerDiscards(s)));
                List<Map<String, Object>> ml = new ArrayList<Map<String, Object>>();
                Player p = rm.getPlayer(s);
                if (p != null) {
                    for (Meld meld : p.melds) {
                        ml.add(serializeMeld(meld));
                    }
                    counts.put(s.name(), p.hand.size());
                } else {
                    counts.put(s.name(), 0);
                }
                melds.put(s.name(), ml);
            }
            Map<String, Object> board = new HashMap<String, Object>();
            board.put("type", "board");
            board.put("discards", discards);
            board.put("melds", melds);
            Map<String, Object> countsMsg = new HashMap<String, Object>();
            countsMsg.put("type", "counts");
            countsMsg.put("counts", counts);
            for (Seat s : Seat.values()) {
                Member mem = room.members.get(s);
                if (mem == null || !bound(mem)) {
                    continue;
                }
                sendJson(mem.gameWs, board);
                sendJson(mem.gameWs, countsMsg);
            }
            for (Seat s : Seat.values()) {
                sendHand(s);
            }
        }

        @Override public void onEnd(RoundResult result) {
            Map<String, Object> m = new HashMap<String, Object>();
            m.put("type", "end");
            m.put("result", result.toString());
            broadcastGame(room, m);
        }

        private void sendHand(Seat seat) {
            if (rm == null) {
                return;
            }
            Member mem = room.members.get(seat);
            if (mem == null || !bound(mem)) {
                return;
            }
            Player p = rm.getPlayer(seat);
            if (p == null) {
                return;
            }
            List<Map<String, Object>> melds = new ArrayList<Map<String, Object>>();
            for (Meld meld : p.melds) {
                melds.add(serializeMeld(meld));
            }
            Map<String, Object> m = new HashMap<String, Object>();
            m.put("type", "hand");
            m.put("hand", new ArrayList<Integer>(p.hand));
            m.put("melds", melds);
            m.put("flowers", new ArrayList<Integer>(p.flowers));
            sendJson(mem.gameWs, m);
        }

        private void sendCounts() {
            if (rm == null) {
                return;
            }
            Map<String, Integer> counts = new HashMap<String, Integer>();
            for (Seat s : Seat.values()) {
                Player p = rm.getPlayer(s);
                counts.put(s.name(), p == null ? 0 : p.hand.size());
            }
            Map<String, Object> m = new HashMap<String, Object>();
            m.put("type", "counts");
            m.put("counts", counts);
            broadcastGame(room, m);
        }

        private void logAll(String text) {
            Map<String, Object> m = new HashMap<String, Object>();
            m.put("type", "log");
            m.put("text", text);
            broadcastGame(room, m);
        }
    }

    private Map<String, Object> serializeMeld(Meld meld) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("type", meld.type.name());
        if (meld.from != null) {
            m.put("from", meld.from.name());
        }
        List<Integer> tiles = new ArrayList<Integer>();
        for (int t : meld.tiles) {
            tiles.add(t);
        }
        m.put("tiles", tiles);
        return m;
    }

    // ==================== 对局持久化 ====================

    private void persistRound(long sessionId, int roundNum, RoundResult r, Room room,
                              Map<Seat, Integer> delta, Map<Seat, String> detailJsons) throws Exception {
        boolean isDraw = r.isDraw;
        int winType = isDraw ? 2 : (r.selfDraw ? 0 : 1);
        Long winnerId = (isDraw || r.winner == null) ? null : userIdOf(room, r.winner);
        Long loserId = (isDraw || r.selfDraw || r.from == null) ? null : userIdOf(room, r.from);
        Integer winTile = (r.winTile >= 0) ? r.winTile : null;

        String fanInfo = "{}";
        int totalFan = 0;
        if (!isDraw) {
            java.util.List<FanType> fans = HainanScore.multFans(r);
            Map<String, Integer> fanMap = new HashMap<String, Integer>();
            for (FanType f : fans) {
                fanMap.put(f.name(), 1);
            }
            fanInfo = mapper.writeValueAsString(fanMap);
            totalFan = fans.size();
        }

        String winHandJson = null;
        if (!isDraw && r.winHand != null) {
            Map<String, Object> wh = new HashMap<String, Object>();
            wh.put("hand", r.winHand);
            List<Map<String, Object>> melds = new ArrayList<Map<String, Object>>();
            if (r.winMelds != null) {
                for (Meld meld : r.winMelds) {
                    melds.add(serializeMeld(meld));
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
                int change = delta == null ? 0 : delta.get(s);
                String detail = detailJsons == null ? "[]"
                        : (detailJsons.get(s) == null ? "[]" : detailJsons.get(s));
                mysql.saveRoundScore(roundId, userIdOf(room, s), s.ordinal(), change, isWinner, detail);
            }
        }
    }

    private Long userIdOf(Room room, Seat seat) {
        if (seat == null) {
            return null;
        }
        Member m = room.members.get(seat);
        return m == null ? null : m.userId;
    }

    // ==================== 断线现场推送 ====================

    private void pushSeatState(WebSocketSession session, Room room, Seat seat) {
        RoomManager rm = room.active;
        if (rm == null) {
            return;
        }
        Map<String, List<Integer>> discards = new HashMap<String, List<Integer>>();
        Map<String, List<Map<String, Object>>> melds = new HashMap<String, List<Map<String, Object>>>();
        for (Seat s : Seat.values()) {
            discards.put(s.name(), new ArrayList<Integer>(rm.getPlayerDiscards(s)));
            List<Map<String, Object>> ml = new ArrayList<Map<String, Object>>();
            Player p = rm.getPlayer(s);
            if (p != null) {
                for (Meld meld : p.melds) {
                    ml.add(serializeMeld(meld));
                }
            }
            melds.put(s.name(), ml);
        }
        Map<String, Object> board = new HashMap<String, Object>();
        board.put("type", "board");
        board.put("discards", discards);
        board.put("melds", melds);
        sendJson(session, board);

        Map<String, Integer> counts = new HashMap<String, Integer>();
        for (Seat s : Seat.values()) {
            Player p = rm.getPlayer(s);
            counts.put(s.name(), p == null ? 0 : p.hand.size());
        }
        Map<String, Object> cm = new HashMap<String, Object>();
        cm.put("type", "counts");
        cm.put("counts", counts);
        sendJson(session, cm);

        Player me = rm.getPlayer(seat);
        if (me != null) {
            List<Map<String, Object>> ml = new ArrayList<Map<String, Object>>();
            for (Meld meld : me.melds) {
                ml.add(serializeMeld(meld));
            }
            Map<String, Object> h = new HashMap<String, Object>();
            h.put("type", "hand");
            h.put("hand", new ArrayList<Integer>(me.hand));
            h.put("melds", ml);
            h.put("flowers", new ArrayList<Integer>(me.flowers));
            sendJson(session, h);
        }
    }

    // ==================== 房间信息推送 ====================

    private void sendRoomInfo(Room room, WebSocketSession only) {
        Map<String, Object> msg = roomInfoMsg(room);
        if (only != null) {
            sendJson(only, msg);
        } else {
            for (Member m : new ArrayList<Member>(room.members.values())) {
                if (m.roomWs != null && m.roomWs.isOpen()) {
                    sendJson(m.roomWs, msg);
                }
            }
        }
    }

    private Map<String, Object> roomInfoMsg(Room room) {
        Map<String, Object> msg = new HashMap<String, Object>();
        msg.put("type", "room_info");
        msg.put("code", room.code);
        msg.put("hostUserId", room.hostUserId);
        msg.put("state", room.state.name());
        List<Map<String, Object>> members = new ArrayList<Map<String, Object>>();
        for (Seat s : Seat.values()) {
            Member m = room.members.get(s);
            if (m == null) {
                continue;
            }
            Map<String, Object> mm = new HashMap<String, Object>();
            mm.put("userId", m.userId);
            mm.put("nickname", m.nickname);
            mm.put("seat", s.name());
            members.add(mm);
        }
        msg.put("members", members);
        return msg;
    }

    // ==================== 成员移除 / 解散 ====================

    private void removeMember(Room room, long userId, boolean disbandIfHost) {
        Member m = memberOf(room, userId);
        if (m == null) {
            codeByUser.remove(userId, room.code);
            return;
        }
        codeByUser.remove(userId, room.code);
        room.members.remove(m.seat);
        m.roomWs = null;
        if (disbandIfHost && userId == room.hostUserId) {
            disband(room, "房主已离开，房间解散");
            return;
        }
        if (room.members.isEmpty()) {
            disband(room, "房间已空，解散");
            return;
        }
        sendRoomInfo(room, null);
    }

    private void disband(Room room, String reason) {
        String text = (reason == null) ? "房间已解散" : reason;
        List<Member> ms = new ArrayList<Member>(room.members.values());
        for (Member m : ms) {
            Map<String, Object> msg = new HashMap<String, Object>();
            msg.put("type", "room_closed");
            msg.put("reason", text);
            sendJson(m.roomWs, msg);
            sendJson(m.gameWs, msg);
        }
        stopAndClear(room);
        log.info("房间 {} 解散：{}", room.code, text);
    }

    private void stopAndClear(Room room) {
        room.state = State.BETWEEN;
        room.stop = true;
        RoomManager active = room.active;
        if (active != null) {
            try {
                active.cancel();
            } catch (Exception e) {
                // 忽略
            }
        }
        List<Member> ms = new ArrayList<Member>(room.members.values());
        roomsByCode.remove(room.code);
        for (Member m : ms) {
            codeByUser.remove(m.userId, room.code);
            m.roomWs = null;
            m.gameWs = null;
        }
        room.members.clear();
        try {
            redis.deleteRoomState(room.code);
        } catch (Exception e) {
            // 忽略
        }
    }

    // ==================== 广播 / 工具 ====================

    private void broadcastGame(Room room, Map<String, Object> msg) {
        for (Member m : new ArrayList<Member>(room.members.values())) {
            if (bound(m)) {
                sendJson(m.gameWs, msg);
            }
        }
    }

    private Map<String, Integer> coinView(Room room) {
        Map<String, Integer> view = new LinkedHashMap<String, Integer>();
        for (Seat s : Seat.values()) {
            view.put(s.name(), room.coins.get(s) == null ? 0 : room.coins.get(s));
        }
        return view;
    }

    private static boolean bound(Member m) {
        return m != null && m.gameWs != null && m.gameWs.isOpen();
    }

    private static boolean allBound(Room room) {
        for (Member m : room.members.values()) {
            if (!bound(m)) {
                return false;
            }
        }
        return true;
    }

    /** 组装每人“本局明细”JSON：{notes, flowers, gangs}，供战绩页图形化渲染。 */
    private Map<Seat, String> buildDetailJsons(HainanScore.Settlement st,
                                               Map<Seat, List<Integer>> flMap,
                                               Map<Seat, List<Meld>> mMap) {
        Map<Seat, String> out = new EnumMap<Seat, String>(Seat.class);
        for (Seat s : Seat.values()) {
            Map<String, Object> obj = new LinkedHashMap<String, Object>();
            List<String> notes = st.notes.get(s);
            obj.put("notes", notes == null ? new ArrayList<String>() : notes);
            List<Integer> fl = flMap == null ? null : flMap.get(s);
            obj.put("flowers", fl == null ? new ArrayList<Integer>() : fl);
            List<Map<String, Object>> gangs = new ArrayList<Map<String, Object>>();
            List<Meld> ms = mMap == null ? null : mMap.get(s);
            if (ms != null) {
                for (Meld m : ms) {
                    if (m.type == Meld.Type.GANG || m.type == Meld.Type.AN_GANG || m.type == Meld.Type.BU_GANG) {
                        Map<String, Object> gm = new LinkedHashMap<String, Object>();
                        gm.put("type", m.type.name());
                        List<Integer> ts = new ArrayList<Integer>();
                        for (int t : m.tiles) {
                            ts.add(t);
                        }
                        gm.put("tiles", ts);
                        gangs.add(gm);
                    }
                }
            }
            obj.put("gangs", gangs);
            try {
                out.put(s, mapper.writeValueAsString(obj));
            } catch (Exception e) {
                out.put(s, "{}");
            }
        }
        return out;
    }

    /** 探测“庄首张出牌被谁明杠”（包杠）；无则 null。 */
    private static Seat dealerFirstGangSeat(Seat dealer, RoomManager rm) {
        if (dealer == null || rm == null) {
            return null;
        }
        List<Integer> ds = rm.getPlayerDiscards(dealer);
        if (ds == null || ds.isEmpty()) {
            return null;
        }
        int t0 = ds.get(0);
        for (Seat s : Seat.values()) {
            Player p = rm.getPlayer(s);
            if (p == null) {
                continue;
            }
            for (Meld m : p.melds) {
                if (m.type == Meld.Type.GANG && m.from == dealer
                        && m.tiles != null && m.tiles.length > 0 && m.tiles[0] == t0) {
                    return s;
                }
            }
        }
        return null;
    }

    private static Member memberOf(Room room, long userId) {        for (Member m : room.members.values()) {
            if (m.userId == userId) {
                return m;
            }
        }
        return null;
    }

    private static int memberCount(Room room) {
        return room.members.size();
    }

    private static Seat nextFreeSeat(Room room) {
        for (Seat s : Seat.values()) {
            if (!room.members.containsKey(s)) {
                return s;
            }
        }
        return null;
    }

    private String nicknameOf(long userId) {
        try {
            String nick = mysql.findNickname(userId);
            if (nick != null && !nick.isEmpty()) {
                return nick;
            }
            String name = mysql.getUsername(userId);
            if (name != null && !name.isEmpty()) {
                return name;
            }
        } catch (Exception e) {
            // 忽略
        }
        return "玩家" + userId;
    }

    private static boolean isSixDigit(String code) {
        return code != null && code.matches("\\d{6}");
    }

    private static Seat parseSeat(WebSocketSession session) {
        String v = parseParam(session, "seat");
        if (v == null) {
            return null;
        }
        try {
            return Seat.valueOf(v);
        } catch (Exception e) {
            return null;
        }
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

    private Map<String, Object> denied(String reason) {
        Map<String, Object> msg = new HashMap<String, Object>();
        msg.put("type", "join_denied");
        msg.put("reason", reason);
        return msg;
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            if (session != null && session.isOpen()) {
                session.close();
            }
        } catch (Exception e) {
            // 忽略
        }
    }

    private void sendJson(WebSocketSession session, Map<String, Object> msg) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            String json = mapper.writeValueAsString(msg);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            log.warn("发送消息失败：{}", e.getMessage());
        }
    }
}
