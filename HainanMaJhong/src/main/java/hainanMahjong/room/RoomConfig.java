package hainanMahjong.room;

/**
 * 房间与对局的固定参数（原 {@code MultiPlayerRoomServiceImpl} 的顶层常量）。
 *
 * <p>单独抽出来的原因：{@link Member} 的构造器要用两个超时常量，而 {@code Member}
 * 已经搬到本包，不能再引用 impl 的私有常量。与其在两个类里各写一份，不如收口到这里。</p>
 *
 * <p>这些是<b>编译期常量</b>，不是配置项；要按房间可调的项在
 * {@link hainanMahjong.rules.HainanConfig}（房间配置）里。</p>
 */
public final class RoomConfig {

    private RoomConfig() {
        // 常量类，不实例化
    }

    /** 一个房间最多 4 个真人座位。 */
    public static final int MAX_MEMBERS = 4;


    /** 出牌超时：前端显示倒计时用（毫秒）。 */
    public static final long DISCARD_TIMEOUT_MS = 30_000L;

    /** 吃碰杠胡响应超时（毫秒）。 */
    public static final long ACTION_TIMEOUT_MS = 15_000L;

    /** 开局后等待四个座位 WebSocket 连入的宽限期（毫秒）。 */
    public static final long BIND_GRACE_MS = 40_000L;

    /** 一手结束到下把开始的停顿（等胡牌大字/音效播完）。 */
    public static final long HAND_END_MS = 2600L;

    /** 流局时的停顿（比 {@link #HAND_END_MS} 短，没有胡牌动画）。 */
    public static final long DRAW_PAUSE_MS = 1000L;
}
