package hainanMahjong.service.impl;

import hainanMahjong.mapper.GameRoundsMapper;
import hainanMahjong.mapper.GameSessionsMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import javax.sql.DataSource;
import java.sql.Connection;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomCloseOutboxTransactionTest {
    @Test void eventAndCloseShareTransactionAndRollbackOnEnqueueFailure() throws Exception {
        for (boolean fail : new boolean[]{false, true}) {
            DataSource source = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            when(source.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenReturn(true);
            var sessions = mock(GameSessionsMapper.class);
            when(sessions.disbandSession(42L)).thenReturn(1);
            var outbox = mock(RoomCloseOutbox.class);
            doAnswer(invocation -> {
                assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                if (fail) throw new IllegalStateException("enqueue failed");
                return null;
            }).when(outbox).enqueue(42, "123456", "generation");
            var target = new RoundPersister(sessions, mock(GameRoundsMapper.class));
            target.setCloseOutbox(outbox);
            var factory = new ProxyFactory(target);
            factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                    new AnnotationTransactionAttributeSource()));
            var proxy = (RoundPersister) factory.getProxy();
            if (fail) {
                assertThrows(IllegalStateException.class, () -> proxy.closeWithOutbox(42, "123456", "generation"));
                verify(connection).rollback(); verify(connection, never()).commit();
            } else {
                proxy.closeWithOutbox(42, "123456", "generation");
                verify(connection).commit(); verify(connection, never()).rollback();
            }
            var order = inOrder(sessions, outbox, connection);
            order.verify(sessions).disbandSession(42L);
            order.verify(outbox).enqueue(42, "123456", "generation");
            if (fail) order.verify(connection).rollback(); else order.verify(connection).commit();
        }
    }

    @Test void enqueueWithoutTransactionIsRejected() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        assertThrows(IllegalStateException.class, () -> new RoomCloseOutbox(jdbc).enqueue(42, "123456", "generation"));
        verifyNoInteractions(jdbc);
    }
}
