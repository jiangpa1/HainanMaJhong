package hainanjong.web;

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
 * APP 安装包下载：GET /download/app
 *
 * <p>默认打包在静态资源 apk/jiangpahnmj.apk 里随 jar 分发；也可用配置
 * {@code app.apk.path}（或环境变量）指向服务器上的安装包路径，更新安装包无需重打包。</p>
 */
@RestController
public class AppDownloadController {

    private static final String FILE_NAME = "jiangpahnmj.apk";

    @Value("${app.apk.path:}")
    private String externalApk;

    @GetMapping("/download/app")
    public ResponseEntity<byte[]> download() throws Exception {
        byte[] data = null;
        String ext = externalApk == null ? "" : externalApk.trim();
        if (!ext.isEmpty()) {
            File f = new File(ext);
            if (f.isFile()) {
                data = Files.readAllBytes(f.toPath());
            }
        }
        if (data == null) {
            ClassPathResource r = new ClassPathResource("/static/apk/" + FILE_NAME);
            try (InputStream in = r.getInputStream()) {
                data = in.readAllBytes();
            }
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + FILE_NAME + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.android.package-archive"))
                .body(data);
    }
}
