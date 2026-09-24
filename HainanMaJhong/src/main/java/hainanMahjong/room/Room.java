package hainanMahjong.room;

import hainanMahjong.engine.RoomManager;
import hainanMahjong.engine.model.GameSnapshot;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.rules.DealerFlow;
import hainanMahjong.rules.HainanConfig;

import java.util.EnumMap;
import java.util.Map;

/**
 * 一个联机房间的<b>共享状态</b>。
 *
 * <p>⚠️⚠️ <b>本对象同时是锁对象</b>。所有模块（门面、引擎、推送、快照）必须继续对
 * <b>同一个 Room 实例</b>加 {@code synchronized}，不要各模块各配一把锁 ——
 * 现有的串行化保证正是建立在"一把锁管全部房间状态"之上。</p>
 *
 * <p>Java 的 {@code synchronized} 可重入，所以"持有 room 锁的方法调用另一个同样锁 room 的方法"
 * 是安全的；但<b>不要在持锁期间做 MySQL / Redis 等耗时 I/O</b>（现有代码把这类调用都放在锁外）。</p>
 *
 * <p>字段多为 public 是历史原因（原为 impl 的嵌套类，靠同包可见性直接访问）。
 * 拆分时保持原样以最小化改动；后续若要收紧访问性，应改为方法而不是直接改字段可见性。</p>
 */
public class Room {

    public final String code;
    public final long hostUserId;

    /** 房间状态机：WAITING / PLAYING / BETWEEN。 */
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

    /** 跑块的线程（名字前缀 room-bind- 或 room-engine-），守护线程。 */
    public Thread blockThread;

    /** 从 Redis 重建房间后，用于续跑本块；被消费后置 null。 */
    public volatile EngineStart resumeSeed;

    /** 房间可设项（创建房间弹窗）。 */
    public HainanConfig cfg;

    /** 当前块的庄/令/底分状态（含重建）。 */
    public volatile DealerFlow flow;

    public Room(String code, long hostUserId) {
        this.code = code;
        this.hostUserId = hostUserId;
    }
}
