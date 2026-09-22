package com.github.xingjianluo.firmwareotaorchestratorjava.service;

import com.github.xingjianluo.firmwareotaorchestratorjava.dto.CreateTaskRequest;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.ExcludeReason;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.RejectReason;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.TaskStatus;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.UpgradePhase;
import com.github.xingjianluo.firmwareotaorchestratorjava.exception.RejectedException;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.Device;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.ExclusionRecord;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.RolloutTask;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.TaskBatch;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.UpgradeRecord;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.VersionRange;
import com.github.xingjianluo.firmwareotaorchestratorjava.store.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 灰度任务创建、批次编排与推进。
 *
 * <p>推进规则：每次 {@link #advance(String)} 在任务锁内评估全部设备——</p>
 * <ol>
 *   <li>不兼容/状态不满足 → 写 {@link ExclusionRecord}（每设备仅记录一次，原因明确不静默）；</li>
 *   <li>已参与（已分配/成功/回滚）的设备不再重复进入批次；</li>
 *   <li>兼容且未参与的设备按注册顺序取 batchSize 台，组成下一批并打开。</li>
 * </ol>
 */
@Service
public class RolloutService {

    private static final Logger log = LoggerFactory.getLogger(RolloutService.class);

    private final InMemoryStore store;
    private final DeviceService deviceService;
    private final CompatibilityService compatibilityService;
    private final java.time.Clock clock;

    public RolloutService(InMemoryStore store,
                          DeviceService deviceService,
                          CompatibilityService compatibilityService,
                          java.time.Clock clock) {
        this.store = store;
        this.deviceService = deviceService;
        this.compatibilityService = compatibilityService;
        this.clock = clock;
    }

    public RolloutTask createTask(CreateTaskRequest request) {
        if (request == null || request.getTargetVersion() == null || request.getTargetVersion().isBlank()) {
            throw new RejectedException(RejectReason.INVALID_ARGUMENT, "targetVersion 不能为空");
        }
        String id = "task-" + (store.tasks().size() + 1) + "-" + Long.toHexString(clock.millis());
        RolloutTask task = new RolloutTask(id);
        task.setTargetVersion(request.getTargetVersion());
        task.setSourceVersionRange(new VersionRange(
                request.getMinSourceVersion(), request.getMaxSourceVersion()));
        task.setHardwareWhitelist(request.getHardwareWhitelist());
        task.setBatchSize(request.getBatchSize() == null || request.getBatchSize() <= 0
                ? 2 : request.getBatchSize());
        task.setDeviceFailureThreshold(request.getDeviceFailureThreshold() == null
                || request.getDeviceFailureThreshold() <= 0 ? 1 : request.getDeviceFailureThreshold());
        task.setTaskFailureRateThreshold(request.getTaskFailureRateThreshold() == null
                ? 0.5 : request.getTaskFailureRateThreshold());
        task.setStatus(TaskStatus.CREATED);
        task.setCreatedAt(clock.millis());
        store.tasks().put(id, task);
        log.info("createTask taskId={} target={} range=[{},{}] models={} batchSize={} devThr={} taskRate={}",
                id, task.getTargetVersion(), request.getMinSourceVersion(),
                request.getMaxSourceVersion(), task.getHardwareWhitelist(),
                task.getBatchSize(), task.getDeviceFailureThreshold(),
                task.getTaskFailureRateThreshold());
        return task;
    }

    public TaskBatch advance(String taskId) {
        RolloutTask task = requireTask(taskId);
        synchronized (task) {
            if (task.getStatus() == TaskStatus.ABORTED) {
                throw new RejectedException(RejectReason.TASK_NOT_IN_PROGRESS,
                        "任务已中止: " + taskId + "，原因: " + task.getAbortReason());
            }
            if (task.getStatus() == TaskStatus.COMPLETED) {
                throw new RejectedException(RejectReason.TASK_NOT_IN_PROGRESS,
                        "任务已完成: " + taskId + "，无可推进批次");
            }
            // 先把已收敛的打开批次关闭（上报路径也会触发，这里保证推进口径幂等）
            closeSettledBatches(task);
            // 仍有打开中的批次时不允许再开新批：必须等当前批次收敛
            for (TaskBatch b : task.getBatches().values()) {
                if (b.isOpen() && !b.isClosed()) {
                    throw new RejectedException(RejectReason.BATCH_NOT_OPEN,
                            "批次 " + b.getIndex() + " 尚未收敛（成功/失败未全部上报），暂不能推进下一批");
                }
            }
            if (task.getStatus() == TaskStatus.CREATED) {
                task.setStatus(TaskStatus.IN_PROGRESS);
            }

            // 先全量评估：不兼容设备必须逐一留痕，绝不因批次选满而静默跳过
            List<Device> candidates = new ArrayList<>();
            for (Device device : deviceService.list()) {
                if (store.upgrades().containsKey(upgradeKey(taskId, device.getId()))) {
                    continue; // 已参与设备绝不重复推进
                }
                var reasonOpt = compatibilityService.evaluate(task, device);
                if (reasonOpt.isPresent()) {
                    if (!alreadyExcluded(taskId, device.getId())) {
                        recordExclusion(task, device, reasonOpt.get());
                    }
                    continue;
                }
                candidates.add(device);
            }
            // 再按注册顺序截取本批
            List<Device> selected = candidates.size() > task.getBatchSize()
                    ? new ArrayList<>(candidates.subList(0, task.getBatchSize()))
                    : candidates;

            if (selected.isEmpty()) {
                log.info("advance 无可推进设备 taskId={}，检查是否收敛完成", taskId);
                if (allParticipantsSettled(task)) {
                    task.setStatus(TaskStatus.COMPLETED);
                    log.info("advance 任务自然完成 taskId={}", taskId);
                }
                return null;
            }

            int index = task.getBatches().size();
            TaskBatch batch = new TaskBatch(index);
            batch.setOpen(true);
            long now = clock.millis();
            for (Device d : selected) {
                batch.getDeviceIds().add(d.getId());
                UpgradeRecord record = new UpgradeRecord(taskId, d.getId());
                record.setBatchIndex(index);
                record.setPhase(UpgradePhase.ASSIGNED);
                record.setUpdatedAt(now);
                store.upgrades().put(upgradeKey(taskId, d.getId()), record);
            }
            task.getBatches().put(index, batch);
            log.info("advance 打开批次 taskId={} batch={} devices={}",
                    taskId, index, batch.getDeviceIds());
            return batch;
        }
    }

    /** 关闭其中所有设备均已收敛（激活成功或已回滚）的打开批次。调用方需持 task 锁。 */
    void closeSettledBatches(RolloutTask task) {
        for (TaskBatch batch : task.getBatches().values()) {
            if (!batch.isOpen() || batch.isClosed()) {
                continue;
            }
            boolean settled = true;
            for (String deviceId : batch.getDeviceIds()) {
                UpgradeRecord r = store.upgrades().get(upgradeKey(task.getId(), deviceId));
                if (r == null || (r.getPhase() != UpgradePhase.ACTIVATED
                        && r.getPhase() != UpgradePhase.ROLLED_BACK)) {
                    settled = false;
                    break;
                }
            }
            if (settled) {
                batch.setClosed(true);
                log.info("批次收敛关闭 taskId={} batch={} success={} failed={}",
                        task.getId(), batch.getIndex(), batch.getSuccessCount(), batch.getFailedCount());
            }
        }
    }

    private boolean allParticipantsSettled(RolloutTask task) {        boolean hasParticipant = false;
        for (UpgradeRecord r : store.upgrades().values()) {
            if (!r.getTaskId().equals(task.getId())) {
                continue;
            }
            hasParticipant = true;
            if (r.getPhase() != UpgradePhase.ACTIVATED && r.getPhase() != UpgradePhase.ROLLED_BACK) {
                return false;
            }
        }
        return hasParticipant;
    }

    private boolean alreadyExcluded(String taskId, String deviceId) {
        return store.exclusions().stream()
                .anyMatch(e -> e.getTaskId().equals(taskId) && e.getDeviceId().equals(deviceId));
    }

    private void recordExclusion(RolloutTask task, Device device, ExcludeReason reason) {
        ExclusionRecord record = new ExclusionRecord(task.getId(), device.getId(), reason,
                compatibilityService.describe(task, device, reason), clock.millis());
        store.exclusions().add(record);
        log.warn("设备被排除 taskId={} deviceId={} reason={} detail={}",
                task.getId(), device.getId(), reason, record.getDetail());
    }

    public Map<String, Object> progress(String taskId) {
        RolloutTask task = requireTask(taskId);
        synchronized (task) {
            int total = 0;
            int success = 0;
            int failed = 0;
            int inProgress = 0;
            Map<Integer, Map<String, Object>> batchViews = new LinkedHashMap<>();
            for (TaskBatch batch : task.getBatches().values()) {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("open", batch.isOpen());
                view.put("closed", batch.isClosed());
                view.put("devices", new ArrayList<>(batch.getDeviceIds()));
                view.put("successCount", batch.getSuccessCount());
                view.put("failedCount", batch.getFailedCount());
                batchViews.put(batch.getIndex(), view);
            }
            for (UpgradeRecord r : store.upgrades().values()) {
                if (!r.getTaskId().equals(taskId)) {
                    continue;
                }
                total++;
                if (r.getPhase() == UpgradePhase.ACTIVATED) {
                    success++;
                } else if (r.getPhase() == UpgradePhase.ROLLED_BACK) {
                    failed++;
                } else {
                    inProgress++;
                }
            }
            double failureRate = total == 0 ? 0 : (double) failed / total;
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("taskId", taskId);
            view.put("status", task.getStatus());
            view.put("targetVersion", task.getTargetVersion());
            view.put("participants", total);
            view.put("success", success);
            view.put("rolledBack", failed);
            view.put("inProgress", inProgress);
            view.put("failureRate", failureRate);
            view.put("abortReason", task.getAbortReason());
            view.put("batches", batchViews);
            view.put("excluded", store.exclusions().stream()
                    .filter(e -> e.getTaskId().equals(taskId)).count());
            return view;
        }
    }

    public List<ExclusionRecord> exclusions(String taskId) {
        requireTask(taskId);
        return store.exclusions().stream().filter(e -> e.getTaskId().equals(taskId)).toList();
    }

    private RolloutTask requireTask(String taskId) {
        RolloutTask task = store.tasks().get(taskId);
        if (task == null) {
            throw new RejectedException(RejectReason.TASK_NOT_FOUND, "任务不存在: " + taskId);
        }
        return task;
    }

    public static String upgradeKey(String taskId, String deviceId) {
        return taskId + ":" + deviceId;
    }
}
