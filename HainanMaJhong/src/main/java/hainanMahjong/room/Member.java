package hainanMahjong.room;

import hainanMahjong.engine.model.Seat;
import hainanMahjong.engine.human.HumanPlayerController;
import org.springframework.web.socket.WebSocketSession;

import static hainanMahjong.room.RoomConfig.ACTION_TIMEOUT_MS;
import static hainanMahjong.room.RoomConfig.DISCARD_TIMEOUT_MS;

/**
 * 一个座位上的成员：可能是真人，也可能是补位机器人。
 *
 * <p><b>真人 / 机器人的判别</b>：{@code userId < 0} 即机器人（见
 * {@code MultiPlayerRoomServiceImpl.isBot}），机器人的两个 WebSocket 字段恒为 null。</p>
 *
 * <p><b>双连接</b>：等待房用 {@link #roomWs}（/room），牌局用 {@link #gameWs}（/game），
 * 两者可以独立断开与重连 —— 这是"对局中刷新/离开不结束游戏"的实现基础。</p>
 *
 * <p><b>托管切换</b>：{@link #controller} 是引擎看到的唯一控制器实例，
 * {@link #human} 是真人实现；断线时把 controller 的代理换成机器人，回来再换回真人。</p>
 *
 * <p>原先嵌套在 {@code MultiPlayerRoomServiceImpl} 内，为了让各拆分模块共用而提为顶层类型。</p>
 */
public class Member {

    public final Seat seat;
    public final long userId;
    public final String nickname;

    /** /room 等待房连接（可为 null）。 */
    public volatile WebSocketSession roomWs;

    /** /game 牌局连接（可为 null）。 */
    public volatile WebSocketSession gameWs;

    /** 对局中标记为离线（座位保留、由机器人托管）。 */
    public volatile boolean offline;

    /** 真人控制器（负责把询问发给前端并等待回复）。 */
    public final HumanPlayerController human;

    /** 引擎看到的控制器：可在真人 / 机器人之间切换。 */
    public final SwitchingController controller;

    public Member(Seat seat, long userId, String nickname, WebSocketSession roomWs) {
        this.seat = seat;
        this.userId = userId;
        this.nickname = nickname;
        this.roomWs = roomWs;
        this.human = new HumanPlayerController(null, DISCARD_TIMEOUT_MS, ACTION_TIMEOUT_MS, seat.name());
        this.controller = new SwitchingController(human);
        this.offline = false;
    }

    /**
     * 在房间里按 userId 找成员；找不到返回 null。
     *
     * <p>原先散在 Service 里的私有工具（{@code memberOf}），因为拆出的多个模块都要用，
     * 收到这里做静态方法 —— 它只读 {@link Room#members}，不改状态。</p>
     */
    public static Member byId(Room room, long userId) {
        if (room == null) {
            return null;
        }
        for (Member m : room.members.values()) {
            if (m.userId == userId) {
                return m;
            }
        }
        return null;
    }
}
