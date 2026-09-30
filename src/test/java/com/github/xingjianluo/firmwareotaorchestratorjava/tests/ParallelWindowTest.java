package com.github.xingjianluo.firmwareotaorchestratorjava.tests;

import com.github.xingjianluo.firmwareotaorchestratorjava.dto.CreateTaskRequest;
import com.github.xingjianluo.firmwareotaorchestratorjava.dto.ReportRequest;
import com.github.xingjianluo.firmwareotaorchestratorjava.exception.RejectedException;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.ExclusionRecord;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.RolloutTask;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.TaskBatch;
import com.github.xingjianluo.firmwareotaorchestratorjava.service.CompatibilityService;
import com.github.xingjianluo.firmwareotaorchestratorjava.service.DeviceService;
import com.github.xingjianluo.firmwareotaorchestratorjava.service.RolloutService;
import com.github.xingjianluo.firmwareotaorchestratorjava.service.UpgradeService;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.RejectReason;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.ReportType;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.TaskStatus;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.UpgradePhase;
import com.github.xingjianluo.firmwareotaorchestratorjava.store.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并行推进窗口（同时在途设备上限）行为测试：
 * 一次铺满、收敛腾位补人、推进/上报并发不超上限不重复入批、中止后不补充但正常收尾、
 * 以及被排除设备条件转好后按注册顺序重新进入。
 */
class ParallelWindowTest {

    private static final Logger log = LoggerFactory.getLogger(ParallelWindowTest.class);

    private final AtomicLong now = new AtomicLong(4_000_000L);
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
        upgradeService = new UpgradeService(store, deviceService, clock);
    }

    private CreateTaskRequest parallelRequest(int batchSize, int maxInFlight,
                                              int deviceFailureThreshold, double taskRate) {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTargetVersion("2.0.0");
        req.setMinSourceVersion("1.0.0");
        req.setMaxSourceVersion("1.9.9");
        req.setHardwareWhitelist(List.of("Box-A"));
        req.setBatchSize(batchSize);
        req.setMaxInFlightDevices(maxInFlight);
        req.setDeviceFailureThreshold(deviceFailureThreshold);
        req.setTaskFailureRateThreshold(taskRate);
        return req;
    }

    private void registerDevices(int n) {
        for (int i = 1; i <= n; i++) {
            deviceService.register("d" + i, "Box-A", "1.0.0");
            // 每台设备注册时间严格递增，确保「按注册先后」顺序唯一确定（避免 Id 字符串序干扰）
            now.incrementAndGet();
        }
    }

    private ReportRequest report(String deviceId, String taskId, ReportType type) {
        ReportRequest req = new ReportRequest();
        req.setDeviceId(deviceId);
        req.setTaskId(taskId);
        req.setType(type);
        return req;
    }

    /** 模拟一台设备完整成功走完全部阶段直到 ACTIVATED。 */
    private void activate(String taskId, String deviceId) {
        upgradeService.report(report(deviceId, taskId, ReportType.DOWNLOAD_STARTED));
        upgradeService.report(report(deviceId, taskId, ReportType.DOWNLOAD_SUCCESS));
        upgradeService.report(report(deviceId, taskId, ReportType.INSTALL_STARTED));
        upgradeService.report(report(deviceId, taskId, ReportType.INSTALL_SUCCESS));
        upgradeService.report(report(deviceId, taskId, ReportType.ACTIVATE_STARTED));
        upgradeService.report(report(deviceId, taskId, ReportType.ACTIVATE_SUCCESS));
    }

    /** 模拟一台设备下载失败（阈值 1）触发回滚收敛。 */
    private void rollback(String taskId, String deviceId) {
        upgradeService.report(report(deviceId, taskId, ReportType.DOWNLOAD_STARTED));
        upgradeService.report(report(deviceId, taskId, ReportType.DOWNLOAD_FAILED));
    }

    private Map<String, Object> progress(String taskId) {
        return rolloutService.progress(taskId);
    }

    private int assignedCount() {
        return store.upgrades().size();
    }

    @Test
    void windowFillsToCap_once_openingMultipleBatches() {
        registerDevices(6);
        RolloutTask task = rolloutService.createTask(parallelRequest(2, 4, 1, 0.5));

        TaskBatch first = rolloutService.advance(task.getId());
        log.info("输入: 6 台设备, batchSize=2, 在途上限=4; 首个批次={}", first.getDeviceIds());

        // 一次推进即在窗口内铺满 4 台，并切成两个同时打开的批次
        assertEquals(2, task.getBatches().size(), "应同时打开多个批次");
        assertEquals(List.of("d1", "d2"), new ArrayList<>(task.getBatches().get(0).getDeviceIds()));
        assertEquals(List.of("d3", "d4"), new ArrayList<>(task.getBatches().get(1).getDeviceIds()));
        assertTrue(task.getBatches().get(0).isOpen());
        assertTrue(task.getBatches().get(1).isOpen());

        Map<String, Object> p = progress(task.getId());
        log.info("铺满后进度: {}", p);
        assertEquals(4, p.get("participants"));
        assertEquals(4, p.get("inFlight"));
        assertEquals(0, p.get("windowRemaining"));
        assertEquals(4, p.get("maxInFlightDevices"));
        assertTrue((Boolean) p.get("parallelWindow"));

        // d5/d6 尚未参与
        assertFalse(store.upgrades().containsKey(RolloutService.upgradeKey(task.getId(), "d5")));
        assertFalse(store.upgrades().containsKey(RolloutService.upgradeKey(task.getId(), "d6")));

        // 窗口满：再推进不补人（返回 null，而非报错），占用数不变
        assertNull(rolloutService.advance(task.getId()), "窗口满时不应新开批次");
        assertEquals(4, progress(task.getId()).get("participants"));
        assertEquals(4, progress(task.getId()).get("inFlight"));
        assertEquals(0, progress(task.getId()).get("windowRemaining"));
    }

    @Test
    void settledDevicesReleaseSlots_thenAdvanceRefillsInRegistrationOrder_andCompletes() {
        registerDevices(6);
        RolloutTask task = rolloutService.createTask(parallelRequest(2, 4, 1, 0.5));
        rolloutService.advance(task.getId()); // d1..d4 在途
        assertEquals(4, progress(task.getId()).get("inFlight"));

        // d1 激活成功 => 释放 1 个名额
        activate(task.getId(), "d1");
        assertEquals(3, progress(task.getId()).get("inFlight"));
        assertEquals(1, progress(task.getId()).get("windowRemaining"));
        TaskBatch refill1 = rolloutService.advance(task.getId());
        log.info("d1 收敛后补人批次={}, 期望优先补 d5", refill1.getDeviceIds());
        assertEquals(List.of("d5"), new ArrayList<>(refill1.getDeviceIds()),
                "空出的名额应优先分给最早注册且未参与的 d5");
        assertEquals(4, progress(task.getId()).get("inFlight"));

        // d2、d3 收敛 => 释放 2 个名额，但只剩 d6 一台可补
        activate(task.getId(), "d2");
        activate(task.getId(), "d3");
        assertEquals(2, progress(task.getId()).get("inFlight"));
        TaskBatch refill2 = rolloutService.advance(task.getId());
        log.info("d2/d3 收敛后补人批次={}, 期望补 d6", refill2.getDeviceIds());
        assertEquals(List.of("d6"), new ArrayList<>(refill2.getDeviceIds()));
        assertEquals(3, progress(task.getId()).get("inFlight"));

        // 所有设备最终都参与、无遗漏、无重复
        assertEquals(6, assignedCount());
        Set<String> allBatched = new HashSet<>();
        for (TaskBatch b : task.getBatches().values()) {
            for (String id : b.getDeviceIds()) {
                assertTrue(allBatched.add(id), "设备 " + id + " 被重复放入批次");
            }
        }
        assertEquals(6, allBatched.size());

        // 剩余在途设备全部收敛 => 无在途且无候选，任务自然收尾
        activate(task.getId(), "d4");
        activate(task.getId(), "d5");
        activate(task.getId(), "d6");
        Map<String, Object> end = progress(task.getId());
        log.info("全部收敛后进度: status={} inFlight={} remaining={}",
                end.get("status"), end.get("inFlight"), end.get("windowRemaining"));
        assertEquals(TaskStatus.COMPLETED, end.get("status"));
        assertEquals(0, end.get("inFlight"));
        assertEquals(4, end.get("windowRemaining"));
        assertEquals(6, end.get("participants"));
    }

    @Test
    void concurrentAdvanceAndReports_neverExceedCap_andNeverDoubleAssign() throws Exception {
        int deviceCount = 20;
        int cap = 5;
        registerDevices(deviceCount);
        RolloutTask task = rolloutService.createTask(parallelRequest(2, cap, 1, 0.5));
        String taskId = task.getId();

        // 先铺满
        rolloutService.advance(taskId);
        assertEquals(cap, progress(taskId).get("inFlight"));

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicBoolean violated = new AtomicBoolean(false);
        AtomicInteger activateCursor = new AtomicInteger(1);
        // 容量 = 1 个监控 + 4 个推进 + 7 个上报，避免监控线程长期占位把工作任务饿死
        ExecutorService pool = Executors.newFixedThreadPool(13);
        CountDownLatch start = new CountDownLatch(1);

        // 监控线程：在整个并发期间持续读取一致快照，在途数绝不允许超过窗口上限
        Thread monitor = new Thread(() -> {
            while (!stop.get()) {
                int inFlight = (Integer) progress(taskId).get("inFlight");
                if (inFlight > cap) {
                    violated.set(true);
                    log.error("超上限! inFlight={} cap={}", inFlight, cap);
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "window-monitor");
        monitor.setDaemon(true);
        monitor.start();

        // 4 个推进线程：任务真正收尾（COMPLETED）前持续尝试补人。
        // 注意退出条件不能是「全部已分配」——最后一批入批瞬间计数即满，
        // 但此时仍有设备在途，需要继续推进/等待收敛。
        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                while (task.getStatus() == TaskStatus.CREATED
                        || task.getStatus() == TaskStatus.IN_PROGRESS) {
                    try {
                        rolloutService.advance(taskId);
                    } catch (RejectedException e) {
                        // 任务在并发中被收尾/中止，推进拒绝即结束
                        assertEquals(RejectReason.TASK_NOT_IN_PROGRESS, e.getReason());
                        break;
                    }
                    // 退避让出 task 锁，避免窗口满时空转把上报线程饿死（防活锁）
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        // 7 个上报线程：按设备依次领取，等其入批后完整激活
        for (int i = 0; i < 7; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                int idx;
                while ((idx = activateCursor.getAndIncrement()) <= deviceCount) {
                    String id = "d" + idx;
                    // 持续等待推进线程把该设备放入某个批次，绝不提前对未入批设备上报
                    while (!store.upgrades().containsKey(RolloutService.upgradeKey(taskId, id))
                            && task.getStatus() != TaskStatus.ABORTED) {
                        try {
                            Thread.sleep(2);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (store.upgrades().containsKey(RolloutService.upgradeKey(taskId, id))) {
                        activate(taskId, id);
                    }
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发用例应在超时前收敛结束");
        stop.set(true);

        assertFalse(violated.get(), "并发推进+上报期间在途数绝不能超过窗口上限");
        Map<String, Object> end = progress(taskId);
        log.info("并发收敛结果: participants={} inFlight={} status={} batches={}",
                end.get("participants"), end.get("inFlight"), end.get("status"),
                task.getBatches().size());
        assertEquals(deviceCount, end.get("participants"), "所有设备都应参与，无遗漏");
        assertEquals(0, end.get("inFlight"));
        for (int i = 1; i <= deviceCount; i++) {
            assertEquals(UpgradePhase.ACTIVATED,
                    store.upgrades().get(RolloutService.upgradeKey(taskId, "d" + i)).getPhase());
        }
        // 不重复入批：所有批次设备两两不相交
        Set<String> seen = new HashSet<>();
        for (TaskBatch b : task.getBatches().values()) {
            for (String id : b.getDeviceIds()) {
                assertTrue(seen.add(id), "设备 " + id + " 被重复放入批次");
            }
        }
        assertEquals(deviceCount, seen.size());
    }

    @Test
    void afterAbort_windowStopsFilling_butInFlightDevicesDrainNormally() {
        registerDevices(6);
        // 阈值 0.2（严格大于）：4 台中 1 台回滚 => 0.25 即中止
        RolloutTask task = rolloutService.createTask(parallelRequest(2, 4, 1, 0.2));
        String taskId = task.getId();
        rolloutService.advance(taskId); // d1..d4 在途
        assertEquals(4, progress(taskId).get("inFlight"));

        rollback(taskId, "d1");
        log.info("d1 回滚后任务状态={} reason={}", task.getStatus(), task.getAbortReason());
        assertEquals(TaskStatus.ABORTED, task.getStatus());

        // 中止后推进直接拒绝，不再补新设备
        RejectedException ex = assertThrowsAbort(taskId);
        assertEquals(RejectReason.TASK_NOT_IN_PROGRESS, ex.getReason());

        // 已在途的 3 台仍可正常把升级跑完
        activate(taskId, "d2");
        activate(taskId, "d3");
        activate(taskId, "d4");

        Map<String, Object> end = progress(taskId);
        log.info("中止后在途收敛: status={} inFlight={} remaining={} participants={} rolledBack={} success={}",
                end.get("status"), end.get("inFlight"), end.get("windowRemaining"),
                end.get("participants"), end.get("rolledBack"), end.get("success"));
        assertEquals(TaskStatus.ABORTED, end.get("status"), "中止任务不得被改写成完成");
        assertEquals(0, end.get("inFlight"), "在途设备应能全部正常收敛");
        assertEquals(4, end.get("windowRemaining"), "名额随收敛正确释放");
        assertEquals(4, end.get("participants"));
        assertEquals(1, end.get("rolledBack"));
        assertEquals(3, end.get("success"));

        // 窗口外的 d5/d6 自始至终没有被补进来
        assertFalse(store.upgrades().containsKey(RolloutService.upgradeKey(taskId, "d5")));
        assertFalse(store.upgrades().containsKey(RolloutService.upgradeKey(taskId, "d6")));
        // 中止之后任何推进都被拒绝
        assertThrowsAbort(taskId);
    }

    @Test
    void deviceRollback_releasesWindowSlot_allowingRefill_withoutAbort() {
        // 阈值放宽到不会触发任务中止，单测「设备失败回滚也释放名额」
        registerDevices(4);
        RolloutTask task = rolloutService.createTask(parallelRequest(2, 3, 1, 0.9));
        String taskId = task.getId();
        rolloutService.advance(taskId); // d1,d2,d3 在途，窗口满
        assertEquals(3, progress(taskId).get("inFlight"));
        assertEquals(0, progress(taskId).get("windowRemaining"));

        // d1 下载失败触发回滚收敛：名额立即释放（注意此时尚未中止任务）
        rollback(taskId, "d1");
        assertEquals(TaskStatus.IN_PROGRESS, task.getStatus(), "单台回滚不应中止任务");
        Map<String, Object> afterRollback = progress(taskId);
        log.info("d1 回滚后未中止: inFlight={} remaining={} rolledBack={}",
                afterRollback.get("inFlight"), afterRollback.get("windowRemaining"),
                afterRollback.get("rolledBack"));
        assertEquals(2, afterRollback.get("inFlight"));
        assertEquals(1, afterRollback.get("windowRemaining"));
        assertEquals(1, afterRollback.get("rolledBack"));

        // 腾出的名额继续补给最早未参与的 d4
        TaskBatch refill = rolloutService.advance(taskId);
        assertEquals(List.of("d4"), new ArrayList<>(refill.getDeviceIds()));
        assertEquals(3, progress(taskId).get("inFlight"));
        assertEquals(0, progress(taskId).get("windowRemaining"));
        assertEquals(4, assignedCount(), "无设备被漏掉或重复");
    }

    private RejectedException assertThrowsAbort(String taskId) {
        RejectedException thrown = null;
        try {
            rolloutService.advance(taskId);
        } catch (RejectedException e) {
            thrown = e;
        }
        org.junit.jupiter.api.Assertions.assertNotNull(thrown, "中止后推进必须被拒绝");
        return thrown;
    }

    @Test
    void previouslyExcludedDevice_reentersWhenConditionImproves_inRegistrationOrder() {
        // 注册顺序：d1(随后置离线)、d2、d3、d4
        deviceService.register("d1", "Box-A", "1.0.0");
        deviceService.register("d2", "Box-A", "1.0.0");
        deviceService.register("d3", "Box-A", "1.0.0");
        deviceService.register("d4", "Box-A", "1.0.0");
        now.addAndGet(DeviceService.OFFLINE_TIMEOUT_MS + 1);
        deviceService.get("d1"); // d1 物化为离线
        // d2/d3/d4 补发新鲜心跳保持在线（不影响注册顺序）
        deviceService.heartbeat("d2", "1.0.0");
        deviceService.heartbeat("d3", "1.0.0");
        deviceService.heartbeat("d4", "1.0.0");

        RolloutTask task = rolloutService.createTask(parallelRequest(2, 2, 1, 0.5));
        String taskId = task.getId();
        rolloutService.advance(taskId); // 窗口 2：d2、d3；d1 因离线被排除
        assertEquals(List.of("d2", "d3"),
                new ArrayList<>(task.getBatches().get(0).getDeviceIds()));
        List<ExclusionRecord> exclusions = rolloutService.exclusions(taskId);
        assertEquals(1, exclusions.size());
        assertEquals("d1", exclusions.get(0).getDeviceId());
        assertNull(rolloutService.advance(taskId), "窗口满，不补人");

        // d1 重新心跳恢复在线；d2、d3 收敛腾出 2 个名额
        deviceService.heartbeat("d1", "1.0.0");
        activate(taskId, "d2");
        activate(taskId, "d3");
        TaskBatch refill = rolloutService.advance(taskId);
        log.info("d1 条件转好后补人批次={}（期望按注册顺序 [d1, d4]）", refill.getDeviceIds());

        // 之前被离线排除的 d1 没有被一直挡住，且因其注册最早排在 d4 前面
        assertEquals(List.of("d1", "d4"), new ArrayList<>(refill.getDeviceIds()));
        // 排除留痕仍然只有一条，不重复记录
        assertEquals(1, rolloutService.exclusions(taskId).size());
        assertEquals(4, assignedCount(), "没有设备被漏掉");
    }
}
