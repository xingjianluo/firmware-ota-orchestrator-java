package com.github.xingjianluo.firmwareotaorchestratorjava.model;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 灰度批次：一批被选中的设备。
 */
public class TaskBatch {

    private final int index;
    private final Set<String> deviceIds = new LinkedHashSet<>();
    private boolean open;
    private boolean closed;
    private int successCount;
    private int failedCount;

    public TaskBatch(int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

    public Set<String> getDeviceIds() {
        return deviceIds;
    }

    public boolean isOpen() {
        return open;
    }

    public void setOpen(boolean open) {
        this.open = open;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(int successCount) {
        this.successCount = successCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(int failedCount) {
        this.failedCount = failedCount;
    }
}
