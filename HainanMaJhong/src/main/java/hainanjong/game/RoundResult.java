package hainanjong.game;

import hainanjong.HuResult;

import java.util.List;

/**
 * 一局的结果。
 */
public class RoundResult {
    public final boolean isDraw;   // 是否流局
    public final Seat winner;      // 胡牌者（流局为 null）
    public final boolean selfDraw; // 是否自摸
    public final int winTile;      // 胡的那张牌（流局为 -1）
    public final Seat from;        // 点炮者（自摸为 null）
    public final HuResult hu;      // 番型结果（流局为 null）
    public final List<Integer> winHand;  // 胡牌时的暗牌手牌（含胡的那张，已排序）
    public final List<Meld> winMelds;    // 胡牌时的副露（杠按 4 张）

    private RoundResult(boolean isDraw, Seat winner, boolean selfDraw, int winTile, Seat from,
                        HuResult hu, List<Integer> winHand, List<Meld> winMelds) {
        this.isDraw = isDraw;
        this.winner = winner;
        this.selfDraw = selfDraw;
        this.winTile = winTile;
        this.from = from;
        this.hu = hu;
        this.winHand = winHand;
        this.winMelds = winMelds;
    }

    public static RoundResult draw() {
        return new RoundResult(true, null, false, -1, null, null, null, null);
    }

    public static RoundResult win(Seat winner, boolean selfDraw, int winTile, Seat from,
                                  HuResult hu, List<Integer> winHand, List<Meld> winMelds) {
        return new RoundResult(false, winner, selfDraw, winTile, from, hu, winHand, winMelds);
    }

    @Override
    public String toString() {
        if (isDraw) {
            return "流局";
        }
        String s = winner + " 胡牌（" + (selfDraw ? "自摸" : from + " 点炮") + "）";
        if (winTile >= 0) {
            s += " " + hainanjong.HuLib.cardName(winTile);
        }
        if (hu != null) {
            s += " 番型=" + hu.fanTypes;
        }
        return s;
    }
}
