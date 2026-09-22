package com.github.xingjianluo.firmwareotaorchestratorjava.service;

import com.github.xingjianluo.firmwareotaorchestratorjava.domain.Device;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.DevicePhase;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.DeviceStatus;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.DeviceUpgradeState;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.FirmwareVersion;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.RejectReason;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.ReleaseTask;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.ReportResult;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.TaskStatus;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.UpgradeStage;
import com.github.xingjianluo.firmwareotaorchestratorjava.error.OperationRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 灰度发布编排：任务创建、分批推进、结果上报与自动回滚。 */
@Service
public class RolloutService {

    private static final Logger log = LoggerFactory.getLogger(RolloutService.class);

    private final Map<String, ReleaseTask> tasks = new ConcurrentHashMap<>();
    private final DeviceService deviceService;
    private final Clock clock;

    public RolloutService(DeviceService deviceService, Clock clock) {
        this.deviceService = deviceService;
        this.clock = clock;
    }

    public ReleaseTask createTask(String taskId, String targetVersion, String minSourceVersion,
                                  String maxSourceVersion, Set<String> supportedHardware,
                                  int batchSize, int failureThreshold) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须为正数");
        }
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold 必须为正数");
        }
        ReleaseTask task = new ReleaseTask(taskId,
                FirmwareVersion.parse(targetVersion),
                FirmwareVersion.parse(minSourceVersion),
                FirmwareVersion.parse(maxSourceVersion),
                supportedHardware, batchSize, failureThreshold);
        if (task.getMinSourceVersion().compareTo(task.getMaxSourceVersion()) > 0) {
            throw new IllegalArgumentException("源版本区间非法: min > max");
        }
        if (tasks.putIfAbsent(taskId, task) != null) {
            throw new IllegalArgumentException("任务已存在: " + taskId);
        }
        log.info("创建发布任务 taskId={} target={} source=[{},{}] hardware={} batchSize={} failureThreshold={}",
                taskId, targetVersion, minSourceVersion, maxSourceVersion,
                supportedHardware, batchSize, failureThreshold);
        return task;
    }

    public ReleaseTask startTask(String taskId) {
        ReleaseTask task = getTask(taskId);
        synchronized (task) {
            if (task.getStatus() != TaskStatus.DRAFT) {
                throw new OperationRejectedException(RejectReason.ILLEGAL_TASK_STATE,
                        "任务不在 DRAFT 状态，无法启动: " + taskId + " 当前=" + task.getStatus());
            }
            task.setStatus(TaskStatus.RUNNING);
        }
        log.info("任务启动 taskId={}", taskId);
        return task;
    }

    /**
     * 推进下一批：对尚未评估过的注册设备做兼容性判定，
     * 兼容设备按 deviceId 排序取前 batchSize 个入批；
     * 不兼容/状态不满足的设备被明确排除并记录原因，绝不静默跳过。
     * 已入批或已排除的设备不会被重复评估，重复推进不会让同一设备重复分批。
     */
    public ReleaseTask advanceBatch(String taskId) {
        ReleaseTask task = getTask(taskId);
        synchronized (task) {
            if (task.getStatus() != TaskStatus.RUNNING) {
                throw new OperationRejectedException(RejectReason.ILLEGAL_TASK_STATE,
                        "任务不在 RUNNING 状态，无法推进批次: " + taskId + " 当前=" + task.getStatus());
            }
            List<Device> candidates = new ArrayList<>();
            for (Device device : deviceService.listDevices()) {
                if (task.getDeviceState(device.getDeviceId()) != null) {
                    continue; // 已评估过（入批或排除），跳过
                }
                String exclusion = checkCompatibility(task, device);
                if (exclusion != null) {
                    DeviceUpgradeState excluded = new DeviceUpgradeState(device.getDeviceId(), -1);
                    excluded.setExclusionReason(exclusion);
                    task.putDeviceStateIfAbsent(excluded);
                    log.info("设备被排除 taskId={} deviceId={} reason={}",
                            taskId, device.getDeviceId(), exclusion);
                } else {
                    candidates.add(device);
                }
            }
            candidates.sort(Comparator.comparing(Device::getDeviceId));
            List<Device> batchDevices = candidates.stream().limit(task.getBatchSize()).toList();
            if (!batchDevices.isEmpty()) {
                int batch = task.nextBatch();
                for (Device device : batchDevices) {
                    task.putDeviceStateIfAbsent(new DeviceUpgradeState(device.getDeviceId(), batch));
                    log.info("设备入批 taskId={} deviceId={} batch={}", taskId, device.getDeviceId(), batch);
                }
            }
            log.info("批次推进完成 taskId={} currentBatch={} progress={}",
                    taskId, task.getCurrentBatch(), task.progress());
        }
        return task;
    }

    /** 兼容性判定：返回 null 表示兼容，否则返回排除原因。 */
    private String checkCompatibility(ReleaseTask task, Device device) {
        if (device.getStatus() != DeviceStatus.ONLINE) {
            return "设备不在线";
        }
        if (!task.getSupportedHardware().contains(device.getHardwareModel())) {
            return "硬件型号不兼容: " + device.getHardwareModel();
        }
        FirmwareVersion v = device.getCurrentVersion();
        if (v.compareTo(task.getMinSourceVersion()) < 0 || v.compareTo(task.getMaxSourceVersion()) > 0) {
            return "当前版本不在源版本区间: " + v;
        }
        return null;
    }

    /**
     * 处理升级结果上报。
     * 幂等：完全相同的上报（同阶段同结果）重复到达时不改变状态；
     * 乱序/迟到/非法转换被拒绝且原因可区分；
     * 失败数达到阈值时自动回滚并停止推进，且回滚只执行一次。
     */
    public ReleaseTask report(String taskId, String deviceId, UpgradeStage stage, ReportResult result) {
        ReleaseTask task = getTask(taskId);
        DeviceUpgradeState state = task.getDeviceState(deviceId);
        if (state == null) {
            throw new OperationRejectedException(RejectReason.DEVICE_NOT_IN_TASK,
                    "设备不在任务中: taskId=" + taskId + " deviceId=" + deviceId);
        }
        String fingerprint = stage + ":" + result;
        long now = clock.millis();
        boolean triggerAbort = false;
        synchronized (state) {
            // 幂等：与最近一次应用的上报完全相同，直接吸收
            if (fingerprint.equals(state.getLastReportFingerprint())) {
                log.info("重复上报被幂等吸收 taskId={} deviceId={} stage={} result={}",
                        taskId, deviceId, stage, result);
                return task;
            }
            if (task.getStatus() == TaskStatus.ABORTED) {
                throw new OperationRejectedException(RejectReason.TASK_ALREADY_ABORTED,
                        "任务已中止回滚，拒绝新上报: " + taskId);
            }
            if (task.getStatus() != TaskStatus.RUNNING) {
                throw new OperationRejectedException(RejectReason.ILLEGAL_TASK_STATE,
                        "任务不在 RUNNING 状态，拒绝上报: " + taskId + " 当前=" + task.getStatus());
            }
            DevicePhase phase = state.getPhase();
            if (phase == DevicePhase.EXCLUDED) {
                throw new OperationRejectedException(RejectReason.DEVICE_EXCLUDED,
                        "设备已被排除，拒绝上报: " + deviceId + " 原因=" + state.getExclusionReason());
            }
            if (phase == DevicePhase.COMPLETED || phase == DevicePhase.FAILED
                    || phase == DevicePhase.ROLLED_BACK) {
                throw new OperationRejectedException(RejectReason.ILLEGAL_TRANSITION,
                        "设备已处于终态 " + phase + "，拒绝上报: " + deviceId);
            }
            int expectedOrder = state.getLastAppliedStageOrder() + 1;
            if (stage.order() < expectedOrder) {
                throw new OperationRejectedException(RejectReason.STALE_REPORT,
                        "迟到的上报被拒绝: deviceId=" + deviceId + " stage=" + stage
                                + " 已完成阶段序号=" + state.getLastAppliedStageOrder());
            }
            if (stage.order() > expectedOrder) {
                throw new OperationRejectedException(RejectReason.ILLEGAL_TRANSITION,
                        "非法阶段跳转: deviceId=" + deviceId + " 期望阶段序号=" + expectedOrder
                                + " 实际=" + stage.order());
            }
            log.info("接受上报 taskId={} deviceId={} stage={} result={} 依据: 期望阶段序号={} 当前阶段={}",
                    taskId, deviceId, stage, result, expectedOrder, phase);
            if (result == ReportResult.SUCCESS) {
                applySuccess(task, state, stage, fingerprint, now);
            } else {
                triggerAbort = applyFailure(task, state, stage, fingerprint, now);
            }
        }
        // 在设备状态锁之外触发整体回滚，锁顺序固定为 task -> state，避免死锁
        if (triggerAbort) {
            abortAndRollback(task);
        }
        return task;
    }

    private void applySuccess(ReleaseTask task, DeviceUpgradeState state, UpgradeStage stage,
                              String fingerprint, long now) {
        switch (stage) {
            case DOWNLOAD -> state.setPhase(DevicePhase.DOWNLOADED);
            case INSTALL -> state.setPhase(DevicePhase.INSTALLED);
            case ACTIVATE -> {
                state.setPhase(DevicePhase.COMPLETED);
                Device device = deviceService.getDevice(state.getDeviceId());
                device.promoteVersion(task.getTargetVersion());
                log.info("设备升级完成 taskId={} deviceId={} version={}",
                        task.getTaskId(), state.getDeviceId(), task.getTargetVersion());
            }
        }
        state.recordAppliedReport(stage.order(), fingerprint, now);
    }

    /** @return true 表示失败数达到阈值，需要在设备状态锁之外触发整体回滚 */
    private boolean applyFailure(ReleaseTask task, DeviceUpgradeState state, UpgradeStage stage,
                                 String fingerprint, long now) {
        state.setPhase(DevicePhase.FAILED);
        state.recordAppliedReport(stage.order(), fingerprint, now);
        int failures = task.incrementFailureCount();
        log.info("设备升级失败 taskId={} deviceId={} stage={} failureCount={} threshold={}",
                task.getTaskId(), state.getDeviceId(), stage, failures, task.getFailureThreshold());
        return failures >= task.getFailureThreshold();
    }

    /**
     * 失败阈值触发：任务置为 ABORTED（只执行一次），
     * 所有已应用过升级阶段的设备回滚到上一稳定版本并标记 ROLLED_BACK，
     * 尚未开始（PENDING）的设备保持原版本、停止推进。
     */
    private void abortAndRollback(ReleaseTask task) {
        synchronized (task) {
            if (task.getStatus() == TaskStatus.ABORTED) {
                return; // 并发下只回滚一次
            }
            task.setStatus(TaskStatus.ABORTED);
        }
        for (DeviceUpgradeState ds : task.getDeviceStates()) {
            synchronized (ds) {
                DevicePhase phase = ds.getPhase();
                if (phase == DevicePhase.DOWNLOADED || phase == DevicePhase.INSTALLED
                        || phase == DevicePhase.COMPLETED || phase == DevicePhase.FAILED) {
                    Device device = deviceService.getDevice(ds.getDeviceId());
                    device.rollbackToLastStable();
                    ds.setPhase(DevicePhase.ROLLED_BACK);
                    log.info("设备回滚 taskId={} deviceId={} batch={} 回滚后版本={}",
                            task.getTaskId(), ds.getDeviceId(), ds.getBatch(),
                            device.getCurrentVersion());
                }
            }
        }
        log.info("任务中止并回滚 taskId={} progress={}", task.getTaskId(), task.progress());
    }

    public ReleaseTask getTask(String taskId) {
        ReleaseTask task = tasks.get(taskId);
        if (task == null) {
            throw new OperationRejectedException(RejectReason.UNKNOWN_TASK, "未知任务: " + taskId);
        }
        return task;
    }
}
