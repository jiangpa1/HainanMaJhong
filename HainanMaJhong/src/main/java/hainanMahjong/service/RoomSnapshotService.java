package hainanMahjong.service;

import hainanMahjong.engine.model.GameSnapshot;
import hainanMahjong.room.Room;

/**
 * 房间状态的 Redis 快照存取。
 *
 * <p>职责边界：本接口只负责"把 {@link Room} 组装成快照并落 Redis"。
 * <b>不碰房间注册表</b>（{@code roomsByCode} / {@code codeByUser} 由房间模块维护），
 * 也<b>不修改传入的 Room</b>。</p>
 *
 * <p>⚠️ <b>快照字段名即线上存档格式</b>：Redis 里可能存着旧版本写入的快照，改字段名会导致
 * 重启后无法恢复。改动 {@code RedisRoom} / {@code MemberInfo} 的字段前，先确认存量快照的兼容性。</p>
 */
public interface RoomSnapshotService {

    /**
     * 把房间当前状态写入 Redis（覆盖写，30 分钟过期）。
     *
     * <p>失败只记 warn，不抛异常 —— 快照是"尽力而为"的容灾手段，不能因为它失败而打断牌局。</p>
     *
     * @param room 要落盘的房间
     * @param snap 当前这一把的引擎快照；可为 null（表示只存房间级状态）
     */
    void save(Room room, GameSnapshot snap);
}
