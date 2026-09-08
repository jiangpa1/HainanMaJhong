package hainanjong.web;

import hainanjong.game.Action;
import hainanjong.game.PlayerController;
import hainanjong.game.Responder;
import hainanjong.game.Seat;

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

    /** 把一条 JSON 消息发给前端。 */
    public interface Sender {
        void send(Map<String, Object> msg);
    }

    private static final class Pending {
        final Responder responder;
        final List<Action> options;
        final Map<String, Object> msg; // 已发送的原始请求，重连时可重发
        Pending(Responder r, List<Action> o, Map<String, Object> m) {
            responder = r;
            options = o;
            msg = m;
        }
    }

    private volatile Sender sender;
    private final long discardTimeoutMs; // 出牌超时（毫秒），会随请求发给前端做倒计时
    private final long actionTimeoutMs;  // 吃碰杠胡/自摸响应超时（毫秒）
    private final AtomicInteger seq = new AtomicInteger();
    private final Map<Integer, Pending> pending = new HashMap<Integer, Pending>();
    private volatile boolean reportHint; // 引擎给出：本次出牌能否报听

    public HumanPlayerController(Sender sender, long discardTimeoutMs, long actionTimeoutMs) {
        this.sender = sender;
        this.discardTimeoutMs = discardTimeoutMs;
        this.actionTimeoutMs = actionTimeoutMs;
    }

    /** 更换消息出口（断线重连后指向新的 WebSocket session）。 */
    public void attach(Sender newSender) {
        this.sender = newSender;
    }

    /** 清空未完成的待决请求（每把开始前调用，避免上把的迟到响应串台）。 */
    public void reset() {
        synchronized (pending) {
            pending.clear();
        }
    }

    /** 把仍在等待前端回复的请求原样重发给当前出口（重连后调用）。 */
    public void resendPending() {
        List<Map<String, Object>> msgs;
        synchronized (pending) {
            msgs = new ArrayList<Map<String, Object>>();
            for (Pending p : pending.values()) {
                if (p.msg != null) {
                    msgs.add(p.msg);
                }
            }
        }
        Sender s = sender;
        for (Map<String, Object> m : msgs) {
            if (s != null) {
                s.send(m);
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
        emit(m);
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
        emit(m);
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
        emit(m);
    }

    private void remember(int id, Responder responder, List<Action> options, Map<String, Object> m) {
        synchronized (pending) {
            pending.clear();
            pending.put(id, new Pending(responder, options, m));
        }
    }

    private void emit(Map<String, Object> m) {
        Sender s = sender;
        if (s != null) {
            s.send(m);
        }
    }

    private List<Map<String, Object>> serializeOptions(List<Action> options) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Action a : options) {
            Map<String, Object> o = new HashMap<String, Object>();
            o.put("type", a.type.name());
            o.put("label", a.toString());
            List<Integer> tiles = new ArrayList<Integer>();
            if (a.tiles != null) {
                for (int t : a.tiles) {
                    tiles.add(t);
                }
            } else if (a.targetTile >= 0) {
                tiles.add(a.targetTile); // 胡：只展示胡的那张
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

    private Pending take(int reqId) {
        synchronized (pending) {
            return pending.remove(reqId);
        }
    }
}
