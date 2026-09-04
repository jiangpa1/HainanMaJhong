package hainanjong.game;

/**
 * 玩家对提示的应答回调。
 * 每种提示场景下，只有其中一个方法是有效的：
 * <ul>
 *   <li>出牌提示 → {@link #discard(int)}</li>
 *   <li>吃碰杠胡/自摸杠提示 → {@link #act(Action)} 或 {@link #pass()}</li>
 * </ul>
 * 超时未应答时，房间管理器会自动执行强制动作（强制出牌 / 视为过）。
 */
public interface Responder {
    /** 打出一张牌（牌下标，必须在自己手牌中）。 */
    void discard(int tileKind);

    /** 执行某个动作（吃/碰/杠/胡）。 */
    void act(Action action);

    /** 放弃/过。 */
    void pass();
}
