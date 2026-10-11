package hainanMahjong.monitoring;

import hainanMahjong.mapper.UserMapper;
import hainanMahjong.pojo.User;
import hainanMahjong.room.RoomRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CapacityAspectTest {
    @Test void timesInheritedMybatisMethodsAndPreservesDatabaseFailure() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        UserMapper target = mock(UserMapper.class);
        when(target.selectById(1L)).thenReturn(new User());
        when(target.selectById(2L)).thenThrow(new IllegalStateException("database unavailable"));
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setInterfaces(UserMapper.class);
        factory.addAspect(new CapacityAspect(new CapacityMetrics(meters, new RoomRegistry())));
        UserMapper proxy = factory.getProxy();
        assertNotNull(proxy.selectById(1L));
        assertThrows(IllegalStateException.class, () -> proxy.selectById(2L));
        assertEquals(1, meters.get("mahjong.db.query").tags("operation", "selectById", "outcome", "success").timer().count());
        assertEquals(1, meters.get("mahjong.db.query").tags("operation", "selectById", "outcome", "error").timer().count());
    }
}
