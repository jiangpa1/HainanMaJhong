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
import hainanMahjong.room.SwitchingController;
import hainanMahjong.service.impl.GameEngineServiceImpl;
import hainanMahjong.service.impl.GamePushServiceImpl;
import hainanMahjong.service.impl.RoundPersister;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 复现"显示轮到你出牌，但前端收不到出牌询问"。
 *
 * <p>现象：前端收到 {@code turn}（"轮到你"）却收不到 {@code request}，
 * 于是手牌一直点不动。这个测试走<b>真实服务链路</b>：
 * 真 {@link Room} / {@link Member} / {@link RoomRegistry} / {@link GameEngineServiceImpl}，
 * 只把持久层与 WebSocket 换成桩，然后把发给真人的每一帧都抓下来看。</p>
 *
 * <p>为什么必须单独测这条链路：四个机器人的冒烟测试（{@code RoomManagerSmokeTest}）
 * 走的是 {@link BotController} 即时应答，<b>压根不需要向任何人发消息</b>，
 * 所以"人类座位收不到询问"这类问题它永远发现不了。</p>
 */
class HumanDiscardPromptTest {

    /**
     * 抓帧用的假 WebSocket。
     *
     * <p>用 Mockito 而不是手写实现：{@code WebSocketSession} 接口方法不少
     * （close / getExtensions / getAttributes / getPrincipal …），
     * 手写会一个个漏、编译一次报一个。这里只桩掉真正用到的两个：
     * {@code isOpen()} 恒真、{@code sendMessage} 把 payload 收进队列。</p>
     */
    private static final class FakeSession {
        final ConcurrentLinkedQueue<String> received = new ConcurrentLinkedQueue<>();
        final WebSocketSession session;

        FakeSession(String id) throws java.io.IOException {
            WebSocketSession s = mock(WebSocketSession.class);
            when(s.getId()).thenReturn(id);
            when(s.isOpen()).thenReturn(true);
            // sendMessage 是 void，用 doAnswer 捕获参数；这里用 when(...).thenAnswer 亦可
            org.mockito.Mockito.doAnswer(inv -> {
                WebSocketMessage<?> msg = inv.getArgument(0);
                received.add(String.valueOf(msg.getPayload()));
                return null;
            }).when(s).sendMessage(org.mockito.ArgumentMatchers.any(WebSocketMessage.class));
            this.session = s;
        }
    }

    /**
     * 取出 {@link SwitchingController} 当前的委托。
     *
     * <p>用反射而不是给它加个 getter：{@code @Setter} 是 Lombok 生成的，
     * 而且这个委托是内部实现细节，为了一个测试去改生产类的公开 API 不值得。</p>
     */
    private static Object delegateOf(SwitchingController c) throws Exception {
        java.lang.reflect.Field f = SwitchingController.class.getDeclaredField("delegate");
        f.setAccessible(true);
        return f.get(c);
    }

    @Test
    @DisplayName("人机对局：轮到我出牌时，必须收到 kind=discard 的 request 帧")
    void humanReceivesDiscardRequest() throws Exception {
        runScenario(Seat.EAST, "BOT001");
    }

    /**
     * 用户真实场景：坐在 <b>SOUTH</b> 家（庄家），牌河/座次与 EAST 完全不同。
     *
     * <p>为什么单独跑一遍：EAST 是"第一位出牌者"，很多边界（谁是首庄、
     * 前面有几家还没打过牌）在 EAST 上根本不会出现。用户日志里
     * welcome 给的座位就是 SOUTH，所以必须按 SOUTH 复现。</p>
     */
    @Test
    @DisplayName("坐在 SOUTH 家（庄家）时出牌也要能被处理")
    void humanAtSouthCanDiscard() throws Exception {
        runScenario(Seat.SOUTH, "BOT002");
    }

    private void runScenario(Seat mySeat, String code) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RoomRegistry registry = new RoomRegistry();

        Room room = new Room(code, 42L);
        room.state = State.PLAYING;

        // 真人坐 mySeat（/room 与 /game 两条连接都在）
        FakeSession roomWs = new FakeSession("room-1");
        FakeSession gameWs = new FakeSession("game-1");
        Member human = new Member(mySeat, 42L, "真人", roomWs.session);
        human.gameWs = gameWs.session;
        room.members.put(mySeat, human);

        // 其余三家是补位电脑（userId 为负 → RoomSupport.isBot 判定为机器人）
        for (Seat s : Seat.values()) {
            if (s == mySeat) {
                continue;
            }
            Member bot = new Member(s, RoomSupport.botUserId(s), "电脑", null);
            bot.controller.setDelegate(new BotController(20260923L + s.ordinal()));
            room.members.put(s, bot);
        }

        registry.register(room);
        registry.bindUser(42L, room.code);
        registry.bindGameSession(gameWs.session, 42L, room.code, mySeat);

        // ---- 依赖：能真就真，碰外部 I/O 的才 mock ----
        RoomService rooms = mock(RoomService.class);
        RoundPersister persister = mock(RoundPersister.class);
        when(persister.createSession(anyString(), anyLong(), anyList())).thenReturn(-1L);

        GamePushService push = new GamePushServiceImpl();
        RoomSnapshotService snapshots = mock(RoomSnapshotService.class);
        GameEngineServiceImpl engine = new GameEngineServiceImpl(persister, push, snapshots, registry, rooms);

        // 真人座位的控制器委托必须是 HumanPlayerController（否则永远不会向人发询问）
        Object delegate = delegateOf((SwitchingController) human.controller);
        assertTrue(delegate instanceof HumanPlayerController,
                "真人座位的控制器委托应该是 HumanPlayerController，实际是 "
                        + (delegate == null ? "null" : delegate.getClass().getName()));
        HumanPlayerController humanCtl = (HumanPlayerController) delegate;

        // 真人第一次连入牌桌时服务端做的那一步：把消息出口接到当前 session
        humanCtl.attach(msg -> push.sendJson(gameWs.session, msg));

        // ---- 开跑：真实 startMatch → runBlock → runEngine ----
        engine.startMatch(room);

        /*
         * 等引擎推进到"向真人要出牌"。
         *
         * 超时给 20 秒而不是几秒：首庄是【随机】的（GameEngineServiceImpl.newFlow
         * 用 random.nextInt(4)），如果庄家不坐在我这家，我得等前面几家各走一手
         * 才轮到自己。只等几秒会偶发失败（踩过一次）。
         */
        Map<String, Object> req = null;
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            for (Map<String, Object> f : parseAll(mapper, gameWs.received)) {
                // 只认 kind=discard：kind=action/draw 的询问没有 hand 字段，取 hand.get(0) 会 NPE
                if ("request".equals(f.get("type"))
                        && "discard".equals(f.get("kind"))
                        && f.get("hand") instanceof List) {
                    req = f;
                    break;
                }
            }
            if (req != null) {
                break;
            }
        }
        assertNotNull(req, "20 秒内没等到 kind=discard 的 request");

        /*
         * 关键一步：模拟玩家点击出牌。
         *
         * 服务端收到 discard 的路径是：
         *   onGameMessage → m.human.discard(reqId, tile)
         *   → Responder.discard(tile) → PromptSupport.reply.accept(tile)
         *   → 引擎线程从 CountDownLatch 醒来，继续跑这一手。
         * 这一步若卡住，前端就是「发出去了但什么都没有、牌也没打出去」。
         */
        int reqId = ((Number) req.get("reqId")).intValue();
        @SuppressWarnings("unchecked")
        List<Integer> hand = (List<Integer>) req.get("hand");
        int tile = hand.get(0);
        final int beforeCount = gameWs.received.size();

        humanCtl.discard(reqId, tile);

        boolean advanced = false;
        long d2 = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < d2) {
            Thread.sleep(100);
            if (gameWs.received.size() > beforeCount) {
                advanced = true;
                break;
            }
        }

        List<Map<String, Object>> all = parseAll(mapper, gameWs.received);
        List<String> allTypes = new ArrayList<>();
        for (Map<String, Object> f : all) {
            allTypes.add(String.valueOf(f.get("type")));
        }
        room.stop = true;

        /*
         * 把结果写到文件 —— surefire 默认不保留 System.out，
         * 只 println 的话什么都看不到（这个坑踩过一次）。
         */
        StringBuilder report = new StringBuilder();
        report.append("座位=").append(mySeat).append('\n');
        report.append("真人收到帧数=").append(all.size()).append('\n');
        report.append("帧类型序列=").append(allTypes).append('\n');
        report.append("收到 request: ").append(req).append('\n');
        report.append("模拟出牌: reqId=").append(reqId).append(" tile=").append(tile).append('\n');
        report.append("出牌前帧数=").append(beforeCount)
                .append("，出牌后帧数=").append(all.size())
                .append("，引擎是否继续=").append(advanced).append('\n');
        try {
            java.nio.file.Files.write(
                    java.nio.file.Paths.get("target", "human-prompt-frames-" + mySeat + ".txt"),
                    report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // 写文件失败不影响测试结论
        }

        assertTrue(advanced,
                "玩家出牌之后引擎再没有发出任何帧 —— 这就是「点了牌、牌没打出去、页面卡住」。"
                        + " 出牌前的帧序列: " + allTypes);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseAll(ObjectMapper mapper, ConcurrentLinkedQueue<String> raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String s : raw) {
            try {
                out.add(mapper.readValue(s, Map.class));
            } catch (Exception e) {
                Map<String, Object> bad = new LinkedHashMap<>();
                bad.put("type", "<解析失败>");
                bad.put("raw", s);
                out.add(bad);
            }
        }
        return Collections.unmodifiableList(out);
    }
}
