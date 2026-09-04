package hainanjong.game;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 房间管理器演示：
 * <ol>
 *   <li>打印动作优先级表；</li>
 *   <li>跑一整局（含花牌 144 张），输出完整事件日志到控制台与 game_log.txt；</li>
 *   <li>快速跑 N 局统计胡牌/流局，验证引擎稳定；</li>
 *   <li>演示 15 秒出牌超时（用短超时模拟）。</li>
 * </ol>
 */
public class GameDemo {

    public static void main(String[] args) throws Exception {
        printPriorityTable();

        System.out.println("\n===== 完整一局（含花牌，144 张）=====");
        runOneFullGame();

        System.out.println("\n===== 统计 300 局 =====");
        runStatistics(300);

        System.out.println("\n===== 出牌超时演示（300ms 模拟 15s）=====");
        runTimeoutDemo();
    }

    static void printPriorityTable() {
        System.out.println("动作优先级（数值越小越先询问）：");
        System.out.println("  胡=" + Action.hu(0, Seat.EAST).priority()
                + "  杠=" + Action.gang(0, Seat.EAST).priority()
                + "  碰=" + Action.peng(0, Seat.EAST).priority()
                + "  吃=" + Action.chi(new int[]{0, 1, 2}, 0, Seat.EAST).priority()
                + "  过=" + new Action(Action.Type.PASS, -1, null, null).priority());
        System.out.println("结论：能胡的玩家优先于能碰的玩家被询问。");
    }

    static Map<Seat, PlayerController> fourBots(long base) {
        Map<Seat, PlayerController> m = new EnumMap<Seat, PlayerController>(Seat.class);
        int i = 0;
        for (Seat s : Seat.values()) {
            m.put(s, new BotController(base + i));
            i++;
        }
        return m;
    }

    static void runOneFullGame() throws Exception {
        PrintStream file = new PrintStream(new FileOutputStream("game_log.txt"), true, "UTF-8");
        GameConfig cfg = new GameConfig(true, Seat.EAST, 15000, 15000, 20260903L, true, 0);
        RoomManager room = new RoomManager(cfg, fourBots(100), new PrintListener(file));
        room.start();
        file.close();
        System.out.println("（完整日志已写入 game_log.txt）");
    }

    static void runStatistics(int games) {
        int hu = 0, selfDraw = 0, draw = 0;
        for (int g = 0; g < games; g++) {
            GameConfig cfg = new GameConfig(true, Seat.values()[g % 4], 0, 0, -1, true, 0);
            RoomManager room = new RoomManager(cfg, fourBots(g * 7), null);
            room.start();
            RoundResult r = room.getResult();
            if (r.isDraw) draw++;
            else {
                hu++;
                if (r.selfDraw) selfDraw++;
            }
        }
        System.out.printf("共 %d 局：胡牌 %d 局（其中自摸 %d 局），流局 %d 局%n", games, hu, selfDraw, draw);
    }

    static void runTimeoutDemo() {
        // 庄家用「慢机器人」：第一次出牌不应答，触发强制出牌
        Map<Seat, PlayerController> m = new EnumMap<Seat, PlayerController>(Seat.class);
        m.put(Seat.EAST, new SlowController(new BotController(1)));
        m.put(Seat.SOUTH, new BotController(2));
        m.put(Seat.WEST, new BotController(3));
        m.put(Seat.NORTH, new BotController(4));

        GameConfig cfg = new GameConfig(false, Seat.EAST, 300, 300, 7L, true, 10);
        RoomManager room = new RoomManager(cfg, m, new PrintListener());
        room.start();
    }

    /** 第一次出牌故意不应答，模拟玩家超时。 */
    static class SlowController implements PlayerController {
        private final PlayerController inner;
        private boolean slowOnce = true;

        SlowController(PlayerController inner) {
            this.inner = inner;
        }

        @Override
        public void onDiscardTurn(Seat seat, List<Integer> hand, Responder r) {
            if (slowOnce) {
                slowOnce = false;
                return; // 不应答 → 超时强制出牌
            }
            inner.onDiscardTurn(seat, hand, r);
        }

        @Override
        public void onActionChance(Seat seat, int tile, List<Action> options, Responder r) {
            inner.onActionChance(seat, tile, options, r);
        }

        @Override
        public void onDrawChance(Seat seat, int drawnTile, List<Action> options, Responder r) {
            inner.onDrawChance(seat, drawnTile, options, r);
        }
    }
}
