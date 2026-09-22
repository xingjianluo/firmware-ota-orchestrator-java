package com.github.xingjianluo.firmwareotaorchestratorjava.domain;

/** 设备在某个发布任务中的推进状态（属于某个批次的记录）。 */
public class DeviceUpgradeState {

    private final String deviceId;
    /** 批次号；-1 表示被排除未分批。批次归属一旦确定不再改变。 */
    private final int batch;
    private DevicePhase phase;
    /** 排除原因（仅 EXCLUDED 时有值）。 */
    private String exclusionReason;
    /** 已成功应用的最高阶段序号，用于拒绝乱序/迟到上报；-1 表示尚未完成任何阶段。 */
    private int lastAppliedStageOrder = -1;
    /** 最近一次应用的上报指纹（阶段+结果），用于重复上报幂等。 */
    private String lastReportFingerprint;
    /** 最后一次应用上报的时间戳，迟到上报据此拒绝。 */
    private long lastReportAt = -1;

    public DeviceUpgradeState(String deviceId, int batch) {
        this.deviceId = deviceId;
        this.batch = batch;
        this.phase = batch < 0 ? DevicePhase.EXCLUDED : DevicePhase.PENDING;
    }

    public String getDeviceId() { return deviceId; }
    public int getBatch() { return batch; }
    public synchronized DevicePhase getPhase() { return phase; }
    public synchronized String getExclusionReason() { return exclusionReason; }
    public synchronized int getLastAppliedStageOrder() { return lastAppliedStageOrder; }
    public synchronized String getLastReportFingerprint() { return lastReportFingerprint; }
    public synchronized long getLastReportAt() { return lastReportAt; }

    public synchronized void setPhase(DevicePhase phase) { this.phase = phase; }
    public synchronized void setExclusionReason(String reason) { this.exclusionReason = reason; }
    public synchronized void recordAppliedReport(int stageOrder, String fingerprint, long timestamp) {
        this.lastAppliedStageOrder = stageOrder;
        this.lastReportFingerprint = fingerprint;
        this.lastReportAt = timestamp;
    }
    public synchronized void recordDuplicateReport(String fingerprint, long timestamp) {
        this.lastReportFingerprint = fingerprint;
        this.lastReportAt = timestamp;
    }
}
