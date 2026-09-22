package com.github.xingjianluo.firmwareotaorchestratorjava.domain;

/**
 * 设备本地状态。所有写操作通过 synchronized 方法保证并发心跳下的一致性：
 * 心跳只接受时间戳单调递增的上报，重复/乱序心跳不改变状态与计数。
 */
public class Device {

    private final String deviceId;
    private final String hardwareModel;
    private DeviceStatus status;
    private FirmwareVersion currentVersion;
    private FirmwareVersion lastStableVersion;
    private long heartbeatCount;
    private long lastHeartbeatAt;

    public Device(String deviceId, String hardwareModel, FirmwareVersion initialVersion, long registeredAt) {
        this.deviceId = deviceId;
        this.hardwareModel = hardwareModel;
        this.currentVersion = initialVersion;
        this.lastStableVersion = initialVersion;
        this.status = DeviceStatus.ONLINE;
        this.heartbeatCount = 0;
        this.lastHeartbeatAt = registeredAt;
    }

    public String getDeviceId() { return deviceId; }
    public String getHardwareModel() { return hardwareModel; }
    public synchronized DeviceStatus getStatus() { return status; }
    public synchronized FirmwareVersion getCurrentVersion() { return currentVersion; }
    public synchronized FirmwareVersion getLastStableVersion() { return lastStableVersion; }
    public synchronized long getHeartbeatCount() { return heartbeatCount; }
    public synchronized long getLastHeartbeatAt() { return lastHeartbeatAt; }

    /**
     * 应用心跳：仅当时间戳严格大于已记录的最后心跳时间时才生效，
     * 重复/乱序/并发心跳被幂等吸收，不会造成计数膨胀或版本倒退。
     *
     * @return true 表示心跳被接受并改变了状态
     */
    public synchronized boolean applyHeartbeat(FirmwareVersion reportedVersion, long timestamp) {
        if (timestamp <= lastHeartbeatAt) {
            return false;
        }
        this.lastHeartbeatAt = timestamp;
        this.heartbeatCount++;
        // 版本只允许前进，不允许倒退（回滚由编排服务显式执行，不走心跳）
        if (reportedVersion.compareTo(this.currentVersion) > 0) {
            this.currentVersion = reportedVersion;
        }
        return true;
    }

    public synchronized void markOffline() {
        this.status = DeviceStatus.OFFLINE;
    }

    public synchronized void markOnline(long timestamp) {
        this.status = DeviceStatus.ONLINE;
        this.lastHeartbeatAt = timestamp;
    }

    /** 升级成功后提升稳定版本基线。 */
    public synchronized void promoteVersion(FirmwareVersion newVersion) {
        this.lastStableVersion = this.currentVersion;
        this.currentVersion = newVersion;
    }

    /** 回滚到上一稳定版本。 */
    public synchronized void rollbackToLastStable() {
        this.currentVersion = this.lastStableVersion;
    }
}
