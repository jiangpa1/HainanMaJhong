package hainanjong.game;

import hainanjong.HuLib;
import hainanjong.HuResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 房间管理器：负责整局流程。
 *
 * <ul>
 *   <li>洗牌发牌：{@link Collections#shuffle}，136（无花）或 144（含花）张。</li>
 *   <li>轮流抓牌/出牌；出牌与吃碰杠胡均带超时（默认 15 秒），超时强制动作。</li>
 *   <li>出牌后广播其余三家，判定吃/碰/杠/胡，按 胡 &gt; 杠 &gt; 碰 &gt; 吃 优先级询问。</li>
 * </ul>
 *
 * <p>调用 {@link #start()} 同步运行一整局（阻塞直到结束）。玩家决策通过
 * {@link PlayerController} 回调，事件通过 {@link GameListener} 输出。</p>
 */
public class RoomManager {

    private final GameConfig config;
    private final Map<Seat, Player> players = new EnumMap<Seat, Player>(Seat.class);
    private final GameListener listener;

    private final List<Integer> deck = new ArrayList<Integer>();
    private final List<Integer> discardPile = new ArrayList<Integer>();
    private final Random random = new Random();

    private ScheduledExecutorService scheduler;
    private volatile boolean roundOver;
    private RoundResult result;
    private int turnCount;

    public RoomManager(GameConfig config, Map<Seat, PlayerController> controllers, GameListener listener) {
        this.config = config;
        this.listener = (listener != null) ? listener : new GameListener() {
            public void onShuffle(int d) {}
            public void onDeal(Seat s) {}
            public void onDraw(Seat s, int t) {}
            public void onFlower(Seat s, int t) {}
            public void onDiscard(Seat s, int t) {}
            public void onMeld(Seat s, Meld m) {}
            public void onHu(Seat s, HuResult r, boolean sd, int t, Seat f) {}
            public void onTimeout(Seat s, String a) {}
            public void onRoundDraw() {}
            public void onEnd(RoundResult r) {}
        };
        for (Seat s : Seat.values()) {
            players.put(s, new Player(s, controllers.get(s)));
        }
    }

    public RoundResult getResult() {
        return result;
    }

    // ==================== 启动 ====================

    public void start() {
        buildDeck();
        shuffle();
        deal();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mahjong-timer");
            t.setDaemon(true);
            return t;
        });
        try {
            doTurn(config.dealer, true, true);
        } finally {
            scheduler.shutdownNow();
        }
        listener.onEnd(result);
    }

    private void buildDeck() {
        deck.clear();
        for (int k = 0; k < 34; k++) {
            for (int i = 0; i < 4; i++) {
                deck.add(k);
            }
        }
        if (config.withFlowers) {
            for (int k = HuLib.HUA; k < HuLib.TOTAL_TILES; k++) {
                deck.add(k); // 花牌各 1 张
            }
        }
    }

    private void shuffle() {
        if (config.seed >= 0) {
            random.setSeed(config.seed);
        }
        Collections.shuffle(deck, random);
        listener.onShuffle(deck.size());
    }

    private void deal() {
        for (Seat s : Seat.values()) {
            Player p = players.get(s);
            int n = (s == config.dealer) ? 14 : 13;
            while (p.hand.size() < n) {
                int t = takeFromWall();
                if (t < 0) break;
                if (isFlower(t)) {
                    p.flowers.add(t);
                    listener.onFlower(s, t);
                    continue;
                }
                p.hand.add(t);
            }
            p.sortHand();
        }
        listener.onDeal(config.dealer);
    }

    // ==================== 回合主流程 ====================

    /**
     * @param skipDraw  是否跳过抓牌（庄家首回合 / 碰吃后）
     * @param allowWin  是否允许自摸胡/杠判定（碰吃后为 false，避免把碰后的 11 张误判成自摸）
     */
    private void doTurn(Seat seat, boolean skipDraw, boolean allowWin) {
        if (roundOver) return;
        Player p = players.get(seat);

        if (!skipDraw) {
            int t = drawOne(p);
            if (t < 0) {
                endDraw();
                return;
            }
        } else {
            p.lastDrawn = -1;
        }

        // 自摸胡 / 暗杠 / 补杠（杠后补牌，可连续）
        if (allowWin) {
            while (!roundOver) {
                List<Action> opts = detectDrawActions(p);
                if (opts.isEmpty()) break;
                Action a = requestDrawAction(p, opts);
                if (a == null || a.type == Action.Type.PASS) break;
                applyDrawAction(p, a);
                if (roundOver) return;
                int t = drawOne(p); // 杠后补牌
                if (t < 0) {
                    endDraw();
                    return;
                }
            }
        }
        if (roundOver) return;

        // 出牌
        int tile = discard(p);
        if (roundOver) return;

        // 处理其他三家的动作
        Claim claim = resolveDiscard(p, tile);
        if (roundOver) return;

        if (claim == null) {
            doTurn(seat.next(), false, true);
        } else if (claim.action.type == Action.Type.PENG || claim.action.type == Action.Type.CHI) {
            doTurn(claim.seat, true, false); // 碰/吃后不抓牌，直接出牌
        } else { // 明杠后补牌
            doTurn(claim.seat, false, true);
        }
    }

    private int drawOne(Player p) {
        while (true) {
            int t = takeFromWall();
            if (t < 0) return -1;
            if (isFlower(t)) {
                p.flowers.add(t);
                listener.onFlower(p.seat, t);
                continue; // 花牌放一旁，补牌
            }
            p.hand.add(t);
            p.sortHand();
            p.lastDrawn = t;
            listener.onDraw(p.seat, t);
            return t;
        }
    }

    private int discard(Player p) {
        int tile = requestDiscard(p);
        p.remove(tile, 1);
        discardPile.add(tile);
        turnCount++;
        listener.onDiscard(p.seat, tile);
        if (config.maxTurns > 0 && turnCount >= config.maxTurns) {
            endDraw();
        }
        return tile;
    }

    private void endDraw() {
        roundOver = true;
        result = RoundResult.draw();
        listener.onRoundDraw();
    }

    private int takeFromWall() {
        return deck.isEmpty() ? -1 : deck.remove(deck.size() - 1);
    }

    private static boolean isFlower(int t) {
        return t >= HuLib.HUA;
    }

    private static boolean isSuit(int t) {
        return t >= 0 && t < 27;
    }

    // ==================== 动作检测 ====================

    /** 别人打出 {@code tile} 后，玩家 {@code q} 可做的动作（已按优先级排序）。 */
    private List<Action> detectDiscardActions(Player q, int tile, Seat discarder) {
        List<Action> acts = new ArrayList<Action>();

        if (canHu(q, tile)) {
            acts.add(Action.hu(tile, discarder));
        }
        if (q.count(tile) >= 2) {
            acts.add(Action.peng(tile, discarder));
        }
        if (q.count(tile) >= 3) {
            acts.add(Action.gang(tile, discarder));
        }
        boolean chiAllowed = !config.chiOnlyNext || q.seat == discarder.next();
        if (chiAllowed && isSuit(tile)) {
            for (int[] seq : chiSequences(tile)) {
                boolean ok = true;
                for (int x : seq) {
                    if (x != tile && q.count(x) < 1) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    acts.add(Action.chi(seq, tile, discarder));
                }
            }
        }
        sortByPriority(acts);
        return acts;
    }

    /** 摸牌后玩家可做的动作：自摸胡 / 暗杠 / 补杠。 */
    private List<Action> detectDrawActions(Player p) {
        List<Action> acts = new ArrayList<Action>();

        if (canHu(p, -1)) {
            acts.add(Action.huSelfDraw(p.lastDrawn));
        }
        for (int t = 0; t < 34; t++) {
            if (p.count(t) == 4) {
                acts.add(Action.anGang(t));
            }
            if (p.count(t) >= 1 && hasPengOf(p, t)) {
                acts.add(Action.buGang(t));
            }
        }
        sortByPriority(acts);
        return acts;
    }

    private boolean hasPengOf(Player p, int tile) {
        for (Meld m : p.melds) {
            if (m.type == Meld.Type.PENG && m.tiles[0] == tile) {
                return true;
            }
        }
        return false;
    }

    /** 将副露视作 3 张一组，连同手牌与额外一张牌，交给查表法判胡。 */
    private boolean canHu(Player p, int extraTile) {
        return HuLib.checkHu(buildVirtualHand(p, extraTile)).isHu;
    }

    private int[] buildVirtualHand(Player p, int extraTile) {
        int[] cnt = new int[HuLib.TOTAL_TILES];
        for (Meld m : p.melds) {
            if (m.type == Meld.Type.CHI) {
                cnt[m.tiles[0]]++;
                cnt[m.tiles[1]]++;
                cnt[m.tiles[2]]++;
            } else {
                cnt[m.tiles[0]] += 3; // 碰/杠都按一组 3 张计
            }
        }
        for (int t : p.hand) {
            cnt[t]++;
        }
        if (extraTile >= 0) {
            cnt[extraTile]++;
        }
        return cnt;
    }

    private static List<int[]> chiSequences(int tile) {
        List<int[]> res = new ArrayList<int[]>();
        if (!isSuit(tile)) return res;
        int p = tile % 9;
        if (p - 2 >= 0) res.add(new int[]{tile - 2, tile - 1, tile});
        if (p - 1 >= 0 && p + 1 <= 8) res.add(new int[]{tile - 1, tile, tile + 1});
        if (p + 2 <= 8) res.add(new int[]{tile, tile + 1, tile + 2});
        return res;
    }

    private static void sortByPriority(List<Action> acts) {
        Collections.sort(acts, new Comparator<Action>() {
            public int compare(Action a, Action b) {
                return a.priority() - b.priority();
            }
        });
    }

    // ==================== 优先级与动作执行 ====================

    private static final class Claim {
        final Seat seat;
        final Action action;
        Claim(Seat s, Action a) { seat = s; action = a; }
    }

    private Claim resolveDiscard(Player discarder, int tile) {
        Map<Seat, List<Action>> byPlayer = new EnumMap<Seat, List<Action>>(Seat.class);
        for (Seat s : Seat.values()) {
            if (s == discarder.seat) continue;
            List<Action> acts = detectDiscardActions(players.get(s), tile, discarder.seat);
            if (!acts.isEmpty()) {
                byPlayer.put(s, acts);
            }
        }
        if (byPlayer.isEmpty()) return null;

        // 按「最高优先级动作」排序；同级按离出牌者近优先
        List<Seat> order = new ArrayList<Seat>(byPlayer.keySet());
        Collections.sort(order, new Comparator<Seat>() {
            public int compare(Seat a, Seat b) {
                int pa = bestPriority(byPlayer.get(a));
                int pb = bestPriority(byPlayer.get(b));
                if (pa != pb) return pa - pb;
                return a.stepsAfter(discarder.seat) - b.stepsAfter(discarder.seat);
            }
        });

        for (Seat s : order) {
            List<Action> acts = byPlayer.get(s);
            Action chosen = requestAction(players.get(s), tile, acts);
            if (chosen == null || chosen.type == Action.Type.PASS) {
                continue;
            }
            applyDiscardAction(players.get(s), discarder, tile, chosen);
            return new Claim(s, chosen);
        }
        return null;
    }

    private static int bestPriority(List<Action> acts) {
        int best = Integer.MAX_VALUE;
        for (Action a : acts) {
            best = Math.min(best, a.priority());
        }
        return best;
    }

    private void applyDiscardAction(Player p, Player discarder, int tile, Action a) {
        switch (a.type) {
            case HU:
                win(p, false, tile, discarder.seat);
                break;
            case PENG:
                p.remove(tile, 2);
                addMeld(p, new Meld(Meld.Type.PENG, new int[]{tile, tile, tile}, discarder.seat));
                popDiscard();
                break;
            case GANG:
                p.remove(tile, 3);
                addMeld(p, new Meld(Meld.Type.GANG, new int[]{tile, tile, tile, tile}, discarder.seat));
                popDiscard();
                break;
            case CHI:
                for (int x : a.tiles) {
                    if (x != tile) p.remove(x, 1);
                }
                addMeld(p, new Meld(Meld.Type.CHI, a.tiles.clone(), discarder.seat));
                popDiscard();
                break;
            default:
                break;
        }
    }

    private void applyDrawAction(Player p, Action a) {
        switch (a.type) {
            case HU:
                win(p, true, -1, null);
                break;
            case AN_GANG:
                p.remove(a.targetTile, 4);
                addMeld(p, new Meld(Meld.Type.AN_GANG, new int[]{a.targetTile
                        , a.targetTile, a.targetTile, a.targetTile}, null));
                break;
            case BU_GANG:
                p.remove(a.targetTile, 1);
                for (int i = 0; i < p.melds.size(); i++) {
                    Meld m = p.melds.get(i);
                    if (m.type == Meld.Type.PENG && m.tiles[0] == a.targetTile) {
                        p.melds.set(i, new Meld(Meld.Type.BU_GANG,
                                new int[]{a.targetTile, a.targetTile, a.targetTile, a.targetTile}, null));
                        listener.onMeld(p.seat, p.melds.get(i));
                        break;
                    }
                }
                break;
            default:
                break;
        }
    }

    private void addMeld(Player p, Meld m) {
        p.melds.add(m);
        listener.onMeld(p.seat, m);
    }

    private void popDiscard() {
        if (!discardPile.isEmpty()) {
            discardPile.remove(discardPile.size() - 1);
        }
    }

    private void win(Player p, boolean selfDraw, int extraTile, Seat from) {
        HuResult res = HuLib.checkHu(buildVirtualHand(p, extraTile));
        roundOver = true;
        int winTile = selfDraw ? p.lastDrawn : extraTile;
        result = RoundResult.win(p.seat, selfDraw, winTile, from, res);
        listener.onHu(p.seat, res, selfDraw, winTile, from);
    }

    // ==================== 决策请求（含超时） ====================

    private int requestDiscard(Player p) {
        final int forcedTile = autoDiscard(p);
        Integer chosen = prompt(p.seat, "出牌", config.discardTimeoutMs,
                reply -> p.controller.onDiscardTurn(p.seat, new ArrayList<Integer>(p.hand),
                        new Responder() {
                            public void discard(int k) { reply.accept(k); }
                            public void act(Action a) { reply.accept(null); }
                            public void pass() { reply.accept(null); }
                        }),
                forcedTile);
        return (chosen != null && p.hand.contains(chosen)) ? chosen : forcedTile;
    }

    private Action requestAction(Player p, int tile, List<Action> opts) {
        return prompt(p.seat, "吃碰杠胡", config.actionTimeoutMs,
                reply -> p.controller.onActionChance(p.seat, tile, opts,
                        new Responder() {
                            public void discard(int k) { reply.accept(null); }
                            public void act(Action a) { reply.accept(a); }
                            public void pass() { reply.accept(null); }
                        }),
                null);
    }

    private Action requestDrawAction(Player p, List<Action> opts) {
        return prompt(p.seat, "自摸/杠", config.actionTimeoutMs,
                reply -> p.controller.onDrawChance(p.seat, p.lastDrawn, opts,
                        new Responder() {
                            public void discard(int k) { reply.accept(null); }
                            public void act(Action a) { reply.accept(a); }
                            public void pass() { reply.accept(null); }
                        }),
                null);
    }

    /** 强制出牌时选择的牌：优先单张孤张（字牌→孤张数牌→任意单张），否则第一张。 */
    private int autoDiscard(Player p) {
        List<Integer> h = p.hand;
        for (int t : h) if (t >= 27 && p.count(t) == 1) return t;
        for (int t : h) if (t < 27 && p.count(t) == 1 && isolated(h, t)) return t;
        for (int t : h) if (p.count(t) == 1) return t;
        return h.get(0);
    }

    private static boolean isolated(List<Integer> h, int t) {
        int p = t % 9;
        boolean left = (p == 0) || !h.contains(t - 1);
        boolean right = (p == 8) || !h.contains(t + 1);
        return left && right;
    }

    // ==================== 超时应答机制 ====================

    private interface PromptBody<T> {
        void invoke(Consumer<T> reply);
    }

    /**
     * 向控制器发起一次决策请求，带超时。控制器通过 {@code reply} 回调应答；
     * 超时则用 {@code forced} 强制值应答。
     */
    private <T> T prompt(Seat seat, String what, long timeoutMs, PromptBody<T> body, T forced) {
        final Object lock = new Object();
        final boolean[] done = {false};
        final Object[] box = new Object[1];
        final boolean[] forcedFlag = {false};
        final CountDownLatch latch = new CountDownLatch(1);

        final Consumer<T> reply = value -> {
            synchronized (lock) {
                if (done[0]) return;
                done[0] = true;
                box[0] = value;
            }
            latch.countDown();
        };

        final Runnable force = () -> {
            synchronized (lock) {
                if (done[0]) return;
                done[0] = true;
                box[0] = forced;
                forcedFlag[0] = true;
            }
            latch.countDown();
        };

        ScheduledFuture<?> f = null;
        if (scheduler != null && timeoutMs > 0) {
            f = scheduler.schedule(force, timeoutMs, TimeUnit.MILLISECONDS);
        }
        try {
            body.invoke(reply);
        } catch (Throwable ex) {
            force.run();
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (f != null) {
            f.cancel(false);
        }
        if (forcedFlag[0]) {
            listener.onTimeout(seat, what);
        }
        @SuppressWarnings("unchecked")
        T r = (T) box[0];
        return r;
    }
}
