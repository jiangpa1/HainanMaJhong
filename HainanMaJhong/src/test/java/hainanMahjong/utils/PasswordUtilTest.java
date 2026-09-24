package hainanMahjong.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码兼容逻辑的单测（不依赖 Spring 容器，也不依赖数据库）。
 *
 * <p>覆盖三种情况：旧的 SHA-256 密文仍能登录、新注册的 BCrypt 能登录、以及"是否需要升级"的判别。</p>
 */
class PasswordUtilTest {

    private final PasswordUtil passwordUtil = new PasswordUtil();

    /** "123456" 的无盐 SHA-256（迁移前的旧格式），长度恰好 64。 */
    private static final String LEGACY_HASH_123456 =
            "8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92";

    // ==================== 旧格式：兼容性 ====================

    @Test
    @DisplayName("旧的无盐 SHA-256 密文仍能校验通过（老用户能登录）")
    void legacyHashStillWorks() {
        assertTrue(passwordUtil.matches("123456", LEGACY_HASH_123456));
    }

    @Test
    @DisplayName("旧密文配错密码必须失败")
    void legacyHashRejectsWrongPassword() {
        assertFalse(passwordUtil.matches("123457", LEGACY_HASH_123456));
        assertFalse(passwordUtil.matches("", LEGACY_HASH_123456));
    }

    @Test
    @DisplayName("isLegacy 只认 64 位小写十六进制")
    void isLegacyDetectsOnlySha256() {
        assertTrue(passwordUtil.isLegacy(LEGACY_HASH_123456));
        // 大写十六进制不是本项目的旧格式（旧代码用 %02x 输出小写）
        assertFalse(passwordUtil.isLegacy(LEGACY_HASH_123456.toUpperCase()));
        // 长度不对
        assertFalse(passwordUtil.isLegacy(LEGACY_HASH_123456.substring(0, 63)));
        // 掺了非十六进制字符
        assertFalse(passwordUtil.isLegacy("z" + LEGACY_HASH_123456.substring(1)));
    }

    // ==================== 新格式：BCrypt ====================

    @Test
    @DisplayName("BCrypt 密文能校验通过")
    void bcryptHashWorks() {
        String encoded = passwordUtil.encode("123456");
        assertTrue(passwordUtil.matches("123456", encoded));
        assertFalse(passwordUtil.matches("123457", encoded));
    }

    @Test
    @DisplayName("同一个密码两次加密结果不同（随机盐生效）")
    void bcryptUsesRandomSalt() {
        String first = passwordUtil.encode("123456");
        String second = passwordUtil.encode("123456");
        assertNotEquals(first, second, "两次加密结果相同说明没有随机盐");
        // 但两个都能校验通过
        assertTrue(passwordUtil.matches("123456", first));
        assertTrue(passwordUtil.matches("123456", second));
    }

    @Test
    @DisplayName("BCrypt 密文长度 60、以 $2a$ 开头，能塞进 VARCHAR(100)")
    void bcryptShape() {
        String encoded = passwordUtil.encode("123456");
        assertTrue(encoded.startsWith("$2a$"), "实际前缀：" + encoded.substring(0, 4));
        assertTrue(encoded.length() == 60, "实际长度：" + encoded.length());
    }

    @Test
    @DisplayName("BCrypt 密文不会被误判成旧格式（否则升级判断会出错）")
    void bcryptIsNotLegacy() {
        assertFalse(passwordUtil.isLegacy(passwordUtil.encode("123456")));
    }

    // ==================== 边界：不能抛 NPE ====================

    @Test
    @DisplayName("null / 空串一律返回 false，不抛异常")
    void nullSafety() {
        assertFalse(passwordUtil.matches(null, LEGACY_HASH_123456));
        assertFalse(passwordUtil.matches(null, passwordUtil.encode("123456")));
        assertFalse(passwordUtil.matches("123456", null));
        assertFalse(passwordUtil.matches("123456", ""));
        assertFalse(passwordUtil.isLegacy(null));
    }

    @Test
    @DisplayName("库里是脏数据（既非 hex 也非 BCrypt）不抛异常，只返回 false")
    void garbageStoredPassword() {
        assertFalse(passwordUtil.matches("123456", "这不是一个合法的密文"));
        assertFalse(passwordUtil.matches("123456", "abc"));
    }
}
