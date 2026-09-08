package hainanjong.web;

import hainanjong.service.MysqlService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * 登录 / 注册 / 个人信息（昵称、密码）接口。
 *
 * <p>密码用 SHA-256 哈希后存库（生产环境建议改用 BCrypt）。</p>
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final MysqlService mysql;

    public AuthController(MysqlService mysql) {
        this.mysql = mysql;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body) {
        String username = trim(body.get("username"));
        String password = body.get("password");
        if (username.isEmpty() || password == null || password.isEmpty()) {
            return fail("用户名或密码不能为空");
        }
        if (password.length() < 6) {
            return fail("密码至少 6 位");
        }
        Long userId = mysql.registerUser(username, sha256(password));
        return userId != null ? okWithUser("注册成功", userId) : fail("用户名已存在");
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String username = trim(body.get("username"));
        String password = body.get("password");
        if (username.isEmpty() || password == null || password.isEmpty()) {
            return fail("用户名或密码不能为空");
        }
        String stored = mysql.findPasswordHash(username);
        if (stored == null || !stored.equals(sha256(password))) {
            return fail("用户名或密码错误");
        }
        Long userId = mysql.findUserId(username);
        return okWithUser("登录成功", userId == null ? 0L : userId);
    }

    /** 修改昵称。 */
    @PostMapping("/user/nickname")
    public Map<String, Object> changeNickname(@RequestBody Map<String, String> body) {
        long userId = parseUserId(body.get("userId"));
        String nickname = trim(body.get("nickname"));
        if (userId <= 0) {
            return fail("未登录");
        }
        if (nickname.isEmpty()) {
            return fail("昵称不能为空");
        }
        if (nickname.length() > 16) {
            return fail("昵称最多 16 个字符");
        }
        mysql.updateNickname(userId, nickname);
        return okWithUser("昵称已更新", userId);
    }

    /** 修改密码。 */
    @PostMapping("/user/password")
    public Map<String, Object> changePassword(@RequestBody Map<String, String> body) {
        long userId = parseUserId(body.get("userId"));
        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");
        if (userId <= 0) {
            return fail("未登录");
        }
        if (oldPassword == null || oldPassword.isEmpty()) {
            return fail("请输入原密码");
        }
        if (newPassword == null || newPassword.length() < 6) {
            return fail("新密码至少 6 位");
        }
        String stored = mysql.findPasswordHashById(userId);
        if (stored == null || !stored.equals(sha256(oldPassword))) {
            return fail("原密码错误");
        }
        mysql.updatePassword(userId, sha256(newPassword));
        return ok("密码已修改");
    }

    private static long parseUserId(String s) {
        try {
            return Long.parseLong(trim(s));
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, Object> okWithUser(String message, long userId) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("success", true);
        m.put("message", message);
        m.put("userId", userId);
        String nick = mysql.findNickname(userId);
        if (nick == null || nick.isEmpty()) {
            nick = mysql.getUsername(userId);
        }
        m.put("nickname", nick);
        return m;
    }

    private static Map<String, Object> ok(String message) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("success", true);
        m.put("message", message);
        return m;
    }

    private static Map<String, Object> fail(String message) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("success", false);
        m.put("message", message);
        return m;
    }
}
