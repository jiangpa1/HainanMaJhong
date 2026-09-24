package hainanMahjong.room;

/**
 * Redis 快照里的一个成员（纯数据，供 Jackson 读写）。
 *
 * <p>字段是 public 且没有构造器 —— Jackson 直接按字段名序列化；改动字段名等于改 Redis 里的
 * 存档格式，会让旧快照无法恢复，改名前请先确认兼容性。</p>
 */
public class MemberInfo {
    public String seat;
    public long userId;
    public String nickname;
}
