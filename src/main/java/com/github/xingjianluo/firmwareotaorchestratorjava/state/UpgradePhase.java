package com.github.xingjianluo.firmwareotaorchestratorjava.state;

/**
 * 单台设备的升级阶段状态机。
 *
 * <pre>
 * IDLE -> ASSIGNED -> DOWNLOADING -> DOWNLOADED -> INSTALLING
 *      -> INSTALLED -> ACTIVATING -> ACTIVATED (成功)
 * 任意在途阶段上报 FAIL -> ROLLING_BACK -> ROLLED_BACK
 * </pre>
 */
public enum UpgradePhase {
    IDLE,
    ASSIGNED,
    DOWNLOADING,
    DOWNLOADED,
    INSTALLING,
    INSTALLED,
    ACTIVATING,
    ACTIVATED,
    FAILED,
    ROLLING_BACK,
    ROLLED_BACK
}
