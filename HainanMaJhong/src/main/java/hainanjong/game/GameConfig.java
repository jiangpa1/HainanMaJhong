package hainanjong.game;

/**
 * 房间/牌局配置。
 */
public class GameConfig {
    public final boolean withFlowers;    // true=144 张（含 8 张花牌），false=136 张
    public final Seat dealer;            // 庄家座位
    public final long discardTimeoutMs;  // 出牌超时（毫秒），<=0 表示不限时
    public final long actionTimeoutMs;   // 吃碰杠胡响应超时（毫秒），<=0 表示不限时
    public final long seed;              // 洗牌随机种子，-1 表示不固定
    public final boolean chiOnlyNext;    // 吃只允许下家（标准规则 true）
    public final int maxTurns;           // 最多出牌次数（0 表示不限），用于演示限长
    public final boolean hainan;         // 是否启用海南规则（有番门禁/尾墙15等）
    public final int roundWindIdx;       // 当前“令”风 0东 1南 2西 3北（海南用）

    public GameConfig(boolean withFlowers, Seat dealer, long discardTimeoutMs, long actionTimeoutMs,
                      long seed, boolean chiOnlyNext, int maxTurns) {
        this(withFlowers, dealer, discardTimeoutMs, actionTimeoutMs, seed, chiOnlyNext, maxTurns,
                false, 0);
    }

    public GameConfig(boolean withFlowers, Seat dealer, long discardTimeoutMs, long actionTimeoutMs,
                      long seed, boolean chiOnlyNext, int maxTurns, boolean hainan, int roundWindIdx) {
        this.withFlowers = withFlowers;
        this.dealer = dealer;
        this.discardTimeoutMs = discardTimeoutMs;
        this.actionTimeoutMs = actionTimeoutMs;
        this.seed = seed;
        this.chiOnlyNext = chiOnlyNext;
        this.maxTurns = maxTurns;
        this.hainan = hainan;
        this.roundWindIdx = roundWindIdx;
    }

    public static GameConfig standard() {
        return new GameConfig(true, Seat.EAST
                , 15000, 15000, 20260903L, true, 0);
    }
}
