package hainanjong.runner;

import hainanjong.game.BotController;
import hainanjong.game.GameConfig;
import hainanjong.game.PlayerController;
import hainanjong.game.RoomManager;
import hainanjong.game.Seat;
import hainanjong.listener.GamePersistenceListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 演示启动器：应用启动后跑一局四机器人对局，结果经
 * {@link GamePersistenceListener} 写入 Redis 与 MySQL。
 *
 * <p>后续接入真实玩家/网络后，可删除本类或改为按需触发。</p>
 */
@Component
public class DemoGameRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoGameRunner.class);

    private final GamePersistenceListener listener;

    public DemoGameRunner(GamePersistenceListener listener) {
        this.listener = listener;
    }

    @Override
    public void run(String... args) {
        String roomId = "room-" + System.currentTimeMillis();
        listener.beginRoom(roomId);

        Map<Seat, PlayerController> bots = new EnumMap<Seat, PlayerController>(Seat.class);
        int i = 0;
        for (Seat s : Seat.values()) {
            bots.put(s, new BotController(i++));
        }

        // 含花牌 144 张，机器人即时响应（超时设为 0）
        GameConfig config = new GameConfig(true, Seat.EAST, 0, 0, 20260903L, true, 0);
        RoomManager room = new RoomManager(config, bots, listener);
        room.start();

        log.info("演示对局 {} 结束：{}", roomId, room.getResult());
    }
}
