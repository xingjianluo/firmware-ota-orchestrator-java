package com.github.xingjianluo.firmwareotaorchestratorjava.state;

/**
 * 非法 / 过期状态转换被拒绝的可区分原因码。
 */
public enum RejectReason {
    DEVICE_NOT_FOUND,
    DEVICE_OFFLINE,
    TASK_NOT_FOUND,
    NOT_TASK_PARTICIPANT,
    ILLEGAL_PHASE_TRANSITION,
    STALE_RESULT,
    ROLLBACK_ALREADY_DONE,
    TASK_NOT_IN_PROGRESS,
    BATCH_NOT_OPEN,
    INVALID_ARGUMENT
}
