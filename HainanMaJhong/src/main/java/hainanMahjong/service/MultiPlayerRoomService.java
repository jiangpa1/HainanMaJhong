package hainanMahjong.service;

import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * 多人联机房间（等待房 + 4 真人 16 局）的对外门面。
 *
 * <p>本接口只暴露 WebSocket 入口和两个查询方法，共 8 个；房间状态机、引擎调度、
 * 快照存取等内部实现见 {@link hainanMahjong.service.impl.MultiPlayerRoomServiceImpl}。</p>
 *
 * <p><b>调用方</b>（三个，都只依赖本接口，因此本接口的方法签名不能随意改）：</p>
 * <ul>
 *   <li>{@code RoomWebSocketHandler} —— /room 等待房连接：
 *       {@link #open} / {@link #onMessage} / {@link #onClose}</li>
 *   <li>{@code GameWebSocketHandler} —— /game 牌局连接：
 *       {@link #openGame} / {@link #onGameMessage} / {@link #onGameClose}</li>
 *   <li>{@code RoomController} —— HTTP 查“返回房间”状态：{@link #pendingReturn}</li>
 * </ul>
 *
 * <p><b>并发约定</b>：房间共享状态与它的锁是同一个对象（{@code Room}）。所有实现类必须继续
 * 对<b>同一个</b> room 实例加 {@code synchronized}，不要各模块各配一把锁 —— 否则会破坏
 * 现有的串行化保证。</p>
 */
public interface MultiPlayerRoomService {

    /** /room 建立连接：把 session 记到 userId 名下（游客 userId=0 不记）。 */
    void open(WebSocketSession session);

    /** /room 收到消息：createRoom / joinRoom / fillBots / startNow / leaveRoom。 */
    void onMessage(WebSocketSession session, String payload);

    /** /room 连接关闭：等待房直接移除座位；对局中由机器人托管，保留座位。 */
    void onClose(WebSocketSession session);

    /** /game 建立连接：校验 userId/code/seat 后回到座位并补发现场。 */
    void openGame(WebSocketSession session);

    /** /game 收到消息：discard / act / pass / baoting / chat / rematch / leaveRoom。 */
    void onGameMessage(WebSocketSession session, String payload);

    /** /game 连接关闭：保留座位，等待重连。 */
    void onGameClose(WebSocketSession session);

    /** 从大厅主动退出房间（HTTP 入口或 /room 的 leaveRoom 消息触发）。 */
    void leave(long userId);

    /** 大厅“返回房间”按钮用：返回该用户当前所在房间的摘要；不在房间时 exists=false。 */
    Map<String, Object> pendingReturn(long userId);
}
