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
 *   <li><b>Registry 锁</b>（本类的 registryLock）：保护本类 6 个 Map 的<b>复合操作</b>。
 *       例如"房间列表为空 ⇒ 删房间"必须原子。</li>
 *   <li><b>Room 锁</b>（调用方的 room.lock）：保护单个房间的成员与状态。</li>
 * </ul>
 *
 * <p>⚠️ <b>锁顺序约束</b>：调用方<b>持有 Registry 锁时不得再去拿 Room 锁</b>。
 * 常规方法只访问 Map；恢复 builder 也不得获取任何 Room 锁，所以
 * "先 room 后 registry"这个顺序永远成立，不会出现交叉等待。</p>
 *
 * <p>本类<u>不</u>负责房间状态机（创建/加入/开局/解散的业务规则留在 Service 层），
 * 也不负责 Redis 快照。</p>
 */
@Component
public class RoomRegistry {
    private final java.util.concurrent.locks.ReentrantLock registryLock = new java.util.concurrent.locks.ReentrantLock();

    /** 房间码 → 房间。 */
    private final ConcurrentHashMap<String, Room> roomsByCode = new ConcurrentHashMap<>();
    // 房间码空间有界，防止本进程在 Redis 关闭保护失败时恢复刚关闭的一代。
    private final ConcurrentHashMap<String, String> closedGenerations = new ConcurrentHashMap<>();

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
    private final ConcurrentHashMap<String, String> gameGenerationOfSession = new ConcurrentHashMap<>();

    // ==================== 房间 ====================

    /** 按房间码找房间；没有返回 null。 */
    public Room find(String code) {
        registryLock.lock();
        try {
            return code == null ? null : roomsByCode.get(code);
        } finally { registryLock.unlock(); }
    }

    /** 该房间码是否已被占用（创建房间时用于生成不重复的码）。 */
    public boolean contains(String code) {
        registryLock.lock();
        try {
            return code != null && roomsByCode.containsKey(code);
        } finally { registryLock.unlock(); }
    }

    /**
     * 登记新房间，并把房主绑到该房间。
     *
     * <p>等价于原实现里"放房间 + 放 codeByUser"两步连续操作，这里合成一个原子操作。</p>
     */
    public void register(Room room) {
        registryLock.lock();
        try {
            if (room == null || room.code == null) {
                return;
            }
            if (roomsByCode.putIfAbsent(room.code, room) != null) {
                throw new IllegalStateException("房间码已被占用");
            }
            codeByUser.put(room.hostUserId, room.code);
        } finally { registryLock.unlock(); }
    }

    /**
     * 删除房间与该房间所有成员的绑定。
     *
     * <p>{@code remove(userId, code)} 的"值匹配才删"语义必须保留：若某个用户此时已经绑到
     * 另一个房间，不能把他在新房间的绑定误删。</p>
     */
    public void unregister(Room room) {
        registryLock.lock();
        try {
            if (room == null || room.code == null) {
                return;
            }
            closedGenerations.put(room.code, room.generation);
            if (!roomsByCode.remove(room.code, room)) return;
            for (Member m : new ArrayList<>(room.members.values())) {
                if (m != null) {
                    codeByUser.remove(m.userId, room.code);
                }
            }
        } finally { registryLock.unlock(); }
    }

    /**
     * 从快照重建场景下的"登记"：<b>已存在则直接返回</b>，否则用 {@code builder} 造一个房间并登记，
     * 同时把它的成员绑回各自房间码。
     *
     * <p>为什么要有这个方法：重建的语义是"查不到才造"，这个<b>判断 + 登记必须原子</b>——
     * 否则两个玩家同时凭房间码回来会各造一个房间，互相覆盖。把这段放进注册表自己的锁里，
     * 调用方就不必再显式加注册表锁 了。</p>
     *
     * <p>{@code builder} 在<b>持有本类锁</b>的情况下执行，其中恢复可能读 MySQL/Redis，因此采用 ReentrantLock；builder
     * <b>不能反过来获取房间锁</b>（虽然可重入不会死锁，但会让临界区变长）。</p>
     *
     * @param code    房间码
     * @param builder 造房间的逻辑；返回 null 表示重建失败，此时不会登记任何东西
     * @return 已存在的或新建的房间；{@code builder} 返回 null 时返回 null
     */
    public Room resurrected(String code, java.util.function.Function<String, Room> builder) {
        registryLock.lock();
        try {
            if (code == null) {
                return null;
            }
            Room existing = roomsByCode.get(code);
            if (existing != null) {
                return existing;
            }
            Room built = builder.apply(code);
            if (built == null || built.state == State.CLOSED
                    || built.generation.equals(closedGenerations.get(code))) {
                return null;
            }
            for (Member m : built.members.values()) {
                String bound = codeByUser.get(m.userId);
                if (m.userId > 0 && bound != null && !bound.equals(code)) return null;
            }
            roomsByCode.put(code, built);
            for (Member m : new ArrayList<>(built.members.values())) {
                if (m != null) {
                    codeByUser.put(m.userId, code);
                }
            }
            return built;
        } finally { registryLock.unlock(); }
    }

    // ==================== 用户 → 房间码 ====================

    /** 用户当前所在房间码；不在任何房间返回 null。 */
    public String codeOfUser(long userId) {
        registryLock.lock();
        try {
            return codeByUser.get(userId);
        } finally { registryLock.unlock(); }
    }

    /** 把用户绑到某个房间。 */
    public void bindUser(long userId, String code) {
        registryLock.lock();
        try {
            if (code != null) {
                codeByUser.put(userId, code);
            }
        } finally { registryLock.unlock(); }
    }

    /** 解绑用户；只有当前绑定确实等于 {@code code} 时才解绑（避免误删新房间的绑定）。 */
    public void unbindUser(long userId, String code) {
        registryLock.lock();
        try {
            if (code != null) {
                codeByUser.remove(userId, code);
            }
        } finally { registryLock.unlock(); }
    }

    // ==================== /room 连接的 session ====================

    /** 记录 /room 连接属于哪个用户。 */
    public void bindClientSession(WebSocketSession session, long userId) {
        registryLock.lock();
        try {
            if (session != null) {
                userOfSession.put(session.getId(), userId);
            }
        } finally { registryLock.unlock(); }
    }

    /** 取 /room 连接对应的用户；未记录返回 null。 */
    public Long clientUser(WebSocketSession session) {
        registryLock.lock();
        try {
            return session == null ? null : userOfSession.get(session.getId());
        } finally { registryLock.unlock(); }
    }

    /** 移除 /room 连接的记录（连接关闭时调用）。 */
    public void unbindClientSession(WebSocketSession session) {
        registryLock.lock();
        try {
            if (session != null) {
                userOfSession.remove(session.getId());
            }
        } finally { registryLock.unlock(); }
    }

    // ==================== /game 连接的 session ====================

    /** 记录 /game 连接：用户 + 房间码 + 座位。 */
    public void bindGameSession(WebSocketSession session, long userId, String code, Seat seat) {
        registryLock.lock();
        try {
            if (session == null) {
                return;
            }
            String id = session.getId();
            gameUserOfSession.put(id, userId);
            gameCodeOfSession.put(id, code);
            gameSeatOfSession.put(id, seat);
            Room room = roomsByCode.get(code);
            if (room != null) gameGenerationOfSession.put(id, room.generation);
        } finally { registryLock.unlock(); }
    }

    public boolean isGameSessionCurrent(WebSocketSession session, Room room) {
        registryLock.lock();
        try {
            return session != null && room != null
                    && room.generation.equals(gameGenerationOfSession.get(session.getId()));
        } finally { registryLock.unlock(); }
    }

    /** 取 /game 连接对应的用户；未记录返回 null。 */
    public Long gameUser(WebSocketSession session) {
        registryLock.lock();
        try {
            return session == null ? null : gameUserOfSession.get(session.getId());
        } finally { registryLock.unlock(); }
    }

    /** 取 /game 连接对应的房间码；未记录返回 null。 */
    public String gameCode(WebSocketSession session) {
        registryLock.lock();
        try {
            return session == null ? null : gameCodeOfSession.get(session.getId());
        } finally { registryLock.unlock(); }
    }

    /** 移除 /game 连接的全部记录（连接关闭时调用）。 */
    public void unbindGameSession(WebSocketSession session) {
        registryLock.lock();
        try {
            if (session == null) {
                return;
            }
            String id = session.getId();
            gameUserOfSession.remove(id);
            gameCodeOfSession.remove(id);
            gameSeatOfSession.remove(id);
            gameGenerationOfSession.remove(id);
        } finally { registryLock.unlock(); }
    }

    // ==================== 维护用 ====================

    /** 当前登记的房间数（诊断/测试用）。 */
    public int roomCount() {
        registryLock.lock();
        try {
            return roomsByCode.size();
        } finally { registryLock.unlock(); }
    }

    /** 当前所有房间的快照列表（诊断/测试用；不对外暴露内部 Map）。 */
    public List<Room> allRooms() {
        registryLock.lock();
        try {
            return new ArrayList<>(roomsByCode.values());
        } finally { registryLock.unlock(); }
    }
}
