package com.github.xingjianluo.firmwareotaorchestratorjava.tests;

import com.github.xingjianluo.firmwareotaorchestratorjava.domain.Device;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.DevicePhase;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.DeviceUpgradeState;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.RejectReason;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.ReleaseTask;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.ReportResult;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.TaskStatus;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.UpgradeStage;
import com.github.xingjianluo.firmwareotaorchestratorjava.error.OperationRejectedException;
import com.github.xingjianluo.firmwareotaorchestratorjava.service.DeviceService;
import com.github.xingjianluo.firmwareotaorchestratorjava.service.RolloutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RolloutServiceTest {

    private static final Logger log = LoggerFactory.getLogger(RolloutServiceTest.class);

    private MutableClock clock;
    private DeviceService deviceService;
    private RolloutService rolloutService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(1_000_000L);
        deviceService = new DeviceService(clock);
        rolloutService = new RolloutService(deviceService, clock);
    }

    private ReleaseTask newTask(String taskId, int batchSize, int failureThreshold) {
        ReleaseTask task = rolloutService.createTask(taskId, "2.0.0", "1.0.0", "1.9.9",
                Set.of("hw-a"), batchSize, failureThreshold);
        rolloutService.startTask(taskId);
        return task;
    }

    private void succeedFully(String taskId, String deviceId) {
        rolloutService.report(taskId, deviceId, UpgradeStage.DOWNLOAD, ReportResult.SUCCESS);
        clock.advance(1);
        rolloutService.report(taskId, deviceId, UpgradeStage.INSTALL, ReportResult.SUCCESS);
        clock.advance(1);
        rolloutService.report(taskId, deviceId, UpgradeStage.ACTIVATE, ReportResult.SUCCESS);
        clock.advance(1);
    }

    @Test
    void incompatibleDevicesAreExcludedWithReason() {
        deviceService.register("dev-ok", "hw-a", "1.5.0");
        deviceService.register("dev-hw", "hw-b", "1.5.0");       // 硬件不兼容
        deviceService.register("dev-ver", "hw-a", "0.9.0");      // 版本低于区间
        deviceService.register("dev-off", "hw-a", "1.5.0");      // 离线
        clock.advance(DeviceService.OFFLINE_TIMEOUT_MS + 1);
        deviceService.sweepOffline();
        // dev-off 离线后，dev-ok 等也因同一时钟全部离线——重新注册前三个使其在线
        deviceService.register("dev-ok", "hw-a", "1.5.0");
        deviceService.register("dev-hw", "hw-b", "1.5.0");
        deviceService.register("dev-ver", "hw-a", "0.9.0");

        ReleaseTask task = newTask("t1", 10, 2);
        rolloutService.advanceBatch("t1");

        assertEquals(DevicePhase.PENDING, task.getDeviceState("dev-ok").getPhase());
        assertEquals(DevicePhase.EXCLUDED, task.getDeviceState("dev-hw").getPhase());
        assertEquals(DevicePhase.EXCLUDED, task.getDeviceState("dev-ver").getPhase());
        assertEquals(DevicePhase.EXCLUDED, task.getDeviceState("dev-off").getPhase());
        log.info("输入: 4 台设备(硬件不兼容/版本越界/离线) 判定依据: 排除原因={}/{}/{}",
                task.getDeviceState("dev-hw").getExclusionReason(),
                task.getDeviceState("dev-ver").getExclusionReason(),
                task.getDeviceState("dev-off").getExclusionReason());
        assertNotNull(task.getDeviceState("dev-hw").getExclusionReason());
        assertNotNull(task.getDeviceState("dev-ver").getExclusionReason());
        assertNotNull(task.getDeviceState("dev-off").getExclusionReason());
    }

    @Test
    void batchAdvanceIsResumableAndNeverDuplicates() {
        for (int i = 1; i <= 5; i++) {
            deviceService.register("dev-" + i, "hw-a", "1.0.0");
        }
        ReleaseTask task = newTask("t1", 2, 3);
        rolloutService.advanceBatch("t1");
        assertEquals(1, task.getCurrentBatch());
        long pending1 = task.progress().getOrDefault(DevicePhase.PENDING, 0L);
        assertEquals(2, pending1);

        rolloutService.advanceBatch("t1");
        assertEquals(2, task.getCurrentBatch());
        rolloutService.advanceBatch("t1");
        assertEquals(3, task.getCurrentBatch());
        // 第 4 次推进：无新设备，批次号不再增长
        rolloutService.advanceBatch("t1");
        assertEquals(3, task.getCurrentBatch());

        // 每台设备恰好属于一个批次
        for (int i = 1; i <= 5; i++) {
            DeviceUpgradeState s = task.getDeviceState("dev-" + i);
            assertTrue(s.getBatch() >= 1, "设备应已入批: dev-" + i);
        }
        log.info("输入: 5 台设备 batchSize=2 连续推进 判定依据: 批次归属唯一, progress={}", task.progress());

        // 成功上报后再次推进，同一设备不会重复入批
        succeedFully("t1", "dev-1");
        rolloutService.advanceBatch("t1");
        assertEquals(3, task.getCurrentBatch());
        assertEquals(DevicePhase.COMPLETED, task.getDeviceState("dev-1").getPhase());
        assertEquals(1, task.getDeviceState("dev-1").getBatch());
    }

    @Test
    void duplicateSuccessReportIsIdempotent() {
        deviceService.register("dev-1", "hw-a", "1.0.0");
        ReleaseTask task = newTask("t1", 5, 2);
        rolloutService.advanceBatch("t1");

        rolloutService.report("t1", "dev-1", UpgradeStage.DOWNLOAD, ReportResult.SUCCESS);
        // 完全相同的重复上报：幂等吸收，不推进、不报错
        rolloutService.report("t1", "dev-1", UpgradeStage.DOWNLOAD, ReportResult.SUCCESS);
        assertEquals(DevicePhase.DOWNLOADED, task.getDeviceState("dev-1").getPhase());
        log.info("输入: 重复 DOWNLOAD/SUCCESS 判定依据: 指纹相同 -> 幂等, phase={}",
                task.getDeviceState("dev-1").getPhase());
    }

    @Test
    void illegalAndStaleTransitionsRejectedWithDistinctReasons() {
        deviceService.register("dev-1", "hw-a", "1.0.0");
        newTask("t1", 5, 2);
        rolloutService.advanceBatch("t1");

        // 跳阶段：PENDING 直接 INSTALL
        OperationRejectedException jump = assertThrows(OperationRejectedException.class,
                () -> rolloutService.report("t1", "dev-1", UpgradeStage.INSTALL, ReportResult.SUCCESS));
        log.info("输入: PENDING->INSTALL 跳阶段 判定依据: 期望阶段序号=0 实际=1 -> 原因={}", jump.getReason());
        assertEquals(RejectReason.ILLEGAL_TRANSITION, jump.getReason());

        rolloutService.report("t1", "dev-1", UpgradeStage.DOWNLOAD, ReportResult.SUCCESS);
        clock.advance(1);
        rolloutService.report("t1", "dev-1", UpgradeStage.INSTALL, ReportResult.SUCCESS);
        clock.advance(1);
        // 迟到上报：已完成 INSTALL，又收到 DOWNLOAD 的成功（与上次指纹不同）
        OperationRejectedException stale = assertThrows(OperationRejectedException.class,
                () -> rolloutService.report("t1", "dev-1", UpgradeStage.DOWNLOAD, ReportResult.FAILURE));
        log.info("输入: INSTALL 完成后迟到的 DOWNLOAD/FAILURE 判定依据: 阶段序号回退 -> 原因={}", stale.getReason());
        assertEquals(RejectReason.STALE_REPORT, stale.getReason());
        // 状态不受迟到上报影响
        assertEquals(DevicePhase.INSTALLED, rolloutService.getTask("t1").getDeviceState("dev-1").getPhase());
    }

    @Test
    void failureThresholdTriggersRollbackOnce() {
        deviceService.register("dev-1", "hw-a", "1.0.0");
        deviceService.register("dev-2", "hw-a", "1.0.0");
        deviceService.register("dev-3", "hw-a", "1.0.0");
        ReleaseTask task = newTask("t1", 3, 2);
        rolloutService.advanceBatch("t1");

        // dev-1 完成升级后版本提升
        succeedFully("t1", "dev-1");
        assertEquals("2.0.0", deviceService.getDevice("dev-1").getCurrentVersion().toString());

        // dev-2、dev-3 先后失败，达到阈值 2 -> 整体回滚
        rolloutService.report("t1", "dev-2", UpgradeStage.DOWNLOAD, ReportResult.FAILURE);
        clock.advance(1);
        rolloutService.report("t1", "dev-3", UpgradeStage.DOWNLOAD, ReportResult.FAILURE);

        assertEquals(TaskStatus.ABORTED, task.getStatus());
        // 已升级的 dev-1 回滚到上一稳定版本，失败设备版本不变，批次归属保留
        assertEquals("1.0.0", deviceService.getDevice("dev-1").getCurrentVersion().toString());
        assertEquals(DevicePhase.ROLLED_BACK, task.getDeviceState("dev-1").getPhase());
        assertEquals(1, task.getDeviceState("dev-1").getBatch());
        assertEquals(DevicePhase.ROLLED_BACK, task.getDeviceState("dev-2").getPhase());
        assertEquals(DevicePhase.ROLLED_BACK, task.getDeviceState("dev-3").getPhase());
        Map<DevicePhase, Long> progress = task.progress();
        log.info("输入: 2 次失败(阈值=2) 判定依据: 触发一次性回滚, progress={}", progress);
        assertEquals(3L, progress.get(DevicePhase.ROLLED_BACK));

        // 重复/迟到的失败上报：不得造成重复回滚或状态倒退
        OperationRejectedException late = assertThrows(OperationRejectedException.class,
                () -> rolloutService.report("t1", "dev-2", UpgradeStage.INSTALL, ReportResult.FAILURE));
        log.info("输入: 回滚后迟到的失败上报 判定依据: 任务已 ABORTED -> 原因={}", late.getReason());
        assertEquals(RejectReason.TASK_ALREADY_ABORTED, late.getReason());
        assertEquals(2, task.getFailureCount());
        assertEquals("1.0.0", deviceService.getDevice("dev-1").getCurrentVersion().toString());

        // 与末次上报完全相同的重复失败上报：幂等吸收，不计数不回滚
        rolloutService.report("t1", "dev-3", UpgradeStage.DOWNLOAD, ReportResult.FAILURE);
        assertEquals(2, task.getFailureCount());
        assertEquals(3L, task.progress().get(DevicePhase.ROLLED_BACK));

        // 回滚后批次推进被拒绝
        OperationRejectedException adv = assertThrows(OperationRejectedException.class,
                () -> rolloutService.advanceBatch("t1"));
        assertEquals(RejectReason.ILLEGAL_TASK_STATE, adv.getReason());
    }

    @Test
    void concurrentDuplicateReportsStayConsistent() throws InterruptedException {
        deviceService.register("dev-1", "hw-a", "1.0.0");
        ReleaseTask task = newTask("t1", 5, 2);
        rolloutService.advanceBatch("t1");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    rolloutService.report("t1", "dev-1", UpgradeStage.DOWNLOAD, ReportResult.SUCCESS);
                    accepted.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // 并发重复上报全部被幂等处理，最终状态唯一且正确
        log.info("输入: {} 线程并发相同上报 判定依据: 最终 phase=DOWNLOADED, 实际={}",
                threads, task.getDeviceState("dev-1").getPhase());
        assertEquals(DevicePhase.DOWNLOADED, task.getDeviceState("dev-1").getPhase());
        assertEquals(threads, accepted.get());
        assertEquals(0, task.getDeviceState("dev-1").getLastAppliedStageOrder());
    }

    @Test
    void reportFromUnknownTaskOrDeviceRejected() {
        OperationRejectedException noTask = assertThrows(OperationRejectedException.class,
                () -> rolloutService.report("ghost-task", "dev-1", UpgradeStage.DOWNLOAD, ReportResult.SUCCESS));
        assertEquals(RejectReason.UNKNOWN_TASK, noTask.getReason());

        deviceService.register("dev-1", "hw-a", "1.0.0");
        newTask("t1", 5, 2);
        rolloutService.advanceBatch("t1");
        OperationRejectedException notInTask = assertThrows(OperationRejectedException.class,
                () -> rolloutService.report("t1", "dev-x", UpgradeStage.DOWNLOAD, ReportResult.SUCCESS));
        log.info("输入: 未入任务设备上报 判定依据: 任务中无该设备 -> 原因={}", notInTask.getReason());
        assertEquals(RejectReason.DEVICE_NOT_IN_TASK, notInTask.getReason());
    }

    @Test
    void excludedDeviceReportRejected() {
        deviceService.register("dev-hw", "hw-b", "1.0.0");
        ReleaseTask task = newTask("t1", 5, 2);
        rolloutService.advanceBatch("t1");
        OperationRejectedException ex = assertThrows(OperationRejectedException.class,
                () -> rolloutService.report("t1", "dev-hw", UpgradeStage.DOWNLOAD, ReportResult.SUCCESS));
        log.info("输入: 被排除设备上报 判定依据: phase=EXCLUDED -> 原因={}", ex.getReason());
        assertEquals(RejectReason.DEVICE_EXCLUDED, ex.getReason());
    }

    @Test
    void fullRolloutCompletes() {
        deviceService.register("dev-1", "hw-a", "1.0.0");
        deviceService.register("dev-2", "hw-a", "1.2.0");
        ReleaseTask task = newTask("t1", 10, 5);
        rolloutService.advanceBatch("t1");
        succeedFully("t1", "dev-1");
        succeedFully("t1", "dev-2");
        Map<DevicePhase, Long> progress = task.progress();
        log.info("输入: 全部设备成功 判定依据: progress={}", progress);
        assertEquals(2L, progress.get(DevicePhase.COMPLETED));
        Device d1 = deviceService.getDevice("dev-1");
        assertEquals("2.0.0", d1.getCurrentVersion().toString());
        assertEquals("1.0.0", d1.getLastStableVersion().toString());
    }
}
