package com.github.xingjianluo.firmwareotaorchestratorjava.tests;

import com.github.xingjianluo.firmwareotaorchestratorjava.dto.CreateTaskRequest;
import com.github.xingjianluo.firmwareotaorchestratorjava.dto.ReportRequest;
import com.github.xingjianluo.firmwareotaorchestratorjava.exception.RejectedException;
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
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分批节奏与收敛收尾修复回归测试：
 *
 * <ol>
 *   <li>业务一：不配置 maxInFlight 时保持旧版默认语义——必须整批收敛才放行下一批，
 *       批次内只有一台先收敛不得补人、不得开新批；</li>
 *   <li>业务二：最后在途设备靠回滚收敛（失败率未超阈值、任务未中止）时也要自动收尾到
 *       COMPLETED（一台成功、一台回滚）；</li>
 *   <li>业务三：任务中止后在途设备仍能跑完/回滚，但状态绝不被翻回 COMPLETED，且不再补人。</li>
 * </ol>
 */
class RolloutConvergenceTest {

    private static final Logger log = LoggerFactory.getLogger(RolloutConvergenceTest.class);

    private final AtomicLong now = new AtomicLong(7_000_000L);
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
        CompatibilityService compatibilityService = new CompatibilityService();
        rolloutService = new RolloutService(store, deviceService, compatibilityService, clock);
        upgradeService = new UpgradeService(store, deviceService, compatibilityService, clock);
    }

    private void register(int n) {
        for (int i = 1; i <= n; i++) {
            deviceService.register("d" + i, "Box-A", "1.0.0");
        }
    }

    private RolloutTask createTask(Integer batchSize, Integer maxInFlight,
                                   int deviceThreshold, double taskRate) {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTargetVersion("2.0.0");
        req.setMinSourceVersion("1.0.0");
        req.setMaxSourceVersion("1.9.9");
        req.setHardwareWhitelist(List.of("Box-A"));
        if (batchSize != null) {
            req.setBatchSize(batchSize);
        }
        if (maxInFlight != null) {
            req.setMaxInFlight(maxInFlight);
        }
        req.setDeviceFailureThreshold(deviceThreshold);
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

    private void driveActivated(String taskId, String deviceId) {
        upgradeService.report(report(deviceId, taskId, ReportType.DOWNLOAD_STARTED));
        upgradeService.report(report(deviceId, taskId, ReportType.DOWNLOAD_SUCCESS));
        upgradeService.report(report(deviceId, taskId, ReportType.INSTALL_STARTED));
        upgradeService.report(report(deviceId, taskId, ReportType.INSTALL_SUCCESS));
        upgradeService.report(report(deviceId, taskId, ReportType.ACTIVATE_STARTED));
        upgradeService.report(report(deviceId, taskId, ReportType.ACTIVATE_SUCCESS));
    }

    private void driveRollback(String taskId, String deviceId) {
        upgradeService.report(report(deviceId, taskId, ReportType.DOWNLOAD_STARTED));
        upgradeService.report(report(deviceId, taskId, ReportType.DOWNLOAD_FAILED));
    }

    /**
     * 业务一：不配置 maxInFlight（默认串行分批）。
     * batchSize=2，4 台设备；首批 d1/d2 在途时，即使 d1 已先激活成功，
     * 再推进也必须被闸门拦截：d3/d4 绝不能提前补入，直到 d2 也整批收敛。
     */
    @Test
    void defaultMode_singleDeviceSettling_mustNotReleaseNextBatch() {
        register(4);
        RolloutTask task = createTask(2, null, 10, 0.5);

        log.info("[业务一] 未配置 maxInFlight: parallelWindowEnabled={} maxInFlight={} batchSize={}",
                task.isParallelWindowEnabled(), task.getMaxInFlight(), task.getBatchSize());
        assertEquals(false, task.isParallelWindowEnabled(), "未配置时必须是默认串行分批模式");

        TaskBatch first = rolloutService.advance(task.getId());
        assertEquals(Set.of("d1", "d2"), first.getDeviceIds());

        // 只有 d1 先收敛成功，d2 仍在途
        driveActivated(task.getId(), "d1");
        Map<String, Object> partial = rolloutService.progress(task.getId());
        log.info("[业务一] d1 先成功 d2 在途: status={} inFlight={} windowRemaining={} participants={}",
                partial.get("status"), partial.get("inFlight"),
                partial.get("windowRemaining"), partial.get("participants"));
        assertEquals(1, partial.get("inFlight"), "d2 仍在途");
        assertEquals(0, partial.get("windowRemaining"), "默认模式本批未整批收敛，剩余可补名额必须为 0");
        assertEquals(2, partial.get("participants"), "d3/d4 不得被提前补入");

        // 连续推进都应被同一闸门拦截，且不产生新批次、不补人
        for (int attempt = 1; attempt <= 2; attempt++) {
            RejectedException ex = assertThrows(RejectedException.class,
                    () -> rolloutService.advance(task.getId()),
                    "第 " + attempt + " 次推进：本批尚未整批收敛，必须拒绝");
            assertEquals(RejectReason.BATCH_NOT_OPEN, ex.getReason());
        }
        assertEquals(1, task.getBatches().size(), "不得偷偷打开第二批");
        assertNull(store.upgrades().get(RolloutService.upgradeKey(task.getId(), "d3")),
                "d3 不得提前入批");
        assertNull(store.upgrades().get(RolloutService.upgradeKey(task.getId(), "d4")),
                "d4 不得提前入批");
        assertEquals(TaskStatus.IN_PROGRESS, task.getStatus());

        // d2 也收敛 → 整批收敛，再推进才放行 d3/d4
        driveActivated(task.getId(), "d2");
        TaskBatch second = rolloutService.advance(task.getId());
        log.info("[业务一] 整批收敛后开第二批: {}", second.getDeviceIds());
        assertEquals(1, second.getIndex());
        assertEquals(Set.of("d3", "d4"), second.getDeviceIds());
    }

    /**
     * 业务二：一台成功、一台回滚（整体失败率 0.5 未严格超过阈值 0.5、任务未中止），
     * 在途归零且窗外无更多兼容设备时，上报路径必须自动把任务收尾到 COMPLETED，
     * 不能卡在 IN_PROGRESS。
     */
    @Test
    void lastDeviceSettledByRollback_withinThreshold_autoCompletes() {
        register(2);
        RolloutTask task = createTask(2, null, 1, 0.5);
        rolloutService.advance(task.getId());

        // d1 完整成功
        driveActivated(task.getId(), "d1");
        log.info("[业务二] d1 成功后: status={}（此时 d2 仍在途，不能收尾）", task.getStatus());
        assertEquals(TaskStatus.IN_PROGRESS, task.getStatus());

        // d2 首次失败即回滚收敛：1/2 = 0.5，不满足「严格大于 0.5」，任务不应中止
        driveRollback(task.getId(), "d2");
        UpgradeRecord d2 = store.upgrades().get(RolloutService.upgradeKey(task.getId(), "d2"));
        assertEquals(UpgradePhase.ROLLED_BACK, d2.getPhase());
        Map<String, Object> p = rolloutService.progress(task.getId());
        log.info("[业务二] d2 回滚收敛后: status={} success={} rolledBack={} inFlight={} failureRate={}",
                task.getStatus(), p.get("success"), p.get("rolledBack"),
                p.get("inFlight"), p.get("failureRate"));

        assertEquals(TaskStatus.COMPLETED, task.getStatus(),
                "最后在途设备靠回滚收敛且失败率未超阈值，必须自动收尾到 COMPLETED");
        assertEquals(0, p.get("inFlight"), "在途应归零");
        assertEquals(1, p.get("success"));
        assertEquals(1, p.get("rolledBack"));
    }

    /**
     * 业务三：任务因失败率超阈值中止后：advance 不再补新设备；
     * 已在途设备的上报仍正常受理（一台跑完成功、一台回滚），在途归零；
     * 任务始终停留在 ABORTED，绝不被翻回 COMPLETED，窗外设备始终未入批。
     */
    @Test
    void afterAbort_inFlightSettle_butStatusNeverFlipsBackToCompleted() {
        register(4);
        // batchSize=1 且显式 maxInFlight=3：首批 d1/d2/d3 并行在途，d4 留窗外；阈值 0.34
        RolloutTask task = createTask(1, 3, 1, 0.34);
        rolloutService.advance(task.getId());
        assertEquals(3, task.getBatches().size());

        // d1 回滚：1/3 ≈ 0.333 ... 用更小阈值确保中止 → 这里 1/3=0.333 不大于 0.34，
        // 故改用 d2 随后也回滚（2/3≈0.667 > 0.34）来触发中止。
        driveRollback(task.getId(), "d1");
        assertEquals(TaskStatus.IN_PROGRESS, task.getStatus(), "1/3 失败率未超阈值，不中止");
        driveRollback(task.getId(), "d2");
        assertEquals(TaskStatus.ABORTED, task.getStatus(),
                "2/3 失败率超过 0.34，任务必须中止");
        log.info("[业务三] 任务中止: reason={}", task.getAbortReason());

        // 中止后推进：拒绝补人，d4 始终不进批
        RejectedException ex = assertThrows(RejectedException.class,
                () -> rolloutService.advance(task.getId()));
        assertEquals(RejectReason.TASK_NOT_IN_PROGRESS, ex.getReason());
        assertNull(store.upgrades().get(RolloutService.upgradeKey(task.getId(), "d4")),
                "中止后不得补入新设备");

        // 已在途的 d3 仍能正常把升级跑完
        driveActivated(task.getId(), "d3");
        Map<String, Object> p = rolloutService.progress(task.getId());
        log.info("[业务三] 在途全部收敛后: status={} success={} rolledBack={} inFlight={} windowRemaining={}",
                task.getStatus(), p.get("success"), p.get("rolledBack"),
                p.get("inFlight"), p.get("windowRemaining"));

        assertEquals(TaskStatus.ABORTED, task.getStatus(), "中止状态绝不能被在途收敛翻回 COMPLETED");
        assertEquals(0, p.get("inFlight"), "在途应全部收敛归零");
        assertEquals(3, p.get("windowRemaining"), "名额随在途收敛全部释放");
        assertEquals(1, p.get("success"));
        assertEquals(2, p.get("rolledBack"));
        assertNull(store.upgrades().get(RolloutService.upgradeKey(task.getId(), "d4")),
                "收尾后窗外设备仍未入批");

        // 再推进依旧拒绝，状态不翻转
        assertThrows(RejectedException.class, () -> rolloutService.advance(task.getId()));
        assertEquals(TaskStatus.ABORTED, task.getStatus());
    }
}
