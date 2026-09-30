package com.github.xingjianluo.firmwareotaorchestratorjava.dto;

import java.util.List;

public class CreateTaskRequest {

    private String targetVersion;
    private String minSourceVersion;
    private String maxSourceVersion;
    private List<String> hardwareWhitelist;
    private Integer batchSize;
    private Integer deviceFailureThreshold;
    private Double taskFailureRateThreshold;
    /**
     * 同时在途设备数上限（并行推进窗口容量）。
     * 为空表示沿用严格串行批次模式；显式给出正值时开启并行窗口模式。
     */
    private Integer maxInFlightDevices;

    public String getTargetVersion() {
        return targetVersion;
    }

    public void setTargetVersion(String targetVersion) {
        this.targetVersion = targetVersion;
    }

    public String getMinSourceVersion() {
        return minSourceVersion;
    }

    public void setMinSourceVersion(String minSourceVersion) {
        this.minSourceVersion = minSourceVersion;
    }

    public String getMaxSourceVersion() {
        return maxSourceVersion;
    }

    public void setMaxSourceVersion(String maxSourceVersion) {
        this.maxSourceVersion = maxSourceVersion;
    }

    public List<String> getHardwareWhitelist() {
        return hardwareWhitelist;
    }

    public void setHardwareWhitelist(List<String> hardwareWhitelist) {
        this.hardwareWhitelist = hardwareWhitelist;
    }

    public Integer getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }

    public Integer getDeviceFailureThreshold() {
        return deviceFailureThreshold;
    }

    public void setDeviceFailureThreshold(Integer deviceFailureThreshold) {
        this.deviceFailureThreshold = deviceFailureThreshold;
    }

    public Double getTaskFailureRateThreshold() {
        return taskFailureRateThreshold;
    }

    public void setTaskFailureRateThreshold(Double taskFailureRateThreshold) {
        this.taskFailureRateThreshold = taskFailureRateThreshold;
    }

    public Integer getMaxInFlightDevices() {
        return maxInFlightDevices;
    }

    public void setMaxInFlightDevices(Integer maxInFlightDevices) {
        this.maxInFlightDevices = maxInFlightDevices;
    }
}
