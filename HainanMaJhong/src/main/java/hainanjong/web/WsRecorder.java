package hainanjong.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 【临时诊断组件 · 架构改造阶段 0 专用 · 阶段 1 结束即删】
 *
 * <p>把服务端发出去 / 收到的每一条 WebSocket 消息原样追加到一个文件，用于给
 * 「分层搬包」建立行为基线（回归对照）。设计原则：
 * <ul>
 *   <li><b>只观察，不介入</b>：不解析消息、不改消息、异常全部吞掉 —— 保证对牌局行为零影响。</li>
 *   <li>行格式：{@code S->C <TAB> <会话短id> <TAB> <json>} / {@code C->S <TAB> <会话短id> <TAB> <payload>}</li>
 *   <li>文件：{@code <工作目录>/docs/ws-recording.log}（追加写，不覆盖）。</li>
 *   <li>开关：系统属性 {@code -Dws.record=off} 可关闭；默认开启。</li>
 * </ul>
 *
 * <p><b>为什么需要它</b>：DevTools 的「消息」面板用 Ctrl+A 只能复制可见行，滚动区外的帧会丢
 * （实测两轮都丢了入房/开局段），所以浏览器侧抓不出完整基线。服务端录制是权威且必然完整的。</p>
 */
final class WsRecorder {

    private static final Logger log = LoggerFactory.getLogger(WsRecorder.class);
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS");

    /**
     * 录制文件路径解析（防"写到不同目录"导致基线分裂）：
     * <ol>
     *   <li>显式指定：{@code -Dws.record.file=...} 优先；</li>
     *   <li>否则从当前工作目录向上找 {@code docs/}（模块目录启动 → 上找一级到仓库根；仓库根启动 → 自身）；</li>
     *   <li>都没有则退回 {@code <cwd>/docs/}。</li>
     * </ol>
     * 实测：{@code user.dir} 等于 JVM 启动时的工作目录，所以从 {@code HainanMaJhong} 或
     * {@code HainanMaJhong2} 启动都能落到同一个 {@code F:\HainanMaJhong2\docs\ws-recording.log}。
     */
    private static String resolveFilePath() {
        String explicit = System.getProperty("ws.record.file");
        if (explicit != null && !explicit.trim().isEmpty()) {
            return explicit.trim();
        }
        File dir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        for (int i = 0; i < 4 && dir != null; i++) {
            File docs = new File(dir, "docs");
            if (docs.isDirectory()) {
                return new File(docs, "ws-recording.log").getAbsolutePath();
            }
            dir = dir.getParentFile();
        }
        return new File("docs/ws-recording.log").getAbsolutePath();
    }

    private static final String FILE_PATH = resolveFilePath();
    private static final boolean ENABLED =
            !"off".equalsIgnoreCase(System.getProperty("ws.record", "on"));

    private static final Object LOCK = new Object();
    private static volatile boolean announced = false;

    private WsRecorder() {
    }

    /** 服务端 → 客户端。 */
    static void recordOut(String sessionId, String json) {
        write("S->C", sessionId, json);
    }

    /** 客户端 → 服务端。 */
    static void recordIn(String sessionId, String payload) {
        write("C->S", sessionId, payload);
    }

    private static void write(String dir, String sessionId, String payload) {
        if (!ENABLED || payload == null) {
            return;
        }
        String sid = sessionId == null ? "?" : (sessionId.length() > 8 ? sessionId.substring(0, 8) : sessionId);
        String line = dir + "\t" + sid + "\t" + TS.format(new Date()) + "\t" + payload + System.lineSeparator();
        synchronized (LOCK) {
            try {
                File f = new File(FILE_PATH);
                File parent = f.getAbsoluteFile().getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                    w.write(line);
                }
                if (!announced) {
                    announced = true;
                    log.info("[WsRecorder] 消息录制已开启 -> {}", f.getAbsolutePath());
                }
            } catch (IOException e) {
                // 绝不影响业务
                log.debug("[WsRecorder] 写入失败：{}", e.getMessage());
            }
        }
    }
}
