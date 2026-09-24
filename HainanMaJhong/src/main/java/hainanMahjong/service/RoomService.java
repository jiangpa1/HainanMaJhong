package hainanMahjong.service;

import hainanMahjong.room.Member;
import hainanMahjong.room.Room;
import org.springframework.web.socket.WebSocketSession;

/**
 * 房间生命周期与成员管理：创建 / 加入 / 成员离开 / 解散 / 从 Redis 重建。
 *
 * <p><b>职责边界</b>：本接口负责"人怎么进、怎么出、房间什么时候没"；
 * <b>不</b>负责牌局本身（开局、跑把、结算）。</p>
 *
 * <p><b>锁约定</b>：实现内部沿用 {@code synchronized (room)}（房间对象即锁）。
 * 调用方若需要"读改写"房间成员，应自行持有该锁；本接口的方法自身不加房间锁，
 * 只在必要的临界区内加。</p>
 *
 * <p>当前仍留在门面实现里的引擎相关操作（{@code startMatch} / {@code fillBotsFor} /
 * {@code startNowFor}）会在引擎模块拆出时一并迁走，届时由那里反向依赖本接口。</p>
 */
public interface RoomService {

    /**
     * 创建房间：生成不重复的 6 位房间码、房主随机入座、登记注册表并绑定连接。
     *
     * @param cfgRaw 前端传来的房间配置（可为 null，用默认）
     */
    void create(WebSocketSession session, long userId, Object cfgRaw);

    /**
     * 加入房间：已在房内则换连接；否则找空位/顶替机器人入座。
     *
     * <p>人满时<b>不</b>会开局 —— 开局由调用方在返回 true 后触发（引擎模块的事）。</p>
     *
     * @return 是否达成"四人真人到齐"（true 时调用方应触发开局）
     */
    boolean join(WebSocketSession session, long userId, String code);

    /** /room 连接关闭：等待房直接移除座位；对局中只标记离线、保留座位。 */
    void onClientSocketClosed(WebSocketSession session, long userId);

    /** 成员主动离开（对局中转为机器人托管；等待房直接解散，与原逻辑一致）。 */
    void handleMemberLeft(Room room, Member m);

    /** 解散房间：通知全部连接、停止引擎、清空房间与注册表、删 Redis 快照。 */
    void disband(Room room, String reason);

    /** 停止引擎并清空房间状态（解散的一半，不含通知）。 */
    void stopAndClear(Room room);

    /** 凭房间码从 Redis 快照重建；已存在则返回已有的。 */
    Room resurrect(String code);

    /**
     * 「能不能加入这个房间」的预检 —— 只读，不改任何注册表状态。
     *
     * <p><b>为什么需要它</b>：大厅里点「加入」原来是先跳到房间页、再由 WebSocket 回
     * {@code join_denied}。用户看到的是"页面已经跳过去了，然后又弹一句房间不存在"，
     * 而且已经离开了大厅。有了这个预检，格式错 / 房间不存在 / 已解散 / 已开局 / 人满
     * 都能在【大厅原地】报错，不跳转。</p>
     *
     * <p>校验口径与 {@link #join} 完全一致（同一套 isSixDigit / 快照 / State / 人数判定），
     * 差别只有一个：这里<b>不会</b>把可重建的房间注册进注册表，真正加入时再重建。</p>
     *
     * @return {@code null} 表示可以加入；否则是给用户看的原因（前端直接显示）
     */
    String checkJoin(long userId, String code);

    /**
     * 把某个账号【已经建立】的所有房间/牌局连接踢掉（发一条 {@code kick} 再关闭）。
     *
     * <p>用途：同一账号在别的设备登录时立刻收掉旧连接。否则旧设备正开着牌桌、
     * WebSocket 还连着，它既不会主动请求接口、也就不会撞上 4011，
     * 表现为"两边同时在线"。</p>
     *
     * <p>发消息在前、关连接在后：前端收到 {@code kick} 才能弹"该账号在别处登录"并回登录页；
     * 直接关掉只会被当成普通掉线。等待房里旧设备被移除座位、对局中标记离线
     * （交由既有的 {@code onClientSocketClosed} 处理）。</p>
     *
     * @return 实际踢掉的连接数
     */
    int kickUser(long userId, String reason);
}
