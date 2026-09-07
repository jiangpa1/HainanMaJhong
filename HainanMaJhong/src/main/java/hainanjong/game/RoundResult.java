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
    public final boolean ganKai;   // 是否杠上开花（杠/花后补牌自摸）
    public final boolean qiangGang;// 是否抢杠胡（补杠那张被胡）
    public final boolean tianHu;   // 是否天胡（当局从未出牌，摸牌即胡）
    public final int winnerBaoTing;// 胡牌者本局是否报听：0 无 / 1 天听 / 2 地听
    public final int winTile;      // 胡的那张牌（流局为 -1）
    public final Seat from;        // 点炮者（自摸为 null）
    public final HuResult hu;      // 番型结果（流局为 null）
    public final List<Integer> winHand;  // 胡牌时的暗牌手牌（含胡的那张，已排序）
    public final List<Meld> winMelds;    // 胡牌时的副露（杠按 4 张）

    private RoundResult(boolean isDraw, Seat winner, boolean selfDraw, boolean ganKai,
                        boolean qiangGang, boolean tianHu, int winnerBaoTing,
                        int winTile, Seat from,
                        HuResult hu, List<Integer> winHand, List<Meld> winMelds) {
        this.isDraw = isDraw;
        this.winner = winner;
        this.selfDraw = selfDraw;
        this.ganKai = ganKai;
        this.qiangGang = qiangGang;
        this.tianHu = tianHu;
        this.winnerBaoTing = winnerBaoTing;
        this.winTile = winTile;
        this.from = from;
        this.hu = hu;
        this.winHand = winHand;
        this.winMelds = winMelds;
    }

    public static RoundResult draw() {
        return new RoundResult(true, null, false, false, false, false, 0, -1, null, null, null, null);
    }

    public static RoundResult win(Seat winner, boolean selfDraw, boolean ganKai, int winTile, Seat from,
                                  HuResult hu, List<Integer> winHand, List<Meld> winMelds) {
        return winFull(winner, selfDraw, ganKai, false, 0, winTile, from, hu, winHand, winMelds);
    }

    public static RoundResult winFull(Seat winner, boolean selfDraw, boolean ganKai,
                                      boolean tianHu, int winnerBaoTing, int winTile, Seat from,
                                      HuResult hu, List<Integer> winHand, List<Meld> winMelds) {
        return new RoundResult(false, winner, selfDraw, ganKai, false, tianHu, winnerBaoTing,
                winTile, from, hu, winHand, winMelds);
    }

    public static RoundResult qiangWin(Seat winner, int winTile, Seat ganger,
                                       HuResult hu, List<Integer> winHand, List<Meld> winMelds) {
        return new RoundResult(false, winner, false, false, true, false, 0, winTile, ganger, hu, winHand, winMelds);
    }

    public static RoundResult qiangWinFull(Seat winner, int winTile, Seat ganger,
                                           boolean tianHu, int winnerBaoTing,
                                           HuResult hu, List<Integer> winHand, List<Meld> winMelds) {
        return new RoundResult(false, winner, false, false, true, tianHu, winnerBaoTing,
                winTile, ganger, hu, winHand, winMelds);
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
