package com.github.xingjianluo.firmwareotaorchestratorjava.service;

import com.github.xingjianluo.firmwareotaorchestratorjava.state.DeviceStatus;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.RejectReason;
import com.github.xingjianluo.firmwareotaorchestratorjava.exception.RejectedException;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.Device;
import com.github.xingjianluo.firmwareotaorchestratorjava.store.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备注册、心跳与离线判定。
 *
 * <p>并发模型：每台设备以其 {@link Device} 实例作为内置锁，心跳的「判定-更新」
 * 在锁内一次完成，保证：心跳计数只增不减、lastHeartbeatAt 单调不倒退、版本不回退。</p>
 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    /** 超过该毫秒数未心跳即判定离线。 */
    public static final long OFFLINE_TIMEOUT_MS = 60_000L;

    private final InMemoryStore store;
    private final java.time.Clock clock;

    public DeviceService(InMemoryStore store, java.time.Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public Device register(String deviceId, String hardwareModel, String firmwareVersion) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new RejectedException(RejectReason.INVALID_ARGUMENT, "deviceId 不能为空");
        }
        long now = clock.millis();
        Device created = new Device(deviceId);
        Device existing = store.devices().putIfAbsent(deviceId, created);
        if (existing != null) {
            // 重复注册：幂等返回已有设备，不重置任何状态
            log.info("register 幂等命中 deviceId={} status={} count={}",
                    deviceId, existing.getStatus(), existing.getHeartbeatCount());
            return existing;
        }
        created.setHardwareModel(hardwareModel);
        created.setCurrentVersion(firmwareVersion);
        created.setStableVersion(firmwareVersion);
        created.setStatus(DeviceStatus.ONLINE);
        created.setRegisteredAt(now);
        created.setLastHeartbeatAt(now);
        created.setHeartbeatCount(1L);
        log.info("register 新设备 deviceId={} model={} version={}", deviceId, hardwareModel, firmwareVersion);
        return created;
    }

    public Device heartbeat(String deviceId, String firmwareVersion) {
        Device device = store.devices().get(deviceId);
        if (device == null) {
            // 未知设备与离线设备必须给出可区分的拒绝原因
            throw new RejectedException(RejectReason.DEVICE_NOT_FOUND,
                    "设备不存在: " + deviceId + "，请先注册");
        }
        long now = clock.millis();
        synchronized (device) {
            long previousTs = device.getLastHeartbeatAt();
            // 迟到/乱序心跳：时间戳不更新、计数不增加，避免抖动
            if (now < previousTs) {
                log.info("heartbeat 迟到忽略 deviceId={} incomingTs={} lastTs={}", deviceId, now, previousTs);
                return device;
            }
            device.setLastHeartbeatAt(now);
            device.setHeartbeatCount(device.getHeartbeatCount() + 1);
            // 离线设备重新心跳即恢复在线（此时是新的一次有效接触）
            device.setStatus(DeviceStatus.ONLINE);
            // 版本只接受不低于当前的上报，杜绝版本倒退
            if (firmwareVersion != null
                    && (device.getCurrentVersion() == null
                        || CompatibilityService.compareVersion(firmwareVersion, device.getCurrentVersion()) >= 0)) {
                device.setCurrentVersion(firmwareVersion);
            }
            log.info("heartbeat 受理 deviceId={} version={} count={} status={}",
                    deviceId, device.getCurrentVersion(), device.getHeartbeatCount(), device.getStatus());
            return device;
        }
    }

    /** 基于当前时间重新判定在线状态（不写回），供 requireOnline / 查询使用。 */
    public boolean isOnline(Device device) {
        long now = clock.millis();
        synchronized (device) {
            return device.getStatus() == DeviceStatus.ONLINE
                    && now - device.getLastHeartbeatAt() <= OFFLINE_TIMEOUT_MS;
        }
    }

    public Device requireOnline(String deviceId) {
        Device device = store.devices().get(deviceId);
        if (device == null) {
            throw new RejectedException(RejectReason.DEVICE_NOT_FOUND,
                    "设备不存在: " + deviceId + "，操作被拒绝");
        }
        if (!isOnline(device)) {
            throw new RejectedException(RejectReason.DEVICE_OFFLINE,
                    "设备离线: " + deviceId + "，最后心跳距今 "
                            + (clock.millis() - device.getLastHeartbeatAt()) + "ms");
        }
        return device;
    }

    public Device get(String deviceId) {
        Device device = store.devices().get(deviceId);
        if (device == null) {
            throw new RejectedException(RejectReason.DEVICE_NOT_FOUND, "设备不存在: " + deviceId);
        }
        // 查询时把超时状态物化，保证 GET 与 sweep 的判定口径一致
        synchronized (device) {
            if (device.getStatus() == DeviceStatus.ONLINE
                    && clock.millis() - device.getLastHeartbeatAt() > OFFLINE_TIMEOUT_MS) {
                device.setStatus(DeviceStatus.OFFLINE);
            }
        }
        return device;
    }

    public List<Device> sweepOffline() {
        List<Device> turned = new ArrayList<>();
        long now = clock.millis();
        for (Device device : store.devices().values()) {
            synchronized (device) {
                if (device.getStatus() == DeviceStatus.ONLINE
                        && now - device.getLastHeartbeatAt() > OFFLINE_TIMEOUT_MS) {
                    device.setStatus(DeviceStatus.OFFLINE);
                    turned.add(device);
                    log.info("sweepOffline 置离线 deviceId={} 静默={}ms",
                            device.getId(), now - device.getLastHeartbeatAt());
                }
            }
        }
        return turned;
    }

    public List<Device> list() {
        return new ArrayList<>(store.devices().values());
    }
}
