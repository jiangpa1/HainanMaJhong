package hainanMahjong.monitoring;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CapacityAspect {
    private final CapacityMetrics metrics;
    public CapacityAspect(CapacityMetrics metrics) { this.metrics = metrics; }
    @Around("execution(* hainanMahjong.mapper..*(..)) || execution(* com.baomidou.mybatisplus.core.mapper.BaseMapper+.*(..))")
    public Object database(ProceedingJoinPoint call) throws Throwable { return measure(call, "mahjong.db.query"); }
    @Around("execution(* hainanMahjong.service.impl.RoundPersister.saveHand(..)) || execution(* hainanMahjong.service.impl.RoundPersister.disbandSession(..)) || execution(* hainanMahjong.service.impl.RoundPersister.closeWithOutbox(..))")
    public Object transaction(ProceedingJoinPoint call) throws Throwable { return measure(call, "mahjong.db.transaction"); }
    @Around("execution(* hainanMahjong.repository.RoomSnapshotRepository.*(..))")
    public Object snapshot(ProceedingJoinPoint call) throws Throwable { return measure(call, "mahjong.room.snapshot"); }
    private Object measure(ProceedingJoinPoint call, String name) throws Throwable {
        long start = System.nanoTime(); String outcome = "success";
        try {
            Object result = call.proceed();
            if (Boolean.FALSE.equals(result)) outcome = "rejected";
            return result;
        } catch (Throwable error) { outcome = "error"; throw error; }
        finally {
            String operation = call.getSignature().getName();
            metrics.time(name, System.nanoTime() - start, "operation", operation, "outcome", outcome);
        }
    }
}
