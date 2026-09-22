package com.github.xingjianluo.firmwareotaorchestratorjava.tests;

import com.github.xingjianluo.firmwareotaorchestratorjava.state.DeviceStatus;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.RejectReason;
import com.github.xingjianluo.firmwareotaorchestratorjava.exception.RejectedException;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.Device;
import com.github.xingjianluo.firmwareotaorchestratorjava.service.DeviceService;
import com.github.xingjianluo.firmwareotaorchestratorjava.store.InMemoryStore;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设备心跳：并发/重复/版本单调/离线拒绝。
 */
class DeviceServiceTest {

    private static final Logger log = LoggerFactory.getLogger(DeviceServiceTest.class);

    /** 可手动推进的时钟，保证测试不依赖真实等待。 */
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }
    };

    private DeviceService newService() {
        return new DeviceService(new InMemoryStore(), clock);
    }

    @Test
    void concurrentHeartbeats_keepCountExactAndStatusStable() throws Exception {
        DeviceService service = newService();
        service.register("dev-1", "Box-A", "1.0.0");
        int threads = 16;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    service.heartbeat("dev-1", "1.0.0");
                }
                return null;
            }));
        }
        log.info("输入: {} 线程 x {} 次并发心跳, 判定依据: 设备内置锁串行化计数+1", threads, perThread);
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        Device d = service.get("dev-1");
        log.info("判定结果: heartbeatCount={} (期望 1 注册 + {}), status={}, version={}",
                d.getHeartbeatCount(), threads * perThread, d.getStatus(), d.getCurrentVersion());
        assertEquals(1 + (long) threads * perThread, d.getHeartbeatCount());
        assertEquals(DeviceStatus.ONLINE, d.getStatus());
        assertEquals("1.0.0", d.getCurrentVersion());
    }

    @Test
    void staleHeartbeat_neverMovesTimestampOrCount() {
        DeviceService service = newService();
        service.register("dev-2", "Box-A", "1.0.0");
        long tsAfterRegister = now.get();
        Device first = service.heartbeat("dev-2", "1.0.0");
        long tsFirst = first.getLastHeartbeatAt();
        // 时钟倒退，模拟乱序/迟到心跳
        now.set(tsAfterRegister - 5_000L);
        log.info("输入: 迟到心跳 ts={} < lastTs={}, 判定依据: 时间戳单调，计数只增", now.get(), tsFirst);
        Device stale = service.heartbeat("dev-2", "1.0.0");
        assertEquals(tsFirst, stale.getLastHeartbeatAt(), "迟到心跳不得刷新时间戳");
        assertEquals(2L, stale.getHeartbeatCount(), "迟到心跳不得增加计数");
    }

    @Test
    void olderVersionHeartbeat_doesNotRollVersionBack() {
        DeviceService service = newService();
        service.register("dev-3", "Box-A", "2.0.0");
        service.heartbeat("dev-3", "1.0.0");
        log.info("输入: 设备处于 2.0.0，收到 1.0.0 心跳; 判定依据: 语义版本只接受不降");
        assertEquals("2.0.0", service.get("dev-3").getCurrentVersion());
        service.heartbeat("dev-3", "2.0.1");
        assertEquals("2.0.1", service.get("dev-3").getCurrentVersion());
    }

    @Test
    void timeoutDevice_markedOffline_andOfflineOpsRejected() {
        DeviceService service = newService();
        service.register("dev-4", "Box-A", "1.0.0");
        now.addAndGet(DeviceService.OFFLINE_TIMEOUT_MS + 1);
        Device d = service.get("dev-4");
        log.info("输入: 推进时钟超过 {}ms; 判定结果: status={}", DeviceService.OFFLINE_TIMEOUT_MS, d.getStatus());
        assertEquals(DeviceStatus.OFFLINE, d.getStatus());

        RejectedException ex = assertThrows(RejectedException.class, () -> service.requireOnline("dev-4"));
        log.info("离线设备操作拒绝原因码: {}", ex.getReason());
        assertEquals(RejectReason.DEVICE_OFFLINE, ex.getReason());
    }

    @Test
    void unknownDevice_rejectedWithDistinctReason() {
        DeviceService service = newService();
        RejectedException hbEx = assertThrows(RejectedException.class,
                () -> service.heartbeat("ghost", "1.0.0"));
        log.info("未知设备心跳拒绝原因码: {}", hbEx.getReason());
        assertEquals(RejectReason.DEVICE_NOT_FOUND, hbEx.getReason());
        assertTrue(hbEx.getMessage().contains("不存在"));
    }

    @Test
    void duplicateRegister_isIdempotent() {
        DeviceService service = newService();
        Device a = service.register("dev-5", "Box-A", "1.0.0");
        Device b = service.register("dev-5", "Box-B", "9.9.9");
        log.info("输入: 重复注册 dev-5; 判定: 幂等返回原对象，不重置型号/版本/计数");
        assertTrue(a == b);
        assertEquals("Box-A", b.getHardwareModel());
        assertEquals("1.0.0", b.getCurrentVersion());
        assertEquals(1L, b.getHeartbeatCount());
    }
}
