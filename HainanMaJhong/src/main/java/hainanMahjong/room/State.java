package hainanMahjong.room;

/**
 * 房间状态机。
 *
 * <ul>
 *   <li>{@link #WAITING} —— 等待房：可创建/加入/人机补齐，人满或房主点开始后进入对局</li>
 *   <li>{@link #PLAYING} —— 对局中：引擎线程在跑，中途离开只做机器人托管、保留座位</li>
 *   <li>{@link #BETWEEN} —— 一块（打满四风）结束，等房主发起"再来一轮"</li>
 * </ul>
 *
 * <p>原先嵌套在 {@code MultiPlayerRoomServiceImpl} 内，为了让各拆分模块共用而提为顶层类型。</p>
 */
public enum State {
    WAITING,
    PLAYING,
    BETWEEN
}
