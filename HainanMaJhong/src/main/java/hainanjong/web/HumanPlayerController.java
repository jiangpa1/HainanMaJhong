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
 */
public class HumanPlayerController implements PlayerController {

    /** 把一条 JSON 消息发给前端。 */
    public interface Sender {
        void send(Map<String, Object> msg);
    }

    private static final class Pending {
        final Responder responder;
        final List<Action> options;
        Pending(Responder r, List<Action> o) { responder = r; options = o; }
    }

    private final Sender sender;
    private final long timeoutMs;
    private final AtomicInteger seq = new AtomicInteger();
    private final Map<Integer, Pending> pending = new HashMap<Integer, Pending>();

    public HumanPlayerController(Sender sender, long timeoutMs) {
        this.sender = sender;
        this.timeoutMs = timeoutMs;
    }

    /** 清空未完成的待决请求（每把开始前调用，避免上把的迟到响应串台）。 */
    public void reset() {
        synchronized (pending) {
            pending.clear();
        }
    }

    @Override
    public void onDiscardTurn(Seat seat, List<Integer> hand, Responder responder) {
        int id = seq.incrementAndGet();
        synchronized (pending) {
            pending.clear();
            pending.put(id, new Pending(responder, null));
        }
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("type", "request");
        m.put("kind", "discard");
        m.put("reqId", id);
        m.put("hand", new ArrayList<Integer>(hand));
        m.put("timeoutMs", timeoutMs);
        sender.send(m);
    }

    @Override
    public void onActionChance(Seat seat, int tile, List<Action> options, Responder responder) {
        int id = seq.incrementAndGet();
        synchronized (pending) {
            pending.clear();
            pending.put(id, new Pending(responder, options));
        }
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("type", "request");
        m.put("kind", "action");
        m.put("reqId", id);
        m.put("tile", tile);
        m.put("options", serializeOptions(options));
        m.put("timeoutMs", timeoutMs);
        sender.send(m);
    }

    @Override
    public void onDrawChance(Seat seat, int drawnTile, List<Action> options, Responder responder) {
        int id = seq.incrementAndGet();
        synchronized (pending) {
            pending.clear();
            pending.put(id, new Pending(responder, options));
        }
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("type", "request");
        m.put("kind", "draw");
        m.put("reqId", id);
        m.put("drawnTile", drawnTile);
        m.put("options", serializeOptions(options));
        m.put("timeoutMs", timeoutMs);
        sender.send(m);
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
