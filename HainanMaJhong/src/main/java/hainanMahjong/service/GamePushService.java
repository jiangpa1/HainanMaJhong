package hainanMahjong.service;

import hainanMahjong.engine.model.Meld;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.room.Room;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * 牌局消息的<b>组装与发送</b>（推送层）。
 *
 * <p>职责边界：本接口只"读房间状态 → 组装 JSON → 发出去"，<b>不修改任何房间状态</b>，
 * 也不做 MySQL / Redis 读写。状态机与引擎调度留在
 * {@link hainanMahjong.service.impl.MultiPlayerRoomServiceImpl}。</p>
 *
 * <p><b>并发约定</b>：本接口的方法<b>不自带锁</b>；调用方通常已持有
 * {@code synchronized (room)}。实现内部不要引入新的锁，也不要在这里做耗时 I/O。</p>
 *
 * <p><b>为什么 sendJson 是 public</b>：它是全项目唯一的 WebSocket 发送出口
 * （统一处理"session 已关闭"与发送异常、并对 session 加锁避免并发写），
 * 房间与引擎的其它模块也要用它，所以必须公开。</p>
 */
public interface GamePushService {

    /** 向房间内所有<b>真人</b>的牌局连接广播（机器人跳过；连接已关闭的跳过）。 */
    void broadcastGame(Room room, Map<String, Object> msg);

    /**
     * 同一件事，但对不同座位发<b>不同内容</b>。
     *
     * <p>目前唯一的用途是<b>暗杠</b>：暗杠的牌面只有自己能看到，别人只能看到
     * "这里有一副暗杠"。如果像普通副露那样一条消息广播给所有人，牌面就泄露出去了
     * （谁都能在浏览器开发者工具里看到那 4 张是什么牌）。</p>
     *
     * @param owner   持有该暗杠的座位：他收到 {@code full}
     * @param full    发给定座的完整内容
     * @param masked  发给其余真人的隐藏内容
     */
    void broadcastGameMasked(Room room, Seat owner, Map<String, Object> full, Map<String, Object> masked);

    /** 向单个 session 发 JSON；session 为 null / 已关闭时静默返回，发送异常只记日志。 */
    void sendJson(WebSocketSession session, Map<String, Object> msg);

    /** 房间各座位金币视图：{座位名 -> 金币}，四个座位都有（缺省 0）。 */
    Map<String, Integer> coinView(Room room);

    /** 副露 → JSON 可序列化结构：{type, tiles[], from?}。 */
    Map<String, Object> serializeMeld(Meld meld);

    /**
     * 副露 → JSON，可指定是否隐藏牌面。
     *
     * @param hidden true 时只带 {@code type} 与 {@code hidden:true}，
     *               {@code tiles} 是占位值（前端看到 hidden 就画牌背，不读 tiles）
     */
    Map<String, Object> serializeMeld(Meld meld, boolean hidden);

    /**
     * 推送房间信息（等待房的成员列表）。
     *
     * @param only 只发给这一个连接（如"刚加入的那个人"）；null 表示发给房内所有等待房连接
     */
    void sendRoomInfo(Room room, WebSocketSession only);

    /** 组装房间信息消息体（{@code room_info}）：房间码、房主、状态、成员座位表。 */
    Map<String, Object> roomInfoMsg(Room room);
}
