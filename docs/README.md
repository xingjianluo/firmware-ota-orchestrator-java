# 设备固件灰度发布与回滚编排

本地内存状态（`InMemoryStore`）模拟设备、发布任务与升级结果，通过**设备内置锁 +
任务锁 + 固定锁顺序**保证重复上报、并发上报、失败回滚下的状态始终一致、可解释。

## 1. 设备在线状态

- `POST /api/devices` 注册：写入型号与初始固件版本，初始在线，`heartbeatCount=1`。
  重复注册幂等，不重置任何状态。
- `POST /api/devices/heartbeat` 心跳：携带 `deviceId` 与当前 `firmwareVersion`。
- 超过 **60s**（`DeviceService.OFFLINE_TIMEOUT_MS`）未收到心跳即判定离线，
  `OfflineSweeper` 每 10s 物化一次；GET 查询也会按同一口径物化。

### 心跳的并发/重复一致性（以 `Device` 对象为锁）

| 情形 | 处理 |
|---|---|
| 并发心跳 | 「判定-更新」在设备锁内完成，计数恰好 +N，不丢不重 |
| 迟到/乱序心跳（时间戳倒退） | 时间戳不刷新、计数不增加、状态不变 |
| 上报更低版本 | 拒绝回退，版本保持较高者（语义化版本比较） |
| 离线设备重新心跳 | 恢复在线 |

对设备操作的拒绝原因码（`RejectReason`，HTTP 可区分）：

- `DEVICE_NOT_FOUND` → **404**（未知设备）
- `DEVICE_OFFLINE` → **409**（已注册但离线，消息中附带静默毫秒数）

## 2. 发布状态机

### 任务级：`TaskStatus`

```
CREATED --首次 advance--> IN_PROGRESS --全部参与设备收敛--> COMPLETED
                          IN_PROGRESS --整体回滚率超阈值--> ABORTED
```

- ABORTED 后**不再打开新批次**（`advance` 返回 409 `TASK_NOT_IN_PROGRESS`），
  但已在途设备的上报仍被正常受理，直到各自收敛。
- 存在**未收敛的打开批次**时禁止开新批（409 `BATCH_NOT_OPEN`）；
  批次内所有设备都到 `ACTIVATED` 或 `ROLLED_BACK` 后自动关闭批次。

### 设备升级阶段：`UpgradePhase`

```
ASSIGNED
  └─ DOWNLOAD_STARTED → DOWNLOADING
        ├─ DOWNLOAD_SUCCESS → DOWNLOADED
        │     └─ INSTALL_STARTED → INSTALLING
        │           ├─ INSTALL_SUCCESS → INSTALLED
        │           │     └─ ACTIVATE_STARTED → ACTIVATING
        │           │           └─ ACTIVATE_SUCCESS → ACTIVATED   ✔ 版本落地为目标版本
        │           └─ INSTALL_FAILED → 回到 DOWNLOADED（可重试安装）
        └─ DOWNLOAD_FAILED → FAILED（可重试点 DOWNLOAD_STARTED）
  任意在途失败达到设备阈值 ──► ROLLING_BACK → ROLLED_BACK（版本恢复 stableVersion）
```

**事件与状态转换的三类判定：**

1. **合法转换**：进入下一阶段（或未达阈值的失败回退重试）。
2. **重复/过期**（返回 `duplicate=true`，状态不变）：
   - 重复的 SUCCESS（已在成功态）；
   - 事件级别 < 当前阶段（旧阶段迟到回执）；
   - 高阶 FAIL 回执早到（设备乱序补发失败）。
3. **非法转换**（409 `ILLEGAL_PHASE_TRANSITION`）：
   高阶 STARTED/SUCCESS 在其前置阶段未完成时到达（如 ASSIGNED 直接 INSTALL_SUCCESS）。

其他可区分拒绝码：`TASK_NOT_FOUND`(404)、`NOT_TASK_PARTICIPANT`(409)、
`STALE_RESULT`、`ROLLBACK_ALREADY_DONE`、`INVALID_ARGUMENT`。

## 3. 兼容性判定规则

`advance(taskId)` 每次**全量评估**所有设备（不会因批次选满而跳过评估）：

| 排除原因 `ExcludeReason` | 判定 |
|---|---|
| `HARDWARE_NOT_MATCH` | 任务配置了硬件白名单且设备型号不在其中 |
| `VERSION_OUT_OF_RANGE` | 当前版本不在源版本闭区间 `[minSourceVersion, maxSourceVersion]` |
| `ALREADY_ON_NEW_VERSION` | 当前版本已等于目标版本 |
| `DEVICE_OFFLINE` | 设备当前离线 |

- 版本号按 `.` 分段做**数值化语义比较**（`1.2.10 > 1.2.9`）。
- 每台被排除设备写一条 `ExclusionRecord`（任务/设备/原因/可读细节/时间），
  可通过 `GET /api/tasks/{id}/exclusions` 查询；同一设备不重复记录。
- 已参与（已分配、成功、已回滚）的设备**绝不重复进入任何批次**。
- 通过筛选的设备按注册顺序取 `batchSize` 台组成下一批。

## 4. 升级上报与自动回滚

`POST /api/tasks/reports`，body：`{deviceId, taskId, type, firmwareVersion?}`，
`type` ∈ `DOWNLOAD_STARTED/SUCCESS/FAILED`、`INSTALL_*`、`ACTIVATE_*`。

回滚触发条件（满足其一）：

1. **设备失败阈值**：单台设备在同一流程中失败计数达到任务的
   `deviceFailureThreshold`（默认 1）→ 立即把该设备回滚到 `stableVersion`，
   原因 `DEVICE_FAILURE_THRESHOLD`。
2. **任务整体失败率**：已回滚设备占参与设备比例 **严格大于** `taskFailureRateThreshold`
   （默认 0.5）→ 任务 `ABORTED`，原因 `TASK_FAILURE_RATE_EXCEEDED`，停止后续推进。

回滚的一致性保证：

- `rolledBack` 标志只置位一次，锁顺序固定为 **task → upgradeRecord → device**，
  并发失败报告恰好触发一次回滚，其余返回 `duplicate=true`；
- 回滚后 `currentVersion = stableVersion`（升级前的上一稳定版本），
  激活成功才会把 stableVersion 提升到新版本；
- 阶段停留在 `ROLLED_BACK`、**批次归属不变**、批次/任务进度中计入 `rolledBack`；
- 回滚后迟到的任何上报一律幂等忽略，不重复回滚、不状态倒退。

## 5. HTTP 接口

| 方法 路径 | 说明 |
|---|---|
| POST `/api/devices` | 注册 `{deviceId, hardwareModel, firmwareVersion}` |
| POST `/api/devices/heartbeat` | 心跳 `{deviceId, firmwareVersion}` |
| GET `/api/devices/{id}` / GET `/api/devices` | 设备详情 / 列表 |
| POST `/api/tasks` | 创建任务（见 `CreateTaskRequest`） |
| POST `/api/tasks/{id}/advance` | 评估并打开下一批 |
| GET `/api/tasks/{id}/progress` | 任务进度（批次、成功/回滚/在途、失败率、排除数） |
| GET `/api/tasks/{id}/exclusions` | 排除明细（不静默跳过的留痕） |
| POST `/api/tasks/reports` | 升级结果上报 |

## 6. 本地验证

```bash
mvn -o test            # 离线运行全部 21 个单测/集成测试（联网可去掉 -o）
mvn -o spring-boot:run # 启动后用 curl 按上面接口走流程
```

测试清单（`src/test/.../tests`）：

- `DeviceServiceTest`（6）：16×50 并发心跳计数精确、迟到心跳不抖动、版本不倒退、
  超时离线、未知/离线拒绝原因可区分、重复注册幂等；
- `RolloutServiceTest`（4）：四类兼容性排除全部留痕、离线排除、重复推进不重复入批、
  批次闸门与分批推进；
- `UpgradeServiceTest`（10）：完整成功链路、非法转换 vs 过期上报、
  重复/迟到失败幂等、阈值触发**仅一次**回滚且版本/批次/进度自洽、
  未达阈值可重试成功、任务失败率中止、20 并发失败只回滚一次、离线/未知/非参与拒绝；
- `ApplicationTests`（1）：MockMvc 端到端 HTTP 全链路与拒绝码校验。

所有单测日志均打印**输入**（并发数/上报类型/版本条件）与**判定依据/结果**
（阶段、计数、原因码、回滚版本），便于复盘。
