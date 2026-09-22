package com.github.xingjianluo.firmwareotaorchestratorjava.tests;

import com.github.xingjianluo.firmwareotaorchestratorjava.dto.CreateTaskRequest;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.ExcludeReason;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.RejectReason;
import com.github.xingjianluo.firmwareotaorchestratorjava.exception.RejectedException;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.ExclusionRecord;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.RolloutTask;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.TaskBatch;
import com.github.xingjianluo.firmwareotaorchestratorjava.service.CompatibilityService;
import com.github.xingjianluo.firmwareotaorchestratorjava.service.DeviceService;
import com.github.xingjianluo.firmwareotaorchestratorjava.service.RolloutService;
import com.github.xingjianluo.firmwareotaorchestratorjava.store.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolloutServiceTest {

    private static final Logger log = LoggerFactory.getLogger(RolloutServiceTest.class);

    private final AtomicLong now = new AtomicLong(2_000_000L);
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

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        deviceService = new DeviceService(store, clock);
        rolloutService = new RolloutService(store, deviceService, new CompatibilityService(), clock);
    }

    private CreateTaskRequest taskRequest() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTargetVersion("2.0.0");
        req.setMinSourceVersion("1.0.0");
        req.setMaxSourceVersion("1.9.9");
        req.setHardwareWhitelist(List.of("Box-A"));
        req.setBatchSize(2);
        req.setDeviceFailureThreshold(1);
        req.setTaskFailureRateThreshold(0.5);
        return req;
    }

    @Test
    void incompatibleDevices_excludedWithDistinctReasons_notSilentlySkipped() {
        deviceService.register("ok", "Box-A", "1.2.0");
        deviceService.register("bad-model", "Box-X", "1.2.0");
        deviceService.register("too-old", "Box-A", "0.9.0");
        deviceService.register("too-new", "Box-A", "2.5.0");
        deviceService.register("already", "Box-A", "2.0.0");

        RolloutTask task = rolloutService.createTask(taskRequest());
        TaskBatch batch = rolloutService.advance(task.getId());
        log.info("输入: 5 台不同条件设备; 判定: 批次设备={}", batch.getDeviceIds());

        // 仅有 ok 进入批次（batchSize=2 但兼容设备只 1 台）
        assertEquals(1, batch.getDeviceIds().size());
        assertTrue(batch.getDeviceIds().contains("ok"));

        List<ExclusionRecord> exclusions = rolloutService.exclusions(task.getId());
        exclusions.forEach(e -> log.info("排除记录 deviceId={} reason={} detail={}",
                e.getDeviceId(), e.getReason(), e.getDetail()));
        assertEquals(4, exclusions.size(), "每台不兼容设备必须留痕，不得静默跳过");
        assertEquals(ExcludeReason.HARDWARE_NOT_MATCH, reasonOf(exclusions, "bad-model"));
        assertEquals(ExcludeReason.VERSION_OUT_OF_RANGE, reasonOf(exclusions, "too-old"));
        assertEquals(ExcludeReason.VERSION_OUT_OF_RANGE, reasonOf(exclusions, "too-new"));
        assertEquals(ExcludeReason.ALREADY_ON_NEW_VERSION, reasonOf(exclusions, "already"));
    }

    @Test
    void offlineDevice_excludedAsOffline() {
        deviceService.register("online", "Box-A", "1.0.0");
        deviceService.register("gone", "Box-A", "1.0.0");
        now.addAndGet(DeviceService.OFFLINE_TIMEOUT_MS + 1);
        // 查询物化离线状态
        deviceService.get("gone");

        RolloutTask task = rolloutService.createTask(taskRequest());
        TaskBatch batch = rolloutService.advance(task.getId());
        log.info("输入: 1 在线 1 离线; 判定批次={}", batch.getDeviceIds());
        assertTrue(batch.getDeviceIds().contains("online"));
        assertFalse(batch.getDeviceIds().contains("gone"));
        List<ExclusionRecord> exclusions = rolloutService.exclusions(task.getId());
        assertEquals(ExcludeReason.DEVICE_OFFLINE, reasonOf(exclusions, "gone"));
    }

    @Test
    void repeatedAdvance_doesNotDoubleAssign_andBatchGatesNext() {
        deviceService.register("d1", "Box-A", "1.0.0");
        deviceService.register("d2", "Box-A", "1.0.0");
        deviceService.register("d3", "Box-A", "1.0.0");
        RolloutTask task = rolloutService.createTask(taskRequest());

        TaskBatch first = rolloutService.advance(task.getId());
        assertEquals(2, first.getDeviceIds().size());

        // 批次未收敛时再推进：拒绝，原因可区分
        RejectedException ex = assertThrows(RejectedException.class,
                () -> rolloutService.advance(task.getId()));
        log.info("未收敛再推进被拒绝 reason={}", ex.getReason());
        assertEquals(RejectReason.BATCH_NOT_OPEN, ex.getReason());

        // 即使强行再查进度，参与设备仍为 2，d3 未被偷偷分配
        long participants = ((Number) rolloutService.progress(task.getId()).get("participants")).longValue();
        assertEquals(2, participants);
    }

    @Test
    void nextBatch_onlyOpensAfterCurrentBatchSettled() {
        deviceService.register("d1", "Box-A", "1.0.0");
        deviceService.register("d2", "Box-A", "1.0.0");
        deviceService.register("d3", "Box-A", "1.0.0");
        deviceService.register("d4", "Box-A", "1.0.0");
        RolloutTask task = rolloutService.createTask(taskRequest());

        TaskBatch b0 = rolloutService.advance(task.getId());
        assertNotNull(b0);
        // 第一批未收敛前不能开第二批
        assertNull(safeAdvance(task.getId()));
        // 模拟第一批全部成功：写记录到 ACTIVATED
        settleAllActivated(task.getId(), b0.getDeviceIds());
        TaskBatch b1 = rolloutService.advance(task.getId());
        assertNotNull(b1, "第一批收敛后应能打开第二批");
        assertEquals(1, b1.getIndex());
        assertEquals(2, b1.getDeviceIds().size());
        log.info("分批推进: batch0={} batch1={}", b0.getDeviceIds(), b1.getDeviceIds());
    }

    private TaskBatch safeAdvance(String taskId) {
        try {
            return rolloutService.advance(taskId);
        } catch (RejectedException e) {
            log.info("闸门拦截: {}", e.getMessage());
            return null;
        }
    }

    private void settleAllActivated(String taskId, java.util.Set<String> deviceIds) {
        for (String deviceId : deviceIds) {
            var record = store.upgrades().get(RolloutService.upgradeKey(taskId, deviceId));
            record.setPhase(com.github.xingjianluo.firmwareotaorchestratorjava.state.UpgradePhase.ACTIVATED);
        }
    }

    private ExcludeReason reasonOf(List<ExclusionRecord> records, String deviceId) {
        return records.stream().filter(r -> r.getDeviceId().equals(deviceId)).findFirst()
                .map(ExclusionRecord::getReason).orElseThrow();
    }
}
