# firmware-ota-orchestrator-java

基于 Spring Boot 的设备固件灰度发布与回滚编排服务。全部状态保存在本地内存中，
通过幂等与单调性设计保证：重复上报、并发上报与失败回滚场景下设备状态始终一致且可解释。

## 领域模型与状态机

### 设备状态（Device）

```
注册 --(ONLINE)--> 心跳维持在线 --(超过 60s 未心跳, sweep-offline)--> OFFLINE
OFFLINE --(重新注册)--> ONLINE
```

- 心跳只在时间戳**严格递增**时被接受：重复、乱序、并发心跳被幂等吸收，
  心跳计数不膨胀、版本不倒退（心跳上报的版本只许前进，回滚由编排服务显式执行）。
- 离线设备与未知设备的任何操作都会被拒绝，且原因可区分：
  `DEVICE_OFFLINE` / `UNKNOWN_DEVICE`。

### 发布任务状态机（ReleaseTask）

```
DRAFT --start--> RUNNING --(所有批次推进且设备完成)--> （推进至无新设备为止）
                 RUNNING --(失败数 >= failureThreshold)--> ABORTED（自动回滚，终态）
```

### 设备在任务中的阶段（DeviceUpgradeState）

```
            兼容性判定不通过
评估 ────────────────────────────> EXCLUDED（记录 exclusionReason，终态）
  │ 通过且入批（batch >= 1）
  ▼
PENDING --DOWNLOAD ok--> DOWNLOADED --INSTALL ok--> INSTALLED --ACTIVATE ok--> COMPLETED（终态）
   任一阶段 FAILURE --> FAILED --(任务失败数达阈值)--> ROLLED_BACK（终态）
   COMPLETED/DOWNLOADED/INSTALLED 在任务中止时同样 --> ROLLED_BACK
```

- 上报必须按 `DOWNLOAD -> INSTALL -> ACTIVATE` 顺序，跳阶段拒绝（`ILLEGAL_TRANSITION`），
  阶段回退的迟到上报拒绝（`STALE_REPORT`）。
- 与最近一次应用的上报**完全相同**（同阶段同结果）的重复上报被幂等吸收，不改变任何状态。
- 批次归属（batch）一旦确定不再改变；重复推进或重复成功上报不会让同一设备重复入批。

## 兼容性判定规则

`advanceBatch` 对每台尚未评估过的注册设备依次检查（任一不满足即排除并记录原因）：

1. 设备必须在线，否则排除原因 `设备不在线`；
2. 设备硬件型号必须在任务的 `supportedHardware` 集合内，否则排除原因 `硬件型号不兼容: <model>`；
3. 设备当前版本必须落在 `[minSourceVersion, maxSourceVersion]` 区间内，
   否则排除原因 `当前版本不在源版本区间: <version>`。

通过判定的设备按 deviceId 排序，取前 `batchSize` 台进入下一批次。
被排除的设备**绝不静默跳过**：其状态为 `EXCLUDED` 且带有可读的 `exclusionReason`。

## 回滚触发条件

- 每次阶段失败上报使任务失败计数 +1；当 `failureCount >= failureThreshold` 时：
  1. 任务原子地置为 `ABORTED`（并发下只执行一次）；
  2. 所有已应用过升级阶段的设备（DOWNLOADED / INSTALLED / COMPLETED / FAILED）
     回滚到各自的上一稳定版本并标记 `ROLLED_BACK`；
  3. 尚未开始的 PENDING 设备保持原版本，任务停止推进（`advanceBatch` 拒绝，`ILLEGAL_TASK_STATE`）。
- 回滚后：与末次上报完全相同的重复失败上报被幂等吸收（不重复计数、不重复回滚）；
  任何新的上报被拒绝（`TASK_ALREADY_ABORTED`）。
- 回滚完成后设备版本、批次归属（batch 字段保留）与任务进度（`progress()` 各阶段计数）自洽。

## 拒绝原因一览（RejectReason）

| 原因 | 含义 |
|---|---|
| `UNKNOWN_DEVICE` / `DEVICE_OFFLINE` | 设备未注册 / 已离线 |
| `DEVICE_ALREADY_REGISTERED` | 在线设备重复注册 |
| `UNKNOWN_TASK` / `ILLEGAL_TASK_STATE` | 任务不存在 / 任务状态不允许该操作 |
| `DEVICE_NOT_IN_TASK` / `DEVICE_EXCLUDED` | 设备不在任务中 / 已被排除 |
| `ILLEGAL_TRANSITION` / `STALE_REPORT` | 非法阶段跳转 / 迟到的过期上报 |
| `TASK_ALREADY_ABORTED` | 任务已中止回滚，拒绝新上报 |

## 本地验证方法

```bash
# 运行全部单元测试（含并发重复上报、兼容性排除、失败阈值回滚边界）
mvn test

# 启动服务
mvn spring-boot:run
```

REST 接口示例：

```bash
# 注册设备并上报心跳
curl -X POST localhost:8080/api/devices -H 'Content-Type: application/json' \
  -d '{"deviceId":"dev-1","hardwareModel":"hw-a","initialVersion":"1.0.0"}'
curl -X POST localhost:8080/api/devices/dev-1/heartbeat -H 'Content-Type: application/json' \
  -d '{"reportedVersion":"1.0.0"}'

# 离线扫描（60s 未心跳的设备置为离线）
curl -X POST localhost:8080/api/devices/sweep-offline

# 创建并启动发布任务：目标 2.0.0，源版本区间 [1.0.0, 1.9.9]，仅 hw-a，每批 2 台，失败阈值 2
curl -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' -d \
  '{"taskId":"t1","targetVersion":"2.0.0","minSourceVersion":"1.0.0","maxSourceVersion":"1.9.9",
    "supportedHardware":["hw-a"],"batchSize":2,"failureThreshold":2}'
curl -X POST localhost:8080/api/tasks/t1/start
curl -X POST localhost:8080/api/tasks/t1/advance

# 上报升级结果（DOWNLOAD / INSTALL / ACTIVATE，SUCCESS / FAILURE）
curl -X POST localhost:8080/api/tasks/t1/reports -H 'Content-Type: application/json' \
  -d '{"deviceId":"dev-1","stage":"DOWNLOAD","result":"SUCCESS"}'

# 查看任务进度（含每台设备的批次、阶段与排除原因）
curl localhost:8080/api/tasks/t1
```

被拒绝的操作返回 404（未知资源）或 409（状态冲突），响应体携带 `reason` 字段以区分原因。
