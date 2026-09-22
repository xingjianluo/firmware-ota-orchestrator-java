package com.github.xingjianluo.firmwareotaorchestratorjava.domain;

/** 语义化固件版本，可比较、可解析 "x.y.z" 形式。 */
public record FirmwareVersion(int major, int minor, int patch) implements Comparable<FirmwareVersion> {

    public static FirmwareVersion parse(String text) {
        String[] parts = text.trim().split("\\.");
        if (parts.length < 1 || parts.length > 3) {
            throw new IllegalArgumentException("非法版本号: " + text);
        }
        int[] nums = {0, 0, 0};
        for (int i = 0; i < parts.length; i++) {
            nums[i] = Integer.parseInt(parts[i]);
        }
        return new FirmwareVersion(nums[0], nums[1], nums[2]);
    }

    @Override
    public int compareTo(FirmwareVersion other) {
        if (major != other.major) return Integer.compare(major, other.major);
        if (minor != other.minor) return Integer.compare(minor, other.minor);
        return Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
