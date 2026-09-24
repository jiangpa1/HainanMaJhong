package hainanMahjong.engine.rule;

import hainanMahjong.engine.model.Seat;

/**
 * 海南规则：<b>根（跟牌）链跟踪</b>。
 *
 * <p>规则：庄家打出首张后，若 下→对→上 三家在<b>没有吃碰杠打断</b>的情况下依次打出<b>同一张牌</b>，
 * 记为一次"根"，参与结算加分。</p>
 *
 * <p>本类是一个小状态机，字段与原 {@code RoomManager} 里的 {@code gen*} 字段一一对应，
 * 逻辑逐字保留。调用方只需在合适的时机调 {@link #onDiscard} / {@link #noteInterrupted}，
 * 并在每把开始时 {@link #reset}。</p>
 *
 * <p><b>调用方要自己做的一件事</b>：本类只认"庄家"这一个座位，
 * 是否启用（{@code config.hainan}）由调用方判断后再调 {@link #onDiscard} ——
 * 与原实现里 {@code if (!config.hainan) return;} 的语义一致。</p>
 */
public class GenChainTracker {

    /** 是否已开始跟踪（庄家首张出牌后置位）。 */
    private boolean genArmed;

    /** 是否已被打断。 */
    private boolean genBroken;

    /** 要跟的那张牌。 */
    private int genTile = -1;

    /** 已连续跟了几家。 */
    private int genStep;

    /** 本把是否已达成"根"。 */
    private boolean genChainHit;

    /**
     * 跟踪一次出牌。
     *
     * @param dealer      庄家座位（跟踪起点）
     * @param seat        本次出牌的座位
     * @param discardCount 该座位到目前为止的出牌张数（用来识别"庄家首张"）
     * @param tile        本次打出的牌
     */
    public void onDiscard(Seat dealer, Seat seat, int discardCount, int tile) {
        if (seat == dealer && discardCount == 1) {
            genArmed = true;
            genBroken = false;
            genStep = 0;
            genTile = tile;
            return;
        }
        if (!genArmed || genBroken) {
            return;
        }
        if (tile != genTile) {
            genArmed = false;
            return;
        }
        Seat expect = dealer;
        for (int i = 0; i <= genStep; i++) {
            expect = expect.next();
        }
        if (seat == expect) {
            genStep++;
            if (genStep >= 3) {
                genChainHit = true;
                genArmed = false;
            }
        } else {
            genArmed = false;
        }
    }

    /** 有吃碰杠打断"依次跟牌"的顺次。 */
    public void noteInterrupted() {
        genArmed = false;
    }

    /** 本把是否达成"根"。 */
    public boolean chainHit() {
        return genChainHit;
    }

    /** 每把开始/恢复时重置。 */
    public void reset() {
        genArmed = false;
        genBroken = false;
        genStep = 0;
        genTile = -1;
        genChainHit = false;
    }
}
