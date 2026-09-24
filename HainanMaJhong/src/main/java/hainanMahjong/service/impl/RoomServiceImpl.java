package hainanMahjong.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import hainanMahjong.engine.RoomManager;
import hainanMahjong.engine.bot.BotController;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.repository.RoomSnapshotRepository;
import hainanMahjong.room.EngineStart;
import hainanMahjong.room.Member;
import hainanMahjong.room.MemberInfo;
import hainanMahjong.room.RedisRoom;
import hainanMahjong.room.Room;
import hainanMahjong.room.RoomRegistry;
import hainanMahjong.room.State;
import hainanMahjong.rules.DealerFlow;
import hainanMahjong.rules.HainanConfig;
import hainanMahjong.service.GamePushService;
import hainanMahjong.mapper.UserMapper;
import hainanMahjong.service.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static hainanMahjong.room.RoomConfig.MAX_MEMBERS;

/**
 * 房间生命周期与成员管理的实现。
 *
 * <p>从 {@code MultiPlayerRoomServiceImpl} 整体搬出，逻辑逐字保留（除方法名与可见性调整）。</p>
 *
 * <p><b>锁</b>：与其它房间模块一致，使用 {@code synchronized (room)}（房间对象即锁），
 * 不使用任何独立锁。注册表自身的并发由 {@link RoomRegistry} 的同步方法负责。</p>
 */
@Service
public class RoomServiceImpl implements RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomServiceImpl.class);

    private final RoomSnapshotRepository redis;
    private final RoomRegistry registry;
    private final GamePushService push;
    private final UserMapper userMapper;
    /**
     * 房间销毁时要把这一场的 {@code end_time} 落库。
     *
     * <p>只依赖两个 Mapper，不会和本类形成循环依赖。</p>
     */
    private final RoundPersister roundPersist;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Random random = new Random();

    public RoomServiceImpl(RoomSnapshotRepository redis,
                           RoomRegistry registry,
                           GamePushService push,
                           UserMapper userMapper,
                           RoundPersister roundPersist) {
        this.redis = redis;
        this.registry = registry;
        this.push = push;
        this.userMapper = userMapper;
        this.roundPersist = roundPersist;
    }

    // ==================== 创建 / 加入 ====================

    @Override
    public void create(WebSocketSession session, long userId, Object cfgRaw) {
        if (userId <= 0) {
            push.sendJson(session, denied("请先登录后再创建房间"));
            return;
        }
        String busy = busyRoomCode(userId);
        if (busy != null) {
            push.sendJson(session, denied("你仍在对局房间 " + busy + " 中，请先返回房间或等对局结束"));
            return;
        }
        String code;
        Room room;
        do {
            code = String.valueOf(100000 + random.nextInt(900000));
        } while (registry.contains(code));
        room = new Room(code, userId);
        room.cfg = parseConfig(cfgRaw);
        // 房主随机入座（而不是固定东），使整桌座位与进房顺序无关
        Seat seat0 = Seat.values()[random.nextInt(4)];
        room.members.put(seat0, new Member(seat0, userId, nicknameOf(userId), session));
        registry.register(room);            // 登记房间 + 绑定房主（注册表内部原子完成）
        registry.bindClientSession(session, userId);
        sendRoomInfo(room, session);
        log.info("用户 {} 创建房间 {}（海南配置 enabled={} 底分={}）", userId, code,
                room.cfg.enabled, room.cfg.basePoint);
    }

    @Override
    public boolean join(WebSocketSession session, long userId, String code) {
        if (userId <= 0) {
            push.sendJson(session, denied("请先登录后再加入房间"));
            return false;
        }
        if (!isSixDigit(code)) {
            push.sendJson(session, denied("房间码应为 6 位数字"));
            return false;
        }
        Room room = registry.find(code);
        if (room == null) {
            room = resurrect(code); // 服务重启后，若 Redis 有 BETWEEN/等待态存档则重建
        }
        if (room == null) {
            push.sendJson(session, denied("房间不存在或已解散"));
            return false;
        }
        synchronized (room) {
            Member existing = Member.byId(room, userId);
            if (existing != null) {
                existing.roomWs = session;
                registry.bindClientSession(session, userId);
                registry.bindUser(userId, code);
                sendRoomInfo(room, session);
                return false;
            }
            if (room.state == State.PLAYING) {
                push.sendJson(session, denied("对局进行中，请直接返回牌局"));
                return false;
            }
            if (realCount(room) >= MAX_MEMBERS) {
                push.sendJson(session, denied("房间已满(4人真人)"));
                return false;
            }
            String busy = busyRoomCode(userId);
            if (busy != null) {
                push.sendJson(session, denied("你仍在对局房间 " + busy + " 中，请先返回房间或等对局结束"));
                return false;
            }

            Seat seat = randomFreeSeat(room); // 随机从空位选座，而非按进房顺序
            Member displaced = null;
            if (seat == null) {
                seat = firstBotSeat(room); // 已有人机补齐：真人加入顶替一个电脑位
                if (seat == null) {
                    push.sendJson(session, denied("房间已满(4人)"));
                    return false;
                }
                displaced = room.members.get(seat);
                room.members.remove(seat);
            }
            Member m = new Member(seat, userId, nicknameOf(userId), session);
            room.members.put(seat, m);
            registry.bindUser(userId, code);
            registry.bindClientSession(session, userId);
            sendRoomInfo(room, null);
            boolean full = room.state == State.WAITING && realCount(room) == MAX_MEMBERS;
            log.info("用户 {} 加入房间 {} 座位 {}{}", userId, code, seat,
                    displaced == null ? "" : "（顶替电脑）");
            return full;
        }
    }

    @Override
    public void onClientSocketClosed(WebSocketSession session, long userId) {
        String code = registry.codeOfUser(userId);
        if (code == null) {
            return;
        }
        Room room = registry.find(code);
        if (room == null) {
            registry.unbindUser(userId, code);
            return;
        }
        synchronized (room) {
            Member m = Member.byId(room, userId);
            if (m == null || m.roomWs != session) {
                return;
            }
            m.roomWs = null;
            if (room.state != State.PLAYING) {
                removeMember(room, userId, true);
            }
        }
    }

    // ==================== 成员离开 / 移除 ====================

    @Override
    public void handleMemberLeft(Room room, Member m) {
        if (room.state == State.PLAYING) {
            m.offline = true;
            m.controller.setDelegate(new BotController());
            log.info("房间 {} 座位 {} 玩家 {} 中途离开，机器人托管(可返回)", room.code, m.seat, m.userId);
            if (noHumanBound(room)) {
                disband(room, "所有玩家已退出，房间解散");
            }
            return;
        }
        disband(room, "有成员离开，房间解散");
    }

    /** 移除成员；房主离开或房间已空时解散。仅房间模块内部调用。 */
    private void removeMember(Room room, long userId, boolean disbandIfHost) {
        registry.unbindUser(userId, room.code);
        Member m = Member.byId(room, userId);
        if (m == null) {
            return;
        }

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

    // ==================== 解散 / 清理 ====================

    @Override
    public void disband(Room room, String reason) {
        String text = (reason == null) ? "房间已解散" : reason;
        List<Member> ms = new ArrayList<>(room.members.values());
        for (Member m : ms) {
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "room_closed");
            msg.put("reason", text);
            if (m.roomWs != null && m.roomWs.isOpen()) {
                push.sendJson(m.roomWs, msg);
            }
            if (m.gameWs != null && m.gameWs.isOpen()) {
                push.sendJson(m.gameWs, msg);
            }
        }
        stopAndClear(room);
        log.info("房间 {} 解散：{}", room.code, text);
    }

    @Override
    public void stopAndClear(Room room) {
        /*
         * 收口：房间一旦销毁，就把这一场的结束时间落库。
         *
         * 这里刻意放在 stopAndClear（房间销毁的唯一收口）而不是各个调用点：
         * 之前只清了内存，game_sessions 的 end_time 一直是 NULL、status 一直停在"进行中"，
         * 战绩页那一场永远显示成"没打完"，也永远排不进已完成列表。
         * 注意 stopAndClear 只在【房间真的没了】时调用（正常打完一轮走的是
         * BETWEEN + 广播 match_finish，不经过这里），所以不会把"再来一轮"的场次误关掉。
         */
        if (room.sessionId > 0) {
            try {
                roundPersist.disbandSession(room.sessionId);
            } catch (Exception e) {
                log.warn("房间 {} 结束时写 end_time 失败：{}", room.code, e.getMessage());
            }
        }
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
        List<Member> ms = new ArrayList<>(room.members.values());
        registry.unregister(room);
        for (Member m : ms) {
            registry.unbindUser(m.userId, room.code);
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

    // ==================== 从 Redis 重建 ====================

    @Override
    public Room resurrect(String code) {
        return registry.resurrected(code, this::buildResurrected);
    }

    /**
     * 踢掉某账号已建立的所有连接（同一账号在别处登录时调，见 {@link RoomService#kickUser}）。
     *
     * <p>先发 {@code {"type":"kick","reason":...}} 再关闭：前端收到 kick 才知道
     * "不是掉线，是被顶了"，从而清登录态回登录页。</p>
     */
    @Override
    public int kickUser(long userId, String reason) {
        int kicked = 0;
        for (Room room : registry.allRooms()) {
            if (room == null || room.members == null) {
                continue;
            }
            // 遍历 Seat.values() 而不是 members 本身：关闭连接会回调 onClientSocketClosed，
            // 那里可能移除座位，直接遍历 map 会 ConcurrentModificationException
            for (Seat s : Seat.values()) {
                Member m = room.members.get(s);
                if (m == null || m.userId != userId) {
                    continue;
                }
                Map<String, Object> msg = new HashMap<>();
                msg.put("type", "kick");
                msg.put("reason", reason);
                if (m.roomWs != null) {
                    push.sendJson(m.roomWs, msg);
                    closeQuietly(m.roomWs);
                    kicked++;
                }
                if (m.gameWs != null) {
                    push.sendJson(m.gameWs, msg);
                    closeQuietly(m.gameWs);
                    kicked++;
                }
                log.info("账号 {} 在别处登录，踢掉房间 {} 座位 {} 的旧连接", userId, room.code, s);
            }
        }
        return kicked;
    }

    /** 关连接：已经关掉的、或关闭时抛异常的，都不该影响踢人流程。 */
    private void closeQuietly(org.springframework.web.socket.WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close(org.springframework.web.socket.CloseStatus.NORMAL);
            }
        } catch (Exception e) {
            log.debug("关闭旧连接失败（忽略）：{}", e.getMessage());
        }
    }

    /**
     * 「能不能加入」的只读预检（大厅点加入前调，见 {@link RoomService#checkJoin}）。
     *
     * <p>判定顺序和 {@link #join} 一模一样，保证"预检通过但真加不进去"不会发生；
     * 唯一的区别是<b>不注册</b>：{@code buildResurrected} 是纯重建（只读 Redis 快照造对象），
     * 真正 join 时 {@code resurrect} 才会把它登记进注册表。</p>
     */
    @Override
    public String checkJoin(long userId, String code) {
        if (!isSixDigit(code)) {
            return "房间码应为 6 位数字";
        }
        Room room = registry.find(code);
        if (room == null) {
            room = buildResurrected(code); // 纯重建，不登记
        }
        if (room == null) {
            return "房间不存在或已解散";
        }
        if (room.state == State.PLAYING) {
            return "对局进行中，请直接返回牌局";
        }
        if (realCount(room) >= MAX_MEMBERS) {
            return "房间已满(4人真人)";
        }
        String busy = busyRoomCode(userId);
        if (busy != null) {
            return "你仍在对局房间 " + busy + " 中，请先返回房间或等对局结束";
        }
        return null;
    }

    /** 纯重建：只从快照造出一个 Room 对象，不碰任何注册表（注册由调用方负责）。 */
    private Room buildResurrected(String code) {
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
            log.info("房间 {} 已从 Redis 快照重建(state={}, hand={})", code, room.state, room.currentHand);
            return room;
        } catch (Exception e) {
            log.warn("重建房间 {} 失败：{}", code, e.getMessage());
            return null;
        }
    }

    // ==================== 配置 / 查询 ====================

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

    /** 若用户已被某个"进行中"房间占座，返回其房间码；否则 null。仅房间模块内部调用。 */
    private String busyRoomCode(long userId) {
        String code = registry.codeOfUser(userId);
        if (code == null) {
            return null;
        }
        Room room = registry.find(code);
        if (room == null) {
            return null;
        }
        if (room.state == State.PLAYING || room.state == State.BETWEEN) {
            return code;
        }
        return null;
    }

    // ==================== 私有工具（原先散在门面实现里） ====================

    /** 是否已没有任何真人在线（四人全离线/未连入；补位的机器人不计）。 */
    private boolean noHumanBound(Room room) {
        for (Member mm : room.members.values()) {
            if (mm != null && !isBot(mm) && !mm.offline && bound(mm)) {
                return false;
            }
        }
        return true;
    }

    /** 人机补齐的机器人成员：userId 为负，无 WebSocket，由引擎以 BotController 托管。 */
    private static boolean isBot(Member m) {
        return m != null && m.userId < 0;
    }

    private static boolean bound(Member m) {
        return m != null && m.gameWs != null && m.gameWs.isOpen();
    }

    /** 真人数（不含补位电脑）。 */
    private static int realCount(Room room) {
        int n = 0;
        for (Member m : room.members.values()) {
            if (!isBot(m)) {
                n++;
            }
        }
        return n;
    }

    /** 从当前空位中随机挑一个（座位随机分配，与进房顺序无关）；无空位返回 null。 */
    private Seat randomFreeSeat(Room room) {
        List<Seat> free = new ArrayList<>();
        for (Seat s : Seat.values()) {
            if (!room.members.containsKey(s)) {
                free.add(s);
            }
        }
        if (free.isEmpty()) {
            return null;
        }
        return free.get(random.nextInt(free.size()));
    }

    /** 返回第一个电脑座位（真人加入时顶替它）。 */
    private static Seat firstBotSeat(Room room) {
        for (Seat s : Seat.values()) {
            Member m = room.members.get(s);
            if (isBot(m)) {
                return s;
            }
        }
        return null;
    }

    private String nicknameOf(long userId) {
        try {
            String nick = userMapper.selectNickname(userId);
            if (nick != null && !nick.isEmpty()) {
                return nick;
            }
            String name = userMapper.selectUsername(userId);
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

    /** 房间信息推送（等待房成员列表）。 */
    private void sendRoomInfo(Room room, WebSocketSession only) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "room_info");
        msg.put("code", room.code);
        msg.put("hostUserId", room.hostUserId);
        msg.put("state", room.state.name());
        List<Map<String, Object>> members = new ArrayList<>();
        for (Seat s : Seat.values()) {
            Member m = room.members.get(s);
            if (m == null) {
                continue;
            }
            Map<String, Object> mm = new HashMap<>();
            mm.put("userId", m.userId);
            mm.put("nickname", m.nickname);
            mm.put("seat", s.name());
            members.add(mm);
        }
        msg.put("members", members);
        if (only != null) {
            push.sendJson(only, msg);
        } else {
            for (Member m : new ArrayList<>(room.members.values())) {
                if (m.roomWs != null && m.roomWs.isOpen()) {
                    push.sendJson(m.roomWs, msg);
                }
            }
        }
    }

    private Map<String, Object> denied(String reason) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "join_denied");
        msg.put("reason", reason);
        return msg;
    }
}
