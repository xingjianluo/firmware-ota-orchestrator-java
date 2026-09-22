package com.github.xingjianluo.firmwareotaorchestratorjava.domain;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 灰度发布任务：按版本区间与硬件条件分批推进，失败阈值触发整体回滚。 */
public class ReleaseTask {

    private final String taskId;
    private final FirmwareVersion targetVersion;
    private final FirmwareVersion minSourceVersion;
    private final FirmwareVersion maxSourceVersion;
    private final Set<String> supportedHardware;
    private final int batchSize;
    private final int failureThreshold;

    private volatile TaskStatus status = TaskStatus.DRAFT;
    private final AtomicInteger currentBatch = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final Map<String, DeviceUpgradeState> deviceStates = new ConcurrentHashMap<>();

    public ReleaseTask(String taskId, FirmwareVersion targetVersion,
                       FirmwareVersion minSourceVersion, FirmwareVersion maxSourceVersion,
                       Set<String> supportedHardware, int batchSize, int failureThreshold) {
        this.taskId = taskId;
        this.targetVersion = targetVersion;
        this.minSourceVersion = minSourceVersion;
        this.maxSourceVersion = maxSourceVersion;
        this.supportedHardware = Set.copyOf(supportedHardware);
        this.batchSize = batchSize;
        this.failureThreshold = failureThreshold;
    }

    public String getTaskId() { return taskId; }
    public FirmwareVersion getTargetVersion() { return targetVersion; }
    public FirmwareVersion getMinSourceVersion() { return minSourceVersion; }
    public FirmwareVersion getMaxSourceVersion() { return maxSourceVersion; }
    public Set<String> getSupportedHardware() { return supportedHardware; }
    public int getBatchSize() { return batchSize; }
    public int getFailureThreshold() { return failureThreshold; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public int getCurrentBatch() { return currentBatch.get(); }
    public int nextBatch() { return currentBatch.incrementAndGet(); }
    public int getFailureCount() { return failureCount.get(); }
    public int incrementFailureCount() { return failureCount.incrementAndGet(); }
    public Collection<DeviceUpgradeState> getDeviceStates() { return deviceStates.values(); }
    public DeviceUpgradeState getDeviceState(String deviceId) { return deviceStates.get(deviceId); }
    public DeviceUpgradeState putDeviceStateIfAbsent(DeviceUpgradeState state) {
        return deviceStates.putIfAbsent(state.getDeviceId(), state);
    }

    /** 任务进度视图：各阶段设备数，用于校验进度自洽。 */
    public Map<DevicePhase, Long> progress() {
        Map<DevicePhase, Long> counts = new java.util.EnumMap<>(DevicePhase.class);
        for (DeviceUpgradeState state : deviceStates.values()) {
            counts.merge(state.getPhase(), 1L, Long::sum);
        }
        return counts;
    }
}
