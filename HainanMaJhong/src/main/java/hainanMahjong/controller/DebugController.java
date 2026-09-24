package hainanMahjong.controller;

import hainanMahjong.common.Result;
import hainanMahjong.engine.RoomManager;
import hainanMahjong.engine.human.HumanPlayerController;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.room.Member;
import hainanMahjong.room.Room;
import hainanMahjong.room.RoomRegistry;
import hainanMahjong.room.RoomSupport;
import hainanMahjong.room.State;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读诊断端点：把房间与牌局的实时状态吐成 JSON。
 *
 * <p><b>这是排查"点了出牌没反应"用的，不参与任何游戏逻辑</b>：
 * 只读取字段，不加锁、不写库、不发消息，因此不会影响正在跑的对局。</p>
 *
 * <p>为什么需要它：前端日志只能看到"服务端没发帧"，线程转储只能看到
 * "引擎卡在 requestDiscard 等人应答"，但**看不出在等哪个座位、那个座位是谁**。
 * 这个端点把那部分直接暴露出来。</p>
 *
 * <h3>怎么用</h3>
 * <pre>
 *   # 卡住的时候访问（--noproxy '*' 是因为本机配了代理）
 *   curl --noproxy "*" http://localhost:8080/api/debug/rooms
 * </pre>
 *
 * 重点看每个房间的 {@code waitingSeat} / {@code awaitingHuman} 两个字段：
 * <ul>
 *   <li>{@code awaitingHuman=true} 且 {@code waitingSeat} 是你 → 引擎在等你，
 *       那问题在"request 没送到你浏览器"</li>
 *   <li>{@code awaitingHuman=false} 或等的是别的座位 → 引擎在等别人，
 *       你看不到帧是正常的（那家可能已断线但没被判定离线）</li>
 * </ul>
 *
 * <p>排查完请删掉本文件</b>。</p>
 *
 * <h3>它为什么只在 local 存在</h3>
 * <p>这个端点会把<b>房间码、房主 id、每个座位的昵称和 userId</b> 全吐出来，
 * 在公网服务器上等于把活跃玩家名单公开，而且它<b>不校验 token</b>
 * （{@code WebMvcConfig.AUTH_FREE} 里放行了 {@code /api/debug/**}，理由见那里）。</p>
 *
 * <p>所以这里挂 {@code @Profile("local")}：</p>
 * <ul>
 *   <li>本地开发（默认 profile 就是 local）照旧能用，排查"点了出牌没反应"不受影响；</li>
 *   <li>云端 {@code SPRING_PROFILES_ACTIVE=prod} 时这个 Bean 根本不注册，
 *       请求 {@code /api/debug/rooms} 直接 404，没有任何暴露面。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/debug")
@Profile("local")
public class DebugController {

    private final RoomRegistry registry;

    public DebugController(RoomRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/rooms")
    public Result<?> rooms() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Room room : registry.allRooms()) {
            out.add(describe(room));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("roomCount", out.size());
        root.put("rooms", out);
        return Result.success(root);
    }

    private Map<String, Object> describe(Room room) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", room.code);
        m.put("state", room.state == null ? null : room.state.name());
        m.put("hostUserId", room.hostUserId);
        m.put("stopped", room.stop);
        m.put("currentHand", room.currentHand);
        m.put("currentDealer", room.currentDealer == null ? null : room.currentDealer.name());
        m.put("blockNo", room.blockNo);
        m.put("sessionId", room.sessionId);

        Thread bt = room.blockThread;
        m.put("blockThreadAlive", bt != null && bt.isAlive());
        m.put("blockThreadState", bt == null ? null : bt.getState().name());

        // 引擎实例在打牌时才非 null；两把之间为 null
        RoomManager rm = room.active;
        m.put("engineActive", rm != null);
        if (rm != null) {
            m.put("deckLeft", rm.deckSize());
            m.put("winnerSeat", rm.getResult() == null || rm.getResult().winner == null
                    ? null : rm.getResult().winner.name());
        }

        List<Map<String, Object>> members = new ArrayList<>();
        for (Seat s : Seat.values()) {
            Member mem = room.members.get(s);
            if (mem == null) {
                continue;
            }
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("seat", s.name());
            mm.put("userId", mem.userId);
            mm.put("nickname", mem.nickname);
            mm.put("isBot", RoomSupport.isBot(mem));
            mm.put("offline", mem.offline);
            mm.put("bound", RoomSupport.bound(mem));
            mm.put("hasRoomWs", mem.roomWs != null);
            mm.put("hasGameWs", mem.gameWs != null);
            mm.put("gameWsOpen", mem.gameWs != null && mem.gameWs.isOpen());
            // controller 的当前委托是机器人还是真人：这决定了引擎会不会向这家发询问
            mm.put("delegate", delegateName(mem));
            // 这个座位此刻有没有一个"正在等回答"的请求
            mm.put("pendingRequest", pendingInfo(mem));
            members.add(mm);
        }
        m.put("members", members);
        return m;
    }

    /** 取出 SwitchingController 当前的委托类名（反射，避免为诊断改生产 API）。 */
    private String delegateName(Member mem) {
        try {
            java.lang.reflect.Field f = hainanMahjong.room.SwitchingController.class.getDeclaredField("delegate");
            f.setAccessible(true);
            Object d = f.get(mem.controller);
            return d == null ? null : d.getClass().getSimpleName();
        } catch (Exception e) {
            return "反射失败: " + e.getClass().getSimpleName();
        }
    }

    /**
     * 这个座位是否有"等待回答中"的请求。
     *
     * <p>{@code HumanPlayerController.pending} 是私有的，这里用反射读一下大小和 reqId。
     * 有 pending 且迟迟不消失，说明引擎发过询问但没人回答。</p>
     */
    private Object pendingInfo(Member mem) {
        try {
            HumanPlayerController h = mem.human;
            java.lang.reflect.Field pf = HumanPlayerController.class.getDeclaredField("pending");
            pf.setAccessible(true);
            Object pending = pf.get(h);
            if (!(pending instanceof Map<?, ?> map) || map.isEmpty()) {
                return null;
            }
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("reqId", e.getKey());
                Object v = e.getValue();
                if (v != null) {
                    // Pending 是 record，直接读它的 msg 字段（用 toString 兜底）
                    one.put("detail", String.valueOf(v));
                }
                list.add(one);
            }
            return list;
        } catch (Exception e) {
            return "读取失败: " + e.getClass().getSimpleName();
        }
    }
}
