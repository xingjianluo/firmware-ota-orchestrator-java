package com.github.xingjianluo.firmwareotaorchestratorjava.service;

import com.github.xingjianluo.firmwareotaorchestratorjava.domain.Device;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.DeviceStatus;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.FirmwareVersion;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.RejectReason;
import com.github.xingjianluo.firmwareotaorchestratorjava.error.OperationRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 设备注册、心跳与离线判定。 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    /** 超过该毫秒数未上报心跳即判定离线。 */
    public static final long OFFLINE_TIMEOUT_MS = 60_000L;

    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final Clock clock;

    public DeviceService(Clock clock) {
        this.clock = clock;
    }

    /**
     * 注册设备。已存在且离线的设备重新注册时恢复在线（视为设备重连），
     * 已在线的设备重复注册被拒绝。
     */
    public Device register(String deviceId, String hardwareModel, String initialVersion) {
        long now = clock.millis();
        Device existing = devices.get(deviceId);
        if (existing != null) {
            synchronized (existing) {
                if (existing.getStatus() == DeviceStatus.ONLINE) {
                    throw new OperationRejectedException(RejectReason.DEVICE_ALREADY_REGISTERED,
                            "设备已注册且在线: " + deviceId);
                }
                existing.markOnline(now);
                log.info("设备重连上线 deviceId={} at={}", deviceId, now);
                return existing;
            }
        }
        Device created = new Device(deviceId, hardwareModel, FirmwareVersion.parse(initialVersion), now);
        Device raced = devices.putIfAbsent(deviceId, created);
        if (raced != null) {
            // 并发注册：递归走已存在分支，保证结果一致
            return register(deviceId, hardwareModel, initialVersion);
        }
        log.info("设备注册 deviceId={} hardware={} version={}", deviceId, hardwareModel, initialVersion);
        return created;
    }

    /**
     * 处理心跳。未知设备与离线设备被拒绝且原因可区分；
     * 重复/乱序心跳由 Device.applyHeartbeat 幂等吸收。
     */
    public Device heartbeat(String deviceId, String reportedVersion) {
        Device device = devices.get(deviceId);
        if (device == null) {
            throw new OperationRejectedException(RejectReason.UNKNOWN_DEVICE, "未知设备: " + deviceId);
        }
        long now = clock.millis();
        FirmwareVersion version = FirmwareVersion.parse(reportedVersion);
        synchronized (device) {
            if (device.getStatus() == DeviceStatus.OFFLINE) {
                throw new OperationRejectedException(RejectReason.DEVICE_OFFLINE,
                        "设备已离线，心跳被拒绝: " + deviceId);
            }
            boolean applied = device.applyHeartbeat(version, now);
            log.info("心跳 deviceId={} version={} at={} applied={} count={}",
                    deviceId, version, now, applied, device.getHeartbeatCount());
        }
        return device;
    }

    /** 扫描并标记超时未上报的设备为离线，返回本次被判定离线的设备。 */
    public List<Device> sweepOffline() {
        long now = clock.millis();
        List<Device> wentOffline = new ArrayList<>();
        for (Device device : devices.values()) {
            synchronized (device) {
                if (device.getStatus() == DeviceStatus.ONLINE
                        && now - device.getLastHeartbeatAt() > OFFLINE_TIMEOUT_MS) {
                    device.markOffline();
                    wentOffline.add(device);
                    log.info("设备离线 deviceId={} lastHeartbeatAt={} now={}",
                            device.getDeviceId(), device.getLastHeartbeatAt(), now);
                }
            }
        }
        return wentOffline;
    }

    public Device getDevice(String deviceId) {
        Device device = devices.get(deviceId);
        if (device == null) {
            throw new OperationRejectedException(RejectReason.UNKNOWN_DEVICE, "未知设备: " + deviceId);
        }
        return device;
    }

    /** 要求设备存在且在线，否则抛出带可区分原因的拒绝异常。 */
    public Device requireOnline(String deviceId) {
        Device device = getDevice(deviceId);
        if (device.getStatus() == DeviceStatus.OFFLINE) {
            throw new OperationRejectedException(RejectReason.DEVICE_OFFLINE, "设备已离线: " + deviceId);
        }
        return device;
    }

    public List<Device> listDevices() {
        return List.copyOf(devices.values());
    }
}
