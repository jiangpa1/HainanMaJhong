package hainanMahjong.common;

public final class CacheKeys {
    private CacheKeys() {}

    private static final String PREFIX = "hainanMahjong:";

    public static String tokenRefresh(Long id)  { return PREFIX + "token:refresh:" + id; }
    public static String tokenBlacklist(String hash)  { return PREFIX + "token:blacklist:" + hash; }

    /**
     * 某个账号【当前有效的那一个 accessToken】的哈希（单点登录用）。
     *
     * <p>每次登录/刷新都会覆盖它，于是同一账号在别的设备登录时，旧设备手里的
     * accessToken 立刻与之不符 —— 拦截器和 WebSocket 握手据此判定"该账号在别处登录"。</p>
     */
    public static String tokenSession(Long id)  { return PREFIX + "token:session:" + id; }

}
