package hainanMahjong.utils;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 密码加密与校验。
 *
 * <p>历史原因库里存在两套密文，本类负责同时兼容：</p>
 * <ul>
 *   <li><b>旧格式</b>：无盐 SHA-256，恰好 64 位小写十六进制（没有 {@code $}）</li>
 *   <li><b>新格式</b>：BCrypt，形如 {@code $2a$10$...}（60 字符，盐和强度都编码在密文里）</li>
 * </ul>
 *
 * <p>靠<b>结构差异</b>判别格式（64 位十六进制 vs {@code $2a$} 前缀），
 * 所以不需要额外的列，也不需要版本号前缀。</p>
 *
 * <p>{@link #matches} 在旧密码校验通过时同样返回 true，调用方可据此把密文
 * <b>透明升级</b>为 BCrypt（见 {@code UserServiceImpl.authenticate}）。</p>
 */
@Component
public class PasswordUtil {

    /** BCrypt 强度：默认 10（约 50~100ms 一次，登录场景够用；调大更安全但更慢）。 */
    private static final int STRENGTH = 10;

    /**
     * 旧格式的判别正则：恰好 64 位小写十六进制。
     * <p>比"看开头是不是 $2"更严格 —— 这是旧格式的充要特征，不会误判。</p>
     */
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(STRENGTH);

    /** SHA-256 是纯函数工具，无状态无依赖，直接 new 即可，不必走 Spring 注入。 */
    private final Sha256Util sha256Util = new Sha256Util();

    /** 加密：新密码一律用 BCrypt（自带随机盐，同一密码每次结果都不同）。 */
    public String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * 校验：自动识别密文格式。
     *
     * @param rawPassword    用户输入的明文
     * @param storedPassword 库里存的密文（旧 SHA-256 或新 BCrypt）
     * @return 匹配返回 true；任一参数为空一律返回 false，不抛异常
     */
    public boolean matches(String rawPassword, String storedPassword) {
        if (rawPassword == null || storedPassword == null || storedPassword.isEmpty()) {
            return false;
        }
        if (isLegacy(storedPassword)) {
            // 旧格式：无盐 SHA-256，sha256Util.matches 内部就是 sha256(明文).equals(密文)
            return sha256Util.matches(rawPassword, storedPassword);
        }
        // 新格式：BCrypt 从密文里读出盐和强度再比对
        return encoder.matches(rawPassword, storedPassword);
    }

    /**
     * 是不是历史遗留的无盐 SHA-256 密文。
     *
     * <p>迁移期用得上：① 登录时据此决定要不要升级密文；② 写脚本统计库里还剩多少旧密文。</p>
     */
    public boolean isLegacy(String storedPassword) {
        return storedPassword != null && SHA256_HEX.matcher(storedPassword).matches();
    }
}
