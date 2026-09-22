package com.github.xingjianluo.firmwareotaorchestratorjava.dto;

import com.github.xingjianluo.firmwareotaorchestratorjava.state.UpgradePhase;

/**
 * 上报处理结果。duplicate=true 表示幂等忽略（重复/迟到上报）。
 */
public class ReportResponse {

    private String deviceId;
    private String taskId;
    private UpgradePhase phase;
    private boolean duplicate;
    private boolean rolledBack;
    private String currentVersion;
    private String message;

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public UpgradePhase getPhase() {
        return phase;
    }

    public void setPhase(UpgradePhase phase) {
        this.phase = phase;
    }

    public boolean isDuplicate() {
        return duplicate;
    }

    public void setDuplicate(boolean duplicate) {
        this.duplicate = duplicate;
    }

    public boolean isRolledBack() {
        return rolledBack;
    }

    public void setRolledBack(boolean rolledBack) {
        this.rolledBack = rolledBack;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(String currentVersion) {
        this.currentVersion = currentVersion;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
