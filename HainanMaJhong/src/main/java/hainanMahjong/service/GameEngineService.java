package hainanMahjong.service;

import hainanMahjong.service.impl.SchemaInitializer;
import hainanMahjong.room.EngineStart;
import hainanMahjong.room.Room;
import hainanMahjong.room.RoomRegistry;

/**
 * 牌局引擎的调度：开局、跑块、跑把、监听引擎事件并推送。
 *
 * <p><b>这是唯一"带线程"的模块</b>：</p>
 * <ul>
 *   <li>{@link #startMatch} 起 <b>room-bind-&lt;房间码&gt;</b> 线程跑 {@code runBlock}
 *       —— 先等座位连入（宽限期），再进入跑把循环</li>
 *   <li>{@link #startEngine} 起 <b>room-engine-&lt;房间码&gt;</b> 线程直接跑 {@code runEngine}
 *       —— 用于"再来一轮"与"从 Redis 快照续跑"</li>
 * </ul>
 *
 * <p>线程名是线上排查的依据，<b>不要改</b>。</p>
 *
 * <p><b>依赖方向</b>：本模块 → {@link RoomService}（解散/清理）、{@link RoomRegistry}、
 * {@link GamePushService}、{@link RoomSnapshotService}、{@link SchemaInitializer}。
 * 反向依赖（这些模块调用引擎）是不允许的 —— 否则会出现环。</p>
 *
 * <p><b>锁</b>：沿用房对象锁 {@code synchronized (room)}；引擎线程长时间持有房间状态、
 * 但不长期持锁（只在需要读改成员/状态时短暂加锁）。</p>
 */
public interface GameEngineService {

    /** 开局：通知各座位 start，然后起 room-bind 线程跑本块。 */
    void startMatch(Room room);

    /** 等待房房主"人机补齐"：空缺座位补入电脑，仍停留在等待房。 */
    void fillBotsFor(long userId);

    /** 等待房房主在补位后"开始对局"。 */
    void startNowFor(long userId);

    /** 房主再来一轮（块与块之间）。 */
    void onRematch(Room room, long userId);

    /** 直接起 room-engine 线程跑一块（不再等绑定；等待逻辑在 {@code runBlock} 内）。 */
    void startEngine(Room room, EngineStart seed);
}
