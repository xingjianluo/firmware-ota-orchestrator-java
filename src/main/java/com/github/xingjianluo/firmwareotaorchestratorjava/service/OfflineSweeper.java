package com.github.xingjianluo.firmwareotaorchestratorjava.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时把长时间未心跳的设备置为离线。
 */
@Component
public class OfflineSweeper {

    private final DeviceService deviceService;

    public OfflineSweeper(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @Scheduled(fixedDelay = 10_000L)
    public void sweep() {
        deviceService.sweepOffline();
    }
}
