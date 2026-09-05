package hainanjong.game;

import hainanjong.HuLib;

import java.util.Arrays;

/**
 * 一个可执行的动作：吃/碰/明杠/暗杠/补杠/胡/过。
 */
public class Action {
    public enum Type {CHI, PENG, GANG, AN_GANG, BU_GANG, HU, PASS}

    public final Type type;
    public final int targetTile; // 触发动作的牌（被吃/碰/杠/胡的那张，或自摸/杠时摸到的那张）；无则为 -1
    public final int[] tiles;    // 吃 = 3 张序列；杠 = 4 张；其余为 null
    public final Seat from;      // 打出该牌的玩家（自摸/暗杠/补杠为 null）

    public Action(Type type, int targetTile, int[] tiles, Seat from) {
        this.type = type;
        this.targetTile = targetTile;
        this.tiles = tiles;
        this.from = from;
    }

    /**
     * 优先级：胡(0) > 杠(1) > 碰(2) > 吃(3) > 过(99)。
     */
    public int priority() {
        switch (type) {
            case HU:
                return 0;
            case GANG:
            case AN_GANG:
            case BU_GANG:
                return 1;
            case PENG:
                return 2;
            case CHI:
                return 3;
            default:
                return 99;
        }
    }

    public static Action hu(int tile, Seat from) {
        return new Action(Type.HU, tile, null, from);
    }

    public static Action huSelfDraw(int tile) {
        return new Action(Type.HU, tile, null, null);
    }

    public static Action peng(int tile, Seat from) {
        return new Action(Type.PENG, tile, new int[]{tile, tile, tile}, from);
    }

    public static Action gang(int tile, Seat from) {
        return new Action(Type.GANG, tile, new int[]{tile, tile, tile, tile}, from);
    }

    public static Action chi(int[] seq, int tile, Seat from) {
        return new Action(Type.CHI, tile, seq, from);
    }

    public static Action anGang(int tile) {
        return new Action(Type.AN_GANG, tile, new int[]{tile, tile, tile, tile}, null);
    }

    public static Action buGang(int tile) {
        return new Action(Type.BU_GANG, tile, new int[]{tile, tile, tile, tile}, null);
    }

    @Override
    public String toString() {
        switch (type) {
            case HU:
                return "胡(" + HuLib.cardName(targetTile) + ")";
            case PENG:
                return "碰(" + HuLib.cardName(targetTile) + ")";
            case GANG:
                return "杠(" + HuLib.cardName(targetTile) + ")";
            case AN_GANG:
                return "暗杠(" + HuLib.cardName(targetTile) + ")";
            case BU_GANG:
                return "补杠(" + HuLib.cardName(targetTile) + ")";
            case CHI:
                return "吃" + Arrays.toString(tiles);
            default:
                return "过";
        }
    }
}
