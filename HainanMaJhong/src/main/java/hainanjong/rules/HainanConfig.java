package hainanjong.rules;

import hainanjong.FanType;

/**
 * 海南规则可设置项（创建房间弹窗编辑、随房间存档/广播）。
 *
 * <p>全部为 public 字段便于 Jackson 序列化。默认值取规则原文；番型倍数、
 * 底分、花分等均可由用户在创建房间弹窗里修改。</p>
 */
public class HainanConfig {

    /** 是否启用海南规则（快速开始/旧对局可关）。 */
    public boolean enabled = true;

    /** 底分（默认 1，庄连庄时累加的是它，非庄胡下庄后重置回本值）。 */
    public int basePoint = 1;

    /** 花杠分（每局结算的额外分）：真花（春夏秋冬一套 或 梅兰竹菊一套）。 */
    public int flowerTrue = 1;

    /** 假花分（数字 1~4 凑齐；有真花则假花归零）。 */
    public int flowerFake = 0;

    /** 真对花花分（摸齐本人两个位置花）。 */
    public int pairTrueFlower = 0;

    /** 假对花花分（摸到同号一对但非本人位置）。 */
    public int pairFakeFlower = 0;

    // ============ 番型倍数（命中多个时相加；平胡为无其它番型时的兜底） ============

    public int multPing = 1;      // 平胡
    public int multPeng = 3;      // 碰碰胡
    public int multHun = 2;       // 混一色
    public int multQing = 4;      // 清一色
    public int multQiDui = 3;     // 七对
    public int multLongQi = 5;    // 龙七对（覆盖七对，不另加）
    public int multShiSanYao = 13;// 十三幺
    public int multTianHu = 5;    // 天胡（后续阶段用）
    public int multTianTing = 4;  // 天听（后续阶段用）
    public int multDiTing = 2;    // 地听（后续阶段用）

    /** 某番型的倍数；未知番型返回 1。 */
    public int multiplier(FanType fan) {
        switch (fan) {
            case PING_HU:      return multPing;
            case PENG_PENG_HU: return multPeng;
            case HUN_YI_SE:    return multHun;
            case QING_YI_SE:   return multQing;
            case QI_DUI:       return multQiDui;
            case LONG_QI_DUI:  return multLongQi;
            case SHI_SAN_YAO:  return multShiSanYao;
            case TIAN_HU:      return multTianHu;
            case TIAN_TING:    return multTianTing;
            case DI_TING:      return multDiTing;
            default:           return 1;
        }
    }

    public static HainanConfig defaultConfig() {
        return new HainanConfig();
    }
}
