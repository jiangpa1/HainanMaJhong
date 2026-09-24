package hainanMahjong.room;

import hainanMahjong.engine.model.Seat;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 联机房间的<b>全局注册表</b>：管"房间码 ↔ 房间 ↔ 用户 ↔ WebSocket 连接"的查找关系。
 *
 * <p>从 {@code MultiPlayerRoomServiceImpl} 抽出来，目的是让房间状态本身（{@link Room}）与
 * "谁在哪里"的索引分开；拆出去的各模块统一从这里取，而不是各自持有 Map。</p>
 *
 * <h3>两把锁的分工（重要）</h3>
 * <ul>
 *   <li><b>Registry 锁</b>（本类的 {@code synchronized} 方法）：保护本类 6 个 Map 的<b>复合操作</b>。
 *       例如"房间列表为空 ⇒ 删房间"必须原子。</li>
 *   <li><b>Room 锁</b>（调用方 {@code synchronized (room)}）：保护单个房间的成员与状态。</li>
 * </ul>
 *
 * <p>⚠️ <b>锁顺序约束</b>：调用方<b>持有 Registry 锁时不得再去拿 Room 锁</b>。
 * 本类的所有方法都是短小的 Map 操作，<b>不会获取任何 Room 锁</b>，所以
 * "先 room 后 registry"这个顺序永远成立，不会出现交叉等待。</p>
 *
 * <p>本类<u>不</u>负责房间状态机（创建/加入/开局/解散的业务规则留在 Service 层），
 * 也不负责 Redis 快照。</p>
 */
@Component
public class RoomRegistry {

    /** 房间码 → 房间。 */
    private final ConcurrentHashMap<String, Room> roomsByCode = new ConcurrentHashMap<>();

    /** 用户 → 所在房间码。 */
    private final ConcurrentHashMap<Long, String> codeByUser = new ConcurrentHashMap<>();

    /** /room 连接的 sessionId → 用户。 */
    private final ConcurrentHashMap<String, Long> userOfSession = new ConcurrentHashMap<>();

    /** /game 连接的 sessionId → 用户。 */
    private final ConcurrentHashMap<String, Long> gameUserOfSession = new ConcurrentHashMap<>();

    /** /game 连接的 sessionId → 房间码。 */
    private final ConcurrentHashMap<String, String> gameCodeOfSession = new ConcurrentHashMap<>();

    /** /game 连接的 sessionId → 座位。 */
    private final ConcurrentHashMap<String, Seat> gameSeatOfSession = new ConcurrentHashMap<>();

    // ==================== 房间 ====================

    /** 按房间码找房间；没有返回 null。 */
    public synchronized Room find(String code) {
        return code == null ? null : roomsByCode.get(code);
    }

    /** 该房间码是否已被占用（创建房间时用于生成不重复的码）。 */
    public synchronized boolean contains(String code) {
        return code != null && roomsByCode.containsKey(code);
    }

    /**
     * 登记新房间，并把房主绑到该房间。
     *
     * <p>等价于原实现里"放房间 + 放 codeByUser"两步连续操作，这里合成一个原子操作。</p>
     */
    public synchronized void register(Room room) {
        if (room == null || room.code == null) {
            return;
        }
        roomsByCode.put(room.code, room);
        codeByUser.put(room.hostUserId, room.code);
    }

    /**
     * 删除房间与该房间所有成员的绑定。
     *
     * <p>{@code remove(userId, code)} 的"值匹配才删"语义必须保留：若某个用户此时已经绑到
     * 另一个房间，不能把他在新房间的绑定误删。</p>
     */
    public synchronized void unregister(Room room) {
        if (room == null || room.code == null) {
            return;
        }
        roomsByCode.remove(room.code);
        for (Member m : new ArrayList<>(room.members.values())) {
            if (m != null) {
                codeByUser.remove(m.userId, room.code);
            }
        }
    }

    /**
     * 从快照重建场景下的"登记"：<b>已存在则直接返回</b>，否则用 {@code builder} 造一个房间并登记，
     * 同时把它的成员绑回各自房间码。
     *
     * <p>为什么要有这个方法：重建的语义是"查不到才造"，这个<b>判断 + 登记必须原子</b>——
     * 否则两个玩家同时凭房间码回来会各造一个房间，互相覆盖。把这段放进注册表自己的锁里，
     * 调用方就不必再显式 {@code synchronized} 了。</p>
     *
     * <p>{@code builder} 在<b>持有本类锁</b>的情况下执行，所以它只能造对象、
     * <b>不能反过来调用本类的其它方法</b>（虽然可重入不会死锁，但会让临界区变长）。</p>
     *
     * @param code    房间码
     * @param builder 造房间的逻辑；返回 null 表示重建失败，此时不会登记任何东西
     * @return 已存在的或新建的房间；{@code builder} 返回 null 时返回 null
     */
    public synchronized Room resurrected(String code, java.util.function.Function<String, Room> builder) {
        if (code == null) {
            return null;
        }
        Room existing = roomsByCode.get(code);
        if (existing != null) {
            return existing;
        }
        Room built = builder.apply(code);
        if (built == null) {
            return null;
        }
        roomsByCode.put(code, built);
        for (Member m : new ArrayList<>(built.members.values())) {
            if (m != null) {
                codeByUser.put(m.userId, code);
            }
        }
        return built;
    }

    // ==================== 用户 → 房间码 ====================

    /** 用户当前所在房间码；不在任何房间返回 null。 */
    public synchronized String codeOfUser(long userId) {
        return codeByUser.get(userId);
    }

    /** 把用户绑到某个房间。 */
    public synchronized void bindUser(long userId, String code) {
        if (code != null) {
            codeByUser.put(userId, code);
        }
    }

    /** 解绑用户；只有当前绑定确实等于 {@code code} 时才解绑（避免误删新房间的绑定）。 */
    public synchronized void unbindUser(long userId, String code) {
        if (code != null) {
            codeByUser.remove(userId, code);
        }
    }

    // ==================== /room 连接的 session ====================

    /** 记录 /room 连接属于哪个用户。 */
    public synchronized void bindClientSession(WebSocketSession session, long userId) {
        if (session != null) {
            userOfSession.put(session.getId(), userId);
        }
    }

    /** 取 /room 连接对应的用户；未记录返回 null。 */
    public synchronized Long clientUser(WebSocketSession session) {
        return session == null ? null : userOfSession.get(session.getId());
    }

    /** 移除 /room 连接的记录（连接关闭时调用）。 */
    public synchronized void unbindClientSession(WebSocketSession session) {
        if (session != null) {
            userOfSession.remove(session.getId());
        }
    }

    // ==================== /game 连接的 session ====================

    /** 记录 /game 连接：用户 + 房间码 + 座位。 */
    public synchronized void bindGameSession(WebSocketSession session, long userId, String code, Seat seat) {
        if (session == null) {
            return;
        }
        String id = session.getId();
        gameUserOfSession.put(id, userId);
        gameCodeOfSession.put(id, code);
        gameSeatOfSession.put(id, seat);
    }

    /** 取 /game 连接对应的用户；未记录返回 null。 */
    public synchronized Long gameUser(WebSocketSession session) {
        return session == null ? null : gameUserOfSession.get(session.getId());
    }

    /** 取 /game 连接对应的房间码；未记录返回 null。 */
    public synchronized String gameCode(WebSocketSession session) {
        return session == null ? null : gameCodeOfSession.get(session.getId());
    }

    /** 移除 /game 连接的全部记录（连接关闭时调用）。 */
    public synchronized void unbindGameSession(WebSocketSession session) {
        if (session == null) {
            return;
        }
        String id = session.getId();
        gameUserOfSession.remove(id);
        gameCodeOfSession.remove(id);
        gameSeatOfSession.remove(id);
    }

    // ==================== 维护用 ====================

    /** 当前登记的房间数（诊断/测试用）。 */
    public synchronized int roomCount() {
        return roomsByCode.size();
    }

    /** 当前所有房间的快照列表（诊断/测试用；不对外暴露内部 Map）。 */
    public synchronized List<Room> allRooms() {
        return new ArrayList<>(roomsByCode.values());
    }
}
