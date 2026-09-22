package com.github.xingjianluo.firmwareotaorchestratorjava.model;

import com.github.xingjianluo.firmwareotaorchestratorjava.state.RollbackReason;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.UpgradePhase;

/**
 * 单设备在单个任务下的升级记录（批次归属与阶段状态机）。
 */
public class UpgradeRecord {

    private final String taskId;
    private final String deviceId;
    private int batchIndex;
    private UpgradePhase phase = UpgradePhase.IDLE;
    private int failureCount;
    private boolean rolledBack;
    private RollbackReason rollbackReason;
    private long updatedAt;

    public UpgradeRecord(String taskId, String deviceId) {
        this.taskId = taskId;
        this.deviceId = deviceId;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public int getBatchIndex() {
        return batchIndex;
    }

    public void setBatchIndex(int batchIndex) {
        this.batchIndex = batchIndex;
    }

    public UpgradePhase getPhase() {
        return phase;
    }

    public void setPhase(UpgradePhase phase) {
        this.phase = phase;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(int failureCount) {
        this.failureCount = failureCount;
    }

    public boolean isRolledBack() {
        return rolledBack;
    }

    public void setRolledBack(boolean rolledBack) {
        this.rolledBack = rolledBack;
    }

    public RollbackReason getRollbackReason() {
        return rollbackReason;
    }

    public void setRollbackReason(RollbackReason rollbackReason) {
        this.rollbackReason = rollbackReason;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
