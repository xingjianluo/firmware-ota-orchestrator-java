package com.github.xingjianluo.firmwareotaorchestratorjava.model;

/**
 * 语义化版本闭区间 [min, max]。
 */
public class VersionRange {

    private String min;
    private String max;

    public VersionRange() {
    }

    public VersionRange(String min, String max) {
        this.min = min;
        this.max = max;
    }

    public String getMin() {
        return min;
    }

    public void setMin(String min) {
        this.min = min;
    }

    public String getMax() {
        return max;
    }

    public void setMax(String max) {
        this.max = max;
    }
}
