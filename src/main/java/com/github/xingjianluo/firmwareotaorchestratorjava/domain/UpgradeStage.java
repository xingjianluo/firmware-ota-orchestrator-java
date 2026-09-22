package com.github.xingjianluo.firmwareotaorchestratorjava.domain;

/** 设备上报的升级阶段，必须按 DOWNLOAD -> INSTALL -> ACTIVATE 顺序推进。 */
public enum UpgradeStage {
    DOWNLOAD(0),
    INSTALL(1),
    ACTIVATE(2);

    private final int order;

    UpgradeStage(int order) {
        this.order = order;
    }

    public int order() {
        return order;
    }
}
