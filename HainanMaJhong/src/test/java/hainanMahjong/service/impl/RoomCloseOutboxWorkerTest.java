package hainanMahjong.service.impl;

import hainanMahjong.monitoring.CapacityMetrics;
import hainanMahjong.repository.RoomSnapshotRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class RoomCloseOutboxWorkerTest {
    private final RoomCloseOutbox outbox = mock(RoomCloseOutbox.class);
    private final RoomSnapshotRepository redis = mock(RoomSnapshotRepository.class);
    private final CapacityMetrics metrics = mock(CapacityMetrics.class);
    private final RoomCloseOutbox.Job job = new RoomCloseOutbox.Job(1, 42, "123456", "old-generation", 0);

    private RoomCloseOutboxWorker worker() {
        when(outbox.stats()).thenReturn(new RoomCloseOutbox.Stats(0, 0, 0));
        return new RoomCloseOutboxWorker(outbox, redis, metrics, true);
    }

    @Test void restartReadsPendingJobsAndAcknowledgesAfterRedis() {
        when(outbox.due()).thenReturn(List.of(job));
        when(redis.closeRoom(job.code(), job.generation())).thenReturn(true);
        worker().poll();
        var order = inOrder(redis, outbox);
        order.verify(outbox).due();
        order.verify(redis).closeRoom(job.code(), job.generation());
        order.verify(outbox).complete(42, job.generation());
        // A new process can replay after Redis success / DB acknowledgement loss.
        worker().poll();
        verify(redis, times(2)).closeRoom(job.code(), job.generation());
    }

    @Test void obsoleteGenerationIsCompletedWithoutRetry() {
        when(outbox.due()).thenReturn(List.of(job));
        when(redis.closeRoom(job.code(), job.generation())).thenReturn(false);
        worker().poll();
        verify(outbox).complete(42, job.generation());
        verify(outbox, never()).fail(any(), anyString());
    }

    @Test void redisFailurePersistsRetryAndContinuesNextJob() {
        var next = new RoomCloseOutbox.Job(2, 43, "654321", "next-generation", 0);
        when(outbox.due()).thenReturn(List.of(job, next));
        when(redis.closeRoom(job.code(), job.generation())).thenThrow(new IllegalStateException("secret endpoint"));
        worker().poll();
        verify(outbox).fail(job, "IllegalStateException");
        verify(outbox, never()).complete(42, job.generation());
        verify(outbox).complete(43, next.generation());
    }

    @Test void failedAcknowledgementCanReplay() {
        when(outbox.due()).thenReturn(List.of(job));
        doThrow(new IllegalStateException()).doNothing().when(outbox).complete(42, job.generation());
        var worker = worker();
        worker.poll(); worker.poll();
        verify(outbox).fail(job, "IllegalStateException");
        verify(redis, times(2)).closeRoom(job.code(), job.generation());
    }

    @Test void pollFailureDoesNotKillFuturePolls() {
        when(outbox.due()).thenThrow(new IllegalStateException()).thenReturn(List.of(job));
        var worker = worker(); worker.poll(); worker.poll();
        verify(metrics).gauge("mahjong.outbox.poll.healthy", 0);
        verify(outbox).complete(42, job.generation());
    }
}
