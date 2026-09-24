package hainanMahjong.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import hainanMahjong.engine.model.FanType;
import hainanMahjong.rules.HuResult;
import hainanMahjong.engine.RoomManager;
import hainanMahjong.engine.bot.BotController;
import hainanMahjong.engine.model.GameConfig;
import hainanMahjong.engine.model.Meld;
import hainanMahjong.engine.model.Player;
import hainanMahjong.engine.model.RoundResult;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.engine.port.GameListener;
import hainanMahjong.engine.port.PlayerController;
import hainanMahjong.room.EngineStart;
import hainanMahjong.room.Member;
import hainanMahjong.room.Room;
import hainanMahjong.room.RoomRegistry;
import hainanMahjong.room.RoomSupport;
import hainanMahjong.room.State;
import hainanMahjong.rules.DealerFlow;
import hainanMahjong.rules.HainanConfig;
import hainanMahjong.rules.HainanScore;
import hainanMahjong.service.GameEngineService;
import hainanMahjong.service.GamePushService;
import hainanMahjong.mapper.GameRoundsMapper;
import hainanMahjong.service.RoomService;
import hainanMahjong.service.RoomSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static hainanMahjong.room.RoomConfig.ACTION_TIMEOUT_MS;
import static hainanMahjong.room.RoomConfig.BIND_GRACE_MS;
import static hainanMahjong.room.RoomConfig.DISCARD_TIMEOUT_MS;
import static hainanMahjong.room.RoomConfig.DRAW_PAUSE_MS;
import static hainanMahjong.room.RoomConfig.HAND_END_MS;
import static hainanMahjong.room.RoomConfig.MAX_MEMBERS;

/**
 * 牌局引擎调度：开局、跑块、跑把、监听并推送。
 *
 * <p>从 {@code MultiPlayerRoomServiceImpl} 整体搬出，<b>逻辑逐字保留</b>
 * （除方法可见性与类名调整）。</p>
 *
 * <p><b>线程</b>（名字是线上排查依据，不要改）：</p>
 * <ul>
 *   <li>{@code room-bind-<房间码>} —— {@link #startMatch} 起的线程，先跑 {@link #runBlock}
 *       等座位连入，再进入 {@link #runEngine}</li>
 *   <li>{@code room-engine-<房间码>} —— {@link #startEngine} 起的线程，直接跑 {@link #runEngine}
 *       （"再来一轮"与 Redis 续跑用）</li>
 * </ul>
 *
 * <p><b>调用链注意</b>：{@code runBlock} 在宽限期内无人连入时会<b>同步</b>调用
 * {@code RoomService.disband} → {@code stopAndClear} → {@code RoomRegistry.unregister}。
 * 即 GameEngineService → RoomService → RoomRegistry 的单向链，<b>不要反向依赖</b>。</p>
 *
 * <p><b>锁</b>：房对象锁 {@code synchronized (room)}，与其它模块一致。</p>
 */
@Service
public class GameEngineServiceImpl implements GameEngineService {

    private static final Logger log = LoggerFactory.getLogger(GameEngineServiceImpl.class);

    private final RoundPersister roundPersist;
    private final GamePushService push;
    private final RoomSnapshotService snapshots;
    private final RoomRegistry registry;
    private final RoomService rooms;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Random random = new Random();

    public GameEngineServiceImpl(RoundPersister roundPersist,
                                 GamePushService push,
                                 RoomSnapshotService snapshots,
                                 RoomRegistry registry,
                                 RoomService rooms) {
        this.roundPersist = roundPersist;
        this.push = push;
        this.snapshots = snapshots;
        this.registry = registry;
        this.rooms = rooms;
    }

    // ==================== 入口 ====================

    @Override
    public void startMatch(Room room) {
        room.state = State.PLAYING;
        room.stop = false;
        Map<String, Object> start = new HashMap<>();
        start.put("type", "start");
        start.put("code", room.code);
        int real = 0;
        for (Seat s : Seat.values()) {
            Member m = room.members.get(s);
            if (m == null || RoomSupport.isBot(m) || m.roomWs == null) {
                continue;
            }
            real++;
            start.put("seat", s.name());
            push.sendJson(m.roomWs, start);
        }
        log.info("房间 {} 开局（{} 真人 + {} 电脑）", room.code, real, RoomSupport.memberCount(room) - real);
        room.blockThread = new Thread(() -> runBlock(room), "room-bind-" + room.code);
        room.blockThread.setDaemon(true);
        room.blockThread.start();
    }

    /** 等待房房主“人机补齐”：空缺座位补入电脑，但仍停留在等待房，由房主再点“开始”开局。 */
    @Override
    public void fillBotsFor(long userId) {
        if (userId <= 0) {
            return;
        }
        String code = registry.codeOfUser(userId);
        if (code == null) {
            return;
        }
        Room room = registry.find(code);
        if (room == null) {
            return;
        }
        synchronized (room) {
            if (userId != room.hostUserId || room.state != State.WAITING) {
                return;
            }
            int added = 0;
            for (Seat s : Seat.values()) {
                if (room.members.containsKey(s)) {
                    continue;
                }
                Member bot = new Member(s, RoomSupport.botUserId(s), RoomSupport.botNickname(s), null);
                bot.controller.setDelegate(new BotController());
                room.members.put(s, bot);
                added++;
            }
            if (added == 0) {
                return;
            }
            push.sendRoomInfo(room, null); // 停留在等待房：房主再点“开始”才开局
        }
    }

    /** 等待房房主在补位后“开始对局”（真人+电脑按当前座位开局）。 */
    @Override
    public void startNowFor(long userId) {
        if (userId <= 0) {
            return;
        }
        String code = registry.codeOfUser(userId);
        if (code == null) {
            return;
        }
        Room room = registry.find(code);
        if (room == null) {
            return;
        }
        synchronized (room) {
            if (userId != room.hostUserId || room.state != State.WAITING) {
                return;
            }
            if (RoomSupport.memberCount(room) < MAX_MEMBERS) {
                return; // 还有空位：先等真人加入或“人机补齐”
            }
            startMatch(room);
        }
    }

    /** 房主再来一轮（块与块之间）。 */
    @Override
    public void onRematch(Room room, long userId) {
        if (userId != room.hostUserId || room.state != State.BETWEEN) {
            return;
        }
        for (Member m : new ArrayList<>(room.members.values())) {
            if (!RoomSupport.isBot(m) && !RoomSupport.bound(m)) {
                rooms.disband(room, "有成员离开，房间解散");
                return;
            }
        }
        room.state = State.PLAYING;
        EngineStart seed = new EngineStart();
        seed.sessionId = room.sessionId; // 沿用同一 session：战绩在同一房间下继续累加
        seed.cfg = room.cfg;
        for (Seat s : Seat.values()) {
            seed.coins.put(s, room.coins.get(s) == null ? 0 : room.coins.get(s)); // 金币不清空
        }
        if (room.flow != null) {
            // 新一轮（随机首庄/东风令重新起算），但把数在上一轮基础上继续，避免唯一键冲突
            DealerFlow nf = newFlow(room);
            nf.handNo = Math.max(1, room.flow.handNo);
            seed.flow = nf;
        }
        log.info("房主 {} 在房间 {} 开启新一轮（同房间同战绩，把数从 {} 继续）",
                userId, room.code, seed.flow == null ? 1 : seed.flow.handNo);
        /*
         * 先广播 match，再起引擎线程。
         *
         * 为什么必须发这一条：前端拿 playing/ended 两个标志决定「再来一轮」按钮显不显示
         * （ended && !playing）。上一轮结束时 match_finish 把 playing 置 false、ended 置 true，
         * 而新一轮不再发 match 的话这两个标志永远不会复位 —— 结果整个新一轮里
         * 右下角那个按钮一直挂着（用户报过："显示一次按钮就行，不要每把都显示"）。
         *
         * 放在 startEngine 之前是为了避免和引擎的第一条 hand_start 抢跑，
         * 保证前端先收到"新一轮开始"再收到"第 N 把开始"。
         */
        Map<String, Object> match = new HashMap<>();
        match.put("type", "match");
        match.put("total", -1);
        match.put("continued", true); // 标记这是"同一房间的又一轮"，前端据此保留局内战绩
        push.broadcastGame(room, match);

        startEngine(room, seed);
    }

    /** 起 room-engine 线程直接跑一块（等待座位连入的逻辑在 {@link #runBlock} 内）。 */
    @Override
    public void startEngine(Room room, EngineStart seed) {
        room.blockThread = new Thread(() -> runEngine(room, seed), "room-engine-" + room.code);
        room.blockThread.setDaemon(true);
        room.blockThread.start();
    }

    // ==================== 引擎主循环 ====================

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
            if (m != null && !RoomSupport.isBot(m) && !RoomSupport.bound(m)) {
                m.offline = true;
                m.controller.setDelegate(new BotController());
                log.info("房间 {} 座位 {} 玩家未连入，机器人托管本块", room.code, s);
            }
        }
        // 四人（含房主）都没连入/已离开 → 房间直接解散，不开 4 机器人空跑
        if (noHumanBound(room)) {
            rooms.disband(room, "无人进入牌局，房间解散");
            return;
        }
        runEngine(room, new EngineStart());
    }

    /** 房间仍在对局时小睡一段（房间被解散/停止会尽早醒来）。 */
    private void sleepQuiet(Room room, long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (!room.stop && room.state == State.PLAYING && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** 跑完一轮（打满四风，或从 Redis 恢复的某把续跑）；再来一轮沿用同一 session/金币继续。 */
    private void runEngine(Room room, EngineStart seed) {
        room.stop = false;
        room.blockNo++;
        room.resumeSeed = null;
        for (Seat s : Seat.values()) {
            room.coins.put(s, seed.coins.get(s) == null ? 0 : seed.coins.get(s));
        }

        long sessionId = seed.sessionId;
        if (sessionId <= 0) {
            List<Long> ids = new ArrayList<>();
            for (Seat s : Seat.values()) {
                Member m = room.members.get(s);
                ids.add(m == null ? -1L : m.userId);
            }
            try {
                sessionId = roundPersist.createSession(room.code, room.hostUserId, ids);
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
        snapshots.save(room, null);

        try {
            while (!room.stop && room.state == State.PLAYING && !flow.finished) {
                int hand = flow.handNo;
                Seat dealer = flow.dealer;
                room.currentHand = hand;
                room.currentDealer = dealer;
                Map<String, Object> hs = new HashMap<>();
                hs.put("type", "hand_start");
                hs.put("hand", hand);
                hs.put("total", -1); // 打满四风，不固定总把数
                hs.put("dealer", dealer.name());
                hs.put("wind", flow.windIdx());
                hs.put("windName", DealerFlow.windName(flow.windIdx()));
                hs.put("bottom", flow.bottom);
                hs.put("coins", push.coinView(room));
                push.broadcastGame(room, hs);

                Map<Seat, PlayerController> controllers = new EnumMap<>(Seat.class);
                for (Seat s : Seat.values()) {
                    Member m = room.members.get(s);
                    if (RoomSupport.isBot(m)) {
                        // 补位电脑：引擎里始终由机器人托管（含重建/再来一轮后）
                        m.controller.setDelegate(new BotController());
                    }
                    controllers.put(s, m == null ? new BotController() : m.controller);
                }
                GameConfig config = new GameConfig(true, dealer, DISCARD_TIMEOUT_MS, ACTION_TIMEOUT_MS,
                        System.currentTimeMillis() + hand, true, 0, cfg.enabled, flow.windIdx());
                config.fanGate = cfg.fanGate; // 有番/无番
                RoomListener listener = new RoomListener(room);
                RoomManager rm = new RoomManager(config, controllers, listener);
                listener.setRoom(rm);
                room.active = rm;
                final int fHand = hand;
                rm.setCheckpoint(snap -> {
                    room.currentSnap = snap;
                    room.currentHand = fHand;
                    snapshots.save(room, snap);
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

                Map<Seat, List<Integer>> flMap = new EnumMap<>(Seat.class);
                Map<Seat, List<Meld>> mMap = new EnumMap<>(Seat.class);
                for (Seat s : Seat.values()) {
                    Player pp = rm.getPlayer(s);
                    flMap.put(s, pp == null ? new ArrayList<>() : new ArrayList<>(pp.flowers));
                    mMap.put(s, pp == null ? new ArrayList<>() : new ArrayList<>(pp.melds));
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
                    } catch (Exception e) {
                        log.warn("保存对局数据失败：{}", e.getMessage());
                    }
                }
                Map<String, Object> cu = new HashMap<>();
                cu.put("type", "coins");
                cu.put("hand", hand);
                cu.put("coins", push.coinView(room));
                /*
                 * 把本把的明细 notes 一起广播。
                 *
                 * 原来 notes 只进落库明细（战绩页才看得到），对局中界面上完全没有 ——
                 * 于是"包三道/包四道"这种"为什么我一个人赔三家"的关键信息，
                 * 玩家在牌桌上根本看不到，只能打完去翻战绩。
                 * 这里随 coins 一起发，前端就能在胡牌横幅和局内战绩里直接写出来。
                 */
                Map<String, List<String>> noteView = new HashMap<>();
                for (Seat s : Seat.values()) {
                    List<String> ns = st.notes.get(s);
                    noteView.put(s.name(), ns == null ? new ArrayList<>() : new ArrayList<>(ns));
                }
                cu.put("notes", noteView);
                push.broadcastGame(room, cu);

                flow.afterRound(r);
                room.flow = flow;
                snapshots.save(room, null);
                // 胡牌大字+音效播放完再开下一把：非流局留足时间，流局稍短
                sleepQuiet(room, r.isDraw ? DRAW_PAUSE_MS : HAND_END_MS);
            }

            if (room.stop || room.state != State.PLAYING) {
                return;
            }
            boolean anyOffline = false;
            for (Member m : room.members.values()) {
                if (m != null && !RoomSupport.isBot(m) && (!RoomSupport.bound(m) || m.offline)) {
                    anyOffline = true;
                    break;
                }
            }
            if (anyOffline) {
                try {
                    if (sid > 0) {
                        roundPersist.disbandSession(sid);
                    }
                } catch (Exception e) {
                    log.warn("标记解散失败：{}", e.getMessage());
                }
                broadcastFinish(room, true, "有玩家中途未归，本轮结束后房间解散");
                rooms.stopAndClear(room);
                return;
            }
            // 一轮打满四风结束：不结束 session/不清金币——房主可“再来一轮”，战绩与金币延续
            room.state = State.BETWEEN;
            snapshots.save(room, null);
            broadcastFinish(room, false, null);
        } catch (Throwable ex) {
            log.warn("对局线程异常：{}", ex.getMessage());
            rooms.disband(room, "对局异常终止");
        }
    }

    private void broadcastFinish(Room room, boolean disbandFlag, String reason) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", "match_finish");
        m.put("coins", push.coinView(room));
        m.put("disband", disbandFlag);
        if (reason != null) {
            m.put("reason", reason);
        }
        for (Member mem : room.members.values()) {
            if (mem == null || RoomSupport.isBot(mem) || mem.gameWs == null || !mem.gameWs.isOpen()) {
                continue;
            }
            m.put("isHost", mem.userId == room.hostUserId);
            push.sendJson(mem.gameWs, m);
        }
    }

    // ==================== 只有本模块用的小工具 ====================

    /** 新开一局/一轮：随机首庄 + 全新流状态。 */
    private DealerFlow newFlow(Room room) {
        HainanConfig cfg = room.cfg == null ? HainanConfig.defaultConfig() : room.cfg;
        Seat first = Seat.values()[random.nextInt(4)];
        return new DealerFlow(cfg, first);
    }

    /** 是否已没有任何真人在线（四人全离线/未连入；补位的机器人不计）。 */
    private boolean noHumanBound(Room room) {
        for (Member mm : room.members.values()) {
            if (mm != null && !RoomSupport.isBot(mm) && !mm.offline && RoomSupport.bound(mm)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allBound(Room room) {
        for (Member m : room.members.values()) {
            if (m == null) {
                return false;
            }
            if (!RoomSupport.isBot(m) && !RoomSupport.bound(m)) {
                return false;
            }
        }
        return true;
    }

    private Long userIdOf(Room room, Seat seat) {
        if (seat == null) {
            return null;
        }
        Member m = room.members.get(seat);
        return m == null ? null : m.userId;
    }

    // ==================== 对局持久化 ====================

    private void persistRound(long sessionId, int roundNum, RoundResult r, Room room,
                              Map<Seat, Integer> delta, Map<Seat, String> detailJsons) throws Exception {
        boolean isDraw = r.isDraw;
        int winType = isDraw ? 2 : (r.selfDraw ? 0 : 1);
        Long winnerId = (isDraw || r.winner == null) ? null : userIdOf(room, r.winner);
        Long loserId = (isDraw || r.selfDraw || r.from == null) ? null : userIdOf(room, r.from);
        Integer winTile = (r.winTile >= 0) ? r.winTile : null;
        // 本把 庄 与 令（供战绩二级明细展示）
        DealerFlow df0 = room.flow;
        String dealerName = (df0 == null || df0.dealer == null) ? null : df0.dealer.name();
        int windIdx = df0 == null ? 0 : df0.windIdx();

        String fanInfo = "{}";
        int totalFan = 0;
        if (!isDraw) {
            java.util.List<FanType> fans = HainanScore.multFans(r);
            Map<String, Integer> fanMap = new HashMap<>();
            for (FanType f : fans) {
                fanMap.put(f.name(), 1);
            }
            fanInfo = mapper.writeValueAsString(fanMap);
            /*
             * total_fan 存的是【番数 = 倍数和】，不是番型个数。
             *
             * 战绩页用它挑"本场最高番型那一把"（RecordsServiceImpl.buildOverview）。
             * 存 fans.size() 的话口径就反了：一手"七对 + 混一色 + 自摸"是 3，
             * 而"十三幺"是 1 —— 十三幺反而选不上。旧版更新说明里明确写了
             * 这个修复（"按番数（倍数和）取最高那把，不再按番型个数比较"）。
             * 倍数和必须带房间配置算，同一个番型在不同房间的倍数可以不一样。
             */
            HainanConfig cfg0 = room.cfg == null ? HainanConfig.defaultConfig() : room.cfg;
            totalFan = HainanScore.multSum(r, cfg0);
        }

        String winHandJson = null;
        if (!isDraw && r.winHand != null) {
            Map<String, Object> wh = new HashMap<>();
            wh.put("hand", r.winHand);
            List<Map<String, Object>> melds = new ArrayList<>();
            if (r.winMelds != null) {
                for (Meld meld : r.winMelds) {
                    melds.add(push.serializeMeld(meld));
                }
            }
            wh.put("melds", melds);
            winHandJson = mapper.writeValueAsString(wh);
        }

        List<GameRoundsMapper.ScoreRow> rows = new ArrayList<>();
        for (Seat s : Seat.values()) {
            Long uid = userIdOf(room, s);
            if (uid == null) {
                continue;
            }
            rows.add(new GameRoundsMapper.ScoreRow(uid, s.ordinal(),
                    delta == null ? 0 : delta.get(s),
                    !isDraw && r.winner == s,
                    detailJsons == null ? "[]"
                            : (detailJsons.get(s) == null ? "[]" : detailJsons.get(s))));
        }
        roundPersist.saveHand(sessionId, roundNum, isDraw, winType,
                winnerId, loserId, winTile, winHandJson, fanInfo, totalFan, dealerName, windIdx, rows);
    }

    // ==================== 断线现场推送 ====================

    /**
     * 断线重连时把"现场"推给刚回到座位的那个连接：牌桌（弃牌/副露/花）+ 各家张数 + 自己的手牌。
     *
     * <p>故意 public 但**不进 GameEngineService 接口**：它只给同包的 WebSocket 门面用，
     * 不希望它成为对外契约的一部分。</p>
     */
    public void pushSeatState(WebSocketSession session, Room room, Seat seat) {
        RoomManager rm = room.active;
        if (rm == null) {
            return;
        }
        Map<String, List<Integer>> discards = new HashMap<>();
        Map<String, List<Map<String, Object>>> melds = new HashMap<>();
        Map<String, List<Integer>> flowers = new HashMap<>();
        for (Seat s : Seat.values()) {
            discards.put(s.name(), new ArrayList<>(rm.getPlayerDiscards(s)));
            List<Map<String, Object>> ml = new ArrayList<>();
            Player p = rm.getPlayer(s);
            if (p != null) {
                for (Meld meld : p.melds) {
                    ml.add(push.serializeMeld(meld));
                }
                flowers.put(s.name(), new ArrayList<>(p.flowers));
            } else {
                flowers.put(s.name(), new ArrayList<>());
            }
            melds.put(s.name(), ml);
        }
        Map<String, Object> board = new HashMap<>();
        board.put("type", "board");
        board.put("discards", discards);
        board.put("melds", melds);
        board.put("flowers", flowers);
        push.sendJson(session, board);

        Map<String, Integer> counts = new HashMap<>();
        for (Seat s : Seat.values()) {
            Player p = rm.getPlayer(s);
            counts.put(s.name(), p == null ? 0 : p.hand.size());
        }
        Map<String, Object> cm = new HashMap<>();
        cm.put("type", "counts");
        cm.put("counts", counts);
        push.sendJson(session, cm);

        Player me = rm.getPlayer(seat);
        if (me != null) {
            List<Map<String, Object>> ml = new ArrayList<>();
            for (Meld meld : me.melds) {
                ml.add(push.serializeMeld(meld));
            }
            Map<String, Object> h = new HashMap<>();
            h.put("type", "hand");
            h.put("hand", new ArrayList<>(me.hand));
            h.put("melds", ml);
            h.put("flowers", new ArrayList<>(me.flowers));
            push.sendJson(session, h);
        }
    }

    // ==================== 结算明细与包杠探测 ====================

    /** 组装每人“本局明细”JSON：{notes, flowers, gangs}，供战绩页图形化渲染。 */
    private Map<Seat, String> buildDetailJsons(HainanScore.Settlement st,
                                               Map<Seat, List<Integer>> flMap,
                                               Map<Seat, List<Meld>> mMap) {
        Map<Seat, String> out = new EnumMap<>(Seat.class);
        for (Seat s : Seat.values()) {
            Map<String, Object> obj = new HashMap<>();
            List<String> notes = st.notes.get(s);
            obj.put("notes", notes == null ? new ArrayList<String>() : notes);
            List<Integer> fl = flMap == null ? null : flMap.get(s);
            obj.put("flowers", fl == null ? new ArrayList<Integer>() : fl);
            List<Map<String, Object>> gangs = new ArrayList<>();
            List<Meld> ms = mMap == null ? null : mMap.get(s);
            if (ms != null) {
                for (Meld m : ms) {
                    if (m.type() == Meld.Type.GANG || m.type() == Meld.Type.AN_GANG || m.type() == Meld.Type.BU_GANG) {
                        Map<String, Object> gm = new HashMap<>();
                        gm.put("type", m.type().name());
                        List<Integer> ts = new ArrayList<>();
                        for (int t : m.tiles()) {
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
                if (m.type() == Meld.Type.GANG && m.from() == dealer
                        && m.tiles() != null && m.tiles().length > 0 && m.tiles()[0] == t0) {
                    return s;
                }
            }
        }
        return null;
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
            Map<String, Object> m = new HashMap<>();
            m.put("type", "dealer");
            m.put("dealer", dealer.name());
            push.broadcastGame(room, m);
            for (Seat s : Seat.values()) {
                sendHand(s);
            }
            sendCounts();
        }

        @Override public void onDraw(Seat seat, int tile) {
            Map<String, Object> m = new HashMap<>();
            m.put("type", "draw");
            m.put("seat", seat.name());
            m.put("tile", tile);
            push.broadcastGame(room, m);
            sendCounts();
            sendHand(seat);
        }

        @Override public void onFlower(Seat seat, int tile) {
            Map<String, Object> m = new HashMap<>();
            m.put("type", "flower");
            m.put("seat", seat.name());
            m.put("tile", tile);
            push.broadcastGame(room, m);
            sendCounts();
            sendHand(seat);
        }

        @Override public void onDiscard(Seat seat, int tile) {
            Map<String, Object> m = new HashMap<>();
            m.put("type", "discard");
            m.put("seat", seat.name());
            m.put("tile", tile);
            push.broadcastGame(room, m);
            sendCounts();
            sendHand(seat);
        }

        @Override public void onMeld(Seat seat, Meld meld) {
            Map<String, Object> m = new HashMap<>();
            m.put("type", "meld");
            m.put("seat", seat.name());
            /*
             * 三道/四道是【吃碰杠的那一刻】就成立的：某一方从另一方吃碰杠满 3 次
             * （点炮时包）或满 4 次（自摸时包）。所以这里当场判断并带上 notice，
             * 前端收到就立刻语音播报 —— 等到结算再说就晚了，玩家那一步已经打完。
             *
             * notice 只在【刚好到达 3 或 4】时给一次，不是每到一次都给。
             * 判定复用 HainanScore.relationCount，与结算用的是同一套逻辑。
             */
            if (meld.from() != null) {
                Map<Seat, List<Meld>> meldsNow = new EnumMap<>(Seat.class);
                for (Seat s : Seat.values()) {
                    Player p = rm == null ? null : rm.getPlayer(s);
                    meldsNow.put(s, p == null ? new ArrayList<>() : new ArrayList<>(p.melds));
                }
                int rel = HainanScore.relationCount(seat, meld.from(), meldsNow);
                if (rel == 3) {
                    m.put("notice", "包三道");
                } else if (rel == 4) {
                    m.put("notice", "包四道");
                }
            }
            if (meld.type() == Meld.Type.AN_GANG) {
                /*
                 * 暗杠：牌面只有自己能看到。
                 * 不能像别的副露那样一条消息广播给全桌 —— 那样这 4 张牌会原样
                 * 出现在每个人的 WebSocket 消息里，等于当众亮牌。
                 * 持有者收完整版，其余真人收 hidden 版（前端画 4 张牌背）。
                 */
                Map<String, Object> full = new HashMap<>(m);
                full.put("meld", push.serializeMeld(meld, false));
                Map<String, Object> masked = new HashMap<>(m);
                masked.put("meld", push.serializeMeld(meld, true));
                push.broadcastGameMasked(room, seat, full, masked);
            } else {
                m.put("meld", push.serializeMeld(meld));
                push.broadcastGame(room, m);
            }
            sendCounts();
            sendHand(seat);
        }

        @Override public void onHu(Seat seat, HuResult result, boolean selfDraw,
                                   boolean tianHu, int baoTing, int tile, Seat from) {
            Map<String, Object> m = new HashMap<>();
            m.put("type", "hu");
            m.put("seat", seat.name());
            m.put("selfDraw", selfDraw);
            m.put("tile", tile);
            m.put("from", from == null ? null : from.name());
            m.put("tianHu", tianHu);
            m.put("baoTing", baoTing);
            List<String> fans = new ArrayList<>();
            if (rm != null) {
                Player p = rm.getPlayer(seat);
                if (p != null) {
                    // 天胡/天听/地听也是番型，与其他番型一并展示（且不与平胡叠加）
                    for (FanType f : HainanScore.fansOfHand(p.hand, p.melds, p.flowers, tile, tianHu, baoTing)) {
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
            push.broadcastGame(room, m);
        }

        @Override public void onTimeout(Seat seat, String action) {
            logAll(seat.cn + " " + action + " 超时，已自动处理");
        }

        @Override public void onRoundDraw() {
            logAll("流局");
        }

        @Override public void onTurnStart(Seat seat, String kind, long timeoutMs) {
            Map<String, Object> m = new HashMap<>();
            m.put("type", "turn");
            m.put("seat", seat == null ? null : seat.name());
            m.put("kind", kind);
            m.put("timeoutMs", timeoutMs);
            push.broadcastGame(room, m);
        }

        @Override public void onReport(Seat seat, int mode) {
            String name = mode == 1 ? "天听" : (mode == 2 ? "地听" : "报听");
            logAll(seat.cn + " 报听（" + name + "）");
            Map<String, Object> m = new HashMap<>();
            m.put("type", "report");
            m.put("seat", seat.name());
            m.put("mode", mode);
            push.broadcastGame(room, m);
        }

        @Override public void onResume() {
            // 从快照恢复一把：把当前全场状态推给所有在座端（服务重启/断线续跑用）
            if (rm == null) {
                return;
            }
            /*
             * ⚠️ board 快照必须【逐座位】组装，不能共用一份。
             *   别人的暗杠对"我"来说只能是 4 张牌背；如果共用一份带真实牌面的
             *   melds，那这条恢复路径又把暗杠亮了一次（和 onMeld 是同一类漏洞）。
             *   其它字段（牌河/张数/花牌）四处相同，没必要重复算。
             */
            Map<String, List<Integer>> discards = new HashMap<>();
            Map<String, Integer> counts = new HashMap<>();
            Map<String, List<Integer>> flowers = new HashMap<>();
            for (Seat s : Seat.values()) {
                discards.put(s.name(), new ArrayList<>(rm.getPlayerDiscards(s)));
                Player p = rm.getPlayer(s);
                if (p != null) {
                    counts.put(s.name(), p.hand.size());
                    flowers.put(s.name(), new ArrayList<>(p.flowers));
                } else {
                    counts.put(s.name(), 0);
                    flowers.put(s.name(), new ArrayList<>());
                }
            }
            Map<String, Object> countsMsg = new HashMap<>();
            countsMsg.put("type", "counts");
            countsMsg.put("counts", counts);
            for (Seat viewer : Seat.values()) {
                Member mem = room.members.get(viewer);
                if (!RoomSupport.bound(mem)) {
                    continue;
                }
                Map<String, List<Map<String, Object>>> melds = new HashMap<>();
                for (Seat s : Seat.values()) {
                    List<Map<String, Object>> ml = new ArrayList<>();
                    Player p = rm.getPlayer(s);
                    if (p != null) {
                        for (Meld meld : p.melds) {
                            boolean hide = meld.type() == Meld.Type.AN_GANG && s != viewer;
                            ml.add(push.serializeMeld(meld, hide));
                        }
                    }
                    melds.put(s.name(), ml);
                }
                Map<String, Object> board = new HashMap<>();
                board.put("type", "board");
                board.put("discards", discards);
                board.put("melds", melds);
                board.put("flowers", flowers);
                push.sendJson(mem.gameWs, board);
                push.sendJson(mem.gameWs, countsMsg);
            }
            for (Seat s : Seat.values()) {
                sendHand(s);
            }
        }

        @Override public void onEnd(RoundResult result) {
            Map<String, Object> m = new HashMap<>();
            m.put("type", "end");
            m.put("result", result.toString());
            push.broadcastGame(room, m);
        }

        private void sendHand(Seat seat) {
            if (rm == null) {
                return;
            }
            Member mem = room.members.get(seat);
            if (!RoomSupport.bound(mem)) {
                return;
            }
            Player p = rm.getPlayer(seat);
            if (p == null) {
                return;
            }
            List<Map<String, Object>> melds = new ArrayList<>();
            for (Meld meld : p.melds) {
                melds.add(push.serializeMeld(meld));
            }
            Map<String, Object> m = new HashMap<>();
            m.put("type", "hand");
            m.put("hand", new ArrayList<>(p.hand));
            m.put("melds", melds);
            m.put("flowers", new ArrayList<>(p.flowers));
            push.sendJson(mem.gameWs, m);
        }

        private void sendCounts() {
            if (rm == null) {
                return;
            }
            Map<String, Integer> counts = new HashMap<>();
            for (Seat s : Seat.values()) {
                Player p = rm.getPlayer(s);
                counts.put(s.name(), p == null ? 0 : p.hand.size());
            }
            Map<String, Object> m = new HashMap<>();
            m.put("type", "counts");
            m.put("counts", counts);
            m.put("wall", rm.deckSize());
            push.broadcastGame(room, m);
        }

        private void logAll(String text) {
            Map<String, Object> m = new HashMap<>();
            m.put("type", "log");
            m.put("text", text);
            push.broadcastGame(room, m);
        }
    }
}
