package com.github.xingjianluo.firmwareotaorchestratorjava.controller;

import com.github.xingjianluo.firmwareotaorchestratorjava.dto.HeartbeatRequest;
import com.github.xingjianluo.firmwareotaorchestratorjava.dto.RegisterRequest;
import com.github.xingjianluo.firmwareotaorchestratorjava.service.DeviceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PostMapping
    public Object register(@RequestBody RegisterRequest request) {
        return deviceService.register(request.getDeviceId(), request.getHardwareModel(),
                request.getFirmwareVersion());
    }

    @PostMapping("/heartbeat")
    public Object heartbeat(@RequestBody HeartbeatRequest request) {
        return deviceService.heartbeat(request.getDeviceId(), request.getFirmwareVersion());
    }

    @GetMapping("/{deviceId}")
    public Object get(@PathVariable String deviceId) {
        return deviceService.get(deviceId);
    }

    @GetMapping
    public Object list() {
        return deviceService.list();
    }
}
