package com.github.xingjianluo.firmwareotaorchestratorjava.service;

import com.github.xingjianluo.firmwareotaorchestratorjava.dto.ReportRequest;
import com.github.xingjianluo.firmwareotaorchestratorjava.dto.ReportResponse;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.RejectReason;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.ReportType;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.RollbackReason;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.TaskStatus;
import com.github.xingjianluo.firmwareotaorchestratorjava.state.UpgradePhase;
import com.github.xingjianluo.firmwareotaorchestratorjava.exception.RejectedException;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.Device;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.RolloutTask;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.TaskBatch;
import com.github.xingjianluo.firmwareotaorchestratorjava.model.UpgradeRecord;
import com.github.xingjianluo.firmwareotaorchestratorjava.store.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 升级结果上报、设备阶段状态机与自动回滚。
 *
 * <p>一致性要点：</p>
 * <ul>
 *   <li>锁顺序固定为 task → device record → device，避免死锁；</li>
 *   <li>同一设备对同一阶段的重复上报幂等：重复成功不重复计数、重复失败不重复回滚；</li>
 *   <li>迟到上报（当前阶段已越过该事件）返回 duplicate=true 且不改状态；</li>
 *   <li>回滚只能发生一次：rolledBack 置位后任何失败都直接忽略；</li>
 *   <li>回滚把设备版本恢复为 stableVersion，记录停留在 ROLLED_BACK，批次归属不变。</li>
 * </ul>
 */
@Service
public class UpgradeService {

    private static final Logger log = LoggerFactory.getLogger(UpgradeService.class);

    private final InMemoryStore store;
    private final DeviceService deviceService;
    private final CompatibilityService compatibilityService;
    private final java.time.Clock clock;

    public UpgradeService(InMemoryStore store, DeviceService deviceService,
                          CompatibilityService compatibilityService, java.time.Clock clock) {
        this.store = store;
        this.deviceService = deviceService;
        this.compatibilityService = compatibilityService;
        this.clock = clock;
    }

    public ReportResponse report(ReportRequest request) {
        if (request == null || request.getDeviceId() == null || request.getTaskId() == null
                || request.getType() == null) {
            throw new RejectedException(RejectReason.INVALID_ARGUMENT,
                    "deviceId/taskId/type 均为必填");
        }
        String deviceId = request.getDeviceId();
        String taskId = request.getTaskId();
        ReportType type = request.getType();

        Device device = store.devices().get(deviceId);
        if (device == null) {
            throw new RejectedException(RejectReason.DEVICE_NOT_FOUND,
                    "设备不存在: " + deviceId + "，上报被拒绝");
        }
        RolloutTask task = store.tasks().get(taskId);
        if (task == null) {
            throw new RejectedException(RejectReason.TASK_NOT_FOUND,
                    "任务不存在: " + taskId + "，上报被拒绝");
        }
        UpgradeRecord record = store.upgrades().get(RolloutService.upgradeKey(taskId, deviceId));
        if (record == null) {
            throw new RejectedException(RejectReason.NOT_TASK_PARTICIPANT,
                    "设备 " + deviceId + " 不属于任务 " + taskId + " 的任何批次，上报被拒绝");
        }

        // 固定锁顺序：task -> record -> device
        synchronized (task) {
            synchronized (record) {
                synchronized (device) {
                    // 在线状态实时判定（物化超时离线），与心跳口径一致
                    if (!deviceService.isOnline(device)) {
                        device.setStatus(com.github.xingjianluo.firmwareotaorchestratorjava.state.DeviceStatus.OFFLINE);
                        throw new RejectedException(RejectReason.DEVICE_OFFLINE,
                                "设备 " + deviceId + " 当前离线（最后心跳 "
                                        + (clock.millis() - device.getLastHeartbeatAt()) + "ms 前），上报被拒绝");
                    }
                    return doReport(task, device, record, type, request.getFirmwareVersion());
                }
            }
        }
    }

    private ReportResponse doReport(RolloutTask task, Device device, UpgradeRecord record,
                                    ReportType type, String reportedVersion) {
        UpgradePhase current = record.getPhase();
        log.info("收到上报 taskId={} deviceId={} type={} 当前阶段={} rolledBack={} failureCount={}",
                task.getId(), device.getId(), type, current, record.isRolledBack(),
                record.getFailureCount());

        if (record.isRolledBack()) {
            // 回滚完成后的任何迟到上报：不重复回滚、状态不倒退
            return duplicate(task, device, record, "回滚已完成，迟到上报 " + type + " 被幂等忽略");
        }
        if (current == UpgradePhase.ACTIVATED) {
            return duplicate(task, device, record, "设备已激活成功，迟到上报 " + type + " 被幂等忽略");
        }

        Transition transition = expectedTransition(current, type);
        if (transition == null) {
            // 阶段与事件不匹配：区分「非法转换」与「过期/重复上报」
            if (isStale(current, type)) {
                return duplicate(task, device, record,
                        "过期上报 " + type + "（当前阶段 " + current + "），状态保持不变");
            }
            throw new RejectedException(RejectReason.ILLEGAL_PHASE_TRANSITION,
                    "非法状态转换：阶段 " + current + " 不接受事件 " + type);
        }

        if (transition.duplicateAlready()) {
            // 与当前所处阶段完全相同的重复事件（如重复 DOWNLOAD_SUCCESS）
            return duplicate(task, device, record, "重复上报 " + type + "，幂等忽略");
        }

        if (transition.failure()) {
            record.setFailureCount(record.getFailureCount() + 1);
            log.warn("失败计数增加 taskId={} deviceId={} type={} failureCount={}/{}",
                    task.getId(), device.getId(), type, record.getFailureCount(),
                    task.getDeviceFailureThreshold());

            TaskBatch batch = task.getBatches().get(record.getBatchIndex());
            if (batch != null) {
                batch.setFailedCount(batch.getFailedCount() + 1);
            }

            if (record.getFailureCount() >= task.getDeviceFailureThreshold()) {
                performRollback(task, device, record, batch,
                        RollbackReason.DEVICE_FAILURE_THRESHOLD);
                checkTaskFailureRate(task);
                maybeCloseBatch(task, batch);
                // 回滚收敛同样释放窗口名额：若这是在途的最后一台且失败率未超阈值
                // （任务未被中止），任务应在此收尾，而不是停在「进行中」
                maybeCompleteTask(task);
                return response(task, device, record, false);
            }
            // 未达阈值：进入该阶段的“进行中/失败待重试”态，等待设备重试
            record.setPhase(transition.to());
            record.setUpdatedAt(clock.millis());
            return response(task, device, record, false);
        }

        // 成功/开始事件：阶段前进
        record.setPhase(transition.to());
        record.setUpdatedAt(clock.millis());
        if (type == ReportType.ACTIVATE_SUCCESS) {
            // 激活成功：版本落地为目标版本，并稳定为新的回滚基线
            device.setCurrentVersion(task.getTargetVersion());
            device.setStableVersion(task.getTargetVersion());
            TaskBatch batch = task.getBatches().get(record.getBatchIndex());
            if (batch != null) {
                batch.setSuccessCount(batch.getSuccessCount() + 1);
                maybeCloseBatch(task, batch);
            }
            maybeCompleteTask(task);
            log.info("激活成功 taskId={} deviceId={} 版本={} batch={}",
                    task.getId(), device.getId(), task.getTargetVersion(), record.getBatchIndex());
        }
        return response(task, device, record, false);
    }

    /**
     * 执行一次性回滚。调用前已持锁。
     */
    private void performRollback(RolloutTask task, Device device, UpgradeRecord record,
                                 TaskBatch batch, RollbackReason reason) {
        // 双重检查杜绝重复回滚
        if (record.isRolledBack()) {
            return;
        }
        record.setPhase(UpgradePhase.ROLLING_BACK);
        String rollbackTo = device.getStableVersion();
        log.warn("触发回滚 taskId={} deviceId={} {} -> 回滚至 stableVersion={}",
                task.getId(), device.getId(), reason, rollbackTo);
        // 模拟回滚动作：版本恢复至上一稳定版本（激活成功前 stableVersion 不会被改写）
        device.setCurrentVersion(rollbackTo);
        record.setPhase(UpgradePhase.ROLLED_BACK);
        record.setRolledBack(true);
        record.setRollbackReason(reason);
        record.setUpdatedAt(clock.millis());
        maybeCloseBatch(task, batch);
    }

    private void checkTaskFailureRate(RolloutTask task) {
        if (task.getStatus() != TaskStatus.IN_PROGRESS) {
            return;
        }
        int total = 0;
        int rolledBack = 0;
        for (UpgradeRecord r : store.upgrades().values()) {
            if (r.getTaskId().equals(task.getId())) {
                total++;
                if (r.isRolledBack()) {
                    rolledBack++;
                }
            }
        }
        double rate = total == 0 ? 0 : (double) rolledBack / total;
        if (rate > task.getTaskFailureRateThreshold()) {
            task.setStatus(TaskStatus.ABORTED);
            task.setAbortReason("整体回滚率 " + String.format("%.2f", rate)
                    + " 超过阈值 " + task.getTaskFailureRateThreshold()
                    + "，停止后续批次推进（已在途设备的上报仍正常受理）");
            log.warn("任务中止 taskId={} rolledBack={}/{} rate={}", task.getId(), rolledBack, total, rate);
        }
    }

    /**
     * 批次内所有设备都已收敛（激活成功或已回滚）即关闭批次，允许推进下一批。
     */
    private void maybeCloseBatch(RolloutTask task, TaskBatch batch) {
        if (batch == null || !batch.isOpen() || batch.isClosed()) {
            return;
        }
        for (String deviceId : batch.getDeviceIds()) {
            UpgradeRecord r = store.upgrades().get(RolloutService.upgradeKey(task.getId(), deviceId));
            if (r == null || (r.getPhase() != UpgradePhase.ACTIVATED
                    && r.getPhase() != UpgradePhase.ROLLED_BACK)) {
                return;
            }
        }
        batch.setClosed(true);
        log.info("批次收敛关闭 taskId={} batch={} success={} failed={}",
                task.getId(), batch.getIndex(), batch.getSuccessCount(), batch.getFailedCount());
    }

    /**
     * 在途全部收敛且无更多可推进设备时收尾为 COMPLETED。
     *
     * <p>激活成功与回滚收敛两条路径都会调用；仅当任务仍处于 IN_PROGRESS 才收尾，
     * 已中止（ABORTED）的任务绝不被翻回完成。</p>
     */
    private void maybeCompleteTask(RolloutTask task) {
        if (task.getStatus() != TaskStatus.IN_PROGRESS) {
            return;
        }
        for (UpgradeRecord r : store.upgrades().values()) {
            if (r.getTaskId().equals(task.getId())
                    && r.getPhase() != UpgradePhase.ACTIVATED
                    && r.getPhase() != UpgradePhase.ROLLED_BACK) {
                return; // 仍有在途设备
            }
        }
        // 并行窗口下，所有在途设备刚收敛时可能还没调用 advance 补人；
        // 只有「不存在未参与且当前仍兼容的设备」时才真正收尾，防止把等待补入的设备漏掉。
        for (Device device : deviceService.list()) {
            if (!store.upgrades().containsKey(RolloutService.upgradeKey(task.getId(), device.getId()))
                    && compatibilityService.evaluate(task, device).isEmpty()) {
                log.info("任务暂不收尾 taskId={}，仍有可补入设备 deviceId={}",
                        task.getId(), device.getId());
                return;
            }
        }
        task.setStatus(TaskStatus.COMPLETED);
        log.info("任务完成 taskId={}（在途设备全部收敛且无更多可推进设备）", task.getId());
    }

    /**
     * 判断某事件在当前阶段是否属于“过期/乱序上报”（幂等忽略，而非非法）。
     *
     * <p>两类视为过期：</p>
     * <ol>
     *   <li>事件级别 &lt; 当前阶段：旧阶段的迟到回执（最常见的重复/乱序）；</li>
     *   <li>高阶的<b>失败</b>回执早到（级别 &gt; 当前）：设备对尚未开始的阶段补发失败，
     *       不构成可执行转换，按乱序忽略；而高阶 STARTED/SUCCESS 早到仍属非法越序。</li>
     * </ol>
     */
    private boolean isStale(UpgradePhase current, ReportType type) {
        int currentLevel = phaseLevel(current);
        if (currentLevel < 0) {
            return false;
        }
        int level = eventLevel(type);
        if (level < currentLevel) {
            return true;
        }
        return level > currentLevel && isFailure(type);
    }

    private static boolean isFailure(ReportType type) {
        return type == ReportType.DOWNLOAD_FAILED
                || type == ReportType.INSTALL_FAILED
                || type == ReportType.ACTIVATE_FAILED;
    }

    private Transition expectedTransition(UpgradePhase current, ReportType type) {
        return switch (type) {
            // 下载：首次从 ASSIGNED 进入；失败未回滚后处于 FAILED，可重试点下载
            case DOWNLOAD_STARTED -> current == UpgradePhase.ASSIGNED
                    || current == UpgradePhase.FAILED
                    ? Transition.to(UpgradePhase.DOWNLOADING, false) : null;
            case DOWNLOAD_SUCCESS -> current == UpgradePhase.DOWNLOADED
                    ? Transition.duplicate()
                    : current == UpgradePhase.DOWNLOADING
                            ? Transition.to(UpgradePhase.DOWNLOADED, false) : null;
            // 失败未达阈值回退到 FAILED（可重试下载）；已在 FAILED 时同类失败幂等
            case DOWNLOAD_FAILED -> current == UpgradePhase.DOWNLOADING
                    || current == UpgradePhase.ASSIGNED
                    ? Transition.to(UpgradePhase.FAILED, true)
                    : current == UpgradePhase.FAILED ? Transition.duplicate() : null;
            // 安装：从 DOWNLOADED 开始；失败回退 DOWNLOADED 以便重试安装
            case INSTALL_STARTED -> current == UpgradePhase.DOWNLOADED
                    ? Transition.to(UpgradePhase.INSTALLING, false) : null;
            case INSTALL_SUCCESS -> current == UpgradePhase.INSTALLED
                    ? Transition.duplicate()
                    : current == UpgradePhase.INSTALLING
                            ? Transition.to(UpgradePhase.INSTALLED, false) : null;
            case INSTALL_FAILED -> current == UpgradePhase.INSTALLING
                    ? Transition.to(UpgradePhase.DOWNLOADED, true)
                    : current == UpgradePhase.DOWNLOADED ? Transition.duplicate() : null;
            // 激活：从 INSTALLED 开始；失败回退 INSTALLED 以便重试激活
            case ACTIVATE_STARTED -> current == UpgradePhase.INSTALLED
                    ? Transition.to(UpgradePhase.ACTIVATING, false) : null;
            case ACTIVATE_SUCCESS -> current == UpgradePhase.ACTIVATED
                    ? Transition.duplicate()
                    : current == UpgradePhase.ACTIVATING
                            ? Transition.to(UpgradePhase.ACTIVATED, false) : null;
            case ACTIVATE_FAILED -> current == UpgradePhase.ACTIVATING
                    ? Transition.to(UpgradePhase.INSTALLED, true)
                    : current == UpgradePhase.INSTALLED ? Transition.duplicate() : null;
        };
    }

    /**
     * 事件级别（与 {@link #phaseLevel} 同尺度）。
     *
     * <p>STARTED 与其 FAILED 回执同处阶段入口级别；SUCCESS 处阶段出口级别。
     * 因此：在更早阶段收到某阶段的失败回执 => 事件级别 &gt; 当前级别 => 过期；
     * 在阶段已越过（已进入后续阶段）后再收到该失败回执 => 事件级别 &lt; 当前级别 => 过期。
     * 只有与当前阶段精确匹配的事件才进入合法转换表。</p>
     */
    private static int eventLevel(ReportType type) {
        return switch (type) {
            case DOWNLOAD_STARTED, DOWNLOAD_FAILED -> 2;
            case DOWNLOAD_SUCCESS -> 3;
            case INSTALL_STARTED, INSTALL_FAILED -> 4;
            case INSTALL_SUCCESS -> 5;
            case ACTIVATE_STARTED, ACTIVATE_FAILED -> 6;
            case ACTIVATE_SUCCESS -> 7;
        };
    }

    private static int phaseLevel(UpgradePhase phase) {
        return switch (phase) {
            case ASSIGNED -> 1;
            case DOWNLOADING -> 2;
            case DOWNLOADED -> 3;
            case INSTALLING -> 4;
            case INSTALLED -> 5;
            case ACTIVATING -> 6;
            case ACTIVATED -> 7;
            case FAILED, ROLLING_BACK, ROLLED_BACK, IDLE -> -1;
        };
    }

    private ReportResponse duplicate(RolloutTask task, Device device, UpgradeRecord record,
                                     String message) {
        log.info("幂等忽略 taskId={} deviceId={} phase={} msg={}",
                task.getId(), device.getId(), record.getPhase(), message);
        ReportResponse resp = response(task, device, record, true);
        resp.setMessage(message);
        return resp;
    }

    private ReportResponse response(RolloutTask task, Device device, UpgradeRecord record,
                                    boolean duplicate) {
        ReportResponse resp = new ReportResponse();
        resp.setDeviceId(device.getId());
        resp.setTaskId(task.getId());
        resp.setPhase(record.getPhase());
        resp.setDuplicate(duplicate);
        resp.setRolledBack(record.isRolledBack());
        resp.setCurrentVersion(device.getCurrentVersion());
        return resp;
    }

    private record Transition(UpgradePhase to, boolean failure, boolean duplicateAlready) {
        static Transition to(UpgradePhase to, boolean failure) {
            return new Transition(to, failure, false);
        }

        static Transition duplicate() {
            return new Transition(null, false, true);
        }
    }
}
