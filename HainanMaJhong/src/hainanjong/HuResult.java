package hainanjong;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 胡牌判定结果。
 * <ul>
 *   <li>{@link #isHu}：是否胡牌</li>
 *   <li>{@link #fanTypes}：命中的番型（可能多个，如“清一色 + 碰碰胡”）</li>
 *   <li>{@link #flowerCount}：手中花牌张数（只计番，不参与牌型）</li>
 * </ul>
 */
public class HuResult {
    public final boolean isHu;
    public final List<FanType> fanTypes;
    public final int flowerCount;

    private HuResult(boolean isHu, List<FanType> fanTypes, int flowerCount) {
        this.isHu = isHu;
        this.fanTypes = Collections.unmodifiableList(fanTypes);
        this.flowerCount = flowerCount;
    }

    static HuResult notHu(int flowerCount) {
        return new HuResult(false, Collections.<FanType>emptyList(), flowerCount);
    }

    static HuResult hu(int flowerCount, FanType... fans) {
        return new HuResult(true, Arrays.asList(fans), flowerCount);
    }

    static HuResult hu(int flowerCount, List<FanType> fans) {
        return new HuResult(true, new ArrayList<FanType>(fans), flowerCount);
    }

    /** 是否命中某个番型。 */
    public boolean hasFan(FanType fan) {
        return fanTypes.contains(fan);
    }

    @Override
    public String toString() {
        if (!isHu) {
            return "未胡" + (flowerCount > 0 ? "（花牌x" + flowerCount + "）" : "");
        }
        String s = "胡牌 " + fanTypes;
        if (flowerCount > 0) {
            s += "，花牌x" + flowerCount;
        }
        return s;
    }
}
