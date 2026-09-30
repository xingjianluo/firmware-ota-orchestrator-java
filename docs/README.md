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
CREATED --首次 advance--> IN_PROGRESS --在途全部收敛且无更多可推进设备--> COMPLETED
                          IN_PROGRESS --整体回滚率超阈值--> ABORTED
```

- ABORTED 后**不再往窗口补新设备**（`advance` 返回 409 `TASK_NOT_IN_PROGRESS`），
  但**已经在途的设备上报仍正常受理**，可以把这次升级跑完；任务停在 ABORTED，
  直到在途全部收敛（不会因为在途成功而翻回 COMPLETED）。
- 批次不再「一批卡住全盘等待」：多个批次可以同时打开、并行推进，由
  **并行推进窗口（容量 `maxInFlight`）** 约束全局同时在途的设备数。

## 2.1 并行推进窗口（`maxInFlight`）

创建任务时可配置「同时在途设备数」上限 `maxInFlight`（与 `batchSize` 并列）：

- **容量怎么算**：`剩余名额 = maxInFlight - 当前在途数`。
  「在途」= 已分配但尚未收敛的参与设备，即升级阶段既不是 `ACTIVATED` 也不是
  `ROLLED_BACK`（含 `ASSIGNED/DOWNLOADING/.../ROLLING_BACK` 等）。
- **怎么铺满**：每次 `advance` 全量评估设备后，把**兼容且从未参与**的设备按
  **设备注册先后**排序，只用剩余名额尽量铺满；一次推进可同时打开多个批次
  （每批仍不超过 `batchSize`）并行推进。
- **窗口满了**：`advance` 不补人，返回 409 `BATCH_NOT_OPEN`（消息里带
  `在途 x/maxInFlight`），等有设备收敛腾位置后，下一次 `advance` 再补。
- **什么时候释放名额**：设备**激活成功（ACTIVATED）**或**回滚收敛
  （ROLLED_BACK）**的瞬间即释放它占用的窗口名额，后续推进优先把名额分给
  还没参与过、且仍符合条件的设备。
- **不漏 / 不重 / 不被批次边界卡住**：是否参与以升级记录
  （`taskId:deviceId`）为准，已参与设备绝不二次入批；窗口与批次边界都不会
  漏掉设备。任务只有在**既没有在途设备、也没有当前仍兼容的未参与设备**时
  才收尾（`COMPLETED`）。
- **被排除过的设备可重新进入**：排除记录只在「当前确实不兼容」时写一条，
  不是永久黑名单；设备重新上线或版本变好后，后续 `advance` 会重新接纳它。
- **默认值与向后兼容**：`maxInFlight` 不传或 ≤0 时取 `batchSize`，此时窗口
  一次只放一批，语义与旧版「一批收敛后再开下一批」的串行推进完全一致。
- 并发安全：`advance` 与结果上报都在**任务锁**内更新窗口占用，锁顺序固定
  task → upgradeRecord → device，并发推进/上报下在途数不会超过上限，
  设备也不会被重复放进批次。
- 批次内所有设备都到 `ACTIVATED` 或 `ROLLED_BACK` 后该批次自动关闭
  （`closed=true`，仅作进度展示）；批次关闭不再阻塞其他批次并行推进。

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
  **排除不是永久挡在外面**：后续推进若设备条件变好（重新上线、版本进入区间
  等），会重新通过筛选并按注册顺序补入。
- 已参与（已分配、成功、已回滚）的设备**绝不重复进入任何批次**。
- 通过筛选的设备按**设备注册先后**（注册序号单调递增，不依赖哈希表遍历顺序）
  取窗口剩余名额内的若干台，组成一个或多个不超过 `batchSize` 的批次。

## 4. 升级上报与自动回滚

`POST /api/tasks/reports`，body：`{deviceId, taskId, type, firmwareVersion?}`，
`type` ∈ `DOWNLOAD_STARTED/SUCCESS/FAILED`、`INSTALL_*`、`ACTIVATE_*`。

回滚触发条件（满足其一）：

1. **设备失败阈值**：单台设备在同一流程中失败计数达到任务的
   `deviceFailureThreshold`（默认 1）→ 立即把该设备回滚到 `stableVersion`，
   原因 `DEVICE_FAILURE_THRESHOLD`。
2. **任务整体失败率**：已回滚设备占参与设备比例 **严格大于** `taskFailureRateThreshold`
   （默认 0.5）→ 任务 `ABORTED`，原因写入 `abortReason`，**并行窗口立即停止补充
   新设备**；但已在途设备的上报仍正常受理、可以正常跑完或回滚，窗口名额随它们
   收敛逐一释放，直到在途归零。

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
| GET `/api/devices/{id}` / GET `/api/devices` | 设备详情 / 列表（列表按注册先后排序） |
| POST `/api/tasks` | 创建任务 `{targetVersion, minSourceVersion, maxSourceVersion, hardwareWhitelist?, batchSize?, maxInFlight?, deviceFailureThreshold?, taskFailureRateThreshold?}` |
| POST `/api/tasks/{id}/advance` | 评估设备并按窗口剩余名额补人（可同时打开多个批次）；窗口满返回 409 `BATCH_NOT_OPEN` |
| GET `/api/tasks/{id}/progress` | 任务进度：`maxInFlight`、`inFlight`（当前在途）、`windowRemaining`（窗口剩余名额）、`participants/success/rolledBack/inProgress`、`failureRate`、各批次视图、排除数 |
| GET `/api/tasks/{id}/exclusions` | 排除明细（不静默跳过的留痕） |
| POST `/api/tasks/reports` | 升级结果上报 |

`progress` 中窗口字段示例：`"maxInFlight": 4, "inFlight": 3, "windowRemaining": 1`。

## 6. 本地验证

```bash
mvn -o test            # 离线运行全部 26 个单测/集成测试（联网可去掉 -o）
mvn -o spring-boot:run # 启动后用 curl 按上面接口走流程
```

手工体验并行窗口（`maxInFlight=4, batchSize=2`）：注册 6 台 `Box-A/1.0.0` 设备后，
一次 `advance` 即同时打开 3 个批次铺满 4 个名额（d1~d4 在途，d5/d6 等待）；
再 `advance` 返回 409 窗口满；把其中 2 台上报成功链路后重新 `advance`，
d5/d6 即按注册顺序补入；`progress` 全程可观察 `inFlight/windowRemaining`。

测试清单（`src/test/.../tests`）：

- `DeviceServiceTest`（6）：16×50 并发心跳计数精确、迟到心跳不抖动、版本不倒退、
  超时离线、未知/离线拒绝原因可区分、重复注册幂等；
- `RolloutServiceTest`（4）：四类兼容性排除全部留痕、离线排除、重复推进不重复入批、
  批次闸门与分批推进（`maxInFlight` 默认=批大小，旧串行行为不回归）；
- `UpgradeServiceTest`（10）：完整成功链路、非法转换 vs 过期上报、
  重复/迟到失败幂等、阈值触发**仅一次**回滚且版本/批次/进度自洽、
  未达阈值可重试成功、任务失败率中止、20 并发失败只回滚一次、离线/未知/非参与拒绝；
- `ParallelWindowTest`（5）：**①窗口一次铺满并同时打开多批、②收敛腾位置后按注册
  顺序继续补人且不重不漏、③4 推进线程 + 2 上报线程并发下在途永不超上限/设备不
  重复入批、④失败率超阈值中止后窗口不再补充但在途设备能跑完并正常收尾、
  ⑤曾被排除的设备条件变好后可重新进入**；
- `ApplicationTests`（1）：MockMvc 端到端 HTTP 全链路与拒绝码校验。

所有单测日志均打印**输入**（并发数/上报类型/版本条件）与**判定依据/结果**
（阶段、计数、原因码、回滚版本），便于复盘。
