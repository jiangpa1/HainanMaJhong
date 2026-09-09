package hainanjong.rules;

import hainanjong.game.RoundResult;
import hainanjong.game.Seat;

/**
 * 海南规则“坐庄/荒庄/令/底分”的房间级推进状态机。
 *
 * <ul>
 *   <li>首局庄随机；庄胡牌或流局(荒庄) → 连庄：底分=初始底分×(连庄次数+1)，令不变；</li>
 *   <li>非庄胡 → 下庄：新庄=原庄下家，底分重置为房间设定值（连庄次数清零）；</li>
 *   <li>“令”跟“首局庄第 2/3/4 次重新上庄”换南/西/北（连庄不推进）；</li>
 *   <li>一轮=打满东南西北四风：北风令里开局庄家的上家下庄（庄交回首局庄）才结束。</li>
 * </ul>
 */
public final class DealerFlow {

    private final HainanConfig cfg;
    private final Seat firstDealer;

    public Seat dealer;       // 当前庄（本把）
    public int bottom;        // 本把的庄底分 = cfg.basePoint × (keeps+1)
    public int windIdx;       // 本把令 0东 1南 2西 3北
    public int handNo;        // 从 1 计的把数（显示用）
    public boolean finished;  // 是否已打满四风

    /** 当前庄连续连庄次数（0=刚上庄）；连庄一次 +1，下庄归零。 */
    private int keeps;

    private int firstDealerBegins; // 首局庄已“开始坐庄”的次数
    private int handsPlayed;

    public DealerFlow(HainanConfig cfg, Seat firstDealer) {
        this.cfg = cfg;
        this.firstDealer = firstDealer;
        this.dealer = firstDealer;
        this.bottom = cfg.basePoint;   // keeps=0 → base×1
        this.windIdx = 0;              // 东风令
        this.handNo = 1;
        this.finished = false;
        this.keeps = 0;
        this.firstDealerBegins = 1;    // 首把即首局庄第一次坐庄
        this.handsPlayed = 0;
    }

    public int windIdx() {
        return windIdx;
    }

    /** 当前庄已连续连庄次数（乘算底分的乘数-1）。 */
    public int consecutiveKeeps() {
        return keeps;
    }

    public static String windName(int idx) {
        return new String[]{"东", "南", "西", "北"}[idx & 3];
    }

    /** 一局结束后推进状态；返回后 dealer/bottom/windIdx 表示下一把。 */
    public void afterRound(RoundResult r) {
        handsPlayed++;
        handNo++;
        boolean dealerWon = !r.isDraw && r.winner == dealer;
        if (dealerWon || r.isDraw) {
            // 连庄：乘算底分 = 初始底分×(连庄次数+1)，令不变（荒庄/庄胡都连庄）
            keeps++;
            bottom = cfg.basePoint * (keeps + 1);
            if (handsPlayed > MAX_HANDS) {
                finished = true; // 防御性兜底，正常不会触发
            }
            return;
        }
        // 下庄：打满一圈 = 打满东/南/西/北四风；须在“北风令”里由开局庄家的上家把庄交回首局庄时才结束
        Seat upperFirst = Seat.values()[(firstDealer.ordinal() + 3) % 4]; // 上家（next 会回到 firstDealer）
        if (dealer == upperFirst && firstDealerBegins >= 4) {
            finished = true;
        }
        dealer = dealer.next();
        keeps = 0;
        bottom = cfg.basePoint; // 重置底分（乘数归 1）
        if (dealer == firstDealer) {
            // 首局庄重新上庄：换令（连庄期间已排除），最多到北
            firstDealerBegins++;
            if (windIdx < 3) {
                windIdx++;
            }
        }
        if (handsPlayed > MAX_HANDS) {
            finished = true;
        }
    }

    /** 防御上限（正常打满四风远小于此）。 */
    public static final int MAX_HANDS = 400;

    // ==================== 快照 / 恢复 ====================

    public Seat firstDealer() {
        return firstDealer;
    }

    public int begins() {
        return firstDealerBegins;
    }

    public int handsPlayed() {
        return handsPlayed;
    }

    /** 从存档重建（服务重启续跑用）。bottom=base×(keeps+1)，故可由 bottom 反推 keeps。 */
    public static DealerFlow restore(HainanConfig cfg, Seat first, Seat dealer, int bottom,
                                     int windIdx, int handNo, int begins, int handsPlayed) {
        DealerFlow f = new DealerFlow(cfg, first);
        f.dealer = dealer;
        f.bottom = bottom;
        f.windIdx = windIdx & 3;
        f.handNo = Math.max(1, handNo);
        f.firstDealerBegins = Math.max(1, begins);
        f.handsPlayed = Math.max(0, handsPlayed);
        int base = cfg.basePoint;
        f.keeps = base > 0 ? Math.max(0, bottom / base - 1) : 0;
        return f;
    }
}
