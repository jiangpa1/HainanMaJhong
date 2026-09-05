package hainanjong.listener;

import hainanjong.HuLib;
import hainanjong.HuResult;
import hainanjong.game.GameListener;
import hainanjong.game.Meld;
import hainanjong.game.PrintListener;
import hainanjong.game.RoundResult;
import hainanjong.game.Seat;
import hainanjong.service.MysqlService;
import hainanjong.service.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 牌局事件监听器：在原有控制台日志之外，把牌局状态写入 Redis、把结果写入 MySQL。
 *
 * <p>实现 {@link GameListener}，由 {@link hainanjong.game.RoomManager} 在牌局各事件时回调。
 * 所有持久化/缓存操作都做了容错，Redis / MySQL 短暂不可用不会中断牌局。</p>
 */
@Component
public class GamePersistenceListener implements GameListener {

    private static final Logger log = LoggerFactory.getLogger(GamePersistenceListener.class);

    private final RedisService redis;
    private final MysqlService mysql;
    private final PrintListener console = new PrintListener();

    private String roomId = "room";

    public GamePersistenceListener(RedisService redis, MysqlService mysql) {
        this.redis = redis;
        this.mysql = mysql;
    }

    /** 开始一局前设置房间号（写入 Redis/MySQL 时作为标识）。 */
    public void beginRoom(String roomId) {
        this.roomId = roomId;
    }

    @Override
    public void onShuffle(int deckSize) {
        console.onShuffle(deckSize);
    }

    @Override
    public void onDeal(Seat dealer) {
        console.onDeal(dealer);
        for (Seat s : Seat.values()) {
            safe(() -> redis.markOnline(s));
        }
        safe(() -> redis.putRoomState(roomId, "dealer", dealer.name()));
    }

    @Override
    public void onDraw(Seat seat, int tile) {
        console.onDraw(seat, tile);
        safe(() -> redis.putRoomState(roomId, "lastDraw", seat.name() + ":" + HuLib.cardName(tile)));
    }

    @Override
    public void onFlower(Seat seat, int tile) {
        console.onFlower(seat, tile);
    }

    @Override
    public void onDiscard(Seat seat, int tile) {
        console.onDiscard(seat, tile);
        safe(() -> redis.putRoomState(roomId, "lastDiscard", seat.name() + ":" + HuLib.cardName(tile)));
    }

    @Override
    public void onMeld(Seat seat, Meld meld) {
        console.onMeld(seat, meld);
    }

    @Override
    public void onHu(Seat seat, HuResult result, boolean selfDraw, int tile, Seat from) {
        console.onHu(seat, result, selfDraw, tile, from);
        safe(() -> redis.putRoomState(roomId, "winner", seat.name()));
    }

    @Override
    public void onTimeout(Seat seat, String action) {
        console.onTimeout(seat, action);
    }

    @Override
    public void onRoundDraw() {
        console.onRoundDraw();
    }

    @Override
    public void onEnd(RoundResult result) {
        console.onEnd(result);
        // 一局结束：MySQL 落库 + Redis 缓存最近战绩
        safe(() -> mysql.saveGameRecord(roomId, result));
        safe(() -> redis.cacheLatestResult(roomId, result.toString()));
        safe(() -> redis.putRoomState(roomId, "result", result.toString()));
    }

    /** 容错执行：持久化/缓存异常只记录日志，不影响牌局。 */
    private void safe(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("持久化/缓存操作失败（牌局继续）：{}", e.getMessage());
        }
    }
}
