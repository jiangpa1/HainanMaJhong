package hainanjong;

/**
 * 番型枚举。本库支持的番型：
 * 清一色、混一色、碰碰胡、七对、十三幺。
 */
public enum FanType {
    QING_YI_SE("清一色"),
    HUN_YI_SE("混一色"),
    PENG_PENG_HU("碰碰胡"),
    QI_DUI("七对"),
    SHI_SAN_YAO("十三幺");

    public final String display;

    FanType(String display) {
        this.display = display;
    }

    @Override
    public String toString() {
        return display;
    }
}
