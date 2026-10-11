package hainanMahjong.monitoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;

/** 单独的采样任务，游戏线程不执行主机读取或外部探测。 */
@Component
public class CapacitySampler {
    private final CapacityMetrics metrics;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    @Value("${capacity.external-probes-enabled:false}") private boolean probes;
    @Value("${capacity.network-interface:eth0}") private String networkInterface;
    public CapacitySampler(CapacityMetrics metrics, StringRedisTemplate redis, JdbcTemplate jdbc) {
        this.metrics = metrics; this.redis = redis; this.jdbc = jdbc;
    }
    @Scheduled(fixedDelayString = "${capacity.sample-ms:10000}")
    public void sample() {
        metrics.sampleRooms(); threads(); linux();
        if (probes) { redis(); mysql(); }
        metrics.gauge("mahjong.capacity.sample.timestamp", System.currentTimeMillis() / 1000);
    }
    private void threads() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean(); long blocked = 0, engines = 0, timers = 0;
        for (ThreadInfo info : bean.getThreadInfo(bean.getAllThreadIds())) {
            if (info == null) continue;
            if (info.getThreadState() == Thread.State.BLOCKED) blocked++;
            if (info.getThreadName().startsWith("room-engine-") || info.getThreadName().startsWith("room-bind-")) engines++;
            if (info.getThreadName().equals("mahjong-timer")) timers++;
        }
        metrics.gauge("mahjong.jvm.threads.blocked", blocked); metrics.gauge("mahjong.engine.threads", engines);
        metrics.gauge("mahjong.engine.timer.threads", timers);
    }
    private void linux() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) metrics.gauge("mahjong.host.memory.available", number(line.split("\\s+")[1]) * 1024);
                if (line.startsWith("MemTotal:")) metrics.gauge("mahjong.host.memory.total", number(line.split("\\s+")[1]) * 1024);
            }
            String[] cpu = Files.readAllLines(Path.of("/proc/stat")).get(0).trim().split("\\s+");
            long total = 0; for (int i = 1; i <= 8 && i < cpu.length; i++) total += number(cpu[i]);
            metrics.gauge("mahjong.host.cpu.ticks.total", total); metrics.gauge("mahjong.host.cpu.ticks.idle", number(cpu[4]) + number(cpu[5]));
            metrics.gauge("mahjong.host.available", 1);
        } catch (Exception e) { metrics.gauge("mahjong.host.available", 0); }
        try {
            boolean found = false;
            for (String line : Files.readAllLines(Path.of("/proc/net/dev"))) {
                String[] halves = line.split(":"); if (halves.length != 2 || !halves[0].trim().equals(networkInterface)) continue;
                String[] fields = halves[1].trim().split("\\s+"); found = true;
                metrics.gauge("mahjong.host.network.receive.bytes", number(fields[0]));
                metrics.gauge("mahjong.host.network.transmit.bytes", number(fields[8]));
            }
            metrics.gauge("mahjong.host.network.available", found ? 1 : 0);
            List<String> snmp = Files.readAllLines(Path.of("/proc/net/snmp"));
            for (int i = 0; i + 1 < snmp.size(); i++) {
                if (!snmp.get(i).startsWith("Tcp:")) continue;
                String[] names = snmp.get(i).split("\\s+"), counts = snmp.get(i + 1).split("\\s+");
                for (int j = 1; j < names.length; j++) if (names[j].equals("RetransSegs")) metrics.gauge("mahjong.host.tcp.retransmits", number(counts[j]));
                break;
            }
        } catch (Exception e) { metrics.gauge("mahjong.host.network.available", 0); }
        try {
            long read = 0, write = 0, io = 0;
            for (String line : Files.readAllLines(Path.of("/proc/diskstats"))) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 14 || !f[2].matches("sd[a-z]+|vd[a-z]+|xvd[a-z]+|nvme\\d+n\\d+")) continue;
                read += number(f[5]) * 512; write += number(f[9]) * 512; io += number(f[12]);
            }
            metrics.gauge("mahjong.host.disk.read.bytes", read); metrics.gauge("mahjong.host.disk.write.bytes", write);
            metrics.gauge("mahjong.host.disk.io.milliseconds", io);
        } catch (Exception ignored) { /* 非 Linux 平台保持指标缺失 */ }
    }
    private void redis() {
        try {
            Properties stats = redis.execute((RedisCallback<Properties>) connection -> connection.info("stats"));
            Properties memory = redis.execute((RedisCallback<Properties>) connection -> connection.info("memory"));
            if (stats == null || memory == null) throw new IllegalStateException("INFO unavailable");
            metrics.gauge("mahjong.redis.hits", property(stats, "keyspace_hits"));
            metrics.gauge("mahjong.redis.misses", property(stats, "keyspace_misses"));
            metrics.gauge("mahjong.redis.evicted", property(stats, "evicted_keys"));
            metrics.gauge("mahjong.redis.expired", property(stats, "expired_keys"));
            metrics.gauge("mahjong.redis.memory.used", property(memory, "used_memory"));
            metrics.gauge("mahjong.redis.probe.up", 1);
        } catch (Exception e) { metrics.gauge("mahjong.redis.probe.up", 0); metrics.count("mahjong.capacity.probe.errors", "source", "redis"); }
    }
    private void mysql() {
        try {
            // 池已满时不为监控争抢连接，避免将等待扩大到游戏业务。
            if (jdbc.getDataSource() instanceof com.zaxxer.hikari.HikariDataSource pool
                    && (pool.getHikariPoolMXBean() == null || pool.getHikariPoolMXBean().getIdleConnections() == 0)) {
                metrics.gauge("mahjong.mysql.probe.up", 0); return;
            }
            for (Map<String, Object> row : jdbc.queryForList("SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Slow_queries','Questions','Innodb_row_lock_time','Innodb_row_lock_waits')")) {
                String key = row.get("Variable_name").toString().toLowerCase(Locale.ROOT).replace('_', '.');
                metrics.gauge("mahjong.mysql." + key, number(row.get("Value").toString()));
            }
            metrics.gauge("mahjong.mysql.probe.up", 1);
        } catch (Exception e) { metrics.gauge("mahjong.mysql.probe.up", 0); metrics.count("mahjong.capacity.probe.errors", "source", "mysql"); }
    }
    private static long number(String s) { return Long.parseLong(s); }
    private static long property(Properties p, String key) { return number(p.getProperty(key, "0")); }
}
