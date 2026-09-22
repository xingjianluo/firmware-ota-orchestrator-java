package com.github.xingjianluo.firmwareotaorchestratorjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端 HTTP 链路：设备/任务/推进/上报/回滚 + 可区分拒绝码。
 */
@SpringBootTest
@AutoConfigureMockMvc
public class ApplicationTests {

    private static final Logger log = LoggerFactory.getLogger(ApplicationTests.class);
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Test
    public void fullRolloutAndRollbackFlow_overHttp() throws Exception {
        // 1. 注册两台设备
        register("web-1", "Box-A", "1.0.0");
        register("web-2", "Box-X", "1.0.0"); // 硬件不兼容

        // 2. 未知设备心跳 -> 404 + DEVICE_NOT_FOUND
        String body = json.writeValueAsString(Map.of("deviceId", "ghost"));
        mvc.perform(post("/api/devices/heartbeat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("DEVICE_NOT_FOUND"));
        log.info("HTTP 未知设备心跳 => 404 DEVICE_NOT_FOUND（与离线 409 DEVICE_OFFLINE 可区分）");

        // 3. 创建任务：仅 Box-A、源版本 1.x
        Map<String, Object> taskReq = Map.of(
                "targetVersion", "2.0.0",
                "minSourceVersion", "1.0.0",
                "maxSourceVersion", "1.9.9",
                "hardwareWhitelist", java.util.List.of("Box-A"),
                "batchSize", 1,
                "deviceFailureThreshold", 1,
                "taskFailureRateThreshold", 0.5);
        String resp = mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(taskReq)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String taskId = json.readTree(resp).get("id").asText();
        log.info("HTTP 创建任务 taskId={}", taskId);

        // 4. 推进：web-1 入批，web-2 被排除并留痕
        mvc.perform(post("/api/tasks/" + taskId + "/advance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceIds[0]").value("web-1"));
        String exclusions = mvc.perform(get("/api/tasks/" + taskId + "/exclusions"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode excl = json.readTree(exclusions);
        org.junit.jupiter.api.Assertions.assertEquals("HARDWARE_NOT_MATCH",
                excl.get(0).get("reason").asText());
        log.info("HTTP 排除记录: {}", exclusions);

        // 5. 上报下载开始 + 失败（阈值 1）=> 自动回滚
        mvc.perform(post("/api/tasks/reports").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "deviceId", "web-1", "taskId", taskId, "type", "DOWNLOAD_STARTED"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tasks/reports").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "deviceId", "web-1", "taskId", taskId, "type", "DOWNLOAD_FAILED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolledBack").value(true))
                .andExpect(jsonPath("$.phase").value("ROLLED_BACK"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // 6. 重复失败 => 幂等
        mvc.perform(post("/api/tasks/reports").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "deviceId", "web-1", "taskId", taskId, "type", "DOWNLOAD_FAILED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));
        log.info("HTTP 重复失败 => duplicate=true，无二次回滚");

        // 7. 进度自洽
        String progress = mvc.perform(get("/api/tasks/" + taskId + "/progress"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode p = json.readTree(progress);
        org.junit.jupiter.api.Assertions.assertEquals(1, p.get("rolledBack").asInt());
        String deviceJson = mvc.perform(get("/api/devices/web-1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals("1.0.0",
                json.readTree(deviceJson).get("currentVersion").asText());
        log.info("HTTP 进度: {}", progress);
    }

    private void register(String id, String model, String version) throws Exception {
        String body = json.writeValueAsString(Map.of(
                "deviceId", id, "hardwareModel", model, "firmwareVersion", version));
        mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }
}
