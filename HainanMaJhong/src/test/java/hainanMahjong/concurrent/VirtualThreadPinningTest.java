package hainanMahjong.concurrent;

import hainanMahjong.engine.RoomEventLoop;
import hainanMahjong.room.Room;
import hainanMahjong.websocket.SessionSendLocks;
import hainanMahjong.service.impl.GamePushServiceImpl;
import hainanMahjong.service.impl.RecordDetailCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.WebSocketSession;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(20)
class VirtualThreadPinningTest {
    @TempDir Path temp;

    @Test void blockingRoomSocketAndCachePathsDoNotEmitPinnedEvents() throws Exception {
        Room room = new Room("PIN001", 1);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        doAnswer(call -> { assertTrue(Thread.currentThread().isVirtual()); pause(); return null; })
                .when(session).sendMessage(any());
        RecordDetailCache cache = new RecordDetailCache(8, 8192, 65536, 1000);
        Path trace = temp.resolve("pinning.jfr");
        try (Recording recording = new Recording()) {
            recording.setMaxSize(8 * 1024 * 1024);
            recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ofMillis(5)).withStackTrace();
            recording.start();
            try (ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
                 ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                 Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
                 Socket peer = server.accept()) {
                CompletableFuture<Void> entered = new CompletableFuture<>();
                Future<Integer> read = tasks.submit(() -> {
                    room.lock.lock();
                    try { entered.complete(null); return client.getInputStream().read(); }
                    finally { room.lock.unlock(); }
                });
                entered.get(2, TimeUnit.SECONDS);
                Thread.sleep(75);
                peer.getOutputStream().write(42);
                assertEquals(42, read.get(2, TimeUnit.SECONDS));
                tasks.submit(() -> new GamePushServiceImpl().sendJson(session, Map.of("type", "test")))
                        .get(3, TimeUnit.SECONDS);
                tasks.submit(() -> cache.get(new RecordDetailCache.Key(1, 1, 1), () -> {
                    assertTrue(Thread.currentThread().isVirtual()); pause(); return List.of();
                })).get(3, TimeUnit.SECONDS);
            }
            recording.stop(); recording.dump(trace);
        }
        var pinned = RecordingFile.readAllEvents(trace).stream()
                .filter(event -> event.getEventType().getName().equals("jdk.VirtualThreadPinned"))
                .filter(event -> event.getStackTrace() != null && event.getStackTrace().getFrames().stream()
                        .anyMatch(frame -> frame.getMethod().getType().getName().startsWith("hainanMahjong.")))
                .toList();
        assertTrue(pinned.isEmpty(), () -> "Application paths pinned a carrier: " + pinned);
        assertSame(SessionSendLocks.lock(session), SessionSendLocks.lock(session));
    }

    @Test void detectorRecognizesAnIntentionalMonitorPin() throws Exception {
        Path trace = temp.resolve("positive-control.jfr");
        try (Recording recording = new Recording()) {
            recording.setMaxSize(8 * 1024 * 1024);
            recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ofMillis(5)).withStackTrace();
            recording.start();
            Thread thread = Thread.ofVirtual().start(() -> {
                synchronized (new Object()) { pause(); }
            });
            thread.join();
            recording.stop(); recording.dump(trace);
        }
        assertTrue(RecordingFile.readAllEvents(trace).stream()
                .anyMatch(event -> event.getEventType().getName().equals("jdk.VirtualThreadPinned")),
                "Positive control must detect the known JDK 21 monitor pin");
    }

    @Test void concurrentVirtualSendersPreserveContiguousFrameOrder() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        attributes.put(hainanMahjong.websocket.GamePushProtocol.MODE, "delta");
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(attributes);
        List<Long> sequences = new CopyOnWriteArrayList<>();
        AtomicInteger writers = new AtomicInteger();
        doAnswer(call -> {
            assertTrue(Thread.currentThread().isVirtual());
            assertEquals(1, writers.incrementAndGet());
            var message = (org.springframework.web.socket.TextMessage) call.getArgument(0);
            sequences.add(new com.fasterxml.jackson.databind.ObjectMapper().readTree(message.getPayload()).get("seq").asLong());
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            writers.decrementAndGet();
            return null;
        }).when(session).sendMessage(any());
        var push = new GamePushServiceImpl();
        try (ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> sent = new ArrayList<>();
            for (int i = 0; i < 40; i++) sent.add(tasks.submit(() -> push.sendJson(session, Map.of("type", "test"))));
            for (Future<?> future : sent) future.get(5, TimeUnit.SECONDS);
        }
        assertEquals(java.util.stream.LongStream.rangeClosed(1, 40).boxed().toList(), sequences);
    }

    private static void pause() { LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(75)); }

    @Test void mailboxSerializesVirtualEventsAcrossBlockingIo() throws Exception {
        RoomEventLoop loop = new RoomEventLoop();
        AtomicInteger executing = new AtomicInteger(), finished = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(40);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 40; i++) loop.execute(() -> {
            try {
                assertTrue(Thread.currentThread().isVirtual());
                assertEquals(1, executing.incrementAndGet());
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
                executing.decrementAndGet(); finished.incrementAndGet();
            } catch (Throwable ex) { failures.add(ex); }
            finally { done.countDown(); }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(List.of(), failures);
        assertEquals(40, finished.get());
    }

    @Test void periodicIoUsesVirtualThreadsWithoutOverlapAndCanBeCancelled() throws Exception {
        AtomicInteger executing = new AtomicInteger(), runs = new AtomicInteger(), overlaps = new AtomicInteger();
        CountDownLatch twice = new CountDownLatch(2);
        try (VirtualPeriodicTask task = new VirtualPeriodicTask("test-periodic")) {
            task.start(() -> {
                assertTrue(Thread.currentThread().isVirtual());
                if (executing.incrementAndGet() != 1) overlaps.incrementAndGet();
                pause(); executing.decrementAndGet(); runs.incrementAndGet(); twice.countDown();
            }, 0, 10, TimeUnit.MILLISECONDS);
            assertTrue(twice.await(3, TimeUnit.SECONDS));
        }
        int stopped = runs.get(); Thread.sleep(100);
        assertEquals(stopped, runs.get()); assertEquals(0, overlaps.get());
    }
}
