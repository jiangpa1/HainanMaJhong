package hainanjong.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import hainanjong.game.BotController;
import hainanjong.game.GameConfig;
import hainanjong.game.PlayerController;
import hainanjong.game.RoomManager;
import hainanjong.game.RoundResult;
import hainanjong.game.Seat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个 WebSocket 连接开一场对局：人类玩家坐东家，其余三家为机器人。
 *
 * <p>一场对局共 {@link #MATCH_HANDS} 把，四家初始金币为 0：胡牌 +1，未胡 -1（流局则四家均 -1）。
 * 牌局跑在独立线程上，人类决策经 WebSocket 异步往返。</p>
 */
@Service
public class WebSocketGameService {

    private static final Logger log = LoggerFactory.getLogger(WebSocketGameService.class);
    private static final int MATCH_HANDS = 16;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Game> games = new ConcurrentHashMap<String, Game>();

    private static final class Game {
        final WebSocketSession session;
        final HumanPlayerController human;
        Game(WebSocketSession session, HumanPlayerController human) {
            this.session = session;
            this.human = human;
        }
    }

    public void startGame(final WebSocketSession session) {
        final Object lock = new Object();
        final HumanPlayerController human = new HumanPlayerController(
                msg -> send(session, lock, msg), 15000);
        final Game game = new Game(session, human);

        Map<Seat, PlayerController> controllers = new EnumMap<Seat, PlayerController>(Seat.class);
        controllers.put(Seat.EAST, human);
        int i = 1;
        for (Seat s : Seat.values()) {
            if (s != Seat.EAST) {
                controllers.put(s, new BotController(i++));
            }
        }

        final WebGameListener listener = new WebGameListener(msg -> send(session, lock, msg), Seat.EAST);
        games.put(session.getId(), game);

        Map<String, Object> welcome = new HashMap<String, Object>();
        welcome.put("type", "welcome");
        welcome.put("seat", "EAST");
        send(session, lock, welcome);

        Map<String, Object> match = new HashMap<String, Object>();
        match.put("type", "match");
        match.put("total", MATCH_HANDS);
        send(session, lock, match);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Map<Seat, Integer> coins = new EnumMap<Seat, Integer>(Seat.class);
                for (Seat s : Seat.values()) {
                    coins.put(s, 0);
                }
                Seat dealer = Seat.EAST;
                try {
                    for (int hand = 1; hand <= MATCH_HANDS; hand++) {
                        if (!session.isOpen()) {
                            break;
                        }
                        human.reset();

                        Map<String, Object> hs = new HashMap<String, Object>();
                        hs.put("type", "hand_start");
                        hs.put("hand", hand);
                        hs.put("total", MATCH_HANDS);
                        hs.put("dealer", dealer.name());
                        hs.put("coins", coinView(coins));
                        send(session, lock, hs);

                        GameConfig config = new GameConfig(true, dealer, 15000, 15000,
                                System.currentTimeMillis() + hand, true, 0);
                        RoomManager room = new RoomManager(config, controllers, listener);
                        listener.setRoom(room);
                        room.start();
                        RoundResult r = room.getResult();

                        // 结算金币：胡 +1，其余 -1；流局则四家 -1
                        if (r.isDraw) {
                            for (Seat s : Seat.values()) {
                                coins.put(s, coins.get(s) - 1);
                            }
                        } else {
                            coins.put(r.winner, coins.get(r.winner) + 1);
                            for (Seat s : Seat.values()) {
                                if (s != r.winner) {
                                    coins.put(s, coins.get(s) - 1);
                                }
                            }
                        }

                        Map<String, Object> cu = new HashMap<String, Object>();
                        cu.put("type", "coins");
                        cu.put("hand", hand);
                        cu.put("coins", coinView(coins));
                        send(session, lock, cu);

                        dealer = dealer.next();
                    }

                    Map<String, Object> end = new HashMap<String, Object>();
                    end.put("type", "match_end");
                    end.put("coins", coinView(coins));
                    send(session, lock, end);
                } catch (Throwable ex) {
                    log.warn("对局异常终止：{}", ex.getMessage());
                } finally {
                    games.remove(session.getId());
                }
            }
        }, "game-" + session.getId());
        t.setDaemon(true);
        t.start();
    }

    /** 把 Seat→Integer 的金币表转成按座位名索引的视图，方便 JSON 序列化。 */
    private static Map<String, Integer> coinView(Map<Seat, Integer> coins) {
        Map<String, Integer> view = new LinkedHashMap<String, Integer>();
        for (Seat s : Seat.values()) {
            view.put(s.name(), coins.get(s));
        }
        return view;
    }

    public void onMessage(WebSocketSession session, String payload) {
        Game g = games.get(session.getId());
        if (g == null) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = mapper.readValue(payload, Map.class);
            String type = (String) msg.get("type");
            int reqId = msg.get("reqId") == null ? -1 : ((Number) msg.get("reqId")).intValue();
            if ("discard".equals(type)) {
                g.human.discard(reqId, ((Number) msg.get("tile")).intValue());
            } else if ("act".equals(type)) {
                g.human.act(reqId, ((Number) msg.get("index")).intValue());
            } else if ("pass".equals(type)) {
                g.human.pass(reqId);
            }
        } catch (Exception e) {
            log.warn("无法解析客户端消息：{}", e.getMessage());
        }
    }

    public void onClose(WebSocketSession session) {
        games.remove(session.getId());
    }

    private void send(WebSocketSession session, Object lock, Map<String, Object> msg) {
        try {
            String json = mapper.writeValueAsString(msg);
            synchronized (lock) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            log.warn("发送消息失败：{}", e.getMessage());
        }
    }
}
