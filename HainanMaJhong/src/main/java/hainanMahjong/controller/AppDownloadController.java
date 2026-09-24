package hainanMahjong.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * 大厅「下载 APP」用的安装包下载端点。
 *
 * <p>URL 是 {@code /download/app}（大厅里那个按钮写死的，见 {@code Lobby.vue}；
 * 旧版 APK / 二维码里也是这个地址，<b>不能改</b>，改了老安装包就下不到新版了）。</p>
 *
 * <h3>两处取包，优先外部覆盖</h3>
 * <ol>
 *   <li>配置文件里的 {@code app.apk.path}（可选）：指向服务器上的 apk 文件。
 *       换包时只要替换文件、重启即可，<b>不用重新打包 jar</b>。</li>
 *   <li>兜底：jar 内的 {@code /static/apk/jiangpahnmj.apk}（随构建产物一起发布）。</li>
 * </ol>
 * <p>与旧版 {@code hainanjong.web.AppDownloadController} 行为一致（那张 jar 包里的实现）。</p>
 *
 * <p>注意：这个端点不在 {@code /api/**} 下，所以不受 JWT 拦截器约束 —— 下载安装包
 * 本来就不该要求先登录。</p>
 */
@RestController
public class AppDownloadController {

    /** 下载下来保存的文件名（旧版一致）。 */
    private static final String FILE_NAME = "jiangpahnmj.apk";

    /** jar 内的兜底路径。 */
    private static final String CLASSPATH_APK = "/static/apk/jiangpahnmj.apk";

    /**
     * 可选的服务器本地 apk 路径（例如 {@code /opt/mahjong/jiangpahnmj.apk}）。
     * 为空或文件不存在时回落到 jar 内那份。
     */
    @Value("${app.apk.path:}")
    private String externalApk;

    @GetMapping("/download/app")
    public ResponseEntity<byte[]> download() throws Exception {
        byte[] body = readExternal();
        if (body == null) {
            body = readClasspath();
        }
        if (body == null) {
            // 兜底也不在（构建时漏了 static/apk/）：给个明确的 404，别抛 500 让人猜
            return ResponseEntity.notFound().build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.android.package-archive"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + FILE_NAME + "\"");
        headers.setContentLength(body.length);
        // 换包后不希望用户拿到浏览器缓存的旧安装包
        headers.setCacheControl("no-cache, no-store, must-revalidate");
        return new ResponseEntity<>(body, headers, org.springframework.http.HttpStatus.OK);
    }

    private byte[] readExternal() {
        String path = externalApk == null ? "" : externalApk.trim();
        if (path.isEmpty()) {
            return null;
        }
        File f = new File(path);
        if (!f.isFile()) {
            return null;
        }
        try {
            return Files.readAllBytes(f.toPath());
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] readClasspath() {
        ClassPathResource res = new ClassPathResource(CLASSPATH_APK);
        if (!res.exists()) {
            return null;
        }
        try (InputStream in = res.getInputStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }
}
