package hainanjong.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 玩家：座位 + 手牌 + 副露 + 花牌。
 */
public class Player {
    public final Seat seat;
    public final PlayerController controller;
    public final List<Integer> hand = new ArrayList<Integer>();   // 手牌（不含花牌），已排序
    public final List<Meld> melds = new ArrayList<Meld>();         // 吃/碰/杠
    public final List<Integer> flowers = new ArrayList<Integer>(); // 花牌
    public int lastDrawn = -1; // 最近摸到的那张牌（用于自摸胡汇报）

    public Player(Seat seat, PlayerController controller) {
        this.seat = seat;
        this.controller = controller;
    }

    public void sortHand() {
        Collections.sort(hand);
    }

    public int count(int tile) {
        int c = 0;
        for (int t : hand) {
            if (t == tile) c++;
        }
        return c;
    }

    public void remove(int tile, int n) {
        for (int i = 0; i < n; i++) {
            hand.remove((Integer) tile);
        }
        sortHand();
    }
}
