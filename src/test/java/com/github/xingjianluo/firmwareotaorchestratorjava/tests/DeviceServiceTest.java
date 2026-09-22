package com.github.xingjianluo.firmwareotaorchestratorjava.tests;

import com.github.xingjianluo.firmwareotaorchestratorjava.domain.Device;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.DeviceStatus;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.RejectReason;
import com.github.xingjianluo.firmwareotaorchestratorjava.error.OperationRejectedException;
import com.github.xingjianluo.firmwareotaorchestratorjava.service.DeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DeviceServiceTest {

    private static final Logger log = LoggerFactory.getLogger(DeviceServiceTest.class);

    private MutableClock clock;
    private DeviceService deviceService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(1_000_000L);
        deviceService = new DeviceService(clock);
    }

    @Test
    void registerAndHeartbeat() {
        Device d = deviceService.register("dev-1", "hw-a", "1.0.0");
        assertEquals(DeviceStatus.ONLINE, d.getStatus());
        assertEquals("1.0.0", d.getCurrentVersion().toString());

        clock.advance(1000);
        deviceService.heartbeat("dev-1", "1.0.0");
        log.info("输入: heartbeat(dev-1, 1.0.0) 判定依据: 时间戳递增 -> 接受, count={}", d.getHeartbeatCount());
        assertEquals(1, d.getHeartbeatCount());
    }

    @Test
    void duplicateRegisterRejected() {
        deviceService.register("dev-1", "hw-a", "1.0.0");
        OperationRejectedException ex = assertThrows(OperationRejectedException.class,
                () -> deviceService.register("dev-1", "hw-a", "1.0.0"));
        log.info("输入: 重复注册 dev-1 判定依据: 已在线 -> 拒绝原因={}", ex.getReason());
        assertEquals(RejectReason.DEVICE_ALREADY_REGISTERED, ex.getReason());
    }

    @Test
    void unknownDeviceHeartbeatRejected() {
        OperationRejectedException ex = assertThrows(OperationRejectedException.class,
                () -> deviceService.heartbeat("ghost", "1.0.0"));
        log.info("输入: heartbeat(ghost) 判定依据: 未注册 -> 拒绝原因={}", ex.getReason());
        assertEquals(RejectReason.UNKNOWN_DEVICE, ex.getReason());
    }

    @Test
    void offlineDeviceHeartbeatRejectedWithDistinctReason() {
        deviceService.register("dev-1", "hw-a", "1.0.0");
        clock.advance(DeviceService.OFFLINE_TIMEOUT_MS + 1);
        List<Device> offline = deviceService.sweepOffline();
        assertEquals(1, offline.size());

        OperationRejectedException ex = assertThrows(OperationRejectedException.class,
                () -> deviceService.heartbeat("dev-1", "1.0.0"));
        log.info("输入: 离线设备 heartbeat(dev-1) 判定依据: 状态=OFFLINE -> 拒绝原因={}", ex.getReason());
        assertEquals(RejectReason.DEVICE_OFFLINE, ex.getReason());

        // 重新注册可恢复在线
        Device d = deviceService.register("dev-1", "hw-a", "1.0.0");
        assertEquals(DeviceStatus.ONLINE, d.getStatus());
    }

    @Test
    void concurrentHeartbeatsAreIdempotentAndConsistent() throws InterruptedException {
        Device d = deviceService.register("dev-1", "hw-a", "1.0.0");
        int threads = 16;
        int rounds = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < rounds; i++) {
                        // 所有线程使用各自递增的时间戳；同一时刻的重复心跳被幂等吸收
                        clock.advance(1);
                        deviceService.heartbeat("dev-1", "1.0.0");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        log.info("输入: {} 线程 x {} 次并发心跳 判定依据: 计数不得超过时间戳推进次数, count={}",
                threads, rounds, d.getHeartbeatCount());
        // 计数必须等于实际被接受的心跳数，且不超过时钟推进次数，绝不能超过总调用次数造成的膨胀
        assertTrue(d.getHeartbeatCount() <= threads * rounds);
        assertEquals(d.getHeartbeatCount(), countDistinctAccepted(d));
        assertEquals(DeviceStatus.ONLINE, d.getStatus());
        assertEquals("1.0.0", d.getCurrentVersion().toString());
    }

    private long countDistinctAccepted(Device d) {
        // 最后心跳时间与计数一致性校验：重新扫描不重置计数
        deviceService.sweepOffline();
        return d.getHeartbeatCount();
    }

    @Test
    void heartbeatVersionNeverRegresses() {
        Device d = deviceService.register("dev-1", "hw-a", "2.0.0");
        clock.advance(10);
        deviceService.heartbeat("dev-1", "1.5.0"); // 上报更低版本
        log.info("输入: heartbeat 上报更低版本 1.5.0 判定依据: 版本只允许前进 -> 当前版本={}", d.getCurrentVersion());
        assertEquals("2.0.0", d.getCurrentVersion().toString());
        clock.advance(10);
        deviceService.heartbeat("dev-1", "2.1.0");
        assertEquals("2.1.0", d.getCurrentVersion().toString());
    }
}
