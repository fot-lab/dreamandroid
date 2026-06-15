# SERV-BACK-0001: 服务层 — 后端管理与队列处理

| 属性 | 值 |
|------|-----|
| 分类 | Service — Backend & Queue |
| 对应章节 | §5 |
| 依赖 | ARCH-OVER-0001, BKND-APIS-0001 |

## 5.1 BackendService

- 前台服务，管理 C++ 原生进程生命周期
- 通过 `libstable_diffusion_core.so` 启动 HTTP 服务
- 状态: Idle / Starting / Running / Error
- 支持 NPU 模式 (QNN)、CPU 模式 (MNN)、Upscaler 模式
- 环境变量管理: LD_LIBRARY_PATH、DSP_LIBRARY_PATH

## 5.2 BackgroundGenerationService

- 前台服务，处理单个 HTTP 生成请求（**兼容路径，主路径已迁移到 GenerationWorker**）
- 最多重试 3 次 (间隔 1.5s)
- SSE 流式解析: progress → Progress 状态 / complete → Complete 状态
- 进度预览解码 (Base64 → RGB → Bitmap)
- Bitmap 消费等待机制 (默认 30s 超时)
- 支持用户主动停止 (ACTION_STOP 广播)

## 5.2.1 GenerationWorker (WorkManager)

- `CoroutineWorker` 子类，Tag: `"generation-queue"`
- `doWork()` 内部循环: `while(isActive) { waitForBackend() → getNextPending() → process() }`
- **`waitForBackend()`**: 后端在线则立即返回 true；离线则 `processingActive=false` + 每 3s 轮询 `/health`
- **不负责启动/重启后端** — 后端生命周期由 Model Screen (§4.2) 管理
- **后端掉线恢复**: `generate()` 抛异常 → `resetTaskToPending()` → 回到 `waitForBackend()`
- 通过 `setForeground()` 自动管理前台通知
- `doWork()` 异常处理:
  - `CancellationException` → `Result.success()`
  - 其他 `Exception` → `hasPendingTasks() ? Result.retry() : Result.failure()`

## 5.2.2 QueueController

- `object` 单例，WorkManager 队列生命周期控制
- `start(context)`: `enqueueUniqueWork("generation-queue", KEEP)` — 幂等
- `stop(context)`: `cancelAllWorkByTag("generation-queue")` + `QueueRepository.cancelAllPending()`
- `observeState(context)`: 返回 `Flow<WorkInfo?>`

## 5.2.3 QueueNotificationHelper

- `object` 单例，统一通知工具
- `ensureChannel(context)`: 创建 `"queue_processing_channel"` 通知通道 (IMPORTANCE_LOW)
- `createForegroundInfo(context, title, progress)`: 供 GenerationWorker 使用
- `createNotification(context, title, progress, stopPendingIntent?)`: 可选 Stop 按钮

## 5.3 QueueRepository

- 内存中的任务队列状态管理 (`MutableStateFlow<List<GenerationTask>>`)
- **进程级单例:** `QueueRepository.getInstance(context)` (double-checked locking)
- 共享于 UI, GenerationWorker, QueueProcessingService
- 方法: `addBatch`, `removeTask`, `removeBatch`, `getNextPending`, `cancelAllPending`
- 状态管理: `markTaskProcessing/Complete/Error`, `resetTaskToPending`, `updateTaskProgress`, `setProcessingActive`
- **线程安全:** 已迁移为 `_tasks.update { ... }` 原子操作

## 5.4 UpscaleBackendManager

- 单例对象，管理 Upscale 后端的完整生命周期
- 进程启动、状态监控、优雅停止
- 自动准备 QNN 运行时库

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §5 内容，创建独立文件 |
