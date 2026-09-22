package com.github.xingjianluo.firmwareotaorchestratorjava.state;

/**
 * 升级结果上报类型。
 */
public enum ReportType {
    DOWNLOAD_STARTED,
    DOWNLOAD_SUCCESS,
    DOWNLOAD_FAILED,
    INSTALL_STARTED,
    INSTALL_SUCCESS,
    INSTALL_FAILED,
    ACTIVATE_STARTED,
    ACTIVATE_SUCCESS,
    ACTIVATE_FAILED
}
