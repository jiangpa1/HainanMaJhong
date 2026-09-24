package hainanMahjong.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import hainanMahjong.engine.bot.BotController;
import hainanMahjong.engine.human.HumanPlayerController;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.room.Member;
import hainanMahjong.room.Room;
import hainanMahjong.room.RoomRegistry;
import hainanMahjong.room.RoomSupport;
import hainanMahjong.room.State;
import hainanMahjong.service.impl.GameEngineServiceImpl;
import hainanMahjong.service.impl.GamePushServiceImpl;
import hainanMahjong.service.impl.RoundPersister;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回归测试：<b>出牌时不能因为 room 锁 / session 锁的加锁顺序而死锁</b>。
 *
 * <p>怀疑过的一个死锁（已用本测试证伪，保留下来防止以后真被引入）：</p>
 * <ul>
 *   <li><b>引擎线程</b>：{@code RoomManager} 里长期持有 {@code room} 锁，
 *       发消息时又要 {@code session} 锁（{@code GamePushServiceImpl.sendJson} 里
 *       {@code synchronized (session)}）</li>
 *   <li><b>WebSocket 线程</b>：{@code WebSocketSession.handleMessage} 是 synchronized 的，
 *       所以它在处理帧时持有 {@code session} 锁，随后进 {@code onGameMessage} 又要 {@code room} 锁</li>
 * </ul>
 *
 * <p>顺序相反本会死锁，表现应该是"点出牌后彻底没反应、后端一行日志都没有"。
 * 但实测引擎能继续推进，说明当前实现的加锁实际是安全的
 * （关键点：{@code HumanPlayerController} 的应答只做一次 {@code CountDownLatch} 放行，
 * 不回调发送；而引擎醒来后第一件事是继续跑牌局，不是立即抢 session 锁发帧）。</p>
 *
 * <p>{@code HumanDiscardPromptTest} 用的是<b>直接</b>调 {@code humanCtl.discard(...)}，
 * 等于去掉了 WebSocket 线程最外层的 session 锁。本测试故意在
 * {@code synchronized (session)} 里发出牌，把那一层补回来，覆盖更真实的调用栈。</p>
 */
class DiscardDeadlockTest {

    /** 抓帧假 session；同时提供一个真实存在的监视器用于复刻加锁。 */
    private static final class FakeSession {
        final ConcurrentLinkedQueue<String> received = new ConcurrentLinkedQueue<>();
        final WebSocketSession session;

        FakeSession(String id) throws java.io.IOException {
            WebSocketSession s = mock(WebSocketSession.class);
            when(s.getId()).thenReturn(id);
            when(s.isOpen()).thenReturn(true);
            doAnswer(inv -> {
                WebSocketMessage<?> msg = inv.getArgument(0);
                received.add(String.valueOf(msg.getPayload()));
                return null;
            }).when(s).sendMessage(any(WebSocketMessage.class));
            this.session = s;
        }
    }

    @Test
    @DisplayName("在持有 session 锁的情况下发出牌：引擎不应死锁（必须能继续推进）")
    void discardWhileHoldingSessionLockMustNotDeadlock() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RoomRegistry registry = new RoomRegistry();

        Room room = new Room("LOCK02", 1L);
        room.state = State.PLAYING;

        FakeSession roomWs = new FakeSession("room-x");
        FakeSession gameWs = new FakeSession("game-x");
        Member human = new Member(Seat.EAST, 1L, "真人", roomWs.session);
        human.gameWs = gameWs.session;
        room.members.put(Seat.EAST, human);

        for (Seat s : Seat.values()) {
            if (s == Seat.EAST) {
                continue;
            }
            Member bot = new Member(s, RoomSupport.botUserId(s), "电脑", null);
            bot.controller.setDelegate(new BotController(20260923L + s.ordinal()));
            room.members.put(s, bot);
        }

        registry.register(room);
        registry.bindUser(1L, room.code);
        registry.bindGameSession(gameWs.session, 1L, room.code, Seat.EAST);

        RoomService rooms = mock(RoomService.class);
        RoundPersister persister = mock(RoundPersister.class);
        when(persister.createSession(anyString(), anyLong(), anyList())).thenReturn(-1L);

        GamePushService push = new GamePushServiceImpl();
        RoomSnapshotService snapshots = mock(RoomSnapshotService.class);
        GameEngineServiceImpl engine = new GameEngineServiceImpl(persister, push, snapshots, registry, rooms);

        HumanPlayerController humanCtl = (HumanPlayerController) delegateOf(human);
        humanCtl.attach(msg -> push.sendJson(gameWs.session, msg));

        engine.startMatch(room);

        // 等引擎推进到"向真人要出牌"。
        // 注意：必须认 {type=request, kind=discard}，不能只认 type=request ——
        // 抓到的可能是 kind=action/draw 的询问，那两个没有 hand 字段，
        // 直接 hand.get(0) 会 NPE（这里踩过）。
        Map<String, Object> req = null;
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && req == null) {
            Thread.sleep(50);
            for (Map<String, Object> f : parseAll(mapper, gameWs.received)) {
                if ("request".equals(f.get("type"))
                        && "discard".equals(f.get("kind"))
                        && f.get("hand") instanceof List) {
                    req = f;
                    break;
                }
            }
        }
        assertNotNull(req, "20 秒内没等到 kind=discard 的 request");

        final int reqId = ((Number) req.get("reqId")).intValue();
        @SuppressWarnings("unchecked")
        final List<Integer> hand = (List<Integer>) req.get("hand");
        final int tile = hand.get(0);
        final int before = gameWs.received.size();

        /*
         * 关键：复刻 Tomcat 那条线程的调用栈。
         * WebSocketSession.handleMessage 是 synchronized 的，所以 WebSocket 线程
         * 在处理任何一帧时都持有 session 监视器；它随后进 onGameMessage 又要 room 锁。
         * 这里就在持有 session 锁的情况下发出牌。
         */
        final CountDownLatch sent = new CountDownLatch(1);
        final AtomicBoolean engineAdvanced = new AtomicBoolean(false);
        Thread tomcat = new Thread(() -> {
            synchronized (gameWs.session) {           // ← 真实存在的加锁
                humanCtl.discard(reqId, tile);        // 等价于 onGameMessage 里那一步
                sent.countDown();
                // 故意继续持有 session 锁一小会儿，模拟"帧处理还没结束"
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "fake-tomcat");
        tomcat.setDaemon(true);
        tomcat.start();

        assertTrue(sent.await(3, TimeUnit.SECONDS), "发出牌的动作没完成");

        /*
         * 观察引擎：它应该醒来并把 discard 广播出来。
         * 若死锁，引擎会卡在 sendJson 的 synchronized(session) 上，这里就永远等不到新帧。
         */
        long d2 = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < d2) {
            Thread.sleep(50);
            if (gameWs.received.size() > before) {
                engineAdvanced.set(true);
                break;
            }
        }

        List<String> types = new ArrayList<>();
        for (Map<String, Object> f : parseAll(mapper, gameWs.received)) {
            types.add(String.valueOf(f.get("type")));
        }
        room.stop = true;

        StringBuilder report = new StringBuilder();
        report.append("收到 request: ").append(req).append('\n');
        report.append("模拟出牌(持有 session 锁): reqId=").append(reqId).append(" tile=").append(tile).append('\n');
        report.append("出牌前帧数=").append(before)
                .append("，出牌后帧数=").append(gameWs.received.size())
                .append("，引擎是否继续=").append(engineAdvanced.get()).append('\n');
        report.append("帧类型序列=").append(types).append('\n');
        try {
            java.nio.file.Files.write(
                    java.nio.file.Paths.get("target", "discard-deadlock.txt"),
                    report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // 忽略
        }

        assertTrue(engineAdvanced.get(),
                "在持有 session 锁时发出牌，引擎再没有任何帧出来 —— 加锁顺序相反导致死锁："
                        + "引擎持 room 锁等 session 锁，WebSocket 线程持 session 锁等 room 锁。"
                        + " 帧序列: " + types);
    }

    private static Object delegateOf(Member m) throws Exception {
        java.lang.reflect.Field f = hainanMahjong.room.SwitchingController.class.getDeclaredField("delegate");
        f.setAccessible(true);
        return f.get(m.controller);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseAll(ObjectMapper mapper, ConcurrentLinkedQueue<String> raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String s : raw) {
            try {
                out.add(mapper.readValue(s, Map.class));
            } catch (Exception ignored) {
                // 忽略解析失败的帧
            }
        }
        return out;
    }
}
