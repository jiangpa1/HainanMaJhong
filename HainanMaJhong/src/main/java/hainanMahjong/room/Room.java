package hainanMahjong.room;

import hainanMahjong.engine.RoomManager;
import hainanMahjong.engine.model.GameSnapshot;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.rules.DealerFlow;
import hainanMahjong.rules.HainanConfig;

import java.util.EnumMap;
import java.util.Map;

/**
 * Shared room state. Every room-state caller must use the SAME reentrant room.lock.
 * I/O may suspend virtual threads while this lock is held, without holding a JVM monitor.
 * Never wait for hand completion while holding the room lock.
 */
public class Room {

    public final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    public final String code;
    public final long hostUserId;
    /** 标识一次生命周期；房间码复用时必须生成新值。 */
    public final String generation;
    /** 在 room 锁内递增，拒绝乱序快照。 */
    public long snapshotVersion;

    /** 房间状态机：WAITING / PLAYING / BETWEEN / CLOSED（终态）。 */
    public volatile State state = State.WAITING;

    public final Map<Seat, Member> members = new EnumMap<Seat, Member>(Seat.class);

    /** 第几块（每块打满四风）。 */
    public int blockNo = 0;

    /** 当前块对应的 game_sessions.id；-1 表示未创建。 */
    public long sessionId = -1L;

    /** 各座位金币（跨块延续，不随每把清零）。 */
    public final Map<Seat, Integer> coins = new EnumMap<Seat, Integer>(Seat.class);

    /** 当前正在跑的一把的引擎实例；两把之间为 null。 */
    public volatile RoomManager active;

    public volatile int currentHand;
    public volatile Seat currentDealer;
    public volatile GameSnapshot currentSnap;

    /** 置位后引擎循环会尽快退出（解散房间时用）。 */
    public volatile boolean stop;

    /** Serial mailbox and cancellable lifecycle deadline; no per-room worker or timer thread. */
    public final hainanMahjong.engine.RoomEventLoop events = new hainanMahjong.engine.RoomEventLoop();
    public volatile boolean engineRunning;
    public boolean awaitingBind;
    public volatile long engineEpoch;
    public java.util.concurrent.ScheduledFuture<?> engineTimer;

    /** 从 Redis 重建房间后，用于续跑本块；被消费后置 null。 */
    public volatile EngineStart resumeSeed;

    /** 房间可设项（创建房间弹窗）。 */
    public HainanConfig cfg;

    /** 当前块的庄/令/底分状态（含重建）。 */
    public volatile DealerFlow flow;

    public Room(String code, long hostUserId) {
        this(code, hostUserId, java.util.UUID.randomUUID().toString());
    }

    public Room(String code, long hostUserId, String generation) {
        this.code = code;
        this.hostUserId = hostUserId;
        this.generation = generation;
    }
}
