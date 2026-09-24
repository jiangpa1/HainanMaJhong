package hainanMahjong.room;

import hainanMahjong.engine.model.GameSnapshot;
import hainanMahjong.rules.HainanConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个房间的 Redis 快照（纯数据，供 Jackson 读写）。
 *
 * <p>服务重启/崩溃后，凭房间码从这里重建 {@link Room} 并续跑本块 —— 这是
 * "重启不丢局"的实现载体。</p>
 *
 * <p>⚠️ 字段名即存档格式：改字段名会让<b>旧快照无法恢复</b>（Jackson 读到 null）。
 * 要改请先确认线上 Redis 里的存量快照怎么处理。</p>
 */
public class RedisRoom {
    public long hostUserId;
    public String state;            // WAITING / PLAYING / BETWEEN
    public int blockNo;
    public long sessionId;
    public int currentHand;
    public String dealer;
    public Map<String, Integer> coins = new LinkedHashMap<String, Integer>();
    public List<MemberInfo> members = new ArrayList<MemberInfo>();
    public GameSnapshot snapshot;   // 可空
    public HainanConfig cfg;         // 可空
    public String firstDealer;       // 庄流（海南）：首局庄
    public int bottom;               // 当前庄底分
    public int windIdx;              // 令 0..3
    public int flowHandNo;           // 下把编号
    public int firstDealerBegins;    // 首局庄已开始坐庄次数
    public int handsPlayed;
}
