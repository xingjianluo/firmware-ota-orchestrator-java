package com.github.xingjianluo.firmwareotaorchestratorjava.model;

import com.github.xingjianluo.firmwareotaorchestratorjava.state.DeviceStatus;

/**
 * 设备实体：心跳并发更新以本对象作为锁。
 */
public class Device {

    private final String id;
    private String hardwareModel;
    private String currentVersion;
    /** 升级开始前的稳定版本，回滚目标。 */
    private String stableVersion;
    private DeviceStatus status;
    private long registeredAt;
    private long lastHeartbeatAt;
    private long heartbeatCount;

    public Device(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public String getHardwareModel() {
        return hardwareModel;
    }

    public void setHardwareModel(String hardwareModel) {
        this.hardwareModel = hardwareModel;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(String currentVersion) {
        this.currentVersion = currentVersion;
    }

    public String getStableVersion() {
        return stableVersion;
    }

    public void setStableVersion(String stableVersion) {
        this.stableVersion = stableVersion;
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public void setStatus(DeviceStatus status) {
        this.status = status;
    }

    public long getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(long registeredAt) {
        this.registeredAt = registeredAt;
    }

    public long getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(long lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public long getHeartbeatCount() {
        return heartbeatCount;
    }

    public void setHeartbeatCount(long heartbeatCount) {
        this.heartbeatCount = heartbeatCount;
    }
}
