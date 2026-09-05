package hainanjong.web;

import hainanjong.FanType;
import hainanjong.HuResult;
import hainanjong.game.GameListener;
import hainanjong.game.Meld;
import hainanjong.game.Player;
import hainanjong.game.RoomManager;
import hainanjong.game.RoundResult;
import hainanjong.game.Seat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把牌局事件转成 JSON 推给前端：手牌快照（仅自己可见）、各玩家张数、公开事件。
 */
public class WebGameListener implements GameListener {

    public interface Sender {
        void send(Map<String, Object> msg);
    }

    private final Sender sender;
    private final Seat human;
    private RoomManager room;

    public WebGameListener(Sender sender, Seat human) {
        this.sender = sender;
        this.human = human;
    }

    public void setRoom(RoomManager room) {
        this.room = room;
    }

    @Override
    public void onShuffle(int deckSize) {
        log("洗牌完成，共 " + deckSize + " 张");
    }

    @Override
    public void onDeal(Seat dealer) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("type", "dealer");
        m.put("dealer", dealer.name());
        sender.send(m);
        sendHand();
        sendCounts();
        log("发牌完成，庄家是" + dealer.cn + (dealer == human ? "（你）" : ""));
    }

    @Override
    public void onDraw(Seat seat, int tile) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("type", "draw");
        m.put("seat", seat.name());
        m.put("tile", tile);
        sender.send(m);
        sendCounts();
        if (seat == human) {
            sendHand();
        }
    }

    @Override
    public void onFlower(Seat seat, int tile) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("type", "flower");
        m.put("seat", seat.name());
        m.put("tile", tile);
        sender.send(m);
        sendCounts();
        if (seat == human) {
            sendHand();
        }
    }

    @Override
    public void onDiscard(Seat seat, int tile) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("type", "discard");
        m.put("seat", seat.name());
        m.put("tile", tile);
        sender.send(m);
        sendCounts();
        if (seat == human) {
            sendHand();
        }
    }

    @Override
    public void onMeld(Seat seat, Meld meld) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("type", "meld");
        m.put("seat", seat.name());
        m.put("meld", serializeMeld(meld));
        sender.send(m);
        sendCounts();
        if (seat == human) {
            sendHand();
        }
    }

    @Override
    public void onHu(Seat seat, HuResult result, boolean selfDraw, int tile, Seat from) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("type", "hu");
        m.put("seat", seat.name());
        m.put("selfDraw", selfDraw);
        m.put("tile", tile);
        m.put("from", from == null ? null : from.name());
        List<String> fans = new ArrayList<String>();
        for (FanType f : result.fanTypes) {
            fans.add(f.toString());
        }
        m.put("fans", fans);
        sender.send(m);
    }

    @Override
    public void onTimeout(Seat seat, String action) {
        log(seat.cn + " " + action + " 超时，已自动处理");
    }

    @Override
    public void onRoundDraw() {
        log("流局");
    }

    @Override
    public void onEnd(RoundResult result) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("type", "end");
        m.put("result", result.toString());
        sender.send(m);
    }

    private void sendHand() {
        if (room == null) {
            return;
        }
        Player p = room.getPlayer(human);
        if (p == null) {
            return;
        }
        List<Map<String, Object>> melds = new ArrayList<Map<String, Object>>();
        for (Meld meld : p.melds) {
            melds.add(serializeMeld(meld));
        }
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("type", "hand");
        m.put("hand", new ArrayList<Integer>(p.hand));
        m.put("melds", melds);
        m.put("flowers", new ArrayList<Integer>(p.flowers));
        sender.send(m);
    }

    private void sendCounts() {
        if (room == null) {
            return;
        }
        Map<String, Integer> counts = new HashMap<String, Integer>();
        for (Seat s : Seat.values()) {
            Player p = room.getPlayer(s);
            counts.put(s.name(), p == null ? 0 : p.hand.size());
        }
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("type", "counts");
        m.put("counts", counts);
        sender.send(m);
    }

    private Map<String, Object> serializeMeld(Meld meld) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("type", meld.type.name());
        List<Integer> tiles = new ArrayList<Integer>();
        for (int t : meld.tiles) {
            tiles.add(t);
        }
        m.put("tiles", tiles);
        return m;
    }

    private void log(String text) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("type", "log");
        m.put("text", text);
        sender.send(m);
    }
}
