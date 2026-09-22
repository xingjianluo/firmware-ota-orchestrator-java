package com.github.xingjianluo.firmwareotaorchestratorjava.domain;

/** 发布任务状态机：DRAFT -> RUNNING -> COMPLETED / ABORTED（失败阈值触发回滚）。 */
public enum TaskStatus {
    DRAFT,
    RUNNING,
    COMPLETED,
    ABORTED
}
