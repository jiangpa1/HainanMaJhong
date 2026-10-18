package hainanMahjong.service.impl;

import hainanMahjong.vo.recordvo.RoundPlayerRowVO;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RecordDetailCacheTest {
    private List<RoundPlayerRowVO> rows(String detail) {
        List<RoundPlayerRowVO> out=new ArrayList<>();
        for(int seat=0;seat<4;seat++) { RoundPlayerRowVO r=new RoundPlayerRowVO(); r.setUserId((long)seat+1); r.setSeat(seat); r.setWinner(seat==0); r.setDetail(detail); out.add(r); }
        return out;
    }
    @Test void copiesRowsAndSeparatesRevisions() {
        RecordDetailCache cache=new RecordDetailCache(10,65536,65536,60000);
        var key=new RecordDetailCache.Key(42,1,1);
        var first=cache.get(key,()->rows("original"));
        first.get(0).setDetail("modified");
        var second=cache.get(key,()->{throw new AssertionError("must hit cache");});
        assertEquals("original",second.get(0).getDetail()); assertTrue(second.get(0).isWinner());
        assertEquals("new",cache.get(new RecordDetailCache.Key(42,1,2),()->rows("new")).get(0).getDetail());
        cache.invalidate(42); assertEquals(0,cache.size()); assertEquals(0,cache.byteSize());
    }
    @Test void boundsMemoryAndEntriesAndBypassesLargeOrIncompleteItems() {
        RecordDetailCache cache=new RecordDetailCache(2,3000,4500,60000);
        for(int round=1;round<10;round++) cache.get(new RecordDetailCache.Key(42,round,1),()->rows("x".repeat(150)));
        assertTrue(cache.size()<=2); assertTrue(cache.byteSize()<=4500);
        int before=cache.size();
        cache.get(new RecordDetailCache.Key(42,20,1),()->rows("x".repeat(3000)));
        cache.get(new RecordDetailCache.Key(42,21,1),()->List.of(new RoundPlayerRowVO()));
        assertEquals(before,cache.size());
    }
    @Test void coalescesConcurrentMissesAndDoesNotCacheFailures() throws Exception {
        RecordDetailCache cache=new RecordDetailCache(10,65536,65536,60000);
        AtomicInteger loads=new AtomicInteger();
        ExecutorService workers=Executors.newFixedThreadPool(8);
        CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<?>> jobs=new ArrayList<>();
            for(int i=0;i<8;i++) jobs.add(workers.submit(()-> {
                try { start.await(); } catch(InterruptedException e) { throw new RuntimeException(e); }
                cache.get(new RecordDetailCache.Key(42,1,1),()-> { loads.incrementAndGet(); return rows("ok"); });
            }));
            start.countDown(); for(Future<?> job:jobs) job.get(5,TimeUnit.SECONDS);
            assertEquals(1,loads.get());
            assertThrows(IllegalStateException.class,()->cache.get(new RecordDetailCache.Key(42,2,1),()->{throw new IllegalStateException("db");}));
            assertEquals("retry",cache.get(new RecordDetailCache.Key(42,2,1),()->rows("retry")).get(0).getDetail());
        } finally { workers.shutdownNow(); }
    }
    @Test void expiresAndReloads() throws Exception {
        RecordDetailCache cache=new RecordDetailCache(10,65536,65536,1);
        var key=new RecordDetailCache.Key(42,1,1);
        cache.get(key,()->rows("old"));
        Thread.sleep(10);
        assertEquals("fresh",cache.get(key,()->rows("fresh")).get(0).getDetail());
    }
}
