package hainanjong.game;

import hainanjong.HuLib;

/**
 * 一组副露（吃/碰/明杠/暗杠/补杠）。
 */
public class Meld {
    public enum Type {CHI, PENG, GANG, AN_GANG, BU_GANG}

    public final Type type;
    public final int[] tiles; // CHI 为 3 张连续牌；PENG 为 3 张同牌；杠为 4 张同牌
    public final Seat from;   // 来源（吃/碰/明杠 = 打出该牌的玩家；暗杠/补杠 = null）

    public Meld(Type type, int[] tiles, Seat from) {
        this.type = type;
        this.tiles = tiles;
        this.from = from;
    }

    @Override
    public String toString() {
        String name;
        switch (type) {
            case CHI:
                name = "吃";
                break;
            case PENG:
                name = "碰";
                break;
            case GANG:
                name = "明杠";
                break;
            case AN_GANG:
                name = "暗杠";
                break;
            default:
                name = "补杠";
                break;
        }
        StringBuilder sb = new StringBuilder(name + "[");
        for (int i = 0; i < tiles.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(HuLib.cardName(tiles[i]));
        }
        sb.append(']');
        if (from != null) sb.append("(来自").append(from).append(')');
        return sb.toString();
    }
}
