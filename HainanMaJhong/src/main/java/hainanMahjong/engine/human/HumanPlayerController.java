package hainanMahjong.engine.human;

import hainanMahjong.engine.model.Action;
import hainanMahjong.engine.port.PlayerController;
import hainanMahjong.engine.port.Responder;
import hainanMahjong.engine.model.Seat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 人类玩家的 {@link PlayerController} 实现：把决策请求转发给前端，
 * 并阻塞等待前端通过 WebSocket 返回的决定。
 *
 * <p>注意：这里不阻塞房间线程——回调方法只记录 responder 并立即返回，
 * 前端回复后由 WebSocket 线程调用 responder 完成应答；超时由 {@code RoomManager}
 * 的定时器强制处理。</p>
 *
 * <p>发送端可在断线重连时通过 {@link #attach} 更换，并用 {@link #resendPending}
 * 把仍在等待回复的请求重新发给新连接（多人房断线回座位用）。</p>
 */
public class HumanPlayerController implements PlayerController {

    private static final Logger log = LoggerFactory.getLogger(HumanPlayerController.class);

    /** 把一条 JSON 消息发给前端。 */
    public interface Sender {
        void send(Map<String, Object> msg);
    }

    /**
     * @param msg 已发送的原始请求，重连时可重发
     */
    private record Pending(Responder responder, List<Action> options, Map<String, Object> msg) {
    }

    private volatile Sender sender;
    private final long discardTimeoutMs; // 出牌超时（毫秒），会随请求发给前端做倒计时
    private final long actionTimeoutMs;  // 吃碰杠胡/自摸响应超时（毫秒）
    private final AtomicInteger seq = new AtomicInteger();
    private final Map<Integer, Pending> pending = new HashMap<Integer, Pending>();
    private volatile boolean reportHint; // 引擎给出：本次出牌能否报听
    /** 仅用于日志定位（哪个座位卡住了）；不参与逻辑。 */
    private final String who;

    public HumanPlayerController(Sender sender, long discardTimeoutMs, long actionTimeoutMs) {
        this(sender, discardTimeoutMs, actionTimeoutMs, "?");
    }

    public HumanPlayerController(Sender sender, long discardTimeoutMs, long actionTimeoutMs, String who) {
        this.sender = sender;
        this.discardTimeoutMs = discardTimeoutMs;
        this.actionTimeoutMs = actionTimeoutMs;
        this.who = who;
    }

    /** 更换消息出口（断线重连后指向新的 WebSocket session）。 */
    public void attach(Sender newSender) {
        this.sender = newSender;
    }

    /** 清空未完成的待决请求（每把开始前调用，避免上把的迟到响应串台）。 */
    /** 把仍在等待前端回复的请求原样重发给当前出口（重连后调用）。 */
    public void resendPending() {
        List<Map<String, Object>> msgs;
        synchronized (pending) {
            msgs = new ArrayList<Map<String, Object>>();
            for (Pending p : pending.values()) {
                if (p.msg() != null) {
                    msgs.add(p.msg());
                }
            }
        }
        Sender s = sender;
        for (Map<String, Object> m : msgs) {
            if (s != null) {
                try {
                    s.send(m);
                } catch (Exception e) {
                    log.warn("[{}] 重发待决请求失败：{}", who, e.getMessage());
                }
            }
        }
    }

    @Override
    public void prepareDiscard(boolean canReport) {
        this.reportHint = canReport;
    }

    @Override
    public void onDiscardTurn(Seat seat, List<Integer> hand, int drawnTile, List<Integer> banned, Responder responder) {
        int id = seq.incrementAndGet();
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("type", "request");
        m.put("kind", "discard");
        m.put("reqId", id);
        m.put("hand", new ArrayList<Integer>(hand));
        m.put("drawnTile", drawnTile);
        m.put("canReport", reportHint);
        m.put("timeoutMs", discardTimeoutMs);
        if (banned != null && !banned.isEmpty()) {
            m.put("ban", new ArrayList<Integer>(banned)); // 吃后禁打：前端置灰
        }
        remember(id, responder, null, m);
        warnIfUndelivered(id, "discard", emit(m));
    }

    @Override
    public void onActionChance(Seat seat, int tile, List<Action> options, Responder responder) {
        int id = seq.incrementAndGet();
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("type", "request");
        m.put("kind", "action");
        m.put("reqId", id);
        m.put("tile", tile);
        m.put("options", serializeOptions(options));
        m.put("timeoutMs", actionTimeoutMs);
        remember(id, responder, options, m);
        warnIfUndelivered(id, "action", emit(m));
    }

    @Override
    public void onDrawChance(Seat seat, int drawnTile, List<Action> options, Responder responder) {
        int id = seq.incrementAndGet();
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("type", "request");
        m.put("kind", "draw");
        m.put("reqId", id);
        m.put("drawnTile", drawnTile);
        m.put("options", serializeOptions(options));
        m.put("timeoutMs", actionTimeoutMs);
        remember(id, responder, options, m);
        warnIfUndelivered(id, "draw", emit(m));
    }

    /**
     * 发一条请求给前端。
     *
     * @return 是否真的送到。返回 false 表示发送通道缺失（前端 /game 根本没连上），
     *         此时引擎必然要干等一个完整超时 —— 这是"卡住"最隐蔽的一种成因，
     *         必须在日志里留痕，否则只能看到一句"超时"却不知为何。
     */
    private boolean emit(Map<String, Object> m) {
        Sender s = sender;
        if (s == null) {
            return false;
        }
        try {
            s.send(m);
            return true;
        } catch (Exception e) {
            log.warn("[{}] 询问发送失败：{}", who, e.getMessage());
            return false;
        }
    }

    /**
     * 记录一个新的待决请求。
     *
     * <p><b>不要再调用 {@code pending.clear()}</b>。原实现每次询问都把上一个请求清掉，
     * 只留最新一个，后果是：</p>
     * <ol>
     *   <li>引擎发出 {@code request#1}，前端因任何原因没收到（网络抖动、页面卡顿、切标签页）；</li>
     *   <li>该请求的 responder 被 {@code #2} 到来时清掉，<b>它自己的超时兜底也随之失效</b>；</li>
     *   <li>于是这一把彻底停住 —— 既等不到应答，也不会自动出牌。</li>
     * </ol>
     *
     * <p>这正是"点了出牌没反应、后端一行日志都没有"的成因。
     * 现在改为保留全部未决请求，由 {@link #take(int)} 按 reqId 精确移除；
     * 引擎每次只等一个，所以正常情况下 map 里最多也只会有一条。</p>
     */
    private void remember(int id, Responder responder, List<Action> options, Map<String, Object> m) {
        synchronized (pending) {
            pending.put(id, new Pending(responder, options, m));
        }
    }

    /** 发出询问后记录结果：没送到就告警，便于把"卡住"直接定位到连接问题。 */
    private void warnIfUndelivered(int id, String kind, boolean delivered) {
        if (!delivered) {
            log.warn("[{}] {} 请求 reqId={} 未送达前端（发送通道缺失，/game 可能已断开）；"
                    + "引擎将等待超时后强制处理", who, kind, id);
        }
    }

    private List<Map<String, Object>> serializeOptions(List<Action> options) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Action a : options) {
            Map<String, Object> o = new HashMap<String, Object>();
            o.put("type", a.type().name());
            o.put("label", a.toString());
            List<Integer> tiles = new ArrayList<Integer>();
            if (a.tiles() != null) {
                for (int t : a.tiles()) {
                    tiles.add(t);
                }
            } else if (a.targetTile() >= 0) {
                tiles.add(a.targetTile()); // 胡：只展示胡的那张
            }
            o.put("tiles", tiles);
            out.add(o);
        }
        return out;
    }

    // ==================== 由 WebSocket 线程调用 ====================

    public void discard(int reqId, int tile) {
        Pending p = take(reqId);
        if (p != null) {
            p.responder.discard(tile);
        }
    }

    /** 前端点击“报听”：应答为报听（引擎会打出刚摸那张并锁定）。 */
    public void baoTing(int reqId) {
        Pending p = take(reqId);
        if (p != null) {
            p.responder.report();
        }
    }

    public void act(int reqId, int index) {
        Pending p = take(reqId);
        if (p != null && p.options != null && index >= 0 && index < p.options.size()) {
            p.responder.act(p.options.get(index));
        }
    }

    public void pass(int reqId) {
        Pending p = take(reqId);
        if (p != null) {
            p.responder.pass();
        }
    }

    /**
     * 取出并移除指定 reqId 的待决请求。
     *
     * <p><b>拿不到时不能什么都不做</b>。原来的实现是 {@code pending.remove(reqId)} 直接返回 null，
     * 于是"玩家的出牌消息晚到 / reqId 对不上 / 消息在传输中丢了"这一类问题<b>完全静默</b>：
     * 前端那边点击时已经把 {@code state.pending} 置空、按钮全部收起，
     * 服务端这边把应答丢掉，双方就干等一个完整超时（出牌 30 秒）。
     * 表现出来就是"点了牌没反应、卡住"。</p>
     *
     * <p>现在的处理：记一条 WARN，把仍在等待的那个请求<b>原样重发</b>给前端。
     * 前端重新拿到正确 reqId 后就能再次应答，不用等超时。</p>
     */
    private Pending take(int reqId) {
        Pending matched;
        Pending stillWaiting = null;
        synchronized (pending) {
            matched = pending.remove(reqId);
            if (matched == null) {
                for (Pending p : pending.values()) {
                    stillWaiting = p;
                    break;
                }
            }
        }
        if (matched == null) {
            if (stillWaiting == null) {
                // 正常情况：引擎超时后强制处理，前端随后才到达的迟到应答，走到这里
                log.debug("[{}] 收到 reqId={} 的应答，但当前没有待决请求（多半是超时后的迟到应答），已忽略",
                        who, reqId);
            } else {
                Map<String, Object> msg = stillWaiting.msg();
                Integer waitingId = msg == null ? null : (Integer) msg.get("reqId");
                log.warn("[{}] 收到 reqId={} 的应答，但当前等待的是 reqId={} —— 应答被丢弃。"
                                + "已把待决请求重发给前端，避免双方干等到超时",
                        who, reqId, waitingId);
                if (msg != null) {
                    emit(msg);
                }
            }
        } else {
            // 命中：这条日志用来确认"前端发出的 reqId 与服务端等的是同一个"
            log.info("[{}] 应答命中 reqId={}", who, reqId);
        }
        return matched;
    }
}
