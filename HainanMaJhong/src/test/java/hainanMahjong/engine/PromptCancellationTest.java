package hainanMahjong.engine;

import hainanMahjong.engine.model.Seat;
import hainanMahjong.engine.port.GameListener;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PromptCancellationTest {
    @Test void cancelReleasesPendingPromptAndStopsTimer() throws Exception {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        ExecutorService engine = Executors.newSingleThreadExecutor();
        PromptSupport prompt = new PromptSupport(timer, mock(GameListener.class));
        CountDownLatch waiting = new CountDownLatch(1);
        try {
            Future<Integer> result = engine.submit(() -> prompt.prompt(Seat.EAST, "discard", 30000,
                    reply -> waiting.countDown(), -1));
            assertTrue(waiting.await(2, TimeUnit.SECONDS)); prompt.shutdown();
            assertEquals(-1, result.get(2, TimeUnit.SECONDS));
            assertTrue(timer.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(-2, prompt.prompt(Seat.EAST, "discard", 30000,
                    reply -> fail("关闭后不能再发起询问"), -2));
        } finally { prompt.shutdown(); engine.shutdownNow(); }
    }
}
