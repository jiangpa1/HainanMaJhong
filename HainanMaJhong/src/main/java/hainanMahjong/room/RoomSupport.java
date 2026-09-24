package hainanMahjong.room;

import hainanMahjong.engine.model.Seat;

/**
 * 房间相关的只读判定工具。
 *
 * <p>为什么单独抽一个类：这几个方法是<b>门面与引擎监听器都要用的</b>纯判定。
 * 如果留在某一方，另一方就得复制一份；复制两份的常量/判定迟早会不一致。</p>
 *
 * <p>本类<b>无状态、不加锁、不修改任何东西</b> —— 只读 {@link Room} / {@link Member} 的字段。
 * 调用方通常已持有 {@code synchronized (room)}，这里不再加锁。</p>
 */
public final class RoomSupport {

    private RoomSupport() {
        // 工具类，不实例化
    }

    /** 人机补齐的机器人成员：userId 为负，无 WebSocket，由引擎以 BotController 托管。 */
    public static boolean isBot(Member m) {
        return m != null && m.userId < 0;
    }

    /** 座位是否已绑上可用的牌局连接。 */
    public static boolean bound(Member m) {
        return m != null && m.gameWs != null && m.gameWs.isOpen();
    }

    /** 补位机器人使用的固定负 id：与战绩页 电脑·南/西/北 的口径一致。 */
    public static long botUserId(Seat seat) {
        switch (seat) {
            case SOUTH: return -1;
            case WEST:  return -2;
            case NORTH: return -3;
            default:    return -4; // EAST 一般不会缺位（房主即东）
        }
    }

    /** 补位机器人的昵称：「电脑·东/南/西/北」。 */
    public static String botNickname(Seat seat) {
        String name = new String[]{"东", "南", "西", "北"}[seat.ordinal() & 3];
        return "电脑·" + name;
    }

    /** 房间当前成员数（含补位电脑）。 */
    public static int memberCount(Room room) {
        return room.members.size();
    }
}
