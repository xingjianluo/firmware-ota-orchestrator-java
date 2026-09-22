package com.github.xingjianluo.firmwareotaorchestratorjava.domain;

/** 设备在发布任务中的阶段状态。 */
public enum DevicePhase {
    /** 不满足兼容性或状态条件，被明确排除（记录原因）。 */
    EXCLUDED,
    /** 已分入某批次，等待上报。 */
    PENDING,
    /** 下载完成。 */
    DOWNLOADED,
    /** 安装完成。 */
    INSTALLED,
    /** 激活完成，升级成功（终态）。 */
    COMPLETED,
    /** 某阶段失败。 */
    FAILED,
    /** 失败阈值触发后已回滚到上一稳定版本（终态）。 */
    ROLLED_BACK
}
