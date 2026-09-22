package com.github.xingjianluo.firmwareotaorchestratorjava.state;

/**
 * 设备被批次排除的原因（兼容性 / 状态不满足）。
 */
public enum ExcludeReason {
    HARDWARE_NOT_MATCH,
    VERSION_OUT_OF_RANGE,
    DEVICE_OFFLINE,
    DEVICE_UNKNOWN,
    ALREADY_ON_NEW_VERSION
}
