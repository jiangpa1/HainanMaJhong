package hainanMahjong.service;

import hainanMahjong.service.impl.SchemaInitializer;
import hainanMahjong.room.EngineStart;
import hainanMahjong.room.Room;
import hainanMahjong.room.RoomRegistry;

/** Match lifecycle scheduled on serial room mailboxes backed by shared workers and deadlines. */
public interface GameEngineService {

    /** 开局：通知各座位 start，等待入座事件或宽限期到期。 */
    void startMatch(Room room);

    /** 等待房房主"人机补齐"：空缺座位补入电脑，仍停留在等待房。 */
    void fillBotsFor(long userId);

    /** 等待房房主在补位后"开始对局"。 */
    void startNowFor(long userId);

    /** 房主再来一轮（块与块之间）。 */
    void onRematch(Room room, long userId);

    /** 调度一轮的启动事件（再来一轮或快照续跑）。 */
    void startEngine(Room room, EngineStart seed);
}
