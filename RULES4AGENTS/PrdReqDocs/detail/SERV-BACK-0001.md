# SERV-BACK-0001: 服务层 — 后端管理与队列处理

| 属性 | 值 |
|------|-----|
| 分类 | Service — Backend & Queue |
| 对应章节 | §5 |
| 依赖 | ARCH-OVER-0001, BKND-APIS-0001 |

## 5.1 BackendManager — C++ 后端进程管理器

`BackendManager` 是 **C++ HTTP 服务器进程的唯一管理者**，负责完整的进程生命周期：

- 通过 `ProcessBuilder` 启动 C++ 原生可执行文件 (`libstable_diffusion_core.so`)
- C++ 进程内嵌 cpp-httplib HTTP Server，默认监听 `127.0.0.1:8081`
- 支持 `--listen_all` 参数切换为 `0.0.0.0` 暴露到局域网
- 状态: `State.Idle` / `State.Starting(mode, modelId)` / `State.Running(mode, modelId)` / `State.Error(message)`
- 模式: `Mode.Diffusion` (扩散模型, QNN/MNN/OpenCL) / `Mode.Upscaler` (超分辨率模型)
- 环境变量管理: LD_LIBRARY_PATH、DSP_LIBRARY_PATH
- 端口互斥: 启动前检测孤儿进程并发送 `/shutdown` 优雅清理
- 进程停止: `stop()` → SIGTERM → waitFor(5s) → destroyForcibly()
- 暴露 HTTP 端点: `/health`, `/generate` (SSE), `/upscale`, `/tokenize`, `/shutdown`
- 持有共享 `OkHttpClient` 用于与 C++ 服务器通信

## 5.1.1 BackendService — HTTP 中间件（UI 层边界）

`BackendService` 是 `BackendManager` 的薄代理层，为 UI 层 (Screen/ViewModel) 提供统一的 HTTP 通信接口：

- **架构定位**: UI 层不允许直接引用 `BackendManager`，所有 HTTP 请求必须通过 `BackendService`
- **当前实现**: 纯委托代理，将所有调用转发给 `BackendManager`
- **未来扩展点**: 可在此层统一添加 logging、metrics、caching、retry policies
- 暴露 `state: StateFlow<BackendManager.State>` 供 UI 观察后端状态
- 代理方法: `startDiffusion()`, `startUpscaler()`, `stop()`, `healthCheck()`, `generate()`, `tokenize()`, `upscale()`

**依赖关系:**
```
UI (Screen/ViewModel) → BackendService (HTTP middleware) → BackendManager (process manager) → C++ HTTP Server
```

## 5.2 队列处理（双路径并存）

队列系统有两条处理路径，功能等价但框架不同：

### 5.2.1 GenerationWorker (WorkManager) — 主路径

- `CoroutineWorker` 子类，Tag: `"generation-queue"`
- `doWork()` 内部循环: `while(isActive) { waitForBackend() → getNextPending() → process() }`
- **`waitForBackend()`**: 后端在线则立即返回 true；离线则 `processingActive=false` + 每 3s 轮询 `/health`
- **不负责启动/重启后端** — 后端生命周期由 Model Screen 管理
- **后端掉线恢复**: `generate()` 抛异常 → `resetTaskToPending()` → 回到 `waitForBackend()`
- 通过 `setForeground()` 自动管理前台通知
- 进程重启后队列不丢失（WorkManager 持久化）

### 5.2.2 QueueProcessingService (Android Service) — 兼容路径

- Android 前台 Service，与 GenerationWorker 实现相同的队列处理循环
- 进度通过自定义 `_currentProgress` StateFlow 暴露
- 位图直接传递（非文件路径）
- 两条路径当前保留，待后续统一

### 5.2.3 QueueController

- `object` 单例，WorkManager 队列生命周期控制
- `start(context)`: `enqueueUniqueWork("generation-queue", KEEP)` — 幂等
- `stop(context)`: `cancelAllWorkByTag("generation-queue")` + `QueueRepository.cancelAllPending()`
- `observeState(context)`: 返回 `Flow<WorkInfo?>`

### 5.2.4 QueueNotificationHelper

- `object` 单例，统一通知工具
- `ensureChannel(context)`: 创建 `"queue_processing_channel"` 通知通道 (IMPORTANCE_LOW)
- `createForegroundInfo(context, title, progress)`: 供 GenerationWorker 使用
- `createNotification(context, title, progress, stopPendingIntent?)`: 可选 Stop 按钮

## 5.3 QueueRepository

- 内存 + Room 持久化的任务队列状态管理
- **进程级单例:** `QueueRepository.getInstance(context)` (double-checked locking)
- 共享于 UI, GenerationWorker, QueueProcessingService
- 方法: `addBatch`, `removeTask`, `removeBatch`, `getNextPending`, `cancelAllPending`
- 状态管理: `markTaskProcessing/Complete/Error`, `resetTaskToPending`, `updateTaskProgress`, `setProcessingActive`
- **线程安全:** 已迁移为 `_tasks.update { ... }` 原子操作

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §5 内容，创建独立文件 |
| 2026-06-16 | 重构 §5.1: 旧 BackendService (前台Service) 已删除；BackendManager 为进程管理器；新增 BackendService 为 HTTP 中间件；删除 §5.4 UpscaleBackendManager (已合并到 BackendManager)；更新 §5.2 双队列路径说明 |
