package com.github.xingjianluo.firmwareotaorchestratorjava.model;

import com.github.xingjianluo.firmwareotaorchestratorjava.state.ExcludeReason;

/**
 * 设备被任务排除的记录：不得静默跳过。
 */
public class ExclusionRecord {

    private final String taskId;
    private final String deviceId;
    private final ExcludeReason reason;
    private final String detail;
    private final long at;

    public ExclusionRecord(String taskId, String deviceId, ExcludeReason reason, String detail, long at) {
        this.taskId = taskId;
        this.deviceId = deviceId;
        this.reason = reason;
        this.detail = detail;
        this.at = at;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public ExcludeReason getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }

    public long getAt() {
        return at;
    }
}
