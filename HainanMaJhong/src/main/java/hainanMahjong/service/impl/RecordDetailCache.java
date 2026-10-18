package hainanMahjong.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import hainanMahjong.vo.recordvo.RoundPlayerRowVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** Cache only public database rows. Deserialize a fresh copy before attaching nickname/isMe. */
@Component
public class RecordDetailCache {
    public record Key(long sessionId, int roundNum, long revision) {}
    private record Entry(byte[] bytes, long expiresAt) {}
    private final Map<Key,Entry> entries = new LinkedHashMap<>(16,.75f,true);
    private final Object[] stripes = new Object[64];
    private final ObjectMapper json = new ObjectMapper();
    private final int maxEntries, maxItemBytes, maxBytes;
    private final long ttlMs;
    private int bytes;
    private io.micrometer.core.instrument.MeterRegistry metrics;
    @org.springframework.beans.factory.annotation.Autowired
    public void setMetrics(io.micrometer.core.instrument.MeterRegistry metrics) {
        this.metrics=metrics;
        metrics.gauge("mahjong.records.cache.entries",this,cache->cache.size());
        metrics.gauge("mahjong.records.cache.bytes",this,cache->cache.byteSize());
    }
    private void count(String outcome) {
        if(metrics!=null) metrics.counter("mahjong.records.cache.requests","outcome",outcome).increment();
    }
    public RecordDetailCache(@Value("${records.p2.cache.max-entries:512}") int maxEntries,
            @Value("${records.p2.cache.max-item-bytes:65536}") int maxItemBytes,
            @Value("${records.p2.cache.max-bytes:8388608}") int maxBytes,
            @Value("${records.p2.cache.ttl-ms:60000}") long ttlMs) {
        if(maxEntries<1 || maxItemBytes<1 || maxBytes<1 || ttlMs<1) throw new IllegalArgumentException("Positive cache limits required");
        this.maxEntries=maxEntries; this.maxItemBytes=maxItemBytes; this.maxBytes=maxBytes; this.ttlMs=ttlMs;
        Arrays.setAll(stripes,i->new Object());
    }
    public List<RoundPlayerRowVO> get(Key key,Supplier<List<RoundPlayerRowVO>> loader) {
        // Fixed stripes bound synchronization objects and coalesce the same key, including failures.
        synchronized(stripes[Math.floorMod(key.hashCode(),stripes.length)]) {
            byte[] cached = read(key);
            if(cached!=null) {
                try {
                    List<RoundPlayerRowVO> rows=json.readValue(cached,new TypeReference<List<RoundPlayerRowVO>>(){});
                    count("hit"); return rows;
                } catch(Exception e) { count("decode_error"); invalidate(key.sessionId()); }
            }
            count("miss");
            List<RoundPlayerRowVO> rows=loader.get();
            if(rows.size()!=4) { count("bypass"); return rows; }
            try {
                byte[] encoded=json.writeValueAsBytes(rows);
                if(encoded.length<=maxItemBytes && encoded.length<=maxBytes) {
                    long expiry=System.currentTimeMillis()+ttlMs+ThreadLocalRandom.current().nextLong(Math.max(1,ttlMs/10));
                    synchronized(entries) {
                        Entry old=entries.put(key,new Entry(encoded,expiry));
                        bytes+=encoded.length-(old==null?0:old.bytes().length);
                        Iterator<Entry> it=entries.values().iterator();
                        while(entries.size()>maxEntries || bytes>maxBytes) { bytes-=it.next().bytes().length; it.remove(); }
                    }
                }
                else count("bypass");
            } catch(Exception ignored) { count("encode_error"); /* DB result remains usable. */ }
            return rows;
        }
    }
    private byte[] read(Key key) {
        synchronized(entries) {
            Entry entry=entries.get(key);
            if(entry==null) return null;
            if(entry.expiresAt()<=System.currentTimeMillis()) { entries.remove(key); bytes-=entry.bytes().length; return null; }
            return entry.bytes();
        }
    }
    public void invalidate(long sessionId) {
        synchronized(entries) {
            Iterator<Map.Entry<Key,Entry>> it=entries.entrySet().iterator();
            while(it.hasNext()) {
                Map.Entry<Key,Entry> entry=it.next();
                if(entry.getKey().sessionId()==sessionId) { bytes-=entry.getValue().bytes().length; it.remove(); }
            }
        }
    }
    int size() { synchronized(entries) { return entries.size(); } }
    int byteSize() { synchronized(entries) { return bytes; } }
}
