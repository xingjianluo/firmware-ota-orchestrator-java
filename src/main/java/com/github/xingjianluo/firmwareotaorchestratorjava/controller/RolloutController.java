package com.github.xingjianluo.firmwareotaorchestratorjava.controller;

import com.github.xingjianluo.firmwareotaorchestratorjava.dto.CreateTaskRequest;
import com.github.xingjianluo.firmwareotaorchestratorjava.dto.ReportRequest;
import com.github.xingjianluo.firmwareotaorchestratorjava.dto.ReportResponse;
import com.github.xingjianluo.firmwareotaorchestratorjava.service.RolloutService;
import com.github.xingjianluo.firmwareotaorchestratorjava.service.UpgradeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class RolloutController {

    private final RolloutService rolloutService;
    private final UpgradeService upgradeService;

    public RolloutController(RolloutService rolloutService, UpgradeService upgradeService) {
        this.rolloutService = rolloutService;
        this.upgradeService = upgradeService;
    }

    @PostMapping
    public Object create(@RequestBody CreateTaskRequest request) {
        return rolloutService.createTask(request);
    }

    @PostMapping("/{taskId}/advance")
    public Object advance(@PathVariable String taskId) {
        return rolloutService.advance(taskId);
    }

    @GetMapping("/{taskId}/progress")
    public Object progress(@PathVariable String taskId) {
        return rolloutService.progress(taskId);
    }

    @GetMapping("/{taskId}/exclusions")
    public Object exclusions(@PathVariable String taskId) {
        return rolloutService.exclusions(taskId);
    }

    @PostMapping("/reports")
    public ReportResponse report(@RequestBody ReportRequest request) {
        return upgradeService.report(request);
    }
}
