package com.github.xingjianluo.firmwareotaorchestratorjava.tests;

import com.github.xingjianluo.firmwareotaorchestratorjava.dto.CreateTaskRequest;
import com.github.xingjianluo.firmwareotaorchestratorjava.dto.ReportRequest;
import com.github.xingjianluo.firmwareotaorchestratorjava.exception.RejectedException;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.Device;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.RolloutTask;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.TaskBatch;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.UpgradeRecord;
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
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并行推进窗口（maxInFlight）与容量上限行为测试。
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
    private CompatibilityService compatibilityService;
    private RolloutService rolloutService;
    private UpgradeService upgradeService;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        deviceService = new DeviceService(store, clock);
        compatibilityService = new CompatibilityService();
        rolloutService = new RolloutService(store, deviceService, compatibilityService, clock);
        upgradeService = new UpgradeService(store, deviceService, compatibilityService, clock);
    }

    private void registerDevices(int n) {
        for (int i = 1; i <= n; i++) {
            deviceService.register("d" + i, "Box-A", "1.0.0");
        }
    }

    private RolloutTask createTask(int batchSize, Integer maxInFlight,
                                   int deviceFailureThreshold, double taskRate) {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTargetVersion("2.0.0");
        req.setMinSourceVersion("1.0.0");
        req.setMaxSourceVersion("1.9.9");
        req.setHardwareWhitelist(List.of("Box-A"));
        req.setBatchSize(batchSize);
        if (maxInFlight != null) {
            req.setMaxInFlight(maxInFlight);
        }
        req.setDeviceFailureThreshold(deviceFailureThreshold);
        req.setTaskFailureRateThreshold(taskRate);
        return rolloutService.createTask(req);
    }

    private ReportRequest report(String deviceId, String taskId, ReportType type) {
        ReportRequest req = new ReportRequest();
        req.setDeviceId(deviceId);
        req.setTaskId(taskId);
        req.setType(type);
        return req;
    }

    /** 把一台在途设备沿完整成功链路推到 ACTIVATED。 */
    private void driveActivated(String taskId, String deviceId) {
        upgradeService.report(report(deviceId, taskId, ReportType.DOWNLOAD_STARTED));
        upgradeService.report(report(deviceId, taskId, ReportType.DOWNLOAD_SUCCESS));
        upgradeService.report(report(deviceId, taskId, ReportType.INSTALL_STARTED));
        upgradeService.report(report(deviceId, taskId, ReportType.INSTALL_SUCCESS));
        upgradeService.report(report(deviceId, taskId, ReportType.ACTIVATE_STARTED));
        upgradeService.report(report(deviceId, taskId, ReportType.ACTIVATE_SUCCESS));
    }

    private UpgradeRecord record(String taskId, String deviceId) {
        return store.upgrades().get(RolloutService.upgradeKey(taskId, deviceId));
    }

    @Test
    void advance_onceFillsWindow_openingMultipleBatchesInRegistrationOrder() {
        registerDevices(6);
        RolloutTask task = createTask(2, 5, 1, 0.5);

        // 一次推进：窗口容量 5，按注册顺序铺满 d1..d5，同时打开 3 个批次（2/2/1）
        TaskBatch first = rolloutService.advance(task.getId());
        log.info("一次铺满: 批次0={} 批次1={} 批次2={}",
                task.getBatches().get(0).getDeviceIds(),
                task.getBatches().get(1).getDeviceIds(),
                task.getBatches().get(2).getDeviceIds());

        assertEquals(0, first.getIndex());
        assertEquals(Set.of("d1", "d2"), task.getBatches().get(0).getDeviceIds());
        assertEquals(Set.of("d3", "d4"), task.getBatches().get(1).getDeviceIds());
        assertEquals(Set.of("d5"), task.getBatches().get(2).getDeviceIds());
        assertNull(record(task.getId(), "d6"), "窗口只有 5 个名额，d6 必须留在窗外");

        Map<String, Object> p = rolloutService.progress(task.getId());
        log.info("铺满后进度: inFlight={} windowRemaining={} participants={}",
                p.get("inFlight"), p.get("windowRemaining"), p.get("participants"));
        assertEquals(5, p.get("inFlight"));
        assertEquals(0, p.get("windowRemaining"));
        assertEquals(5, p.get("maxInFlight"));

        // 窗口已满：再推进被闸门拦截，不偷偷加人
        RejectedException ex = assertThrows(RejectedException.class,
                () -> rolloutService.advance(task.getId()));
        assertEquals(RejectReason.BATCH_NOT_OPEN, ex.getReason());
        assertEquals(5, rolloutService.progress(task.getId()).get("participants"));
    }
    @Test
    void settledDevices_releaseSlots_andAdvanceRefillsByRegistrationOrder() {
        registerDevices(6);
        RolloutTask task = createTask(2, 4, 1, 0.5);

        TaskBatch first = rolloutService.advance(task.getId());
        assertEquals(Set.of("d1", "d2"), first.getDeviceIds(), "首批仍受 batchSize=2 限制");
        // 同一轮推进继续开窗：再补 d3、d4，共占满 4 个名额（两个批次并行在途）
        assertEquals(Set.of("d3", "d4"), task.getBatches().get(1).getDeviceIds());
        assertEquals(0, rolloutService.progress(task.getId()).get("windowRemaining"));

        // d1、d2 激活成功收敛，腾出 2 个名额
        driveActivated(task.getId(), "d1");
        driveActivated(task.getId(), "d2");
        Map<String, Object> p1 = rolloutService.progress(task.getId());
        log.info("d1/d2 收敛后: inFlight={} windowRemaining={}",
                p1.get("inFlight"), p1.get("windowRemaining"));
        assertEquals(2, p1.get("inFlight"));
        assertEquals(2, p1.get("windowRemaining"));

        // 再推进：空出的名额优先给未参与过、仍符合条件的 d5、d6（注册先后）
        rolloutService.advance(task.getId());
        assertEquals(Set.of("d5", "d6"), task.getBatches().get(2).getDeviceIds());
        log.info("补人后批次: 0={} 1={} 2={}",
                task.getBatches().get(0).getDeviceIds(),
                task.getBatches().get(1).getDeviceIds(),
                task.getBatches().get(2).getDeviceIds());

        // 全量断言：每台设备恰好进一个批次，既不重复也不漏
        Set<String> assigned = new LinkedHashSet<>();
        for (TaskBatch b : task.getBatches().values()) {
            for (String id : b.getDeviceIds()) {
                assertTrue(assigned.add(id), "设备 " + id + " 被重复放入批次");
            }
        }
        assertEquals(Set.of("d1", "d2", "d3", "d4", "d5", "d6"), assigned);

        // 全部收敛：最后一台激活时上报路径已自动收尾（无更多可补设备）
        for (String id : List.of("d3", "d4", "d5", "d6")) {
            driveActivated(task.getId(), id);
        }
        assertEquals(TaskStatus.COMPLETED, task.getStatus());
        assertEquals(6, rolloutService.progress(task.getId()).get("success"));
    }
    @Test
    void concurrentAdvanceAndReports_neverExceedWindow_norDuplicateAssignment() throws Exception {
        int deviceCount = 30;
        int maxInFlight = 4;
        registerDevices(deviceCount);
        RolloutTask task = createTask(2, maxInFlight, 10, 0.99);

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger windowViolations = new AtomicInteger();
        AtomicInteger oversizeBatches = new AtomicInteger();
        AtomicInteger advanceErrors = new AtomicInteger();
        Collection<Map<String, Object>> samples = new ConcurrentLinkedQueue<>();
        // 防呆截止时间：正常应在秒级内完成，超时说明有设备被漏掉/卡死，直接失败而非挂死
        long deadlineMs = 30_000;

        // 推进线程：不断尝试开窗补人，直到任务结束或超时
        Runnable advancer = () -> {
            try {
                start.await();
                long end = System.currentTimeMillis() + deadlineMs;
                while (System.currentTimeMillis() < end) {
                    TaskStatus st;
                    synchronized (task) {
                        st = task.getStatus();
                    }
                    if (st == TaskStatus.COMPLETED || st == TaskStatus.ABORTED) {
                        return;
                    }
                    try {
                        rolloutService.advance(task.getId());
                    } catch (RejectedException e) {
                        advanceErrors.incrementAndGet(); // 窗口满/状态跳变，属正常反压
                    }
                    Map<String, Object> snap = rolloutService.progress(task.getId());
                    samples.add(snap);
                    if (((Number) snap.get("inFlight")).intValue() > maxInFlight) {
                        windowViolations.incrementAndGet();
                    }
                    // batches 与 deviceIds 都在 task 锁内演进，测试侧遍历也必须持锁
                    synchronized (task) {
                        for (TaskBatch b : task.getBatches().values()) {
                            if (b.getDeviceIds().size() > 2) {
                                oversizeBatches.incrementAndGet();
                            }
                        }
                    }
                }
                throw new IllegalStateException("advancer 等待任务收尾超时，疑似有设备长期卡在在途");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        // 上报线程：领取一个设备编号，等它入批后沿成功链路推到激活；每台只被一个线程领取
        AtomicInteger next = new AtomicInteger(1);
        Runnable reporter = () -> {
            try {
                start.await();
                int i;
                while ((i = next.getAndIncrement()) <= deviceCount) {
                    String id = "d" + i;
                    UpgradeRecord r = record(task.getId(), id);
                    long end = System.currentTimeMillis() + deadlineMs;
                    while (r == null && System.currentTimeMillis() < end) {
                        Thread.sleep(2);
                        r = record(task.getId(), id);
                    }
                    if (r == null) {
                        // 绝不能静默放弃：否则该设备永远 ASSIGNED、窗口被占死
                        throw new IllegalStateException("设备 " + id + " 等待入批超时，被推进漏掉");
                    }
                    driveActivated(task.getId(), id);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            futures.add(pool.submit(advancer));
        }
        for (int k = 0; k < 2; k++) {
            futures.add(pool.submit(reporter));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        log.info("并发推进+上报完成: 采样窗口次数={} 超上限采样={} 超批大小={} 反压拒绝={}",
                samples.size(), windowViolations.get(), oversizeBatches.get(),
                advanceErrors.get());

        assertEquals(0, windowViolations.get(), "任何时刻在途数都不能超过 maxInFlight=" + maxInFlight);
        assertEquals(0, oversizeBatches.get(), "任何批次设备数都不能超过 batchSize=2");

        // 不重不漏：每台设备恰好属于一个批次
        Set<String> assigned = new LinkedHashSet<>();
        for (TaskBatch b : task.getBatches().values()) {
            for (String id : b.getDeviceIds()) {
                assertTrue(assigned.add(id), "设备 " + id + " 被重复放入批次");
            }
        }
        assertEquals(deviceCount, assigned.size());
        assertEquals(deviceCount, rolloutService.progress(task.getId()).get("participants"));
        assertEquals(deviceCount, rolloutService.progress(task.getId()).get("success"));
        assertEquals(TaskStatus.COMPLETED, task.getStatus());
    }
    @Test
    void afterAbort_windowStopsRefilling_butInFlightFinish_andTaskSettles() {
        registerDevices(6);
        // batchSize=1, maxInFlight=3, 设备失败阈值=1, 任务失败率阈值=0.2（>20% 即中止）
        RolloutTask task = createTask(1, 3, 1, 0.2);

        rolloutService.advance(task.getId());
        // 一次推进铺满 3 个名额：d1/d2/d3 三个批次并行在途，d4/d5/d6 留在窗外
        assertEquals(3, task.getBatches().size());
        assertEquals(3, rolloutService.progress(task.getId()).get("inFlight"));

        // d1 上报失败触发回滚：1/3 ≈ 0.333 > 0.2，任务中止
        upgradeService.report(report("d1", task.getId(), ReportType.DOWNLOAD_STARTED));
        upgradeService.report(report("d1", task.getId(), ReportType.DOWNLOAD_FAILED));
        assertEquals(TaskStatus.ABORTED, task.getStatus());
        log.info("d1 回滚后任务中止: status={} reason={}",
                task.getStatus(), task.getAbortReason());

        // 中止后推进：拒绝补人，窗外 d4/d5/d6 绝不入批，窗口占用不增加
        RejectedException ex = assertThrows(RejectedException.class,
                () -> rolloutService.advance(task.getId()));
        assertEquals(RejectReason.TASK_NOT_IN_PROGRESS, ex.getReason());
        Map<String, Object> p1 = rolloutService.progress(task.getId());
        assertEquals(3, p1.get("participants"), "中止后不得再补新设备");
        assertEquals(2, p1.get("inFlight"), "回滚已释放 d1 的窗口名额，d2/d3 仍在途");
        assertNull(record(task.getId(), "d4"));

        // 已在途的 d2/d3 仍能正常把升级跑完（上报正常受理）
        driveActivated(task.getId(), "d2");
        driveActivated(task.getId(), "d3");
        Map<String, Object> p2 = rolloutService.progress(task.getId());
        log.info("在途设备跑完后: status={} success={} rolledBack={} inFlight={} remaining={}",
                task.getStatus(), p2.get("success"), p2.get("rolledBack"),
                p2.get("inFlight"), p2.get("windowRemaining"));
        assertEquals(TaskStatus.ABORTED, task.getStatus(), "中止状态不被在途成功翻转");
        assertEquals(2, p2.get("success"));
        assertEquals(1, p2.get("rolledBack"));
        assertEquals(0, p2.get("inFlight"), "在途全部收敛后窗口占用归零");
        assertEquals(3, p2.get("windowRemaining"), "名额全部释放：3 - 在途0");
        assertNull(record(task.getId(), "d4"), "中止后窗外设备始终未被补入");
        assertNull(record(task.getId(), "d5"));
        assertNull(record(task.getId(), "d6"));
    }

    @Test
    void defaultWindow_unsetMaxInFlight_waitsForWholeBatchSettledBeforeNextBatch() {
        registerDevices(4);
        // 不配置 maxInFlight：默认语义 = 整批收敛后才放行下一批（旧串行行为）
        RolloutTask task = createTask(2, null, 1, 0.5);

        TaskBatch b0 = rolloutService.advance(task.getId());
        assertEquals(Set.of("d1", "d2"), b0.getDeviceIds());
        assertEquals(1, task.getBatches().size(), "默认窗口一次只放一批");
        log.info("默认窗口首批: batch0={} maxInFlight={}(默认=batchSize)",
                b0.getDeviceIds(), task.getMaxInFlight());

        // 只收敛 d1 一台：不得放行下一批，d3 绝不能被补进新批次
        driveActivated(task.getId(), "d1");
        Map<String, Object> p1 = rolloutService.progress(task.getId());
        log.info("仅 d1 收敛后: inFlight={} windowRemaining={} participants={}",
                p1.get("inFlight"), p1.get("windowRemaining"), p1.get("participants"));
        assertEquals(1, p1.get("inFlight"));
        assertEquals(0, p1.get("windowRemaining"), "默认语义下批未整批收敛，剩余名额必须为 0");
        RejectedException ex = assertThrows(RejectedException.class,
                () -> rolloutService.advance(task.getId()));
        assertEquals(RejectReason.BATCH_NOT_OPEN, ex.getReason());
        assertEquals(1, task.getBatches().size(), "只收敛一台就开新批 = 悄悄改掉旧分批节奏");
        assertNull(record(task.getId(), "d3"));
        assertEquals(2, rolloutService.progress(task.getId()).get("participants"));

        // 整批收敛（d2 也激活）后才放行：d3/d4 组成第二批
        driveActivated(task.getId(), "d2");
        TaskBatch b1 = rolloutService.advance(task.getId());
        log.info("整批收敛后放行: batch1={}", b1.getDeviceIds());
        assertEquals(1, b1.getIndex());
        assertEquals(Set.of("d3", "d4"), b1.getDeviceIds());
    }

    @Test
    void lastInFlightSettlesByRollback_taskAutoCompletes_whenFailureRateWithinThreshold() {
        registerDevices(2);
        // 失败率阈值 0.5：1 成功 + 1 回滚 => 率 0.5，未严格超过，不中止
        RolloutTask task = createTask(2, null, 1, 0.5);
        rolloutService.advance(task.getId());
        assertEquals(Set.of("d1", "d2"), task.getBatches().get(0).getDeviceIds());

        // d1 激活成功收敛
        driveActivated(task.getId(), "d1");
        assertEquals(TaskStatus.IN_PROGRESS, task.getStatus());
        assertEquals(1, rolloutService.progress(task.getId()).get("inFlight"));

        // d2 失败回滚收敛：这是在途的最后一台，任务应立即自动收尾而不是卡在「进行中」
        upgradeService.report(report("d2", task.getId(), ReportType.DOWNLOAD_STARTED));
        upgradeService.report(report("d2", task.getId(), ReportType.DOWNLOAD_FAILED));
        Map<String, Object> p = rolloutService.progress(task.getId());
        log.info("d2 回滚收敛后: status={} success={} rolledBack={} inFlight={} failureRate={}",
                p.get("status"), p.get("success"), p.get("rolledBack"),
                p.get("inFlight"), p.get("failureRate"));
        assertEquals(UpgradePhase.ROLLED_BACK, record(task.getId(), "d2").getPhase());
        assertEquals(0, p.get("inFlight"));
        assertEquals(TaskStatus.COMPLETED, task.getStatus(),
                "在途靠回滚收敛归零且失败率未超阈值时，任务必须自动收尾");
        assertEquals(1, p.get("success"));
        assertEquals(1, p.get("rolledBack"));
    }

    @Test
    void abortedTask_lastInFlightSettlesByRollback_statusStaysAborted() {
        registerDevices(4);
        // batchSize=1, maxInFlight=2, 失败率阈值 0.2：第一台回滚即中止
        RolloutTask task = createTask(1, 2, 1, 0.2);
        rolloutService.advance(task.getId());
        assertEquals(2, task.getBatches().size());
        assertEquals(2, rolloutService.progress(task.getId()).get("inFlight"));

        // d1 回滚：率 1/2 = 0.5 > 0.2，任务中止；d2 仍在途
        upgradeService.report(report("d1", task.getId(), ReportType.DOWNLOAD_STARTED));
        upgradeService.report(report("d1", task.getId(), ReportType.DOWNLOAD_FAILED));
        assertEquals(TaskStatus.ABORTED, task.getStatus());
        log.info("d1 回滚触发中止: status={} reason={}", task.getStatus(), task.getAbortReason());

        // 中止后在途的 d2 以回滚方式收敛：状态绝不被翻回 COMPLETED
        upgradeService.report(report("d2", task.getId(), ReportType.DOWNLOAD_STARTED));
        upgradeService.report(report("d2", task.getId(), ReportType.DOWNLOAD_FAILED));
        Map<String, Object> p = rolloutService.progress(task.getId());
        log.info("在途 d2 回滚收敛后: status={} rolledBack={} inFlight={} windowRemaining={}",
                p.get("status"), p.get("rolledBack"), p.get("inFlight"), p.get("windowRemaining"));
        assertEquals(UpgradePhase.ROLLED_BACK, record(task.getId(), "d2").getPhase());
        assertEquals(0, p.get("inFlight"), "在途全部收敛，窗口占用归零");
        assertEquals(2, p.get("rolledBack"));
        assertEquals(TaskStatus.ABORTED, task.getStatus(), "已中止任务不得被翻回完成");
        assertNull(record(task.getId(), "d3"), "中止后窗外设备始终未被补入");
        assertNull(record(task.getId(), "d4"));
    }

    @Test
    void excludedDevice_whenConditionsImprove_getsPickedInLaterAdvance() {
        registerDevices(4);
        // 先把 d2 置离线：首次推进时它被排除留痕；d1 入批占住窗口
        now.addAndGet(DeviceService.OFFLINE_TIMEOUT_MS + 1);
        deviceService.get("d2"); // 物化离线
        RolloutTask task = createTask(1, 1, 1, 0.5);
        rolloutService.advance(task.getId());
        assertEquals(Set.of("d1"), task.getBatches().get(0).getDeviceIds());
        assertEquals(1, rolloutService.exclusions(task.getId()).size());

        // d1 收敛腾位（先补心跳恢复在线）；d2 重新心跳恢复在线（条件变好）
        deviceService.heartbeat("d1", "1.0.0");
        driveActivated(task.getId(), "d1");
        deviceService.heartbeat("d2", "1.0.0");

        // 再次推进：曾经被排除的 d2 不应被永久挡住，按注册顺序在 d3/d4 之前补入
        rolloutService.advance(task.getId());
        assertEquals(Set.of("d2"), task.getBatches().get(1).getDeviceIds());
        log.info("条件变好后 d2 重新进入批次 1");
        // 排除明细仍只记录首次那一条，不重复刷
        assertEquals(1, rolloutService.exclusions(task.getId()).size());
    }
}
