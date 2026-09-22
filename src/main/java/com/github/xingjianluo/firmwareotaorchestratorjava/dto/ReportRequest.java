package com.github.xingjianluo.firmwareotaorchestratorjava.dto;

import com.github.xingjianluo.firmwareotaorchestratorjava.state.ReportType;

public class ReportRequest {

    private String deviceId;
    private String taskId;
    private ReportType type;
    private String firmwareVersion;

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

    public ReportType getType() {
        return type;
    }

    public void setType(ReportType type) {
        this.type = type;
    }

    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    public void setFirmwareVersion(String firmwareVersion) {
        this.firmwareVersion = firmwareVersion;
    }
}
