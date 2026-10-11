package hainanMahjong.monitoring;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.util.UUID;
import java.util.regex.*;

/** 直接转发响应，只保存 1024 字节前缀识别业务码，不复制完整战绩或令牌。 */
@Component
public class CapacityHttpFilter extends OncePerRequestFilter {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CapacityHttpFilter.class);
    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*(\\d+)");
    private final CapacityMetrics metrics;
    public CapacityHttpFilter(CapacityMetrics metrics) { this.metrics = metrics; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime(); CountingResponse counted = new CountingResponse(response);
        String requestId = UUID.randomUUID().toString(); request.setAttribute("capacityRequestId", requestId);
        response.setHeader("X-Request-ID", requestId); boolean exception = false;
        try { chain.doFilter(request, counted); }
        catch (ServletException | IOException | RuntimeException error) { exception = true; throw error; }
        finally {
            counted.finish(); long duration = System.nanoTime() - start;
            Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String route = matched == null ? "unmatched" : matched.toString();
            Matcher code = CODE.matcher(counted.prefix.toString(java.nio.charset.StandardCharsets.UTF_8));
            boolean businessError = code.find() && !"200".equals(code.group(1));
            String outcome = exception || response.getStatus() >= 500 ? "server_error" : businessError || response.getStatus() >= 400 ? "business_error" : "success";
            String[] tags = {"route", route, "method", request.getMethod(), "outcome", outcome};
            metrics.time("mahjong.http.response", duration, tags);
            metrics.time("mahjong.http.first_byte", counted.firstWrite == 0 ? duration : counted.firstWrite - start, tags);
            metrics.bytes("mahjong.http.response.body", counted.bytes, tags);
            if (!"success".equals(outcome) || duration > 500_000_000L)
                log.info("capacity requestId={} route={} method={} outcome={} durationMs={} bytes={}",
                    requestId, route, request.getMethod(), outcome, duration / 1_000_000, counted.bytes);
        }
    }
    private static class CountingResponse extends HttpServletResponseWrapper {
        long bytes, firstWrite;
        final ByteArrayOutputStream prefix = new ByteArrayOutputStream(1024);
        private ServletOutputStream stream;
        private PrintWriter writer;
        CountingResponse(HttpServletResponse response) { super(response); }
        @Override public ServletOutputStream getOutputStream() throws IOException {
            if (stream == null) {
                ServletOutputStream delegate = super.getOutputStream();
                stream = new ServletOutputStream() {
                    @Override public boolean isReady() { return delegate.isReady(); }
                    @Override public void setWriteListener(WriteListener listener) { delegate.setWriteListener(listener); }
                    @Override public void write(int b) throws IOException { write(new byte[]{(byte)b}, 0, 1); }
                    @Override public void write(byte[] data, int offset, int length) throws IOException {
                        if (firstWrite == 0) firstWrite = System.nanoTime(); bytes += length;
                        prefix.write(data, offset, Math.min(length, 1024 - prefix.size())); delegate.write(data, offset, length);
                    }
                    @Override public void flush() throws IOException { delegate.flush(); }
                };
            }
            return stream;
        }
        @Override public PrintWriter getWriter() throws IOException {
            if (writer == null) writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), getCharacterEncoding()));
            return writer;
        }
        void finish() { if (writer != null) writer.flush(); }
    }
}
