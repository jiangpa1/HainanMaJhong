package hainanjong.game;

import hainanjong.HuLib;
import hainanjong.HuResult;

import java.io.PrintStream;

/**
 * 把牌局事件打印到控制台（并可选写入 UTF-8 日志文件）。
 */
public class PrintListener implements GameListener {

    private final PrintStream file;

    public PrintListener() {
        this(null);
    }

    public PrintListener(PrintStream file) {
        this.file = file;
    }

    private void log(String s) {
        System.out.println(s);
        if (file != null) {
            file.println(s);
            file.flush();
        }
    }

    @Override
    public void onShuffle(int deckSize) {
        log("[洗牌] 共 " + deckSize + " 张牌");
    }

    @Override
    public void onDeal(Seat dealer) {
        log("[发牌] 庄家=" + dealer);
    }

    @Override
    public void onDraw(Seat seat, int tile) {
        log("    " + seat + " 摸牌 " + HuLib.cardName(tile));
    }

    @Override
    public void onFlower(Seat seat, int tile) {
        log("    " + seat + " 摸到花牌 " + HuLib.cardName(tile) + "，补牌");
    }

    @Override
    public void onDiscard(Seat seat, int tile) {
        log("  > " + seat + " 打出 " + HuLib.cardName(tile));
    }

    @Override
    public void onMeld(Seat seat, Meld meld) {
        log("  * " + seat + " " + meld);
    }

    @Override
    public void onHu(Seat seat, HuResult result, boolean selfDraw, boolean tianHu, int baoTing, int tile, Seat from) {
        String s = "  ★ " + seat + " 胡牌！" + (selfDraw ? "（自摸 " + HuLib.cardName(tile) + "）" : "（" + from + " 点炮 " + HuLib.cardName(tile) + "）");
        s += "  番型=" + result.fanTypes;
        if (tianHu) s += " 天胡";
        if (baoTing == 1) s += " 天听";
        if (baoTing == 2) s += " 地听";
        if (result.flowerCount > 0) s += "，花牌x" + result.flowerCount;
        log(s);
    }

    @Override
    public void onTimeout(Seat seat, String action) {
        log("  ⏱ " + seat + " " + action + " 超时，强制执行");
    }

    @Override
    public void onRoundDraw() {
        log("[流局] 牌墙已摸完");
    }

    @Override
    public void onResume() {
        log("[恢复] 已从快照恢复对局");
    }

    @Override
    public void onEnd(RoundResult result) {
        log("[结束] " + result);
    }
}
