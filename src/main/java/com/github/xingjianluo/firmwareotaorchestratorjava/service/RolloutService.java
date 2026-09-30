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
import java.util.Comparator;
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
        // 显式给出正的在途上限才开启并行窗口；否则沿用严格串行批次模式（旧语义不变）
        if (request.getMaxInFlightDevices() != null && request.getMaxInFlightDevices() > 0) {
            task.setParallelWindow(true);
            task.setMaxInFlightDevices(request.getMaxInFlightDevices());
        } else {
            task.setParallelWindow(false);
            task.setMaxInFlightDevices(task.getBatchSize());
        }
        task.setStatus(TaskStatus.CREATED);
        task.setCreatedAt(clock.millis());
        store.tasks().put(id, task);
        log.info("createTask taskId={} target={} range=[{},{}] models={} batchSize={} devThr={} taskRate={} parallelWindow={} maxInFlight={}",
                id, task.getTargetVersion(), request.getMinSourceVersion(),
                request.getMaxSourceVersion(), task.getHardwareWhitelist(),
                task.getBatchSize(), task.getDeviceFailureThreshold(),
                task.getTaskFailureRateThreshold(), task.isParallelWindow(),
                task.isParallelWindow() ? task.getMaxInFlightDevices() : null);
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
            return task.isParallelWindow() ? advanceParallel(task) : advanceSerial(task);
        }
    }

    /**
     * 严格串行批次推进（旧语义）：必须等当前打开批次整体收敛后才能开下一批。
     * 调用方需持 task 锁。
     */
    private TaskBatch advanceSerial(RolloutTask task) {
        String taskId = task.getId();
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

        List<Device> candidates = evaluateCandidates(task);
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
        return openBatch(task, selected);
    }

    /**
     * 并行推进窗口推进：允许多个批次同时在途。
     *
     * <p>在途数直接由「已参与但尚未收敛（未 ACTIVATED/ROLLED_BACK）的设备数」派生，
     * 因此设备激活成功或回滚收敛即自动释放名额，不存在独立计数器漂移的问题。
     * 每次推进在窗口剩余名额内、按设备注册顺序把当前兼容的未参与设备尽量铺满，
     * 按 batchSize 切成一个或多个同时打开的批次。窗口已满时不补人（返回 null）。</p>
     *
     * <p>调用方需持 task 锁。</p>
     */
    private TaskBatch advanceParallel(RolloutTask task) {
        String taskId = task.getId();
        if (task.getStatus() == TaskStatus.CREATED) {
            task.setStatus(TaskStatus.IN_PROGRESS);
        }
        int inFlight = countInFlight(task);
        int remaining = task.getMaxInFlightDevices() - inFlight;
        if (remaining <= 0) {
            // 窗口被在途设备占满：本次不补人，也不视为异常，待有设备收敛腾位后再推进
            log.info("advance 并行窗口已满 taskId={} inFlight={} maxInFlight={}，暂不补人",
                    taskId, inFlight, task.getMaxInFlightDevices());
            task.setCandidateExhausted(false);
            return null;
        }

        List<Device> candidates = evaluateCandidates(task);
        int take = Math.min(remaining, candidates.size());
        // 本次评估后，未参与且当前兼容的候选是否已被全部取走（含本来就没有候选）。
        // 报告路径据此判断：全部在途收敛后是否还需要等待下一次 advance 补人。
        task.setCandidateExhausted(take == candidates.size());
        if (take == 0) {
            log.info("advance 窗口有空位但暂无可推进设备 taskId={} remaining={} inFlight={}",
                    taskId, remaining, inFlight);
            // 没有在途设备，且没有任何当前兼容且未参与的设备：任务收尾
            if (inFlight == 0 && allParticipantsSettled(task)) {
                task.setStatus(TaskStatus.COMPLETED);
                log.info("advance 并行任务自然完成 taskId={}（无在途且无候选）", taskId);
            }
            return null;
        }

        // 按 batchSize 把入选设备切成一个或多个批次，多个批次同时打开并行推进
        TaskBatch first = null;
        for (int from = 0; from < take; from += task.getBatchSize()) {
            int to = Math.min(from + task.getBatchSize(), take);
            TaskBatch batch = openBatch(task, new ArrayList<>(candidates.subList(from, to)));
            if (first == null) {
                first = batch;
            }
        }
        log.info("advance 并行窗口补人 taskId={} inFlightBefore={} maxInFlight={} selected={}",
                taskId, inFlight, task.getMaxInFlightDevices(), take);
        return first;
    }

    /**
     * 全量评估设备，返回当前兼容且未参与、按注册先后（注册时间、再按设备Id）排序的候选列表；
     * 不兼容设备逐一写排除留痕（每设备仅记录一次，但每次推进都会重新评估，
     * 因此之前被排除、后续条件转好的设备仍有机会重新进入候选）。
     *
     * <p>调用方需持 task 锁。</p>
     */
    private List<Device> evaluateCandidates(RolloutTask task) {
        String taskId = task.getId();
        List<Device> ordered = new ArrayList<>(deviceService.list());
        ordered.sort(Comparator.comparingLong(Device::getRegisteredAt)
                .thenComparing(Device::getId));
        List<Device> candidates = new ArrayList<>();
        for (Device device : ordered) {
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
        return candidates;
    }

    /** 用给定设备（保持顺序）打开一个新批次并写入升级记录。调用方需持 task 锁。 */
    private TaskBatch openBatch(RolloutTask task, List<Device> selected) {
        String taskId = task.getId();
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

    /** 统计任务当前在途（已参与但尚未激活成功/回滚收敛）的设备数。调用方需持 task 锁。 */
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
            view.put("parallelWindow", task.isParallelWindow());
            view.put("participants", total);
            view.put("success", success);
            view.put("rolledBack", failed);
            view.put("inProgress", inProgress);
            view.put("inFlight", inProgress);
            // 窗口容量与剩余名额：仅并行窗口模式有意义；串行模式返回 null
            view.put("maxInFlightDevices", task.isParallelWindow() ? task.getMaxInFlightDevices() : null);
            view.put("windowRemaining", task.isParallelWindow()
                    ? Math.max(0, task.getMaxInFlightDevices() - inProgress) : null);
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
