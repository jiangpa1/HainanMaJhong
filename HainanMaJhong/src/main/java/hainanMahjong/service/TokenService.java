package hainanMahjong.service;

import hainanMahjong.vo.TokenPair;

public interface TokenService {
    TokenPair issue(Long userId, String username, Integer role);

    TokenPair refresh(String refreshToken);

    void logout(String authorization);

    boolean isRevoked(String accessToken);

    /**
     * 该 accessToken 是否仍是这个账号【当前】的那一个（单点登录）。
     *
     * <p>同一账号在别的设备登录后，旧设备的 accessToken 会立刻不再是"当前会话"，
     * 不必等它 30 分钟自然过期。Redis 查不到记录时放行（fail-open）。</p>
     */
    boolean isSessionCurrent(Long userId, String accessToken);
}

