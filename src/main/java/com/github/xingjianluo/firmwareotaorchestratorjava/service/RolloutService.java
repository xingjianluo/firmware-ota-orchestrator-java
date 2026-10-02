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
 * 灰度任务创建、批次编排与推进（默认串行分批；显式配置 maxInFlight 时为并行窗口模型）。
 *
 * <p>推进规则：每次 {@link #advance(String)} 在任务锁内评估全部设备——</p>
 * <ol>
 *   <li>不兼容/状态不满足 → 写 {@link ExclusionRecord}（每设备仅记录一次，原因明确不静默）；
 *       排除不是永久黑名单：设备后续条件变好（上线、版本变动）时可再次成为候选；</li>
 *   <li>已参与（已分配/成功/回滚）的设备不再重复进入批次；</li>
 *   <li>兼容且未参与的设备按注册顺序入选：
 *     <ul>
 *       <li><b>默认（未配置 maxInFlight）串行分批</b>：当前批次未整批收敛前绝不开
 *           下一批，即使批次内已有设备先收敛；整批收敛后下一轮只开一个新批次；</li>
 *       <li><b>并行窗口（显式 maxInFlight &gt; 0）</b>：在剩余名额
 *           （maxInFlight - 当前在途数）内尽量铺满，一次推进可同时打开多个
 *           不超过 batchSize 的批次并行推进。</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>在途 = 已分配但尚未收敛（阶段既非 ACTIVATED 也非 ROLLED_BACK）的参与设备。
 * 设备激活成功或回滚收敛即释放窗口名额；窗口满时 advance 反压拒绝（BATCH_NOT_OPEN）。
 * 只有「既无在途设备、也无当前仍兼容的未参与设备」时任务才收尾（成功或回滚收敛
 * 都会触发，ABORTED 任务不参与收尾，状态不被翻回 COMPLETED）。</p>
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
        // 并行窗口只在显式配置 maxInFlight(>0) 时启用；未配置时保持旧版默认语义：
        // 必须等当前这批设备整批收敛（全部成功或回滚）后才能开下一批。
        boolean parallelWindow = request.getMaxInFlight() != null && request.getMaxInFlight() > 0;
        task.setParallelWindowEnabled(parallelWindow);
        task.setMaxInFlight(parallelWindow ? request.getMaxInFlight() : task.getBatchSize());
        task.setDeviceFailureThreshold(request.getDeviceFailureThreshold() == null
                || request.getDeviceFailureThreshold() <= 0 ? 1 : request.getDeviceFailureThreshold());
        task.setTaskFailureRateThreshold(request.getTaskFailureRateThreshold() == null
                ? 0.5 : request.getTaskFailureRateThreshold());
        task.setStatus(TaskStatus.CREATED);
        task.setCreatedAt(clock.millis());
        store.tasks().put(id, task);
        log.info("createTask taskId={} target={} range=[{},{}] models={} batchSize={} maxInFlight={} devThr={} taskRate={}",
                id, task.getTargetVersion(), request.getMinSourceVersion(),
                request.getMaxSourceVersion(), task.getHardwareWhitelist(),
                task.getBatchSize(), task.getMaxInFlight(),
                task.getDeviceFailureThreshold(), task.getTaskFailureRateThreshold());
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

            // 并行窗口：在途 = 已分配但尚未收敛（非 ACTIVATED/ROLLED_BACK）的参与设备
            int inFlight = countInFlight(task);

            if (!task.isParallelWindowEnabled()) {
                // 默认串行分批语义：只要还有未整批收敛的打开批次，就绝不开下一批，
                // 哪怕其中部分设备已经先收敛（不会把新设备悄悄补进旧批次节奏）。
                if (hasOpenBatch(task)) {
                    throw new RejectedException(RejectReason.BATCH_NOT_OPEN,
                            "当前批次尚未整批收敛（在途 " + inFlight + "），"
                                    + "须等本批设备全部激活成功或回滚后才能推进下一批");
                }
            } else {
                // 并行窗口语义：按在途名额滑动补人
                int capacity = task.getMaxInFlight() - inFlight;
                if (capacity <= 0) {
                    // 窗口已满：本次不补人，等设备收敛腾位置后下一次推进再补
                    throw new RejectedException(RejectReason.BATCH_NOT_OPEN,
                            "并行窗口已满（在途 " + inFlight + "/" + task.getMaxInFlight()
                                    + "），等待在途设备激活成功或回滚收敛后再推进");
                }
            }

            // 全量评估：不兼容设备必须逐一留痕，绝不因窗口选满而静默跳过。
            // 注意：排除不留永久黑名单——设备本次条件变好（上线/版本变动）即可重新成为候选。
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

            if (candidates.isEmpty()) {
                log.info("advance 暂无可推进设备 taskId={} inFlight={}", taskId, inFlight);
                if (inFlight == 0) {
                    // 既没有可推进设备，也没有在途设备 → 任务收尾
                    task.setStatus(TaskStatus.COMPLETED);
                    log.info("advance 任务自然完成 taskId={}", taskId);
                }
                return null;
            }

            if (task.getStatus() == TaskStatus.CREATED) {
                task.setStatus(TaskStatus.IN_PROGRESS);
            }

            // 本轮可选设备数：
            // - 默认串行：上一批已整批收敛（上方闸门保证），本轮只开「一个」新批次 → batchSize；
            // - 并行窗口：只用空出的在途名额把窗口尽量铺满 → maxInFlight - inFlight。
            int capacity = task.isParallelWindowEnabled()
                    ? task.getMaxInFlight() - inFlight : task.getBatchSize();
            List<Device> selected = candidates.size() > capacity
                    ? new ArrayList<>(candidates.subList(0, capacity)) : candidates;

            // 一次推进可同时打开多个批次（每批仍不超过 batchSize），多批并行推进
            long now = clock.millis();
            TaskBatch firstOpened = null;
            for (int from = 0; from < selected.size(); from += task.getBatchSize()) {
                int to = Math.min(from + task.getBatchSize(), selected.size());
                int index = task.getBatches().size();
                TaskBatch batch = new TaskBatch(index);
                batch.setOpen(true);
                for (Device d : selected.subList(from, to)) {
                    batch.getDeviceIds().add(d.getId());
                    UpgradeRecord record = new UpgradeRecord(taskId, d.getId());
                    record.setBatchIndex(index);
                    record.setPhase(UpgradePhase.ASSIGNED);
                    record.setUpdatedAt(now);
                    store.upgrades().put(upgradeKey(taskId, d.getId()), record);
                }
                task.getBatches().put(index, batch);
                if (firstOpened == null) {
                    firstOpened = batch;
                }
                log.info("advance 打开批次 taskId={} batch={} devices={} 窗口占用={}/{}",
                        taskId, index, batch.getDeviceIds(),
                        countInFlight(task), task.getMaxInFlight());
            }
            return firstOpened;
        }
    }

    /** 是否存在仍打开（未整批收敛关闭）的批次。调用方需持 task 锁。 */
    boolean hasOpenBatch(RolloutTask task) {
        for (TaskBatch batch : task.getBatches().values()) {
            if (batch.isOpen() && !batch.isClosed()) {
                return true;
            }
        }
        return false;
    }

    /** 统计任务下仍在途（未收敛）的设备数：阶段既非激活成功也非已回滚。调用方需持 task 锁。 */
    int countInFlight(RolloutTask task) {
        int inFlight = 0;
        for (UpgradeRecord r : store.upgrades().values()) {
            if (r.getTaskId().equals(task.getId())
                    && r.getPhase() != UpgradePhase.ACTIVATED
                    && r.getPhase() != UpgradePhase.ROLLED_BACK) {
                inFlight++;
            }
        }
        return inFlight;
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
            int inFlightNow = total - success - failed;
            // 默认串行模式：批次未整批收敛前不放行任何新设备，剩余可补名额恒为 0；
            // 并行窗口模式：剩余名额 = maxInFlight - 在途数。
            int windowRemaining = !task.isParallelWindowEnabled() && hasOpenBatch(task)
                    ? 0 : Math.max(0, task.getMaxInFlight() - inFlightNow);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("taskId", taskId);
            view.put("status", task.getStatus());
            view.put("targetVersion", task.getTargetVersion());
            view.put("batchSize", task.getBatchSize());
            view.put("maxInFlight", task.getMaxInFlight());
            view.put("parallelWindowEnabled", task.isParallelWindowEnabled());
            view.put("participants", total);
            view.put("success", success);
            view.put("rolledBack", failed);
            view.put("inProgress", inProgress);
            // 并行窗口：当前在途设备数与剩余可补名额
            view.put("inFlight", inFlightNow);
            view.put("windowRemaining", windowRemaining);
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
