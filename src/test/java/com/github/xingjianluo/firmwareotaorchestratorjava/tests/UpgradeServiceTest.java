package com.github.xingjianluo.firmwareotaorchestratorjava.tests;

import com.github.xingjianluo.firmwareotaorchestratorjava.dto.CreateTaskRequest;
import com.github.xingjianluo.firmwareotaorchestratorjava.dto.ReportRequest;
import com.github.xingjianluo.firmwareotaorchestratorjava.dto.ReportResponse;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.RejectReason;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.ReportType;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.RollbackReason;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.TaskStatus;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.UpgradePhase;
import com.github.xingjianluo.firmwareotaorchestratorjava.exception.RejectedException;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.Device;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.RolloutTask;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.TaskBatch;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.UpgradeRecord;
import com.github.xingjianluo.firmwareotaorchestratorjava.service.CompatibilityService;
import com.github.xingjianluo.firmwareotaorchestratorjava.service.DeviceService;
import com.github.xingjianluo.firmwareotaorchestratorjava.service.RolloutService;
import com.github.xingjianluo.firmwareotaorchestratorjava.service.UpgradeService;
import com.github.xingjianluo.firmwareotaorchestratorjava.store.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradeServiceTest {

    private static final Logger log = LoggerFactory.getLogger(UpgradeServiceTest.class);

    private final AtomicLong now = new AtomicLong(3_000_000L);
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

    private InMemoryStore store;
    private DeviceService deviceService;
    private RolloutService rolloutService;
    private UpgradeService upgradeService;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        deviceService = new DeviceService(store, clock);
        rolloutService = new RolloutService(store, deviceService, new CompatibilityService(), clock);
        upgradeService = new UpgradeService(store, deviceService, new CompatibilityService(), clock);
    }

    private RolloutTask createTask(int batchSize, int deviceFailureThreshold, double taskRate) {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTargetVersion("2.0.0");
        req.setMinSourceVersion("1.0.0");
        req.setMaxSourceVersion("1.9.9");
        req.setBatchSize(batchSize);
        req.setDeviceFailureThreshold(deviceFailureThreshold);
        req.setTaskFailureRateThreshold(taskRate);
        return rolloutService.createTask(req);
    }

    private String setupOneDeviceTask() {
        deviceService.register("d1", "Box-A", "1.0.0");
        RolloutTask task = createTask(2, 1, 0.5);
        rolloutService.advance(task.getId());
        return task.getId();
    }

    private ReportRequest report(String deviceId, String taskId, ReportType type) {
        ReportRequest req = new ReportRequest();
        req.setDeviceId(deviceId);
        req.setTaskId(taskId);
        req.setType(type);
        return req;
    }

    private UpgradeRecord record(String taskId, String deviceId) {
        return store.upgrades().get(RolloutService.upgradeKey(taskId, deviceId));
    }

    @Test
    void happyFlow_phasesAdvance_andVersionLandsOnActivation() {
        String taskId = setupOneDeviceTask();
        ReportType[] seq = {
                ReportType.DOWNLOAD_STARTED, ReportType.DOWNLOAD_SUCCESS,
                ReportType.INSTALL_STARTED, ReportType.INSTALL_SUCCESS,
                ReportType.ACTIVATE_STARTED, ReportType.ACTIVATE_SUCCESS
        };
        UpgradePhase[] expected = {
                UpgradePhase.DOWNLOADING, UpgradePhase.DOWNLOADED,
                UpgradePhase.INSTALLING, UpgradePhase.INSTALLED,
                UpgradePhase.ACTIVATING, UpgradePhase.ACTIVATED
        };
        for (int i = 0; i < seq.length; i++) {
            ReportResponse resp = upgradeService.report(report("d1", taskId, seq[i]));
            log.info("输入 type={} => 阶段={} duplicate={}", seq[i], resp.getPhase(), resp.isDuplicate());
            assertEquals(expected[i], resp.getPhase());
        }
        Device d = deviceService.get("d1");
        assertEquals("2.0.0", d.getCurrentVersion());
        assertEquals("2.0.0", d.getStableVersion());
        TaskBatch batch = store.tasks().get(taskId).getBatches().get(0);
        assertTrue(batch.isClosed(), "全部收敛后批次应关闭");
        assertEquals(1, batch.getSuccessCount());
        assertEquals(TaskStatus.COMPLETED, store.tasks().get(taskId).getStatus());
    }

    @Test
    void illegalTransition_rejectedWithDistinctReason() {
        String taskId = setupOneDeviceTask();
        // ASSIGNED 阶段直接上报 INSTALL_SUCCESS：非法转换
        RejectedException ex = assertThrows(RejectedException.class,
                () -> upgradeService.report(report("d1", taskId, ReportType.INSTALL_SUCCESS)));
        log.info("非法转换拒绝 reason={} message={}", ex.getReason(), ex.getMessage());
        assertEquals(RejectReason.ILLEGAL_PHASE_TRANSITION, ex.getReason());
        assertEquals(UpgradePhase.ASSIGNED, record(taskId, "d1").getPhase());
    }

    @Test
    void staleReport_afterActivation_isIdempotentDuplicate() {
        String taskId = setupOneDeviceTask();
        for (ReportType t : List.of(ReportType.DOWNLOAD_STARTED, ReportType.DOWNLOAD_SUCCESS,
                ReportType.INSTALL_STARTED, ReportType.INSTALL_SUCCESS,
                ReportType.ACTIVATE_STARTED, ReportType.ACTIVATE_SUCCESS)) {
            upgradeService.report(report("d1", taskId, t));
        }
        // 迟到的下载失败 / 重复激活成功
        ReportResponse lateFail = upgradeService.report(report("d1", taskId, ReportType.DOWNLOAD_FAILED));
        ReportResponse dupSuccess = upgradeService.report(report("d1", taskId, ReportType.ACTIVATE_SUCCESS));
        log.info("迟到失败 duplicate={} phase={}; 重复成功 duplicate={} phase={}",
                lateFail.isDuplicate(), lateFail.getPhase(), dupSuccess.isDuplicate(), dupSuccess.getPhase());
        assertTrue(lateFail.isDuplicate());
        assertTrue(dupSuccess.isDuplicate());
        assertEquals(UpgradePhase.ACTIVATED, record(taskId, "d1").getPhase());
        assertEquals("2.0.0", deviceService.get("d1").getCurrentVersion());
    }

    @Test
    void downloadFailure_hitsThreshold_triggersRollbackOnce_andStateSelfConsistent() {
        String taskId = setupOneDeviceTask();
        upgradeService.report(report("d1", taskId, ReportType.DOWNLOAD_STARTED));
        ReportResponse fail1 = upgradeService.report(report("d1", taskId, ReportType.DOWNLOAD_FAILED));
        log.info("第一次失败 => phase={} rolledBack={} version={}",
                fail1.getPhase(), fail1.isRolledBack(), fail1.getCurrentVersion());
        assertTrue(fail1.isRolledBack());
        assertEquals(UpgradePhase.ROLLED_BACK, fail1.getPhase());
        assertEquals(RollbackReason.DEVICE_FAILURE_THRESHOLD, record(taskId, "d1").getRollbackReason());

        // 重复失败：不得二次回滚、版本不倒退、批次归属不变
        ReportResponse fail2 = upgradeService.report(report("d1", taskId, ReportType.DOWNLOAD_FAILED));
        ReportResponse fail3 = upgradeService.report(report("d1", taskId, ReportType.INSTALL_FAILED));
        log.info("重复/迟到失败 duplicate={}/{} phase={} rolledBack={}",
                fail2.isDuplicate(), fail3.isDuplicate(), fail2.getPhase(), fail2.isRolledBack());
        assertTrue(fail2.isDuplicate());
        assertTrue(fail3.isDuplicate());
        Device d = deviceService.get("d1");
        assertEquals("1.0.0", d.getCurrentVersion(), "回滚后版本必须是 stableVersion 1.0.0");
        assertEquals(UpgradePhase.ROLLED_BACK, record(taskId, "d1").getPhase());
        assertEquals(0, record(taskId, "d1").getBatchIndex(), "批次归属保持不变");
        assertEquals(1, record(taskId, "d1").getFailureCount(), "重复失败不得重复计数");
        TaskBatch batch = store.tasks().get(taskId).getBatches().get(0);
        assertEquals(1, batch.getFailedCount(), "批次失败计数不得因重复上报增加");
        assertTrue(batch.isClosed());
    }

    @Test
    void belowThreshold_failureDoesNotRollback_andRetrySucceeds() {
        deviceService.register("d1", "Box-A", "1.0.0");
        RolloutTask task = createTask(2, 3, 0.5);
        rolloutService.advance(task.getId());
        upgradeService.report(report("d1", task.getId(), ReportType.DOWNLOAD_STARTED));
        ReportResponse f1 = upgradeService.report(report("d1", task.getId(), ReportType.DOWNLOAD_FAILED));
        ReportResponse f2 = upgradeService.report(report("d1", task.getId(), ReportType.DOWNLOAD_FAILED));
        // 阈值 3：前两次失败（第二次为 FAILED 态重复事件，幂等不计数）——只允许计数 1
        log.info("阈值3 第一次失败 phase={} count={} rolledBack={}",
                f1.getPhase(), record(task.getId(), "d1").getFailureCount(), f1.isRolledBack());
        assertFalse(f1.isRolledBack());
        // 重试下载并成功走完全程
        upgradeService.report(report("d1", task.getId(), ReportType.DOWNLOAD_STARTED));
        upgradeService.report(report("d1", task.getId(), ReportType.DOWNLOAD_SUCCESS));
        upgradeService.report(report("d1", task.getId(), ReportType.INSTALL_STARTED));
        upgradeService.report(report("d1", task.getId(), ReportType.INSTALL_SUCCESS));
        upgradeService.report(report("d1", task.getId(), ReportType.ACTIVATE_STARTED));
        ReportResponse ok = upgradeService.report(report("d1", task.getId(), ReportType.ACTIVATE_SUCCESS));
        assertEquals(UpgradePhase.ACTIVATED, ok.getPhase());
        assertEquals("2.0.0", ok.getCurrentVersion());
    }

    @Test
    void taskFailureRateExceeded_abortsAndStopsFurtherBatches() {
        // 阈值 0.5：2 台中 2 台失败（率 1.0）即中止；batchSize=2，一次纳入全部
        deviceService.register("d1", "Box-A", "1.0.0");
        deviceService.register("d2", "Box-A", "1.0.0");
        RolloutTask task = createTask(2, 1, 0.5);
        TaskBatch batch = rolloutService.advance(task.getId());
        assertEquals(2, batch.getDeviceIds().size());

        for (String id : batch.getDeviceIds()) {
            upgradeService.report(report(id, task.getId(), ReportType.DOWNLOAD_STARTED));
            upgradeService.report(report(id, task.getId(), ReportType.DOWNLOAD_FAILED));
        }
        log.info("两台均失败后任务状态={} abortReason={}",
                task.getStatus(), task.getAbortReason());
        assertEquals(TaskStatus.ABORTED, task.getStatus());

        RejectedException ex = assertThrows(RejectedException.class,
                () -> rolloutService.advance(task.getId()));
        assertEquals(RejectReason.TASK_NOT_IN_PROGRESS, ex.getReason());
        log.info("中止后推进拒绝 reason={}", ex.getReason());

        // 新注册兼容设备也无法再被编排
        deviceService.register("d3", "Box-A", "1.0.0");
        assertThrows(RejectedException.class, () -> rolloutService.advance(task.getId()));
    }

    @Test
    void unknownAndNonParticipant_reportsRejectedDistinctly() {
        String taskId = setupOneDeviceTask();
        RejectedException unknownDevice = assertThrows(RejectedException.class,
                () -> upgradeService.report(report("ghost", taskId, ReportType.DOWNLOAD_STARTED)));
        assertEquals(RejectReason.DEVICE_NOT_FOUND, unknownDevice.getReason());

        deviceService.register("outsider", "Box-A", "1.0.0");
        RejectedException notParticipant = assertThrows(RejectedException.class,
                () -> upgradeService.report(report("outsider", taskId, ReportType.DOWNLOAD_STARTED)));
        assertEquals(RejectReason.NOT_TASK_PARTICIPANT, notParticipant.getReason());

        RejectedException unknownTask = assertThrows(RejectedException.class,
                () -> upgradeService.report(report("d1", "nope", ReportType.DOWNLOAD_STARTED)));
        assertEquals(RejectReason.TASK_NOT_FOUND, unknownTask.getReason());
        log.info("三类拒绝原因可区分: {}/{}/{}", unknownDevice.getReason(),
                notParticipant.getReason(), unknownTask.getReason());
    }

    @Test
    void higherStageFailure_receivedEarly_isStaleNotIllegal() {
        String taskId = setupOneDeviceTask();
        // 尚在 ASSIGNED，就收到激活失败：过期事件，幂等忽略，不当作非法转换
        ReportResponse resp = upgradeService.report(report("d1", taskId, ReportType.ACTIVATE_FAILED));
        log.info("早到的高阶失败 => duplicate={} phase={}", resp.isDuplicate(), resp.getPhase());
        assertTrue(resp.isDuplicate());
        assertEquals(UpgradePhase.ASSIGNED, record(taskId, "d1").getPhase());
    }

    @Test
    void offlineDevice_reportRejected() {
        String taskId = setupOneDeviceTask();
        now.addAndGet(DeviceService.OFFLINE_TIMEOUT_MS + 1);
        RejectedException ex = assertThrows(RejectedException.class,
                () -> upgradeService.report(report("d1", taskId, ReportType.DOWNLOAD_STARTED)));
        log.info("离线设备上报拒绝 reason={}", ex.getReason());
        assertEquals(RejectReason.DEVICE_OFFLINE, ex.getReason());
        assertEquals(UpgradePhase.ASSIGNED, record(taskId, "d1").getPhase(), "拒绝不得改写阶段");
    }

    @Test
    void concurrentFailureReports_triggerExactlyOneRollback() throws Exception {
        String taskId = setupOneDeviceTask();
        upgradeService.report(report("d1", taskId, ReportType.DOWNLOAD_STARTED));
        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReportResponse>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return upgradeService.report(report("d1", taskId, ReportType.DOWNLOAD_FAILED));
            }));
        }
        log.info("输入: {} 个并发 DOWNLOAD_FAILED; 判定依据: task->record->device 锁序 + rolledBack 单次置位", n);
        start.countDown();
        int rollbacks = 0;
        int duplicates = 0;
        for (Future<ReportResponse> f : futures) {
            ReportResponse r = f.get();
            if (r.isRolledBack() && !r.isDuplicate()) {
                rollbacks++;
            }
            if (r.isDuplicate()) {
                duplicates++;
            }
        }
        pool.shutdown();
        log.info("判定结果: 实际触发回滚={} 次(期望1), 幂等忽略={} 次, failureCount={}, version={}",
                rollbacks, duplicates, record(taskId, "d1").getFailureCount(),
                deviceService.get("d1").getCurrentVersion());
        assertEquals(1, rollbacks);
        assertEquals(n - 1, duplicates);
        assertEquals(1, record(taskId, "d1").getFailureCount());
        assertEquals("1.0.0", deviceService.get("d1").getCurrentVersion());
        assertEquals(UpgradePhase.ROLLED_BACK, record(taskId, "d1").getPhase());
    }
}
