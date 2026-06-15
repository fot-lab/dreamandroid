# FEND-QUEU-0001: 任务队列页面 (Queue Tab)

| 属性 | 值 |
|------|-----|
| 分类 | Frontend — Queue |
| 对应章节 | §4.3 |
| 依赖 | ARCH-OVER-0001, SERV-BACK-0001 |

## 功能定位

作为 Generate 和 Backend 之间的中间层，管理请求处理的完整生命周期。**队列调度由 Android WorkManager 原生管理**，UI 通过 StateFlow 观察状态。

## 职责边界

Queue 不负责启动、重启或管理后端 C++ 进程。后端生命周期由 Model Screen (§4.2) 通过 `BackendService` 手动管理。Queue 仅通过 health check 感知后端状态并自动适配行为。

## 核心职责

1. **接收任务:** 从 Generate Screen 接收批量生成请求 → `QueueRepository.addBatch()`
2. **任务调度:** 通过 `QueueController.start()` 向 WorkManager 提交 `GenerationWorker`（`ExistingWorkPolicy.KEEP` 防重复）
3. **FIFO 处理:** `GenerationWorker.processLoop()` 内部顺序处理 PENDING 任务
4. **后端状态感知:** 每个任务处理前调用 `waitForBackend()` — 后端离线则暂停并持续轮询 (3s 间隔)
5. **后端掉线恢复:** 生成中途后端崩溃 → `resetTaskToPending()` 将任务回退为 PENDING
6. **进度同步:** `setProgress()` (WorkManager Data) + `QueueRepository.updateTaskProgress()` 双通道上报
7. **结果处理:** Complete → `HistoryManager.save()` → `markTaskComplete()`；Error → `markTaskError()`
8. **停止/销毁:** `QueueController.stop()` → `WorkManager.cancelAllWorkByTag()` + `cancelAllPending()`

## 队列调度架构（WorkManager）

```
QueueController (统一入口)
  ├── start(context)     → WorkManager.enqueueUniqueWork(KEEP)
  ├── stop(context)      → WorkManager.cancelAllWorkByTag + cancelAllPending
  └── observeState()     → Flow<WorkInfo?> (RUNNING/SUCCEEDED/FAILED/CANCELLED)

GenerationWorker : CoroutineWorker (Tag: "generation-queue")
  ├── doWork()           → processLoop() → while(isActive) { ... }
  ├── waitForBackend()   → health check polling loop (3s interval)
  ├── setForeground()    → ForegroundInfo (notification)
  └── setProgress()      → WorkData (progress/taskId/prompt)

QueueNotificationHelper
  ├── createForegroundInfo() → GenerationWorker 前台通知
  └── createNotification()   → QueueProcessingService 通知 (兼容路径)
```

## 任务数据模型 (GenerationTask)

```
字段: id, batchGroupId, batchIndex, modelId,
      prompt, negativePrompt, steps, cfg, seed,
      width, height, effectiveWidth, effectiveHeight,
      denoiseStrength, useOpenCL, scheduler, aspectRatio,
      status (PENDING|PROCESSING|COMPLETED|ERROR|CANCELLED),
      timestamp, resultBitmap, resultSeed, errorMessage, progress
```

## Worker 处理流程

1. `doWork()` 启动
2. `while (isActive):`
   a. `getNextPending()` → 获取下一个 PENDING 任务
   b. `waitForBackend()` → 确保后端 HTTP 服务在线
   c. `markTaskProcessing(task.id)` + `processingActive=true`
   d. `BackendManager.generate(params).collect { event }`:
      - Progress → `updateTaskProgress()` + `setProgress()`
      - Complete → decode base64 → `HistoryManager.save()` → `markTaskComplete()`
      - SseEvent.Error → `markTaskError(AppError.Backend)`
3. 异常处理:
   a. CancellationException → `resetTaskToPending()` → throw → success
   b. 其他 Exception → `resetTaskToPending()` → 回到步骤 2

## UI 功能

- **批量折叠显示:** 同一批 (相同 batchGroupId) 的请求折叠为一组
- **状态指示:** 颜色圆点 (PENDING灰、PROCESSING蓝/紫、COMPLETED绿、ERROR红、CANCELLED浅灰)
- **进度显示:** `LinearProgressIndicator` + 百分比文本 (`XX%`)
- **左右滑动操作:**
  - **左滑删除:** 非 PROCESSING 状态的任务/批次左滑露出红色删除图标
  - **右滑保存:** 非 PROCESSING 状态的任务/批次右滑露出绿色保存图标，通过 `RecordRepository.addRecord()` 存入
- **空队列提示:** "No tasks in queue"

## Queue-Backend 交互规范

### 职责分离

```
Model Screen:
  负责: 后端进程生命周期管理
  BackendService (Foreground Service)
    ├── startBackend(modelId, width, height) → 启动 C++ 进程
    └── stopBackend() → Process.destroy() + waitFor(5s)

Queue (GenerationWorker):
  负责: 任务队列 + 后端状态感知 + 生成调度
  不负责: 启动/重启/停止后端进程
```

### waitForBackend() 协议

1. `healthCheck()` → OK → 快速路径返回 true
2. `healthCheck()` → FAIL → `processingActive = false` → 通知 "Waiting for backend..." → 每 3s 轮询
3. 关键行为:
   - 不标记任何任务为 ERROR
   - 不调用后端的启动/重启方法
   - 不触碰后端进程生命周期

### 错误区分

| 异常类型 | 含义 | 任务状态 | 后续行为 |
|---------|------|---------|---------|
| `SseEvent.Error` (SSE 流内) | 后端处理到错误 (OOM、无效参数) | `ERROR` | 跳过此任务 |
| 连接中断/超时 (Exception) | 后端崩溃或网络断开 | 回退 `PENDING` | 进入 waitForBackend() |
| `CancellationException` | 用户按下 Stop 按钮 | 回退 `PENDING` | Worker 终止 |

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §4.3 内容，创建独立文件 |
