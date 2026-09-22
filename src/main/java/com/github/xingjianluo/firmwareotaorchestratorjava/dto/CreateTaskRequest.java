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
}
