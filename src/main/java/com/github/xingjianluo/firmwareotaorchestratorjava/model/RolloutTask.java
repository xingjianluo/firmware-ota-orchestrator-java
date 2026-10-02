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
    /** 并行推进窗口容量：同时允许处于在途（未收敛）状态的设备数上限。 */
    private int maxInFlight;
    /**
     * 创建任务时是否显式配置了 maxInFlight。
     * 未配置时走默认语义：整批收敛（在途归零）后才放行下一批，与旧串行行为一致；
     * 配置后按窗口剩余名额随收敛随补。
     */
    private boolean maxInFlightConfigured;
    /** 单台设备连续失败次数达到该值即触发该设备回滚。 */
    private int deviceFailureThreshold;
    /** 任务整体失败率(0~1)超过该值则中止推进。 */
    private double taskFailureRateThreshold;
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

    public int getMaxInFlight() {
        return maxInFlight;
    }

    public void setMaxInFlight(int maxInFlight) {
        this.maxInFlight = maxInFlight;
    }

    public boolean isMaxInFlightConfigured() {
        return maxInFlightConfigured;
    }

    public void setMaxInFlightConfigured(boolean maxInFlightConfigured) {
        this.maxInFlightConfigured = maxInFlightConfigured;
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
