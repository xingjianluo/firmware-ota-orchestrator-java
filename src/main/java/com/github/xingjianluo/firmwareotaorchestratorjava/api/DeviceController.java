package com.github.xingjianluo.firmwareotaorchestratorjava.api;

import com.github.xingjianluo.firmwareotaorchestratorjava.domain.Device;
import com.github.xingjianluo.firmwareotaorchestratorjava.service.DeviceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    public record RegisterRequest(String deviceId, String hardwareModel, String initialVersion) {}
    public record HeartbeatRequest(String reportedVersion) {}

    @PostMapping
    public Device register(@RequestBody RegisterRequest request) {
        return deviceService.register(request.deviceId(), request.hardwareModel(), request.initialVersion());
    }

    @PostMapping("/{deviceId}/heartbeat")
    public Device heartbeat(@PathVariable String deviceId, @RequestBody HeartbeatRequest request) {
        return deviceService.heartbeat(deviceId, request.reportedVersion());
    }

    @PostMapping("/sweep-offline")
    public List<Device> sweepOffline() {
        return deviceService.sweepOffline();
    }

    @GetMapping("/{deviceId}")
    public Device get(@PathVariable String deviceId) {
        return deviceService.getDevice(deviceId);
    }

    @GetMapping
    public List<Device> list() {
        return deviceService.listDevices();
    }
}
