package com.github.xingjianluo.firmwareotaorchestratorjava.domain;

/** 操作被拒绝的可区分原因。 */
public enum RejectReason {
    UNKNOWN_DEVICE,
    DEVICE_OFFLINE,
    DEVICE_ALREADY_REGISTERED,
    UNKNOWN_TASK,
    ILLEGAL_TASK_STATE,
    DEVICE_NOT_IN_TASK,
    DEVICE_EXCLUDED,
    ILLEGAL_TRANSITION,
    STALE_REPORT,
    TASK_ALREADY_ABORTED
}
