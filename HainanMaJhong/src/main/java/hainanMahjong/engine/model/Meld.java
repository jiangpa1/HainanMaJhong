package hainanMahjong.engine.model;

import hainanMahjong.rules.HuLib;

/**
 * 一组副露（吃/碰/明杠/暗杠/补杠）。
 *
 * @param tiles CHI 为 3 张连续牌；PENG 为 3 张同牌；杠为 4 张同牌
 * @param from  来源（吃/碰/明杠 = 打出该牌的玩家；暗杠/补杠 = null）
 */
public record Meld(Type type, int[] tiles, Seat from) {
    public enum Type {CHI, PENG, GANG, AN_GANG, BU_GANG}

    @Override
    public String toString() {
        String name = switch (type) {
            case CHI -> "吃";
            case PENG -> "碰";
            case GANG -> "明杠";
            case AN_GANG -> "暗杠";
            default -> "补杠";
        };
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
