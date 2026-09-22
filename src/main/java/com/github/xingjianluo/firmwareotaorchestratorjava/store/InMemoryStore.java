package com.github.xingjianluo.firmwareotaorchestratorjava.store;

import com.github.xingjianluo.firmwareotaorchestratorjava.model.Device;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.ExclusionRecord;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.RolloutTask;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.UpgradeRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地内存状态：设备、任务、升级记录与排除记录。
 */
@Component
public class InMemoryStore {

    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final Map<String, RolloutTask> tasks = new ConcurrentHashMap<>();
    /** key = taskId + ":" + deviceId */
    private final Map<String, UpgradeRecord> upgrades = new ConcurrentHashMap<>();
    private final List<ExclusionRecord> exclusions = new ArrayList<>();

    public Map<String, Device> devices() {
        return devices;
    }

    public Map<String, RolloutTask> tasks() {
        return tasks;
    }

    public Map<String, UpgradeRecord> upgrades() {
        return upgrades;
    }

    public List<ExclusionRecord> exclusions() {
        return exclusions;
    }
}
