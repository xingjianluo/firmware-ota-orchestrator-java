package com.github.xingjianluo.firmwareotaorchestratorjava.service;

import com.github.xingjianluo.firmwareotaorchestratorjava.state.DeviceStatus;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.ExcludeReason;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.Device;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.RolloutTask;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.VersionRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 兼容性判定：硬件白名单 + 源版本闭区间 + 设备状态 + 目标版本去重。
 *
 * <p>所有判定均为纯函数式、可解释：返回明确的 {@link ExcludeReason}，由调用方落库，
 * 不允许「静默跳过」。</p>
 */
@Service
public class CompatibilityService {

    private static final Logger log = LoggerFactory.getLogger(CompatibilityService.class);

    /**
     * 判定设备能否参与任务。空 Optional 表示通过；否则给出排除原因。
     */
    public Optional<ExcludeReason> evaluate(RolloutTask task, Device device) {
        ExcludeReason reason = evaluateReason(task, device);
        if (reason != null) {
            log.info("兼容性判定 taskId={} deviceId={} model={} version={} => 排除 {}",
                    task.getId(), device.getId(), device.getHardwareModel(),
                    device.getCurrentVersion(), reason);
            return Optional.of(reason);
        }
        log.info("兼容性判定 taskId={} deviceId={} model={} version={} => 通过",
                task.getId(), device.getId(), device.getHardwareModel(), device.getCurrentVersion());
        return Optional.empty();
    }

    private ExcludeReason evaluateReason(RolloutTask task, Device device) {
        if (device.getStatus() != DeviceStatus.ONLINE) {
            return ExcludeReason.DEVICE_OFFLINE;
        }
        if (device.getCurrentVersion() != null
                && compareVersion(device.getCurrentVersion(), task.getTargetVersion()) == 0) {
            return ExcludeReason.ALREADY_ON_NEW_VERSION;
        }
        if (task.getHardwareWhitelist() != null && !task.getHardwareWhitelist().isEmpty()) {
            if (device.getHardwareModel() == null
                    || !task.getHardwareWhitelist().contains(device.getHardwareModel())) {
                return ExcludeReason.HARDWARE_NOT_MATCH;
            }
        }
        VersionRange range = task.getSourceVersionRange();
        if (range != null && device.getCurrentVersion() != null) {
            boolean geMin = range.getMin() == null
                    || compareVersion(device.getCurrentVersion(), range.getMin()) >= 0;
            boolean leMax = range.getMax() == null
                    || compareVersion(device.getCurrentVersion(), range.getMax()) <= 0;
            if (!geMin || !leMax) {
                return ExcludeReason.VERSION_OUT_OF_RANGE;
            }
        }
        return null;
    }

    public String describe(RolloutTask task, Device device, ExcludeReason reason) {
        return switch (reason) {
            case HARDWARE_NOT_MATCH -> "硬件型号 " + device.getHardwareModel()
                    + " 不在白名单 " + task.getHardwareWhitelist();
            case VERSION_OUT_OF_RANGE -> "当前版本 " + device.getCurrentVersion()
                    + " 不在源版本区间 [" + task.getSourceVersionRange().getMin() + ", "
                    + task.getSourceVersionRange().getMax() + "]";
            case DEVICE_OFFLINE -> "设备当前离线，不允许进入批次";
            case DEVICE_UNKNOWN -> "设备未注册";
            case ALREADY_ON_NEW_VERSION -> "设备版本已是目标版本 " + task.getTargetVersion();
        };
    }

    /**
     * 语义化版本比较：支持以「.」分隔的数字段（如 1.2.10），缺失段按 0 处理。
     *
     * @return a 小于/等于/大于 b 时分别返回负/零/正数
     */
    public static int compareVersion(String a, String b) {
        String[] pa = a == null ? new String[0] : a.trim().split("\\.");
        String[] pb = b == null ? new String[0] : b.trim().split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parseSegment(pa[i]) : 0;
            int vb = i < pb.length ? parseSegment(pb[i]) : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    private static int parseSegment(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
