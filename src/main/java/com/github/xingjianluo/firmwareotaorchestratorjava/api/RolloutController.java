package com.github.xingjianluo.firmwareotaorchestratorjava.api;

import com.github.xingjianluo.firmwareotaorchestratorjava.domain.ReleaseTask;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.ReportResult;
import com.github.xingjianluo.firmwareotaorchestratorjava.domain.UpgradeStage;
import com.github.xingjianluo.firmwareotaorchestratorjava.service.RolloutService;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/api/tasks")
public class RolloutController {

    private final RolloutService rolloutService;

    public RolloutController(RolloutService rolloutService) {
        this.rolloutService = rolloutService;
    }

    public record CreateTaskRequest(String taskId, String targetVersion, String minSourceVersion,
                                    String maxSourceVersion, Set<String> supportedHardware,
                                    int batchSize, int failureThreshold) {}
    public record ReportRequest(String deviceId, UpgradeStage stage, ReportResult result) {}

    @PostMapping
    public ReleaseTask create(@RequestBody CreateTaskRequest request) {
        return rolloutService.createTask(request.taskId(), request.targetVersion(),
                request.minSourceVersion(), request.maxSourceVersion(),
                request.supportedHardware(), request.batchSize(), request.failureThreshold());
    }

    @PostMapping("/{taskId}/start")
    public ReleaseTask start(@PathVariable String taskId) {
        return rolloutService.startTask(taskId);
    }

    @PostMapping("/{taskId}/advance")
    public ReleaseTask advance(@PathVariable String taskId) {
        return rolloutService.advanceBatch(taskId);
    }

    @PostMapping("/{taskId}/reports")
    public ReleaseTask report(@PathVariable String taskId, @RequestBody ReportRequest request) {
        return rolloutService.report(taskId, request.deviceId(), request.stage(), request.result());
    }

    @GetMapping("/{taskId}")
    public ReleaseTask get(@PathVariable String taskId) {
        return rolloutService.getTask(taskId);
    }
}
