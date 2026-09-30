package com.github.xingjianluo.firmwareotaorchestratorjava.model;

import com.github.xingjianluo.firmwareotaorchestratorjava.state.TaskStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 灰度发布任务。
 */
public class RolloutTask {

    private final String id;
    private String targetVersion;
    private VersionRange sourceVersionRange;
    /** 允许参与的硬件型号，空表示不限。 */
    private java.util.List<String> hardwareWhitelist;
    private int batchSize;
    /** 单台设备连续失败次数达到该值即触发该设备回滚。 */
    private int deviceFailureThreshold;
    /** 任务整体失败率(0~1)超过该值则中止推进。 */
    private double taskFailureRateThreshold;
    /** 是否开启并行推进窗口（true=多个批次可同时在途）。 */
    private boolean parallelWindow;
    /** 并行窗口容量：同时允许在途（未收敛）的设备数上限。 */
    private int maxInFlightDevices;
    /**
     * 最近一次推进评估后，是否已不存在「当前兼容且未参与」的设备。
     * 仅并行窗口模式使用：报告路径据此在全部在途收敛且无候选可补时收尾。
     */
    private boolean candidateExhausted;
    private TaskStatus status;
    private final Map<Integer, TaskBatch> batches = new LinkedHashMap<>();
    private long createdAt;
    private String abortReason;

    public RolloutTask(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public String getTargetVersion() {
        return targetVersion;
    }

    public void setTargetVersion(String targetVersion) {
        this.targetVersion = targetVersion;
    }

    public VersionRange getSourceVersionRange() {
        return sourceVersionRange;
    }

    public void setSourceVersionRange(VersionRange sourceVersionRange) {
        this.sourceVersionRange = sourceVersionRange;
    }

    public java.util.List<String> getHardwareWhitelist() {
        return hardwareWhitelist;
    }

    public void setHardwareWhitelist(java.util.List<String> hardwareWhitelist) {
        this.hardwareWhitelist = hardwareWhitelist;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getDeviceFailureThreshold() {
        return deviceFailureThreshold;
    }

    public void setDeviceFailureThreshold(int deviceFailureThreshold) {
        this.deviceFailureThreshold = deviceFailureThreshold;
    }

    public double getTaskFailureRateThreshold() {
        return taskFailureRateThreshold;
    }

    public void setTaskFailureRateThreshold(double taskFailureRateThreshold) {
        this.taskFailureRateThreshold = taskFailureRateThreshold;
    }

    public boolean isParallelWindow() {
        return parallelWindow;
    }

    public void setParallelWindow(boolean parallelWindow) {
        this.parallelWindow = parallelWindow;
    }

    public int getMaxInFlightDevices() {
        return maxInFlightDevices;
    }

    public void setMaxInFlightDevices(int maxInFlightDevices) {
        this.maxInFlightDevices = maxInFlightDevices;
    }

    public boolean isCandidateExhausted() {
        return candidateExhausted;
    }

    public void setCandidateExhausted(boolean candidateExhausted) {
        this.candidateExhausted = candidateExhausted;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public Map<Integer, TaskBatch> getBatches() {
        return batches;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getAbortReason() {
        return abortReason;
    }

    public void setAbortReason(String abortReason) {
        this.abortReason = abortReason;
    }
}
