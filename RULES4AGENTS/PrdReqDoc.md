# DreamHub Product Requirements Document (PRD)

> 版本: 4.0
> 更新日期: 2026-06-15
> 本文档描述 DreamHub Android 应用的完整产品架构、模块接口标准与技术规范。

---

## 1. 产品概述

DreamHub 是一款本地 AI 图像生成 Android 应用。它通过原生 C++ 后端（cpp-httplib HTTP Server，端口 8081）在设备本地运行 Stable Diffusion 模型和 Real-ESRGAN 超分辨率模型，提供文生图、图生图、超分辨率放大等功能。前端使用 Jetpack Compose + Material 3 构建。

---

## 2. 系统架构

### 2.1 分层架构

```
┌──────────────────────────────────────────────────────────────┐
│  Presentation Layer (Jetpack Compose)                        │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  │
│  │ Models   │  │  Queue   │  │ Generate │  │ Upscale  │  │ Browse   │  │
│  │ Tab      │  │ Tab      │  │  Tab     │  │ Tab      │  │ Tab      │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  │
│       │              │             │             │              │         │
│  ┌────┴──────────────┴─────────────┴─────────────┴──────────────┴──────┐  │
│  │            MainActivity (Orchestrator / God Class)     │  │
│  │  - ~1800 lines, holds ALL UI state (~20 gen* vars)    │  │
│  │  - Directly calls QueueRepository / BackendService    │  │
│  │  - ⚠️ No ViewModels — bypasses MVVM layering          │  │
│  └────────────────────┬──────────────────────────────────┘  │
└───────────────────────┼──────────────────────────────────────┘
                        │
┌───────────────────────┼──────────────────────────────────────┐
│  Service Layer        │                                      │
│  ┌────────────────────┴──────────────────────────────────┐  │
│  │  Queue Processing (2 parallel paths)                   │  │
│  │  ┌─ GenerationWorker (WorkManager, primary)         ─┐ │  │
│  │  │   · processLoop() → waitForBackend() → generate() │ │  │
│  │  │   · CancellationException → Result.success()      │ │  │
│  │  │   · Backend crash mid-task → resetTaskToPending() │ │  │
│  │  └──────────────────────────────────────────────────┘ │  │
│  │  ┌─ QueueProcessingService (Foreground, legacy)     ─┐ │  │
│  │  │   · Similar loop but different error policy       │ │  │
│  │  │   · Health fail → markTaskError() (permanent)     │ │  │
│  │  └──────────────────────────────────────────────────┘ │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Backend Management (2 parallel systems)             │  │
│  │  ┌─ BackendService (legacy Foreground Service)    ─┐  │  │
│  │  │   · Starts C++ process, own state management    │  │  │
│  │  │   · Used by MainActivity.loadModel()            │  │  │
│  │  │   · BackendState: Idle/Starting/Running/Error   │  │  │
│  │  └─────────────────────────────────────────────────┘  │  │
│  │  ┌─ BackendManager (new unified manager)          ─┐  │  │
│  │  │   · API gateway: healthCheck/generate/tokenize  │  │  │
│  │  │   · State: Idle/Starting/Running/Error          │  │  │
│  │  │   · Used by GenerationWorker for API calls      │  │  │
│  │  │   · ⚠️ Can also start C++ process independently │  │  │
│  │  └─────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                             │
│  ┌─ QueueRepository (process-wide singleton, StateFlow) ─┐  │
│  │   · tasks: MutableStateFlow<List<GenerationTask>>     │  │
│  │   · processingActive: MutableStateFlow<Boolean>       │  │
│  │   · ⚠️ In-memory only — lost on process death        │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                             │
│  ┌─ HistoryManager (file + Room DB) ─────────────────────┐  │
│  │   · saveGeneratedImage(): PNG/JPEG + Room insert      │  │
│  │   · ⚠️ Returns null on failure, caller may not check  │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                             │
│  ┌─ RecordRepository (JSON file persistence) ────────────┐  │
│  │   · generate_records.json in internal storage         │  │
│  │   · ⚠️ Corrupted JSON → all records silently lost     │  │
│  └──────────────────────────────────────────────────────┘  │
└───────────────────────┬──────────────────────────────────────┘
                        │ HTTP (OkHttp, single shared client)
┌───────────────────────┴──────────────────────────────────────┐
│  C++ Backend (libstable_diffusion_core.so)                   │
│  HTTP Server: http://localhost:8081                           │
│  - /health      (GET)  — health check, 3s timeout            │
│  - /generate    (POST) — SSE streaming generation, 3600s     │
│  - /upscale     (POST) — raw RGB → 4× upscaled, 300s         │
│  - /tokenize    (POST) — CLIP token count, 5s                │
│  - Qualcomm QNN SDK (NPU) | alibaba/MNN (CPU)                │
│  - cpp-httplib (HTTP Server) | xtensor-stack (Tensor ops)    │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 核心数据流

#### 2.2.1 生成任务主路径: Generate → Queue → Worker → Backend → History

```
[User] ──fills params──▶ MainActivity gen* StateFlow variables
                                  │
                    onAddToQueue  │  GenerateScreen calls back to MainActivity
                                  ▼
                    QueueRepository.addBatch(modelId, prompt, ...)
                      │  └─ Creates N GenerationTask(PENDING) in _tasks StateFlow
                      │
          ┌───────────┴────────────┐
          │  LaunchedEffect detects │  (MainActivity line 205)
          │  hasPendingTasks()      │
          └───────────┬────────────┘
                      │
          QueueController.start(context)
            │  └─ WorkManager.enqueueUniqueWork("generation-queue", KEEP)
            │
            ▼
    ┌── GenerationWorker.doWork() ──────────────────────────┐
    │                                                        │
    │  processLoop():                                        │
    │    while (isActive) {                                  │
    │      task = QueueRepository.getNextPending() ───────── │
    │      if (task == null) → Result.success()              │
    │                                                        │
    │      ┌─ Step 1: waitForBackend() ──────────────────    │
    │      │  BackendManager.healthCheck()                   │
    │      │   ├─ true  → proceed                            │
    │      │   └─ false → setProcessingActive(false)         │
    │      │             → poll /health every 3s             │
    │      │             → until OK or worker cancelled      │
    │      └────────────────────────────────────────────────  │
    │                                                        │
    │      markTaskProcessing(task.id)                       │
    │      setProcessingActive(true)                         │
    │                                                        │
    │      ┌─ Step 2: GenerateParams(task) ──────────────    │
    │      └────────────────────────────────────────────────  │
    │                                                        │
    │      ┌─ Step 3: BackendManager.generate(params) ───    │
    │      │  POST /generate → SSE Flow<SseEvent>            │
    │      │   ├─ Progress → updateTaskProgress()            │
    │      │   ├─ Complete → HistoryManager.save()           │
    │      │   │             → markTaskComplete()            │
    │      │   └─ Error    → markTaskError(AppError)         │
    │      └────────────────────────────────────────────────  │
    │                                                        │
    │      Exception handlers:                               │
    │       ├─ CancellationException → resetTaskToPending()  │
    │       │    throw e → Result.success()                  │
    │       └─ Exception (backend crash) → resetTaskToPending│
    │            re-enter loop → waitForBackend()            │
    │    }                                                   │
    │                                                        │
    │  Top-level handlers:                                   │
    │   ├─ CancellationException → Result.success()          │
    │   └─ Exception + hasPending → Result.retry()           │
    └────────────────────────────────────────────────────────┘
                              │
                              ▼
                    Browse Tab (view/manage history)
```

#### 2.2.2 模型加载与后端生命周期

```
[User] clicks Load Model → MainActivity.loadModel(mId)
  ├─ Stop current BackendService if running
  │   ├─ sendBroadcast(ACTION_STOP) → BackgroundGenerationService
  │   └─ stopService(BackendService.ACTION_STOP)
  ├─ delay(500ms)
  ├─ startForegroundService(BackendService, modelId, width, height)
  │   └─ BackendService.onStartCommand()
  │       └─ startBackend(model) → ProcessBuilder.start(C++ executable)
  │           └─ BackendState: Starting → Running
  └─ selectedModelId = mId

[User] clicks Unload → MainActivity.unloadModel()
  ├─ sendBroadcast(ACTION_STOP) → BackgroundGenerationService
  ├─ stopService(BackendService.ACTION_STOP)
  │   └─ BackendService.onDestroy() → stopBackend()
  │       └─ Process.destroy() → waitFor(5s) → destroyForcibly()
  └─ selectedModelId = null

⚠️ Dual Backend System:
  BackendService (legacy)     vs    BackendManager (new)
  · Starts C++ process              · API gateway only (healthCheck, generate, tokenize)
  · Own StateFlow (static)          · Own StateFlow (instance)
  · Used by: MainActivity           · Used by: GenerationWorker
  · BackendState sealed class       · State sealed class (different type!)
  · both reference port 8081        · can also start process (startDiffusion/startUpscaler)
```

#### 2.2.3 队列控制流

```
Start:
  LaunchedEffect(queueTasks) [MainActivity line 205]
    → if hasPending && !processingActive → QueueController.start(context)
      → WorkManager.enqueueUniqueWork("generation-queue", KEEP)

Stop:
  QueueTopBar [■ Stop] → QueueController.stop(context)
    ├─ WorkManager.cancelAllWorkByTag("generation-queue")
    │   └─ GenerationWorker receives CancellationException
    │       └─ processLoop: resetTaskToPending() → throw e → Result.success()
    ├─ QueueRepository.cancelAllPending()
    │   └─ All PENDING tasks → CANCELLED
    └─ QueueRepository.setProcessingActive(false)

processingActive 同步 (⚠️ 双重数据源):
  源1: QueueRepository.processingActive
       · set by: GenerationWorker (true on start, false on idle/wait)
       · set by: QueueController.stop()
  源2: WorkInfo.State.RUNNING
       · LaunchedEffect [MainActivity line 195]:
           observeState().collect { info →
             if !RUNNING → QueueRepository.setProcessingActive(false)
           }
```

#### 2.2.4 历史保存与错误处理流

```
HistoryManager.saveGeneratedImage(modelId, bitmap, params, mode)
  ┌─ Dispatchers.IO ────────────────────────────────────┐
  │ 1. Write PNG/JPEG to files/history/{modelId}/{ts}.png│
  │ 2. Insert row into Room DB (generation_history table) │
  │ 3. Return HistoryItem or null on failure              │
  └──────────────────────────────────────────────────────┘

⚠️ 调用方未检查返回值:
  GenerationWorker (line 169):
    historyManager.saveGeneratedImage(...)  // return value IGNORED
    queueRepository.markTaskComplete(...)   // marked complete even if save failed
  → 如果磁盘写满或 Room 插入失败，任务仍标记 COMPLETED，图片数据丢失

QueueProcessingService (line 188): 同样未检查返回值
```

### 2.3 状态管理全景

```
┌───────────────┬──────────────────────┬─────────────────────────────┐
│ 状态           │ 存储位置              │ 观察者                       │
├───────────────┼──────────────────────┼─────────────────────────────┤
│ 队列任务列表    │ QueueRepository      │ MainActivity → QueueScreen   │
│               │ _tasks StateFlow     │                             │
├───────────────┼──────────────────────┼─────────────────────────────┤
│ 队列处理中      │ QueueRepository      │ MainActivity (queueProcessing)│
│               │ processingActive     │ + WorkInfo sync LaunchedEffect│
│               │ + WorkInfo.State     │                             │
├───────────────┼──────────────────────┼─────────────────────────────┤
│ 生成参数        │ ~20 gen* 变量在      │ GenerateScreen (通过参数传递) │
│               │ MainActivity.kt      │ GenerateTopBar              │
│               │ (remember + mutable) │                             │
├───────────────┼──────────────────────┼─────────────────────────────┤
│ 后端扩散状态    │ BackendService       │ MainActivity (isModelLoaded) │
│               │ backendState (static) │                             │
├───────────────┼──────────────────────┼─────────────────────────────┤
│ 后端管理器状态  │ BackendManager       │ (无 UI 观察者)               │
│               │ state (instance)     │                             │
├───────────────┼──────────────────────┼─────────────────────────────┤
│ 超分辨率状态    │ UpscaleBackendManager│ MainActivity                │
│               │ state (static)       │                             │
├───────────────┼──────────────────────┼─────────────────────────────┤
│ 模型列表        │ ModelRepository      │ MainActivity → ModelsTab    │
│               │ (in-memory + refresh) │                             │
├───────────────┼──────────────────────┼─────────────────────────────┤
│ 参数偏好        │ GenerationPreferences│ MainActivity + GenerateScreen│
│               │ (SharedPreferences)  │ ⚠️ 两处同时加载                 │
├───────────────┼──────────────────────┼─────────────────────────────┤
│ 历史记录        │ HistoryManager       │ BrowseScreen                │
│               │ (Room DB + files)    │                             │
├───────────────┼──────────────────────┼─────────────────────────────┤
│ 参数记录        │ RecordRepository     │ GenerateScreen Records Tab  │
│               │ (JSON file)          │                             │
├───────────────┼──────────────────────┼─────────────────────────────┤
│ 迁移状态        │ DreamAndroidApp      │ MainActivity (顶层路由)       │
│               │ migrationState       │                             │
└───────────────┴──────────────────────┴─────────────────────────────┘
```

### 2.4 错误处理体系

```
AppError (sealed class)
  ├─ Network(message, code, cause)   — HTTP/IO 错误
  ├─ Backend(message)                 — 后端返回错误
  ├─ Parse(message, cause)            — JSON/Bitmap 解析失败
  └─ Storage(message, cause)          — 文件/DB 操作失败

AppError.from(e: Throwable) — 自动分类:
  · AppError      → 原样返回
  · IOException   → Network
  · JSONException → Parse
  · 其他           → Backend

GenerationWorker 错误策略:
  ├─ waitForBackend() 失败 → 暂停轮询 (非错误，等待基础设施恢复)
  ├─ SSE Error event      → markTaskError(AppError.Backend)
  ├─ CancellationException→ resetTaskToPending() → 任务恢复为 PENDING
  ├─ Backend crash        → resetTaskToPending() → 重新进入等待循环
  └─ Worker 级别异常       → Result.retry() 或 Result.failure()

QueueProcessingService 错误策略 (⚠️ 不一致):
  ├─ healthCheck 失败      → markTaskError() (标记为永久失败)
  └─ generation 异常        → markTaskError(AppError.from(e)) (标记为永久失败)
```

---

## 3. 后端 HTTP 接口

后端为 C++ 原生进程，通过 `cpp-httplib` 在端口 8081 上提供 HTTP 服务。

### 3.1 健康检查

| 项目 | 描述 |
|------|------|
| **端点** | `GET http://localhost:8081/health` |
| **功能** | 验证后端 HTTP 服务是否在线可用 |
| **请求体** | 无 |
| **响应** | HTTP 200 (服务可用) 或连接拒绝/超时 (不可用) |
| **超时** | 连接/读取超时 3s |
| **调用方** | `GenerationWorker.waitForBackend()` (每 3s 轮询), `BackendManager.healthCheckWithRetry()` |
| **重试策略** | Queue 侧: 连续轮询直到后端上线 (3s 间隔)，不自动重启后端 |

> **重要:** Queue 不负责启动或重启后端 C++ 进程。后端生命周期由 Model Screen 通过 `BackendService` (Foreground Service) 手动管理。Queue 仅作为后端消费者，通过 health check 感知后端状态变化。

### 3.2 图片生成

| 项目 | 描述 |
|------|------|
| **端点** | `POST http://localhost:8081/generate` |
| **功能** | 提交图像生成请求，返回 SSE 流式响应 |
| **Content-Type** | `application/json` |
| **超时** | 连接/读/写/Call 超时 3600s |

**请求参数 (JSON Body):**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `prompt` | string | 是 | — | 正向提示词 |
| `negative_prompt` | string | 否 | `""` | 负面提示词 |
| `steps` | int | 是 | 28 | 采样步数 (1-50) |
| `cfg` | float | 是 | 7.0 | CFG 引导系数 (1.0-30.0) |
| `use_cfg` | bool | 是 | true | 是否使用 CFG |
| `width` | int | 是 | 512 | 生成宽度 (64-4096) |
| `height` | int | 是 | 512 | 生成高度 (64-4096) |
| `denoise_strength` | float | 是 | 0.6 | 去噪强度 (图生图) |
| `use_opencl` | bool | 是 | false | 是否使用GPU (CPU模型) |
| `scheduler` | string | 是 | `"dpm"` | 调度器选择 |
| `show_diffusion_process` | bool | 是 | false | 是否返回中间步骤预览 |
| `show_diffusion_stride` | int | 是 | 1 | 中间预览步长 |
| `aspect_ratio` | string | 是 | `"1:1"` | 宽高比 |
| `seed` | long | 否 | 随机 | 随机种子 |
| `image` | string (base64) | 否 | — | 输入图片 (图生图) |
| `mask` | string (base64) | 否 | — | 蒙版图片 (Inpainting) |

**调度器选项 (scheduler):**
- `dpm` — DPM++ 2M
- `dpm_sde` — DPM++ 2M SDE
- `euler_a` — Euler A
- `euler` — Euler
- `lcm` — LCM
- 以上均可附加 `_karras` 后缀 (LCM 除外)

**响应格式 (SSE Streaming):**
```
data: {"type":"progress","step":1,"total_steps":20,"image":"<base64>"}
data: {"type":"progress","step":2,"total_steps":20,"image":"<base64>"}
...
data: {"type":"complete","image":"<base64>","seed":12345,"width":512,"height":512}
data: [DONE]
```
或错误：
```
data: {"type":"error","message":"<error description>"}
```

**调用方:** `GenerationWorker` (通过 `BackendManager.generate()`), `BackgroundGenerationService` (兼容路径)

### 3.3 超分辨率放大

| 项目 | 描述 |
|------|------|
| **端点** | `POST http://localhost:8081/upscale` |
| **功能** | 对图片进行超分辨率放大 |
| **Content-Type** | `application/octet-stream` |
| **请求体** | RGB 原始字节 (width×height×3 bytes) |
| **超时** | 连接/读取超时 300s |

**请求头:**

| 头名称 | 说明 |
|--------|------|
| `X-Image-Width` | 输入图片宽度 (像素) |
| `X-Image-Height` | 输入图片高度 (像素) |
| `X-Upscaler-Path` | Upscaler 模型文件绝对路径 |

**响应:** RGB 字节流 (4× 放大后尺寸)

**调用方:** `performUpscale()` in ImageUtils.kt (by UpscaleScreen)

### 3.4 Token 计数

| 项目 | 描述 |
|------|------|
| **端点** | `POST http://localhost:8081/tokenize` |
| **功能** | 计算提示词的 CLIP Token 数量 |
| **Content-Type** | `application/json` |
| **超时** | 连接 2s，读取 5s |

**请求参数 (JSON):**

| 参数 | 类型 | 说明 |
|------|------|------|
| `prompt` | string | 需要计数的提示词文本 |

**响应 (JSON):**

| 字段 | 类型 | 说明 |
|------|------|------|
| `count` | int | Token 数量 |
| `max_length` | int | CLIP 最大 token 长度 (77) |
| `overflow_offset` | int | 溢出开始的字符偏移，-1 表示未溢出 |

**调用方:** GenerateScreen (tokenizePromptForGenerate 函数)

---

## 4. 前端功能需求

### 4.1 导航结构

底部导航栏 (Bottom Navigation Bar) 包含 5 个 Tab：

| 顺序 | Tab | 路由 | 图标 | 功能简述 |
|------|-----|------|------|---------|
| 1 | Models | `models` | Memory (内存芯片) | 模型管理 |
| 2 | Queue | `queue` | AutoAwesome (魔法星星) | 任务队列 |
| 3 | Generate | `generate` | AutoFixHigh (魔法棒/wizard) | 参数组合 |
| 4 | Upscale | `upscale` | ImageSearch (放大镜图片) | 超分辨率 |
| 5 | Browse | `browse` | PhotoLibrary (图库) | 图片画廊 |

每个 Tab 配有独立 TopAppBar，左侧菜单按钮打开导航抽屉（Navigation Drawer），设置是其中的一个条目。

---

### 4.2 Models Tab — 模型管理页面

**功能定位:** 管理后端服务的启停、模型选取与加载、模型导入/删除/重命名。

#### 4.2.1 模型列表

- 显示已下载/导入的生成模型（Diffusion Models）
- 显示已下载/导入的超分辨率模型（Upscale Models）
- 每张卡片显示: 模型名称、描述、类型标签
- 选中状态视觉反馈 (secondaryContainer 背景色)
- 已加载状态视觉反馈 (primaryContainer + primary 边框)

#### 4.2.2 模型类型

**生成模型 (Diffusion Models) — 三种子类型:**

| 类型 | backendType | 运行时 | 分辨率 | 说明 |
|------|-------------|--------|--------|------|
| SD 1.5 NPU | `sd15npu` | Qualcomm 芯片 NPU | 128-512 | 需要 QNN SDK |
| SD 1.5 CPU | `sd15cpu` | MNN CPU Runtime | 128-512 | 可切换 GPU(OpenCL) |
| SDXL NPU | `sdxl` | Qualcomm 8Gen3+ NPU | 1024 | SDXL 大分辨率 |

**预置模型列表:**

| 模型 ID | 名称 | 类型 | 大小 |
|---------|------|------|------|
| `sdxl_base` | SDXL Base 1.0 | SDXL NPU | 4.2GB |
| `illustrious_v16` | Illustrious v16 | SDXL NPU | 4.2GB |
| `anythingv5` | Anything V5.0 | SD1.5 NPU | 1.1GB |
| `anythingv5cpu` | Anything V5.0 | SD1.5 CPU | 1.2GB |
| `qteamix` | QteaMix | SD1.5 NPU | 1.1GB |
| `qteamixcpu` | QteaMix | SD1.5 CPU | 1.2GB |
| `absolutereality` | Absolute Reality | SD1.5 NPU | 1.1GB |
| `absoluterealitycpu` | Absolute Reality | SD1.5 CPU | 1.2GB |
| `cuteyukimix` | CuteYukiMix | SD1.5 NPU | 1.1GB |
| `cuteyukimixcpu` | CuteYukiMix | SD1.5 CPU | 1.2GB |
| `chilloutmix` | ChilloutMix | SD1.5 NPU | 1.1GB |
| `chilloutmixcpu` | ChilloutMix | SD1.5 CPU | 1.2GB |

**超分辨率模型 (Upscale Models):**

| 模型 ID | 名称 | 说明 |
|---------|------|------|
| `upscaler_anime` | Anime Upscaler | Real-ESRGAN 4x 动漫 |
| `upscaler_realistic` | Realistic Upscaler | UltraSharpV2 Lite 4x 写实 |

#### 4.2.3 模型操作

- **加载模型:** 停止当前后端 → 启动新 BackendService (传 modelId、width、height、use_opencl)
- **卸载模型:** 停止所有生成服务 → 发送 ACTION_STOP 广播
- **下载模型:** 从 HuggingFace (或镜像站) 下载，显示进度
- **导入自定义模型:**
  - CPU 模型: 选择文件 → convertCustomModel() 转换 → 标记 `finished`
  - NPU 模型: 选择 ZIP → extractNpuModel() → 标记 `npucustom`
  - SDXL 模型: 标记 `SDXL` + `npucustom`
- **导入 Upscale 模型:** 选择 .bin 文件 → 复制到 models/{id}/ 目录 → 标记 `upscaler_custom`
- **重命名模型:** 重命名模型目录 → 更新 selectedModelId
- **删除模型:** 删除模型目录 + 清除历史记录 + 清除偏好设置。如已加载则先卸载

#### 4.2.4 TopAppBar 操作

- Menu: 打开导航抽屉
- Load Model 按钮 (模型已选且未加载时显示)
- Unload Model 按钮 (模型已加载时显示)
- Loading 进度指示器 (模型加载中)
- 重命名按钮 (✏️)
- 删除按钮 (🗑️)
- 导入按钮 (+)，下拉菜单: 导入模型 / 导入NPU模型 / 导入Upscale模型

---

### 4.3 Queue Tab — 任务队列管理

**功能定位:** 作为 Generate 和 Backend 之间的中间层，管理请求处理的完整生命周期。**队列调度由 Android WorkManager 原生管理**，UI 通过 StateFlow 观察状态。

**职责边界:** Queue 不负责启动、重启或管理后端 C++ 进程。后端生命周期由 Model Screen (§4.2) 通过 `BackendService` 手动管理。Queue 仅通过 health check 感知后端状态并自动适配行为。

#### 4.3.1 核心职责

1. **接收任务:** 从 Generate Screen 接收批量生成请求 → `QueueRepository.addBatch()`
2. **任务调度:** 通过 `QueueController.start()` 向 WorkManager 提交 `GenerationWorker`（`ExistingWorkPolicy.KEEP` 防重复）
3. **FIFO 处理:** `GenerationWorker.processLoop()` 内部顺序处理 PENDING 任务
4. **后端状态感知:** 每个任务处理前调用 `waitForBackend()` — 后端离线则暂停并持续轮询 (3s 间隔)，后端上线自动恢复
5. **后端掉线恢复:** 生成中途后端崩溃 → `resetTaskToPending()` 将任务回退为 PENDING，自动重入等待循环
6. **进度同步:** `setProgress()` (WorkManager Data) + `QueueRepository.updateTaskProgress()` 双通道上报
7. **结果处理:** Complete → `HistoryManager.save()` → `markTaskComplete()`；Error → `markTaskError()`
8. **停止/销毁:** `QueueController.stop()` → `WorkManager.cancelAllWorkByTag()` + `cancelAllPending()`

#### 4.3.2 队列调度架构（WorkManager）

```
QueueController (统一入口)
  ├── start(context)     → WorkManager.enqueueUniqueWork(KEEP)
  ├── stop(context)      → WorkManager.cancelAllWorkByTag + cancelAllPending
  └── observeState()     → Flow<WorkInfo?> (RUNNING/SUCCEEDED/FAILED/CANCELLED)

GenerationWorker : CoroutineWorker (Tag: "generation-queue")
  ├── doWork()           → processLoop() → while(isActive) { ... }
  ├── waitForBackend()   → health check polling loop (3s interval)
  ├── setForeground()    → ForegroundInfo (notification)
  ├── setProgress()      → WorkData (progress/taskId/prompt)
  └── onStopped()        → CoroutineWorker 原生取消

QueueNotificationHelper
  ├── createForegroundInfo() → GenerationWorker 前台通知
  └── createNotification()   → QueueProcessingService 通知 (兼容路径)
```

**WorkManager 提供的原生能力：**

| 能力 | WorkManager API | 说明 |
|------|----------------|------|
| **任务入队** | `enqueueUniqueWork(KEEP)` | 幂等操作，防重复启动 |
| **取消所有任务** | `cancelAllWorkByTag(TAG)` | 一键终止 |
| **进度观察** | `getWorkInfosForUniqueWorkLiveData()` | LiveData→Flow 桥接 |
| **前台服务** | `CoroutineWorker.setForeground()` | 自动管理通知生命周期 |
| **持久化** | WorkManager 内置 Room DB | 杀进程后任务恢复 |
| **协程安全** | `CoroutineWorker` | 自动取消 + doWork 在 IO 线程 |

#### 4.3.3 任务数据模型 (GenerationTask)

```
字段: id, batchGroupId, batchIndex, modelId,
      prompt, negativePrompt, steps, cfg, seed,
      width, height, effectiveWidth, effectiveHeight,
      denoiseStrength, useOpenCL, scheduler, aspectRatio,
      status (PENDING|PROCESSING|COMPLETED|ERROR|CANCELLED),
      timestamp, resultBitmap, resultSeed, errorMessage, progress
```

#### 4.3.4 Worker 处理流程

```
1. GenerationWorker.doWork() 启动
2. while (isActive):
     a. getNextPending() → 获取下一个 PENDING 任务
     b. (任务为 null) → processingActive=false → Result.success() → Worker 完成
     c. waitForBackend() → 确保后端 HTTP 服务在线 (见 §4.3.7)
        - 在线: 继续步骤 3
        - 离线: 暂停轮询直到在线或被用户取消
     d. markTaskProcessing(task.id) + processingActive=true
     e. 发送前台通知 "Generating: {prompt}..."
3. BackendManager.generate(params).collect { event }:
   a. Progress → updateTaskProgress() + setProgress() + 前台通知更新
   b. Complete → decode base64 → HistoryManager.save() → markTaskComplete()
   c. SseEvent.Error → markTaskError(AppError.Backend)
4. 异常处理:
   a. CancellationException (用户取消) → resetTaskToPending() → throw e → doWork() 返回 success
   b. 其他 Exception (后端崩溃/网络错误) → resetTaskToPending() → 回到步骤 2
5. 循环回到步骤 2 (处理下一个 PENDING 任务)
```

> **与旧架构的关键区别：**
> - 旧: MainActivity LaunchedEffect 轮询 (500ms) + BackgroundGenerationService 单任务启动
> - 新: GenerationWorker 常驻 Worker 内部 while 循环 + 协程原生取消
> - 旧: health check 失败 → 标记 ERROR → 手动重启后端
> - 新: health check 失败 → 暂停等待 → 用户从 Model Screen 启动后端 → 自动恢复
> - 旧: serviceStopTime/Variable in MainActivity
> - 新: CoroutineWorker.isActive 自动响应取消

#### 4.3.5 UI 功能

- **批量折叠显示:** 同一批 (相同 batchGroupId) 的请求折叠为一组
  - 折叠状态: 显示提示词、数量徽章、完成/运行/失败统计
  - 展开状态: 显示每个独立条目的完整卡片
  - 单条目批: 不折叠，直接显示平铺卡片
- **状态指示:** 颜色圆点 (PENDING灰、PROCESSING蓝/紫、COMPLETED绿、ERROR红、CANCELLED浅灰)
- **进度显示:** PROCESSING 状态任务显示 `LinearProgressIndicator` + 百分比文本 (`XX%`)，数据来自 `task.progress` 字段 (0f..1f)
- **详情展开 (ℹ️ 按钮):** 点击展开查看 Steps、CFG、Size、Scheduler、Seed、Negative Prompt
- **左右滑动操作:**
  - **左滑删除 (Swipe Left →):** 非 PROCESSING 状态的任务/批次左滑露出红色删除图标，确认后删除
  - **右滑保存 (Swipe Right →):** 非 PROCESSING 状态的任务/批次右滑露出绿色保存图标，保存提示词和模型参数到 `Documents/DreamHub/`
- **空队列提示:** "No tasks in queue" + "Tap Generate to add tasks"

#### 4.3.6 队列生成控制

Queue Tab 承接了原 Generate Tab 的后台生成管理功能：

| 功能 | 实现方式 | 说明 |
|------|---------|------|
| **停止生成** | `QueueController.stop(context)` → WorkManager.cancelAllWorkByTag | 取消 Worker + 标记所有 PENDING 为 CANCELLED |
| **进度查看** | `LinearProgressIndicator` + `XX%` 文本 | 每个 PROCESSING 任务的卡片中实时显示，百分比从 `task.progress * 100` 计算 |
| **通知控制** | WorkManager ForegroundInfo + QueueNotificationHelper | `GenerationWorker.setForeground()` 自动管理通知，含进度条 |

> **设计原则：** Generate Tab 仅负责参数组合与"添加到队列"操作，不承担任何运行时生成控制职责。所有生成进行中的控制（进度、停止、取消）全部由 Queue Tab + WorkManager 承接。

#### 4.3.7 TopAppBar 操作

Queue TopBar（已实现）：
- Menu: 打开导航抽屉
- **Stop 按钮 (■):** 队列处理中 (`processingActive = true`) 时显示，点击调用 `QueueController.stop(context)`

> 实现：`QueueTopBar(drawerState, processingActive, onStop = { QueueController.stop(context) })`

#### 4.3.8 Queue-Backend 交互规范 (核心设计)

本节规定 GenerationWorker (Queue) 与 C++ Model Service (Backend) 之间的交互契约。这是整个队列系统的核心设计约束。

##### 4.3.8.1 职责分离

```
┌─────────────────────────────────────────────────────────┐
│                   Model Screen (§4.2)                    │
│                                                         │
│  负责: 后端进程生命周期管理                               │
│  ┌─────────────────────────────────────────────────┐   │
│  │ BackendService (Foreground Service)              │   │
│  │  ├── startBackend(modelId, width, height)        │   │
│  │  │   → 启动 C++ 进程 (libstable_diffusion_core)  │   │
│  │  │   → 监听 stdout/stderr                        │   │
│  │  │   → 进程退出 → BackendState.Error             │   │
│  │  ├── stopBackend()                               │   │
│  │  │   → Process.destroy() + waitFor(5s)           │   │
│  │  └── BackendState StateFlow:                     │   │
│  │      Idle / Starting / Running / Error(message)   │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
│  用户操作:                                               │
│   - ModelRunScreen → 选中模型 → 自动 startForegroundService│
│   - 切换模型 → 停止旧进程 → 启动新进程                     │
│   - 离开页面 → 可选停止后端                                │
└────────────────────────┬────────────────────────────────┘
                         │ HTTP localhost:8081
                         │
┌────────────────────────┴────────────────────────────────┐
│                   Queue (GenerationWorker)               │
│                                                         │
│  负责: 任务队列 + 后端状态感知 + 生成调度                  │
│  ┌─────────────────────────────────────────────────┐   │
│  │ 不负责: 启动/重启/停止后端进程                     │   │
│  │ 仅负责:                                           │   │
│  │  1. 每个任务处理前 → waitForBackend()             │   │
│  │  2. 后端在线 → markTaskProcessing → generate()   │   │
│  │  3. 后端离线 → 暂停 + 轮询 health check (3s)     │   │
│  │  4. 后端恢复 → 自动继续处理 PENDING 任务          │   │
│  │  5. 后端崩溃 → resetTaskToPending() → 回到步骤1  │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

##### 4.3.8.2 后端状态感知协议: `waitForBackend()`

```
waitForBackend(): Boolean

  入口:
    ├─ healthCheck() → OK ──────────────────→ return true (快速路径)
    │
    └─ healthCheck() → FAIL
         │
         ├─ processingActive = false     (UI 显示队列暂停)
         ├─ 通知: "Waiting for backend..."
         │
         └─ while (isActive):
              ├─ delay(3000ms)
              ├─ healthCheck() → OK ──→ 通知: "Backend online" → return true
              └─ healthCheck() → FAIL → 继续循环

  出口:
    ├─ true  → 后端在线，可以继续处理任务
    └─ false → Worker 被用户取消 (isActive = false)
```

**关键行为:**
- **不标记任何任务为 ERROR** — 后端不在线是暂时状态，不是任务失败
- **不调用后端的启动/重启方法** — 队列不触碰后端进程生命周期
- **`processingActive = false`** — 队列处于暂停状态，UI 不应显示 Stop 按钮
- **3秒轮询间隔** — 平衡响应速度与电池消耗
- **前台通知更新** — 用户可在通知栏看到 "Waiting for backend..."

##### 4.3.8.3 后端意外掉线恢复

当 `BackendManager.generate()` 在生成过程中抛出异常（如连接中断、超时）：

```
BackendManager.generate(params).collect { ... }
    │
    └─ catch (Exception)  // 非 CancellationException
         │
         ├─ 判断: 这是后端崩溃 (非任务参数错误)
         │
         ├─ queueRepository.resetTaskToPending(taskId)
         │   └─ 状态: PROCESSING → PENDING
         │   └─ progress: 重置为 0f
         │   └─ resultBitmap: 清空
         │
         └─ 循环回到 processLoop() 顶部
              │
              └─ waitForBackend() → 进入轮询等待
                   │
                   └─ 用户从 Model Screen 重新启动后端
                        │
                        └─ healthCheck() OK → 重新处理该任务
```

**为什么重置为 PENDING 而不是 ERROR:**
- 任务参数（prompt、steps、seed 等）本身有效
- 失败原因纯粹是基础设施暂时不可用
- 标记为 ERROR 会导致用户误认为任务是坏的
- 回退为 PENDING 确保后端恢复后自动重试，无需用户手动操作

##### 4.3.8.4 与 SseEvent.Error 的区分

| 异常类型 | 含义 | 任务状态 | 后续行为 |
|---------|------|---------|---------|
| `SseEvent.Error` (SSE 流内) | 后端处理到错误（如 OOM、无效参数） | `ERROR` | 跳过此任务，继续下一个 |
| 连接中断/超时 (Exception) | 后端崩溃或网络断开 | 回退 `PENDING` | 进入 waitForBackend() |
| `CancellationException` | 用户按下 Stop 按钮 | 回退 `PENDING` | Worker 终止 |

**判断逻辑:** SSE 流内的 `SseEvent.Error` 表示后端进程正常运行但请求本身失败（参数问题、模型问题）。而连接中断/超时（非 SSE Error）表示后端进程已经不可达。

##### 4.3.8.5 用户交互流程

```
场景 1: 正常处理
  User adds tasks → Worker auto-starts → backend is running → tasks process ✓

场景 2: 后端未启动
  User adds tasks → Worker starts → waitForBackend() → polls /health
  → User goes to Model Screen → selects model → BackendService starts
  → health check succeeds → Worker resumes → tasks process ✓

场景 3: 中途后端崩溃
  Worker processing task → backend crashes → generate() throws
  → resetTaskToPending() → waitForBackend() → polls /health
  → User restarts backend from Model Screen
  → Worker resumes → retries task → completes ✓

场景 4: 用户主动停止
  User taps Stop → WorkManager.cancelAllWorkByTag
  → isActive=false → CancellationException → resetTaskToPending
  → Worker returns success → pending tasks stay PENDING
```

##### 4.3.8.6 技术实现要点

| 组件 | 方法/属性 | 说明 |
|------|----------|------|
| `GenerationWorker` | `waitForBackend(): Boolean` | 阻塞式后端等待，3s 轮询 `/health` |
| `GenerationWorker` | `processLoop(): Result` | 主循环: while(isActive) { waitForBackend → process → handle errors } |
| `QueueRepository` | `resetTaskToPending(id)` | PROCESSING/ERROR → PENDING, progress=0 |
| `QueueRepository` | `setProcessingActive(Boolean)` | 控制 UI Stop 按钮可见性和状态显示 |
| `BackendManager` | `healthCheck(): Boolean` | 单次 GET /health，超时 3s |
| `BackendManager` | `healthCheckWithRetry()` | 保留但不再被 GenerationWorker 使用 |
| `BackendService` | `backendState: StateFlow` | 进程级别状态 (Model Screen 使用，Queue 不依赖) |

##### 4.3.8.7 配置常量

| 常量 | 位置 | 值 | 说明 |
|------|------|-----|------|
| `BACKEND_POLL_INTERVAL_MS` | `GenerationWorker.Companion` | 3000ms | 后端不可用时 health check 轮询间隔 |
| `HEALTH_CHECK_TIMEOUT_S` | `DreamHubConstants` | 3s | 单次 health check HTTP 超时 |
| `BACKEND_PORT` | `DreamHubConstants` | 8081 | C++ HTTP Server 端口 |

#### 4.3.9 Swipe-to-Save — 右滑保存提示词和参数

Queue Tab 中每条任务/批次支持**右滑保存**，将提示词和模型参数保存为文本文件，**保存目标路径与 Gallery Save Info 一致** (`Documents/DreamHub/`)。

##### 4.3.9.1 双方向滑动示意图

```
           右滑保存 ←                    → 左滑删除
┌──────────────────┬────────────────────┬──────────────────┐
│  💾 Save        │                    │        Delete 🗑  │
│  primaryContainer│   Task/Batch Card  │   errorContainer │
│  (green tint)   │                    │    (red tint)    │
└──────────────────┴────────────────────┴──────────────────┘
  enableDismissFromStartToEnd = true     enableDismissFromEndToStart = true
```

##### 4.3.9.2 实现规范

| 属性 | 左滑删除 (已有) | 右滑保存 (新增) |
|------|----------------|----------------|
| **方向** | `EndToStart` (从右向左) | `StartToEnd` (从左向右) |
| **背景色** | `errorContainer` (红色) | `primaryContainer` (绿色) |
| **图标** | `Icons.Default.Delete` | `Icons.Default.SaveAlt` |
| **图标颜色** | `error` | `onPrimaryContainer` |
| **启用条件** | 非 PROCESSING 状态 | 非 PROCESSING 状态 |
| **SwipeToDismissBox 配置** | `enableDismissFromEndToStart = !isProcessing` | `enableDismissFromStartToEnd = !isProcessing` |
| **动画** | `tween(300)` 颜色渐变 | `tween(300)` 颜色渐变 |
| **回调** | `onRemove()` | `onSaveInfo()` |

##### 4.3.9.3 保存内容

从 `GenerationTask` 提取以下字段写入 `.txt` 文件：

```
文本文件内容格式:
─────────────────────────────
DreamHub - Generation Parameters
─────────────────────────────
Prompt:        {task.prompt}
Negative:      {task.negativePrompt}
Model:         {task.modelId}
Steps:         {task.steps}
CFG Scale:     {task.cfg}
Seed:          {task.seed}
Scheduler:     {task.scheduler}
Size:          {task.width} x {task.height}
Timestamp:     {task.timestamp}
Status:        {task.status.name}
─────────────────────────────
```

##### 4.3.9.4 保存目标

Queue 右滑保存**不再直接写文件**，而是通过 `RecordRepository.addRecord()` 存入 Generate Screen 的记录管理器 (§4.4.8)：

| 属性 | 值 |
|------|-----|
| **存储方式** | `RecordRepository.addRecord()` → `generate_records.json` |
| **记录 `source`** | `RecordSource.QUEUE` |
| **保存内容** | GenerationTask → GenerateParameterRecord (prompt, negativePrompt, modelId, steps, cfg, seed, width, height, scheduler, timestamp) |
| **单任务保存** | 创建一个 Record |
| **批量保存** | 右滑批次组 → 循环为每个任务创建一个 Record |

> 记录与 Queue 任务解耦：任务删除后记录保留，仅在 Generate → Records Tab 中手动删除。

##### 4.3.9.5 反馈

| 场景 | 反馈 |
|------|------|
| **单任务保存成功** | Toast "Parameters saved" |
| **批量保存成功** | Toast "N tasks' parameters saved" |
| **保存失败** | Toast "Failed to save: {error}" |

##### 4.3.9.6 实现伪代码

```kotlin
// TaskCard SwipeToDismissBox 修改
val dismissState = rememberSwipeToDismissBoxState(
    confirmValueChange = { value ->
        when (value) {
            SwipeToDismissBoxValue.EndToStart -> {
                onRemove() // 左滑删除 (已有)
                true
            }
            SwipeToDismissBoxValue.StartToEnd -> {
                onSaveInfo() // 右滑保存 (新增)
                false // 不 dismiss，保留卡片
            }
            else -> false
        }
    },
)

// 背景内容 (双方向)
SwipeToDismissBox(
    state = dismissState,
    backgroundContent = {
        // 根据滑动手势方向显示不同背景
        if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
            // 红色删除背景 (已有)
            DeleteBackground()
        } else if (dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd) {
            // 绿色保存背景 (新增)
            SaveBackground()
        }
    },
    enableDismissFromStartToEnd = task.status != TaskStatus.PROCESSING,
    enableDismissFromEndToStart = task.status != TaskStatus.PROCESSING,
) { ... }

// 保存实现 (通过 RecordRepository 而非直接写文件)
val onSaveInfo: (GenerationTask) -> Unit = { task ->
    scope.launch {
        val record = GenerateParameterRecord(
            prompt = task.prompt,
            negativePrompt = task.negativePrompt,
            modelId = task.modelId,
            steps = task.steps,
            cfg = task.cfg,
            seed = task.seed,
            width = task.width,
            height = task.height,
            scheduler = task.scheduler,
            timestamp = task.timestamp,
            source = RecordSource.QUEUE,
        )
        recordRepository?.addRecord(record)
        Toast.makeText(context, "Parameters saved", Toast.LENGTH_SHORT).show()
    }
}
```

> **BatchGroupCard 同理:** 右滑批次组时，对 `group.tasks` 中每个任务调用 `onSaveInfo(task)`，Toast 汇报总数。

> **设计原则:** 右滑保存不 dismiss 卡片 (`return false`)，因为这是复制信息操作而非移除操作。任务保留在队列中，用户可以继续等待生成完成。

---

### 4.4 Generate Tab — 参数组合页面

**功能定位:** 负责组合生成请求的所有参数，并提供一键添加到队列。

#### 4.4.1 参数列表

| 参数 | 类型 | 范围/选项 | 默认值 | 持久化 | 说明 |
|------|------|-----------|--------|--------|------|
| Batch Count | int | 1 - 60 | 1 | 全局 | 批量生成数量（有seed时固定为1） |
| Prompt | string | — | 模型默认 | 全局 | 正向提示词，CLIP 77 token 限制 |
| Negative Prompt | string | — | 模型默认 | 全局 | 负面提示词 |
| Width | int | 64 - 4096 | 512 | 全局 | 图片宽度 |
| Height | int | 64 - 4096 | 512 | 全局 | 图片高度 |
| Steps | int | 1 - 50 (Slider) | 20 | 按模型 | 采样步数 |
| CFG Scale | float | 1.0 - 30.0 (Slider) | 7.0 | 按模型 | 引导强度 |
| Scheduler | string | dpm/dpm_sde/euler_a/euler/lcm | dpm | 按模型 | 采样调度器 |
| Karras | bool | on/off | off | 按模型 | Karras 噪声调度（LCM不可用） |
| Seed | long | 任意数字 (留空=随机) | 空 | 按模型 | 随机种子 |
| OpenCL | bool | CPU/GPU | CPU | 按模型 | 仅 CPU 模型可见 |
| Denoise Strength | float | — | 0.6 | 按模型 | 去噪强度 (CPU模型) |

#### 4.4.2 Token 计数功能

- 输入提示词后 400ms 防抖发送到 `POST /tokenize` 
- 实时显示 `当前token数/最大token数(77)`
- 超出 CLIP 限制的字符以 38% 透明度灰显
- 超出限制时显示 ⚠️ 警告图标

#### 4.4.3 TopAppBar — "开始生成" 按钮

**"Add to Queue" 按钮从页面下方移至 TopAppBar 右上角 actions 区域**，作为 Generate Tab 的专属 TopAppBar 操作。

##### 4.4.3.1 GenerateTopBar 布局

```
┌─ GenerateTopBar ────────────────────────────────────────┐
│  [☰ Menu]  {Model Name / "未加载模型"}     [⏸ Add to Queue] │
└──────────────────────────────────────────────────────────┘
```

| 区域 | 内容 | 说明 |
|------|------|------|
| **navigationIcon** | ☰ Menu 图标 | 打开导航抽屉 |
| **title** | 已加载模型名 或 ⚠️ "未加载模型" (红色) | 与现有一致 |
| **actions** | **PlayArrow 图标按钮** | "开始生成" (添加到队列) |

##### 4.4.3.2 按钮行为

| 场景 | 行为 |
|------|------|
| **未加载模型** | 按钮灰显 (disabled) 或 弹出 "未加载模型" AlertDialog |
| **已加载模型** | 按钮可点击，点击触发添加队列 + 播放飞行动画 |

##### 4.4.3.3 实现接口

```kotlin
// GenerateTopBar 新签名
@Composable
private fun GenerateTopBar(
    drawerState: DrawerState,
    modelId: String?,
    isModelLoaded: Boolean,
    onAddToQueue: () -> Unit,          // 新增: 触发添加队列
    hasPendingAdd: Boolean,            // 新增: 是否有待处理动画
)
```

#### 4.4.4 飞行动画 — 从 TopBar 到 Queue Tab

**核心设计:** "开始生成"按钮被点击后，从 TopAppBar 的 PlayArrow 位置发射一个动画元素飞向底部 NavigationBar 的 Queue Tab 图标。**动画本身即为"任务已添加到队列"的视觉确认，不再需要 Snackbar 提示。**

##### 4.4.4.1 动画流程

```
点击 TopBar [PlayArrow]
       │
       ▼
 1. queueRepository.addBatch(...)
       │
       ▼
 2. 触发飞行动画
       │
  ┌──────────────────────────────────────────┐
  │  起点: TopAppBar actions 区域 (右上)       │
  │    ↓                                      │
  │  动画元素 (小圆点/图标) 沿弧线飞行           │
  │    ↓                                      │
  │  终点: BottomBar Queue Tab 图标            │
  │    ↓                                      │
  │  到达后: Queue Tab 图标弹跳/脉冲 (+ 徽章)   │
  └──────────────────────────────────────────┘
       │
       ▼
 3. 动画结束，清除动画状态
```

##### 4.4.4.2 动画实现规范

| 属性 | 值 | 说明 |
|------|-----|------|
| **动画元素** | 半透明 PlayArrow 图标 / 彩色圆点 | 尺寸 24dp，`primary` 颜色 |
| **动画时长** | 400-600ms | 自然不急促 |
| **运动路径** | 贝塞尔曲线 (弧线) | 从右上飞向左下，带轻微弧度 |
| **缓动** | `FastOutSlowInEasing` | 开始快、结束慢，自然着陆感 |
| **透明度** | 起点 1.0 → 终点 0.7 (渐隐) | 到达目标时半透明消散 |
| **缩放** | 1.0 → 0.6 | 飞行过程中缩小 |

##### 4.4.4.3 技术实现方案

使用 Compose `AnimatedVisibility` + `graphicsLayer` 或 `Animatable` 实现：

```kotlin
// 方案: 使用 AnimatedVisibility + graphicsLayer offset
var animateToQueue by remember { mutableStateOf(false) }

// 点击按钮时:
// 1. onAddToQueue(count)
// 2. animateToQueue = true  (触发动画)

// Overlay 层绘制飞行动画
if (animateToQueue) {
    val topBarOffset = ...   // 获取 TopBar actions 图标位置
    val queueTabOffset = ... // 获取 BottomBar Queue Tab 位置
    
    LaunchedEffect(Unit) {
        // 从 topBarOffset 动画移动到 queueTabOffset
        // 使用 Animatable<Offset> + 贝塞尔曲线路径
        // 动画结束后: animateToQueue = false
    }
    
    // 飞行中的动画元素
    Icon(
        Icons.Default.PlayArrow,
        modifier = Modifier
            .offset { IntOffset(animOffset.x.roundToInt(), animOffset.y.roundToInt()) }
            .scale(animScale)
            .alpha(animAlpha),
    )
}
```

> **坐标获取:** TopBar 按钮位置通过 `onGloballyPositioned` 获取；BottomBar Queue Tab 位置同理。使用 `LayoutCoordinates.positionInRoot()` 获取相对于窗口的坐标。

##### 4.4.4.4 动画结束反馈

| 反馈 | 说明 |
|------|------|
| Queue Tab 图标弹跳 | 到达时 Queue 图标放大 1.2× 再缩回，持续 200ms |
| Queue Tab 徽章更新 | 显示最新队列总数 (Badge) |
| **无 Snackbar** | 动画本身已经是充分的视觉确认，移除 "Added N to queue" Snackbar |

#### 4.4.5 页面内操作按钮

- **Reset (🔄):** 重置所有参数为默认值，位于参数列表底部。保持现有实现不变。

#### 4.4.6 参数持久化

- 全局参数 (Prompt, Negative Prompt, Batch Count, Width, Height): 通过 GenerationPreferences 全局持久化
- 模型参数 (Steps, CFG, Seed, Scheduler, Denoise, OpenCL): 按 modelId 持久化
- 切换模型时自动加载该模型的保存参数；全局参数保留（不覆盖）

> **关键设计变更:**
> - "Add to Queue" 按钮从 `GenerateScreen` 底部 → `GenerateTopBar` 右上角 actions
> - 新增飞行动画：TopBar → BottomBar Queue Tab，替代 Snackbar 提示
> - 移除 `Snackbar "Added N to queue"` 和 `queueAddMessage` AnimatedVisibility Card

#### 4.4.7 Generate 子 Tab 结构

**Generate Screen 内部分为两个子 Tab**，通过 `TabRow` 切换，位于 TopAppBar 下方：

```yml
┌─ GenerateTopBar ────────────────────────────────────────┐
│  [☰ Menu]  {Model Name}                    [⏸ Add to Queue] │
├─ Generate TabRow ──────────────────────────────────────┤
│  [ Parameters ]  [ Records ]                            │
├─ Generate Content ─────────────────────────────────────┤
│                                                          │
│  Tab 1 (Parameters): 参数编辑表单                         │
│  Tab 2 (Records):    记录管理器                           │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

| Tab | 名称 | 功能 | 图标 |
|-----|------|------|------|
| **Tab 1** | **Parameters (参数编辑)** | 现有参数编辑界面，Batch Count/Prompt/Negative/Width/Height/Steps/CFG/Scheduler/Seed 等 | `Tune` |
| **Tab 2** | **Records (记录管理)** | 管理从 Queue/Gallery 保存的提示词+参数记录，可加载到编辑器或删除 | `Bookmarks` / `FolderOpen` |

##### 4.4.7.1 Tab 行为

- **TabRow 位置:** TopAppBar 正下方，`indicatorColor = primary`
- **默认 Tab:** Parameters（打开 Generate Tab 时默认显示参数编辑）
- **切换方式:** 点击 Tab 或左右滑动 (配合 `HorizontalPager`)
- **状态保持:** 切换 Tab 不丢失参数编辑状态，Record 列表保持独立滚动位置

#### 4.4.8 Record Manager — 记录管理器

**功能定位:** 集中管理所有从 Queue 和 Gallery 保存的提示词与生成参数记录。保存源与记录生命周期解耦，删除源 (任务/图片) 不影响记录。

##### 4.4.8.1 数据模型 (GenerateParameterRecord)

```kotlin
enum class RecordSource { QUEUE, GALLERY }

data class GenerateParameterRecord(
    val id: String,              // UUID
    val prompt: String,
    val negativePrompt: String,
    val modelId: String,
    val steps: Int,
    val cfg: Float,
    val seed: Long?,
    val width: Int,
    val height: Int,
    val scheduler: String,
    val timestamp: Long,
    val source: RecordSource,    // QUEUE 或 GALLERY
)
```

##### 4.4.8.2 持久化方案

| 属性 | 值 |
|------|-----|
| **存储引擎** | JSON 文件 (`generate_records.json`) |
| **存储位置** | `context.filesDir` / `generate_records.json` |
| **序列化** | `org.json.JSONArray` / `JSONObject` (Android 内置) |
| **Repository** | `RecordRepository(context)` — 单例或通过 Application 注入 |

##### 4.4.8.3 保存来源

```
Queue Tab (右滑保存) ──→ RecordRepository.addRecord(...) ──→ generate_records.json
Gallery Save Info     ──→ RecordRepository.addRecord(...) ──→ generate_records.json
```

| 来源 | 触发方式 | 保存字段 | 记录 `source` 值 |
|------|---------|---------|-----------------|
| **Queue** | 右滑任务/批次卡片 | GenerationTask → prompt, negativePrompt, modelId, steps, cfg, seed, width, height, scheduler, timestamp | `QUEUE` |
| **Gallery** | ImageViewer Save Info / 选择模式 Save | HistoryItem.params → 同上 | `GALLERY` |

> **关键设计:** 记录与源数据独立。Queue 任务删除后记录保留；Gallery 图片删除后记录保留。记录仅在 Record Manager 中手动删除。

##### 4.4.8.4 Record Manager UI

```
┌─ Record Manager ─────────────────────────────────────────┐
│                                                           │
│  ┌──────────────────────────────────────────────────┐    │
│  │ [QUEUE] "a cat sitting..."                        │    │
│  │ SDXL · 20 steps · CFG 7.0 · 512×512       [🗑] [▶] │    │
│  └──────────────────────────────────────────────────┘    │
│  ┌──────────────────────────────────────────────────┐    │
│  │ [GALLERY] "sunset over mountains..."              │    │
│  │ SD1.5 · 30 steps · CFG 9.0 · 768×512      [🗑] [▶] │    │
│  └──────────────────────────────────────────────────┘    │
│                                                           │
│  [Empty State: "No saved records" + icon]                 │
└──────────────────────────────────────────────────────────┘
```

| 元素 | 说明 |
|------|------|
| **来源标签** | `QUEUE` (蓝色 Chip) 或 `GALLERY` (绿色 Chip) |
| **Prompt 预览** | 首行, `maxLines = 1`, `TextOverflow.Ellipsis` |
| **参数摘要** | 第二行: `{modelId} · {steps} steps · CFG {cfg} · {width}×{height}` |
| **🗑 删除按钮** | 红色 Delete 图标, 弹出确认对话框后删除 |
| **▶ 加载按钮** | PlayArrow 图标, 将记录参数加载到 Parameters Tab 编辑器 |
| **列表组件** | `LazyColumn`，按 `timestamp` 降序排列 |

##### 4.4.8.5 一键加载到参数编辑器

点击记录的 ▶ (Load) 按钮 → 切换到 Parameters Tab → 填充所有参数：

```kotlin
fun loadRecordToEditor(record: GenerateParameterRecord) {
    onPromptChange(record.prompt)
    onNegativePromptChange(record.negativePrompt)
    onModelChange(record.modelId)        // 切换到记录的模型
    onStepsChange(record.steps.toFloat())
    onCfgChange(record.cfg)
    onSeedChange(record.seed?.toString() ?: "")
    onWidthChange(record.width)
    onHeightChange(record.height)
    onSchedulerChange(record.scheduler)
    // 切换到 Parameters Tab
    selectedGenerateTab = 0
}
```

##### 4.4.8.6 空状态

- `Bookmarks` 图标 (64dp, 半透明)
- "No saved records"
- "Save prompts from Queue or Gallery to see them here"

> **存储位置统一:** Queue 右滑保存和 Gallery Save Info 均通过 `RecordRepository` 保存到 Generate Record Manager，不再各自写入 `Documents/DreamHub/`。`Documents/DreamHub/` 仅用于 Gallery Download (图片像素) 操作。

---

### 4.5 Upscale Tab — 超分辨率放大页面

**功能定位:** 对已有图片进行 AI 超分辨率放大处理。

#### 4.5.1 功能列表

- **图片选择:** 点击卡片区域 → 系统图片选择器 (image/*)
- **输入验证:** 图库版 (filter flavor) 限制最大 2048×2048
- **图片预览:** 原图区域支持双指缩放/平移 (1×-5×)
- **图片信息:** 左下角显示分辨率 "W × H"
- **清除图片:** 右上角 ✕ 按钮
- **放大执行:** FAB 按钮 (AutoFixHigh 图标)，依赖 Upscale 模型已加载
  - 未加载时 FAB 灰显
  - 执行时显示 BlockingProgressOverlay (圆形进度 + 瓦片进度 "N/M")
- **结果预览:** 放大后结果区域同样支持缩放/平移
- **保存结果:** 右上角 Save 按钮 → saveImage() 保存到相册
- **错误处理:** 浮动错误卡片，点击关闭

#### 4.5.2 Upscale 后端

- **启动:** UpscaleBackendManager.start(context, upscalerId)
  - 同用端口 8081，会先停止 Diffusion 后端
  - 启动 `libstable_diffusion_core.so --upscaler_mode`
  - 自动准备 QNN 运行时库
  - 8 秒超时自动标记 Running
- **停止:** UpscaleBackendManager.stop()
- **状态:** Idle / Starting(upscalerId) / Running(upscalerId) / Error(message)
- **处理:** 前端将 Bitmap 转 RGB bytes → POST /upscale → 解析返回 → 重建 Bitmap

#### 4.5.3 Upscale 模型管理 (在 Models Tab)

- 显示区域: "Upscale Models" 分隔标题
- 模型卡片: 名称、描述、加载/卸载按钮
- 选择状态跟踪 (SharedPreferences)
- 支持自定义模型导入 (.bin文件)

---

### 4.6 Browse Tab — 图库/画廊页面

**功能定位:** 浏览、管理和操作所有生成的历史图片。以方形网格布局展示缩略图，支持多选批量操作。

#### 4.6.1 网格布局

##### 4.6.1.1 布局实现

- **容器:** `LazyVerticalGrid`，使用 `GridCells.Fixed(columnCount)`
- **列数:** 通过 SegmentedButton 切换 1 / 2 / 3 / 4 列，默认 **3 列**
- **持久化:** 列数设置存入 `SharedPreferences` (`app_prefs`)，key: `browse_grid_columns`
- **间距:** 网格水平与垂直间距统一为 `8.dp`，容器外边距 `12.dp`

##### 4.6.1.2 列数控件

- **位置:** FilterChip 行下方，图片网格上方
- **组件:** `SingleChoiceSegmentedButtonRow`，包含 4 个 `SegmentedButton` (1 / 2 / 3 / 4)
- **样式:** 与 Material 3 风格一致，`primary` 颜色选中态
- **行为:** 切换后网格立即重新排列，滚动位置重置到顶部

#### 4.6.2 方形画布缩略图 (Uniform Square Canvas)

**强制要求:** 所有图片缩略图必须展示在统一的方形画布中，确保浏览页显示尺寸统一、视觉整齐。

##### 4.6.2.1 单元格结构

```
┌──────────────────────┐
│                      │
│   背景填充 (#XX)      │
│   ┌──────────────┐   │
│   │              │   │
│   │  实际图片     │   │
│   │  ContentScale│   │
│   │  .Fit        │   │
│   │              │   │
│   └──────────────┘   │
│                      │
├──────────────────────┤
│ prompt text (1行)     │
├──────────────────────┤
│ model · W×H          │
└──────────────────────┘
```

##### 4.6.2.2 实现规范

| 属性 | 值 | 说明 |
|------|-----|------|
| 单元格 Modifier | `.fillMaxWidth().aspectRatio(1f)` | 强制正方形 |
| 图片 `contentScale` | `ContentScale.Fit` | 图片完整展示，保持原始宽高比，不裁切 |
| 单元格背景 | `MaterialTheme.colorScheme.surfaceVariant` (深色) | 为未被图片覆盖的区域 (letterbox) 提供均匀背景 |
| 图片修饰 | `.clip(RoundedCornerShape(8.dp))` | 圆角裁剪 |
| 加载方式 | Coil `AsyncImage` + `ImageRequest.Builder(data=filePath).crossfade(true)` | 本地文件异步加载，淡入过渡 |

##### 4.6.2.3 设计要点

- **不做裁切:** `ContentScale.Fit` 确保无论原始图片宽高比如何，完整图片始终可见
- **尺寸统一:** 方形画布 + Fit 策略使所有单元格高度一致，视觉整齐
- **背景掩蔽:** 非 1:1 图片 (如 16:9 横图或 9:16 竖图) 的空白区域由 `surfaceVariant` 颜色填充，避免与页面背景混淆
- **信息行:** 方形缩略图下方显示提示词 (单行省略) 和模型信息，与缩略图同宽

#### 4.6.3 模型筛选

- **位置:** 网格上方、列数控件上方
- **组件:** 横向滚动 `Row` + `FilterChip`
- **选项:** "All" (默认) + 各已知 modelId
- **实现:** `filterModelId` 状态变量，`null` 表示全部

#### 4.6.4 Image Viewer — 全屏图片查看器

点击缩略图进入全屏 Image Viewer，**不再使用 AlertDialog**，改为全屏 Overlay 页面。复用项目已有的 `ZoomableImageOverlay` 组件。

##### 4.6.4.1 实现方案

| 属性 | 实现 | 说明 |
|------|------|------|
| **组件** | `ZoomableImageOverlay` | 复用现有组件 (`ui/components/ZoomableImageOverlay.kt`) |
| **图片加载** | `BitmapFactory.decodeFile(item.imageFile.absolutePath)` | 从本地文件解码原始分辨率 Bitmap |
| **缩放范围** | 0.5× – 5.0× | 双指捏合缩放，质心焦点算法 |
| **拖拽平移** | 放大后支持单指拖拽 | 跟随手势移动 |
| **双击行为** | 双击在 1× ↔ 2.5× 之间切换 | 智能复位到居中 |
| **单击空白区** | 关闭 Image Viewer | tap outside 图片边界时 dismiss |
| **系统返回键** | `BackHandler` 拦截 → 关闭 Viewer | 与现有 ZoomableImageOverlay 一致 |
| **缩放复位** | 右下角 Refresh 按钮 | 重置 scale=1.0, offset=0 |
| **缩放提示** | 底部居中显示 "150%" 等百分比 | `showScaleIndicator = true` |
| **背景** | 黑色 scrim (`0.9f` alpha) | 沉浸式全屏体验 |

##### 4.6.4.2 ImageViewer TopBar

ImageViewer 顶部叠加半透明 TopBar，包含操作按钮：

```
┌─ ImageViewer TopBar ───────────────────────────────────────┐
│  [← 返回]              [Download] [Save Info] [🗑 Delete]  [⋮] │
└────────────────────────────────────────────────────────────┘
```

| 按钮 | 图标 | 功能 | 说明 |
|------|------|------|------|
| **返回** | ← ArrowBack | 关闭 ImageViewer | `onDismiss()` |
| **Download** | Download | 将图片保存到系统相册 | `saveBitmapToGallery()` → `Pictures/DreamHub/` |
| **Save Info** | SaveAlt / Description | **保存生成信息** (提示词+参数+模型) | 导出为文本文件或嵌入图片 EXIF |
| **Delete** | Delete (红色) | 删除该图片及历史记录 | 二次确认对话框 |
| **⋮ 更多** | MoreVert (三点) | 展开溢出菜单 | 见 §4.6.4.3 |

##### 4.6.4.3 溢出菜单 (⋮ 三点菜单)

点击右上角 `MoreVert` 图标弹出 `DropdownMenu`：

| 菜单项 | 功能 |
|--------|------|
| **分享 (Share)** | `Intent.ACTION_SEND` 分享图片文件到其他应用 |
| **设置为壁纸 (Set as Wallpaper)** | 通过 `WallpaperManager` 设置壁纸 |
| **查看详情 (View Details)** | 以 BottomSheet 展示完整生成参数 (Prompt/NegativePrompt/Steps/CFG/Seed/Scheduler/Size/Model/时间) |
| **以其他应用打开 (Open With)** | `Intent.ACTION_VIEW` 调起系统图片查看器 |
| **复制提示词 (Copy Prompt)** | 将 Prompt 复制到系统剪贴板 |

> 溢出菜单项可根据平台能力 (Android API Level) 动态显示/隐藏 (如 "Set as Wallpaper" 需要 API 24+)。

##### 4.6.4.4 Download vs Save Info 语义区分

| 操作 | 保存内容 | 目标 | 格式 |
|------|---------|------|------|
| **Download** | 图片像素数据 (.png) | `Pictures/DreamHub/` 系统相册 | PNG |
| **Save Info** | 提示词 + 参数 + 模型信息 → **Generate Record Manager** | `RecordRepository` → `generate_records.json` (§4.4.8) | `GenerateParameterRecord` (source=GALLERY) |

> **Save Info 实现:** Gallery Save Info **不再写独立文本文件**，改为通过 `RecordRepository.addRecord()` 存入 Generate Screen 的记录管理器，与 Queue 右滑保存统一存储。记录与 Gallery 图片解耦：图片删除后记录保留。

#### 4.6.5 选择模式 (Selection Mode)

##### 4.6.5.1 进入与退出

| 操作 | 触发 | 行为 |
|------|------|------|
| **进入选择模式** | 长按任意单元格 | `isSelectionMode = true`，`BrowseTopBar` 切换为 SelectionMode TopBar，该单元格被勾选 |
| **退出选择模式** | 点击 TopBar 的 ✕ / ← 按钮 | `isSelectionMode = false`，`selectedItems.clear()`，TopBar 恢复默认 |

##### 4.6.5.2 SelectionMode BrowseTopBar 布局

**选择模式下，BrowseTopBar 完全替换为选择操作 TopBar：**

```
┌─ BrowseTopBar (Selection Mode) ──────────────────────────────────────┐
│  [✕]  "N selected"  [Select All] [Invert] [Deselect] [Download] [Save] [🗑] │
└──────────────────────────────────────────────────────────────────────┘
```

| 按钮 | 图标 | 功能 | 说明 |
|------|------|------|------|
| **✕ Close** | Close | 退出选择模式 | `selectedItems.clear()` + 恢复默认 TopBar |
| **N selected** | — (文本) | 显示已选数量 | `pluralStringResource` 复数形式 |
| **Select All** | SelectAll / DoneAll | 全选当前筛选下所有图片 | `selectedItems.addAll(displayItems)` |
| **Invert** | SwapHoriz | 反选 | 已选 ↔ 未选 互换 |
| **Deselect** | Deselect | 全不选 | `selectedItems.clear()` |
| **Download** | Download | 批量下载图片到相册 | 逐个 `saveBitmapToGallery()`，Toast 成功/失败计数 |
| **Save** | SaveAlt | 批量保存生成信息 | 逐个导出 prompt+params 文本文件 |
| **🗑 Delete** | Delete (红色) | 批量删除 | 二次确认 → 逐个删除记录+文件 → 退出选择模式 |

> **小屏适配:** TopBar 按钮区使用 `horizontalScroll` 包裹，确保所有操作按钮可访问。大屏设备按钮区自然铺满。

##### 4.6.5.3 选择操作伪代码

```kotlin
// Select All
selectedItems.clear()
selectedItems.addAll(displayItems)

// Deselect All
selectedItems.clear()

// Invert Selection
val toRemove = selectedItems.filter { it in displayItems }.toSet()
val toAdd = displayItems.filter { it !in selectedItems }
selectedItems.removeAll(toRemove)
selectedItems.addAll(toAdd)

// Batch Download
var success = 0; var fail = 0
selectedItems.forEach { item ->
    val bitmap = BitmapFactory.decodeFile(item.imageFile.absolutePath)
    if (bitmap != null && saveBitmapToGallery(context, bitmap, item.modelId)) success++ else fail++
}
Toast: "$success saved, $fail failed"

// Batch Save Info
selectedItems.forEach { item ->
    saveGenerationInfo(context, item) // export prompt+params to .txt
}

// Batch Delete
showConfirmDialog("Delete N images?")
selectedItems.forEach { historyManager.deleteHistoryItem(it) }
selectedItems.clear(); isSelectionMode = false
```

##### 4.6.5.4 选择模式下的视觉反馈

- 每个单元格叠加半透明遮罩 (`Modifier.overlay`)
- 已选中的单元格: 遮罩 + 蓝色边框 (`2.dp`, `primary` 颜色) + 右上角 Checkbox (Checked)
- 未选中单元格: 半透明暗色遮罩 + 右上角 Checkbox (Unchecked)
- Checkbox 位于缩略图右上角，`padding(4.dp)`

#### 4.6.6 空状态

- 显示 `ImageSearch` 图标 (64dp, 半透明)
- 标题: "No generated images"
- 副标题: "Tap Generate to add tasks" (或 localised 版本)

#### 4.6.7 Summary — 完整页面结构

```
┌─ Browse Tab ──────────────────────────────────────────────────────┐
│                                                                     │
│  Gallery TopBar:  [☰]   [All] [SDXL] [SD1.5] ...                  │
│                                                                     │
│  Selection Mode TopBar:  [✕] "N" [SelAll] [Inv] [Desel] [Dw] [Sv] [🗑] │
│                                                                     │
│  Column Selector:  [1] [2] [3] [4]  (SegmentedButton)              │
│                                                                     │
│  ┌──────┐ ┌──────┐ ┌──────┐                                        │
│  │      │ │      │ │      │                                        │
│  │ img  │ │ img  │ │ img  │  LazyVerticalGrid                      │
│  │      │ │      │ │      │  GridCells.Fixed(N)                    │
│  └──────┘ └──────┘ └──────┘                                        │
│  ┌──────┐ ┌──────┐ ┌──────┐                                        │
│  │      │ │      │ │      │                                        │
│  │ img  │ │ img  │ │ img  │                                        │
│  │      │ │      │ │      │                                        │
│  └──────┘ └──────┘ └──────┘                                        │
│                                                                     │
│  [单击图片 → 全屏 ImageViewer (ZoomableImageOverlay)]               │
│  [长按图片 → 进入选择模式 (SelectionMode)]                           │
└─────────────────────────────────────────────────────────────────────┘

┌─ ImageViewer Overlay ──────────────────────────────────────────────┐
│  TopBar: [←]          [Download] [Save Info] [🗑]  [⋮]             │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                                                              │   │
│  │                    Pinch-to-Zoom (0.5x~5x)                   │   │
│  │                    Pan / Double-tap                          │   │
│  │                                                              │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                        [🔄 Reset]  150%            │
│                                                                     │
│  ⋮ Overflow Menu:  [Share] [Wallpaper] [Details] [Open With]       │
│                    [Copy Prompt]                                    │
└─────────────────────────────────────────────────────────────────────┘
```

> **关键设计变更:**
> - `AlertDialog` 详情弹窗 → 全屏 `ZoomableImageOverlay` 图片查看器 (复用现有组件)
> - Gallery TopBar 在 Selection Mode 下完整替换为操作 TopBar (Select All / Invert / Deselect / Download / Save / Delete)
> - 新增 Image Viewer TopBar (Download / Save Info / Delete / 三点溢出菜单)
> - 明确 Download (图片到相册) 与 Save Info (生成参数到文本文件) 的语义区分

---

## 5. 服务层

### 5.1 BackendService

- 前台服务，管理 C++ 原生进程生命周期
- 通过 `libstable_diffusion_core.so` 启动 HTTP 服务
- 状态: Idle / Starting / Running / Error
- 支持 NPU 模式 (QNN)、CPU 模式 (MNN)、Upscaler 模式
- 环境变量管理: LD_LIBRARY_PATH、DSP_LIBRARY_PATH

### 5.2 BackgroundGenerationService

- 前台服务，处理单个 HTTP 生成请求（**保留兼容路径，主路径已迁移到 GenerationWorker**）
- 最多重试 3 次 (间隔 1.5s)
- SSE 流式解析: progress → Progress 状态 / complete → Complete 状态
- 进度预览解码 (Base64 → RGB → Bitmap)
- Bitmap 消费等待机制 (默认 30s 超时)
- 支持用户主动停止 (ACTION_STOP 广播)

### 5.2.1 GenerationWorker (WorkManager)

- `CoroutineWorker` 子类，Tag: `"generation-queue"`
- `doWork()` 内部循环: `while(isActive) { waitForBackend() → getNextPending() → process() }`
- **`waitForBackend()`**: 后端在线则立即返回 true；离线则 `processingActive=false` + 每 3s 轮询 `/health`，直到上线或 Worker 取消
- **不负责启动/重启后端** — 后端生命周期由 Model Screen (§4.2) 管理
- **后端掉线恢复**: `generate()` 抛异常 → `resetTaskToPending()` → 回到 `waitForBackend()`
- 通过 `setForeground()` 自动管理前台通知（`ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC`）
- 通过 `setProgress(workDataOf(...))` 上报进度 (taskId, prompt, progress%)
- 收到 WorkManager 取消信号 → `isActive=false` → 抛出 `CancellationException` → `doWork()` 返回 `success`（不 retry）
- `doWork()` 异常处理:
  - `CancellationException` → `Result.success()` (用户主动停止)
  - 其他 `Exception` → `hasPendingTasks() ? Result.retry() : Result.failure()`

### 5.2.2 QueueController

- `object` 单例，WorkManager 队列生命周期控制
- `start(context)`: `enqueueUniqueWork("generation-queue", KEEP)` — 幂等，不重复启动
- `stop(context)`: `cancelAllWorkByTag("generation-queue")` + `QueueRepository.cancelAllPending()`
- `observeState(context)`: 返回 `Flow<WorkInfo?>`，通过 `LiveData.asFlow()` 桥接，供 MainActivity 观察 Worker 状态

### 5.2.3 QueueNotificationHelper

- `object` 单例，统一通知工具
- `ensureChannel(context)`: 创建 `"queue_processing_channel"` 通知通道 (IMPORTANCE_LOW)
- `createForegroundInfo(context, title, progress)`: 返回 `ForegroundInfo` 供 GenerationWorker 使用
- `createNotification(context, title, progress, stopPendingIntent?)`: 返回 `Notification`，可选 Stop 按钮

### 5.3 QueueRepository

- 内存中的任务队列状态管理 (`MutableStateFlow<List<GenerationTask>>`)
- **进程级单例:** `QueueRepository.getInstance(context)` (double-checked locking)
- 共享于 UI (MainActivity), WorkManager Worker (GenerationWorker), QueueProcessingService
- 方法: `addBatch`, `removeTask`, `removeBatch`, `getNextPending`, `cancelAllPending`
- 状态管理: `markTaskProcessing/Complete/Error`, `resetTaskToPending`, `updateTaskProgress`, `setProcessingActive`
- `resetTaskToPending(id)`: 将 PROCESSING/ERROR 任务回退为 PENDING（progress=0，清空错误信息），用于后端掉线恢复
- 批量分组: `getBatchGroups()` 返回 `List<BatchGroupDisplay>`

> **线程安全:** 已迁移为 `_tasks.update { ... }` 确保原子操作。

### 5.4 UpscaleBackendManager

- 单例对象，管理 Upscale 后端的完整生命周期
- 进程启动、状态监控、优雅停止
- 自动准备 QNN 运行时库

---

## 6. 数据持久化

### 6.1 HistoryManager (Room Database)

- 存储生成的图片记录
- 字段: modelId, imageFile, params (Steps/CFG/Seed/Prompt/NegativePrompt/Width/Height/GenerationTime), mode
- 支持按 modelId 筛选
- 支持观察 (Flow)

### 6.2 GenerationPreferences (SharedPreferences `app_prefs`)

- 全局参数持久化: prompt, negativePrompt, batchCounts, width, height
- 模型参数持久化 (按 modelId): steps, cfg, seed, scheduler, denoiseStrength, useOpenCL
- HuggingFace Base URL 配置

### 6.3 Upscaler Preferences (SharedPreferences `upscaler_prefs`)

- `upscaler_standalone_selected_upscaler`: 最后选择的 Upscaler 模型 ID

---

## 7. 导航抽屉 (Navigation Drawer)

左侧导航抽屉通过 TopAppBar 左侧菜单按钮（☰）打开，包含完整的应用设置。抽屉标题为 "Settings"，右侧有关闭按钮（✕）。设置项按功能分为 6 个分组，分组标题使用 `primary` 颜色高亮。

### 7.1 侧边栏结构

```
┌─ Settings ────────────────────────────────────── [✕] ─┐
│                                                        │
│  Appearance                                            │
│    • Dynamic Color          ── Switch                  │
│    • Dark Mode              ── [System] [Light] [Dark] │
│    • OLED pure black        ── Switch (非 Light 可见)  │
│    • Color theme            ── [Tangerine] [Forest] …  │
│                                                        │
│  Backend                                               │
│    • Allow LAN access       ── Switch                  │
│    • SDXL low RAM           ── Switch                  │
│    • Capture logs           ── Switch                  │
│                                                        │
│  Generation                                            │
│    • Show process           ── Switch                  │
│    • Preview stride         ── Slider (1-10)           │
│    • Generation timeout     ── Slider (15-600s)        │
│    • Bitmap consumed timeout── Slider (5-120s)         │
│                                                        │
│  Health Check                                          │
│    • Retry interval         ── Slider (5-120s)         │
│    • Max failures           ── Slider (1-20)           │
│                                                        │
│  Downloads                                             │
│    • Download from          ── [HF] [Mirror] [Custom]  │
│    • Custom URL             ── TextField (Custom 可见) │
│                                                        │
│  About                                                 │
│    • Version: YYYY.MM.DD.HH.mm                         │
│    • 须知/免责声明全文                                  │
└────────────────────────────────────────────────────────┘
```

### 7.2 通用组件

侧边栏使用以下可复用 Compose 组件：

| 组件 | 说明 |
|------|------|
| `SectionHeader(title)` | 分组标题，使用 `primary` 颜色 |
| `SwitchSetting(title, hint, checked, onCheckedChange)` | 行布局：标题 + 描述 + Switch |
| `ChipSetting<T>(title, hint, options, selected, onSelect)` | 标题 + 描述（可选）+ FilterChip 行，支持泛型选项值 |
| `SliderSetting(title, hint, value, range, steps, suffix, onValueChangeFinished, onValueChange)` | 标题 + 描述 + 值显示 + Slider |

### 7.3 Appearance 分组

| 配置项 | 组件 | 存储位置 | 说明 | 默认值 |
|--------|------|---------|------|--------|
| Dynamic Color | SwitchSetting | `ThemePreferences` | Material You 动态取色 (Android 12+) | 开 |
| Dark Mode | ChipSetting (3 选项) | `ThemePreferences` | System / Light / Dark 三选一 | System |
| OLED Pure Black | SwitchSetting | `ThemePreferences` | AMOLED 纯黑背景；仅 Dark 或 System 模式下可见 | 关 |
| Color Theme | ChipSetting | `ThemePreferences` | Tangerine / Forest / … 主题预设颜色 | Tangerine |

**OLED Pure Black 实现：** 当 `oledBlack == true` 且当前为暗色主题时，在 `DreamHubTheme` 中覆盖 `colorScheme` 的 surface/background 为 `Color(0xFF000000)`，surface 容器层级依次为 `0xFF0A0A0A` / `0xFF111111` / `0xFF1A1A1A`。

### 7.4 Backend 分组

| 配置项 | 组件 | SP Key | 说明 | 默认值 |
|--------|------|--------|------|--------|
| Allow LAN Access | SwitchSetting | `listen_on_all_addresses` | 后端是否监听所有网络地址 | 否 |
| SDXL Low RAM | SwitchSetting | `sdxl_lowram` | SDXL 低内存模式，减少显存占用 | 是 |
| Capture Logs | SwitchSetting | `enable_log_capture` | 启用后端日志捕获 | 否 |

### 7.5 Generation 分组

| 配置项 | 组件 | SP Key | 范围 | 默认值 |
|--------|------|--------|------|--------|
| Show Process | SwitchSetting | `show_diffusion_process` | on/off | 否 |
| Preview Stride | Slider (缩进显示) | `show_diffusion_stride` | 1-10 | 1 |
| Generation Timeout | SliderSetting | `generation_timeout_s` | 15-600s | 60s |
| Bitmap Consumed Timeout | SliderSetting | `bitmap_consumed_timeout_s` | 5-120s | 30s |

> **Preview Stride** 仅在 Show Process 开启时显示，行左侧缩进 8dp。

### 7.6 Health Check 分组

| 配置项 | 组件 | SP Key | 范围 | 默认值 |
|--------|------|--------|------|--------|
| Retry Interval | SliderSetting | `health_check_retry_interval_s` | 5-120s | 20s |
| Max Failures | SliderSetting | `health_check_max_failures` | 1-20 | 4 |

### 7.7 Downloads 分组

| 配置项 | 组件 | SP Key | 说明 | 默认值 |
|--------|------|--------|------|--------|
| Download From | ChipSetting (3 选项) | `download_source` | HuggingFace / HF-Mirror / Custom | HuggingFace |
| Custom URL | OutlinedTextField | `hf_base_url` | 仅选择 Custom 时显示，带 Save 按钮 | -- |

- 切换 HuggingFace / HF-Mirror 时自动写入固定 URL
- 选择 Custom 时保留现有自定义 URL
- 支持保存自定义 URL

### 7.8 About 分组

- 版本号显示：`Version: {VERSION_NAME}` (读取 `BuildConfig.VERSION_NAME`)
- 须知/免责声明 (`must_read` 字符串)

### 7.9 持久化架构

侧边栏设置通过 2 种方式持久化：

| 存储方式 | 文件/类 | 用途 |
|---------|---------|------|
| `SharedPreferences` | `"app_prefs"` (`appPrefs`) | 后端、生成、健康检查、下载设置（非主题） |
| `ThemeController` → `ThemePreferences` | `"theme_prefs"` | 外观设置（Dynamic Color、Dark Mode、OLED、Preset） |
| `GenerationPreferences` (DataStore) | `preferences.dat` | 下载源选择与自定义 URL

---

## 8. 权限要求

| 权限 | 用途 | 适用条件 |
|------|------|---------|
| POST_NOTIFICATIONS | 后台生成通知 | Android 13+ |
| WRITE_EXTERNAL_STORAGE | 保存图片到相册 | Android < 10 |
| INTERNET | HTTP 通信 (localhost) | 所有 |

---

## 9. 文件结构

```
app/src/main/java/io/github/dreamandroid/local/
├── MainActivity.kt              # 主 Activity，状态管理和 WorkManager 编排
├── DreamAndroidApplication.kt   # Application 类，持有 QueueRepository 单例
├── navigation/
│   └── Navigation.kt            # BottomTab 枚举和路由
├── data/
│   ├── Model.kt                 # Model/UpscalerModel 数据类 + Repository
│   ├── QueueModels.kt           # GenerationTask/TaskStatus/BatchGroupDisplay
│   ├── HistoryManager.kt        # 历史记录 Room DB 管理
│   ├── GenerationPreferences.kt # 生成参数持久化
│   └── db/                      # Room Database Entity/DAO
├── service/
│   ├── BackendService.kt        # C++ 后端进程管理
│   ├── BackgroundGenerationService.kt  # 单次 HTTP 生成服务 (兼容路径)
│   ├── QueueRepository.kt       # 任务队列状态管理 (进程级单例)
│   ├── UpscaleBackendManager.kt # Upscale 后端管理
│   ├── ModelDownloadService.kt  # 模型下载服务
│   └── queue/
│       ├── GenerationWorker.kt        # WorkManager CoroutineWorker (主路径)
│       ├── QueueController.kt         # WorkManager 生命周期控制
│       ├── QueueNotificationHelper.kt # 统一通知工具
│       ├── QueueProcessingService.kt  # 前台服务队列处理 (兼容路径)
│       └── SseStreamParser.kt         # SSE 流解析器
├── ui/
│   ├── screens/
│   │   ├── ModelListScreen.kt   # 模型列表/下载页面
│   │   ├── ModelRunScreen.kt    # 模型详情/操作页面
│   │   ├── GenerateScreen.kt    # 生成参数组合页面
│   │   ├── QueueScreen.kt       # 任务队列页面
│   │   ├── UpscaleScreen.kt     # 超分辨率页面
│   │   └── BrowseScreen.kt      # 图库/画廊页面
│   ├── components/              # 通用 Compose 组件
│   └── theme/                   # 主题配置
├── utils/
│   ├── ImageUtils.kt            # performUpscale/saveImage
│   └── LogCapture.kt            # 日志捕获
└── cpp/
    └── src/main.cpp             # C++ 后端 HTTP Server
```

---

## 10. 目标架构设计

> 本章定义重构后的目标架构，包含分层设计、模块依赖、接口契约和技术规范。
> 当前实现的问题清单与优化方案详见 `ArchitectureReview.md`。

### 10.1 架构全景图

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Android App Layer                            │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    Presentation (UI)                          │   │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────┐ ┌───────┐  │   │
│  │  │ Models   │ │ Generate │ │  Queue   │ │Upscl │ │Browse │  │   │
│  │  │ Screen   │ │ Screen   │ │  Screen  │ │Screen│ │Screen │  │   │
│  │  └────┬─────┘ └────┬─────┘ └────┬─────┘ └──┬───┘ └──┬────┘  │   │
│  │       │             │           │          │        │       │   │
│  │  ┌────┴─────────────┴───────────┴──────────┴────────┴────┐  │   │
│  │  │                    ViewModels                          │  │   │
│  │  │  ModelsVM  GenerateVM  QueueVM  UpscaleVM  BrowseVM    │  │   │
│  │  └────────────────────────┬───────────────────────────────┘  │   │
│  └───────────────────────────┼──────────────────────────────────┘   │
│                              │                                      │
│  ┌───────────────────────────┼──────────────────────────────────┐   │
│  │                    Service Layer (Domain)                     │   │
│  │  ┌────────────────────┐   │   ┌──────────────────────────┐   │   │
│  │  │  BackendManager    │←──┼──→│  QueueProcessingService  │   │   │
│  │  │  ┌──────────────┐  │   │   │  ┌───────────────────┐   │   │   │
│  │  │  │ HttpClient   │  │   │   │  │  SseStreamParser  │   │   │   │
│  │  │  │ HealthCheck  │  │   │   │  │  QueueRepository  │   │   │   │
│  │  │  │ ProcessMgr   │  │   │   │  │  HistoryManager   │   │   │   │
│  │  │  │ RuntimePrep  │  │   │   │  └───────────────────┘   │   │   │
│  │  │  └──────────────┘  │   │   └──────────────────────────┘   │   │
│  │  └────────────────────┘   │                                    │   │
│  └───────────────────────────┼────────────────────────────────────┘   │
│                              │                                      │
│  ┌───────────────────────────┼──────────────────────────────────┐   │
│  │                      Data Layer                              │   │
│  │  ┌──────────────┐ ┌──────┴───────┐ ┌────────────────────┐   │   │
│  │  │ ModelRepo    │ │ HistoryRepo  │ │ PreferencesManager │   │   │
│  │  │ (Room+Files) │ │ (Room)       │ │ (DataStore)        │   │   │
│  │  └──────────────┘ └──────────────┘ └────────────────────┘   │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
                              │ HTTP (OkHttp, single client)
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│              C++ Backend (libstable_diffusion_core.so)               │
│              http://localhost:8081                                   │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐             │
│  │ /health  │ │/generate │ │/upscale  │ │/tokenize │             │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘             │
└─────────────────────────────────────────────────────────────────────┘
```

### 10.2 分层架构原则

| 层 | 职责 | 依赖方向 | 约束 |
|----|------|---------|------|
| **Presentation** | Compose UI + ViewModels | → Service Layer | 不直接调用 HTTP，不直接操作文件系统 |
| **Service** | 业务逻辑编排、进程管理、队列调度 | → Data Layer | 不持有 UI 引用，通过 StateFlow 暴露状态 |
| **Data** | Room DAO、Preferences、文件 I/O | → 无外部依赖 | 不引用 Service 或 UI 层 |

**核心原则：**

1. **单向依赖：** Presentation → Service → Data。下层不知道上层的存在。
2. **StateFlow 通信：** Service 层通过 `StateFlow` 暴露状态给 ViewModel，ViewModel 通过 `collectAsState()` 驱动 UI。
3. **Single Source of Truth：** Room 是模型/历史数据的唯一数据源；文件系统仅为存储位置。
4. **协程安全：** 所有 I/O 操作必须在 `Dispatchers.IO` 中执行；禁止 `runBlocking` 出现在主线程调用路径上。
5. **统一错误模型：** 所有错误通过 `sealed class AppError` 体系传播（见 12.1 节）。

### 10.3 数据流全景图

```
┌──────────┐    StateFlow        ┌──────────┐    suspend/Flow    ┌──────────────┐
│  Compose │←───────────────────│ViewModel │←───────────────────│ Service      │
│  UI      │   collectAsState()  │          │   launch/call      │ (BackendMgr  │
│          │                     │          │                    │  QueueProc)  │
│          │  events             │          │  domain types      │              │
│          │────────────────────→│          │───────────────────→│              │
│          │  button.onClick()   │          │  vm.onAction()     │              │
└──────────┘                     └──────────┘                    └──────┬───────┘
                                                                       │
                                                                   suspend
                                                                       │
                                                                       ▼
                                                                ┌──────────────┐
                                                                │ Data Layer   │
                                                                │ Room / Files │
                                                                └──────────────┘

                    ┌──────────────┐    HTTP (OkHttp)
                    │ C++ Backend  │←─────────────────── BackendManager.httpClient
                    │ :8081        │   POST /generate   (single client, shared pool)
                    └──────────────┘   GET /health
                                       POST /tokenize
                                       POST /upscale
```

### 10.4 模块间接口交互流（队列生成场景 — WorkManager v3.1）

```
User taps "Add to Queue"
  │
  ▼
GenerateScreen ──── action ────→ GenerateViewModel.addToQueue()
                                      │
                                      ▼
                                  QueueRepository.addBatch(tasks)
                                      │
                                      │ StateFlow emit (UI updates)
                                      ▼
                                  QueueScreen ← observe ← QueueTasks StateFlow
                                      │
                                      │ LaunchedEffect detects new tasks
                                      ▼
                                  QueueController.start(context)
                                      │
                                      │ WorkManager.enqueueUniqueWork(KEEP)
                                      ▼
┌─── GenerationWorker (CoroutineWorker) ───────────────────────────────────┐
│                                                                          │
│  while (isActive):                                                       │
│    task = queueRepository.getNextPending()                               │
│                                                                          │
│    waitForBackend() ──────────────────────────────────────┐              │
│      ├─ healthCheck() OK → continue ───┐                 │              │
│      └─ healthCheck() FAIL             │                 │              │
│          → processingActive = false    │                 │              │
│          → 通知 "Waiting for backend"   │                 │              │
│          → while(isActive):            │                 │              │
│              delay(3s)                 │                 │              │
│              healthCheck()             │                 │              │
│              ├─ OK → return true ──────┘                 │              │
│              └─ FAIL → continue polling                  │              │
│                                                          │              │
│    queueRepository.markTaskProcessing(task.id)  ←────────┘              │
│    setForeground(...)                                                   │
│                                                                          │
│    backendManager.generate(task.toParams())                              │
│      .collect { event ->                                                 │
│        Progress → queueRepository.updateProgress()                       │
│                   setProgress(workData)                                   │
│        Complete → HistoryManager.save()                                  │
│                   queueRepository.markComplete()                          │
│        SseEvent.Error → queueRepository.markError()                      │
│      }                                                                   │
│                                                                          │
│    Exception (backend crashed):                                          │
│      → queueRepository.resetTaskToPending()  // 不回退为 ERROR           │
│      → 重新进入 waitForBackend()              // 循环等待后端恢复         │
│                                                                          │
│    CancellationException (user stopped):                                  │
│      → queueRepository.resetTaskToPending()                               │
│      → throw → doWork() → Result.success()                               │
│                                                                          │
│  Queue empty → Result.success() → Worker completes                       │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘

Stop Signal:
  Queue Tab [■ Stop] → QueueController.stop(context)
      → WorkManager.cancelAllWorkByTag("generation-queue")
      → QueueRepository.cancelAllPending()
```

### 10.5 模块间接口交互流（Upscale 场景）

```
User selects image → UpscaleScreen ──── action ────→ UpscaleViewModel.upscale()
                                                           │
                                                           ▼
                                                   backendManager.startUpscaler(id)
                                                   │ (内部自动 stop diffusion → start upscaler)
                                                   │
                                                   ▼
                                                   backendManager.httpClient
                                                   .newCall(POST /upscale, body=RGB bytes)
                                                   │
                                                   ├── OK → Bitmap → UpscaleViewModel.result
                                                   │
                                                   └── Error → AppError.Network/Backend
```

---

## 11. 核心模块接口标准

### 11.1 BackendManager — 后端进程管理器

```kotlin
/**
 * 统一的后端进程管理器。
 * 保证同一时刻只有一个 C++ 进程在端口 8081 上运行。
 */
interface BackendManager {

    enum class Mode { Diffusion, Upscaler }

    sealed class State {
        object Idle : State()
        data class Starting(val mode: Mode, val modelId: String) : State()
        data class Running(val mode: Mode, val modelId: String) : State()
        data class Error(val message: String) : State()
    }

    /** 当前状态流（必须从 Dispatchers.Main 收集） */
    val state: StateFlow<State>

    /** 统一 HTTP 客户端（共享连接池、超时配置） */
    val httpClient: OkHttpClient

    // ── 生命周期管理 ──

    /** 启动 Diffusion 模式后端。内部自动处理旧进程停止。 */
    suspend fun startDiffusion(
        modelId: String,
        width: Int,
        height: Int,
        useOpenCL: Boolean
    ): Result<Unit>

    /** 启动 Upscaler 模式后端。内部自动处理旧进程停止。 */
    suspend fun startUpscaler(upscalerId: String): Result<Unit>

    /**
     * 优雅停止当前进程：
     * 1. SIGTERM → waitFor(5s)
     * 2. 超时 → destroyForcibly() → waitFor()
     * 3. 进程退出 → process = null → state = Idle
     */
    suspend fun stop()

    // ── 健康检查 ──

    /** GET /health，复用 httpClient */
    suspend fun healthCheck(): Boolean

    /** 带重试的健康检查 */
    suspend fun healthCheckWithRetry(
        maxRetries: Int = 4,
        intervalSeconds: Long = 20
    ): Boolean

    // ── 业务端点 ──

    /** POST /generate → SSE streaming */
    fun generate(params: GenerateParams): Flow<SseEvent>

    /** POST /tokenize */
    suspend fun tokenize(prompt: String): TokenizeResult

    /** POST /upscale */
    suspend fun upscale(
        rgbBytes: ByteArray,
        width: Int,
        height: Int,
        upscalerPath: String
    ): ByteArray
}
```

**接口约束：**
- `state` 必须在 IO 线程更新，Main 线程收集
- `startXxx()` 返回值 `Result<Unit>` 统一错误处理，不抛异常
- `stop()` 必须等待进程真正退出后才返回（防止僵尸进程）
- `httpClient` 全局唯一，在 `DreamAndroidApplication.onCreate()` 中初始化

### 11.2 QueueProcessingService — 队列处理服务

```kotlin
/**
 * 前台服务，顺序处理生成队列。
 * 生命周期：当队列有 PENDING 任务时保持运行，队列空后 stopSelf()。
 */
class QueueProcessingService : Service() {

    // 通过 Application 获取依赖
    private val backendManager: BackendManager
        get() = (application as DreamAndroidApplication).backendManager
    private val queueRepository: QueueRepository
        get() = (application as DreamAndroidApplication).queueRepository
    private val historyManager: HistoryManager
        get() = (application as DreamAndroidApplication).historyManager

    /** 处理中标志，用于外部判断是否繁忙 */
    val isProcessing: StateFlow<Boolean>

    /** 当前处理进度 0f..1f */
    val currentProgress: StateFlow<Float>

    // ── 内部处理循环 ──

    private suspend fun processLoop() {
        while (true) {
            val task = queueRepository.getNextPending() ?: break
            queueRepository.markProcessing(task.id)

            // 1. 健康检查
            if (!backendManager.healthCheckWithRetry()) {
                queueRepository.markError(task.id, AppError.Backend("Health check failed"))
                continue
            }

            // 2. 发起生成请求
            try {
                backendManager.generate(task.toParams()).collect { event ->
                    when (event) {
                        is SseEvent.Progress -> {
                            queueRepository.updateProgress(task.id, event.step.toFloat() / event.total)
                            currentProgress.value = event.step.toFloat() / event.total
                        }
                        is SseEvent.Complete -> {
                            val bitmap = decode(event.imageBase64)
                            historyManager.save(task.toRecord(bitmap))
                            queueRepository.markComplete(task.id, bitmap)
                            bitmap.recycle()
                        }
                        is SseEvent.Error -> {
                            queueRepository.markError(task.id, AppError.Backend(event.message))
                        }
                    }
                }
            } catch (e: Exception) {
                queueRepository.markError(task.id, AppError.from(e))
            }
        }
        stopSelf()
    }
}
```

### 11.3 SseStreamParser — SSE 流解析器

```kotlin
/**
 * 独立可测试的 SSE 流解析器。
 * 从 InputStream 逐行读取，解析为结构化事件流。
 */
class SseStreamParser(
    private val inputStream: InputStream
) {
    sealed class SseEvent {
        data class Progress(
            val step: Int,
            val totalSteps: Int,
            val imageBase64: String
        ) : SseEvent()

        data class Complete(
            val imageBase64: String,
            val seed: Long,
            val width: Int,
            val height: Int
        ) : SseEvent()

        data class Error(val message: String) : SseEvent()
    }

    /**
     * 返回冷 Flow，收集时开始解析，取消时关闭流。
     * 线程安全：必须在 IO Dispatcher 上收集。
     */
    fun events(): Flow<SseEvent> = flow {
        val reader = BufferedReader(InputStreamReader(inputStream))
        reader.use {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (l.startsWith("data: ")) {
                    val json = l.removePrefix("data: ")
                    if (json == "[DONE]") break
                    emit(parseEvent(json))
                }
            }
        }
    }

    private fun parseEvent(json: String): SseEvent {
        val obj = JSONObject(json)
        return when (obj.getString("type")) {
            "progress" -> SseEvent.Progress(
                step = obj.getInt("step"),
                totalSteps = obj.getInt("total_steps"),
                imageBase64 = obj.getString("image")
            )
            "complete" -> SseEvent.Complete(
                imageBase64 = obj.getString("image"),
                seed = obj.optLong("seed"),
                width = obj.getInt("width"),
                height = obj.getInt("height")
            )
            "error" -> SseEvent.Error(obj.getString("message"))
            else -> SseEvent.Error("Unknown event type: ${obj.getString("type")}")
        }
    }
}
```

### 11.4 数据层接口

```kotlin
// ── QueueRepository ──
interface QueueRepository {
    val tasks: StateFlow<List<GenerationTask>>
    val batchGroups: StateFlow<List<BatchGroupDisplay>>

    suspend fun addBatch(tasks: List<GenerationTask>)
    suspend fun getNextPending(): GenerationTask?
    suspend fun markProcessing(taskId: String)
    suspend fun markComplete(taskId: String, bitmap: Bitmap)
    suspend fun markError(taskId: String, error: AppError)
    suspend fun updateProgress(taskId: String, progress: Float)
    suspend fun removeTask(taskId: String)
    suspend fun cancelAllPending()
}

// ── HistoryManager ──
interface HistoryManager {
    fun getHistory(modelId: String? = null): Flow<List<HistoryRecord>>
    suspend fun save(record: HistoryRecord)
    suspend fun delete(recordId: Long)
    suspend fun deleteMultiple(ids: List<Long>)
    suspend fun clearForModel(modelId: String)
}

// ── ModelRepository ──
interface ModelRepository {
    fun observeModels(): Flow<List<ModelEntity>>      // 单一数据源：Room
    suspend fun deleteModel(modelId: String)           // 事务内删 Room + 文件
    suspend fun importModel(source: Uri): ModelEntity
    suspend fun renameModel(modelId: String, newName: String)
}

// ── PreferencesManager ──
interface PreferencesManager {
    // 全局生成参数
    val prompt: Flow<String>
    val negativePrompt: Flow<String>
    val batchCount: Flow<Int>
    // ... 所有 key 集中定义
    suspend fun setPrompt(value: String)
}
```

---

## 12. 技术规范

### 12.1 统一错误模型

```kotlin
/** 应用层统一错误类型 */
sealed class AppError(
    override val message: String,
    open val cause: Throwable? = null
) : Exception(message) {

    /** 网络层错误（连接超时、DNS、HTTP error） */
    data class Network(
        override val message: String,
        val code: Int? = null,
        override val cause: Throwable? = null
    ) : AppError(message, cause)

    /** 后端业务错误（400/500 + message from JSON） */
    data class Backend(
        override val message: String
    ) : AppError(message)

    /** 数据解析错误（JSON、SSE、Bitmap decode） */
    data class Parse(
        override val message: String,
        override val cause: Throwable? = null
    ) : AppError(message, cause)

    /** 存储错误（Room、文件 I/O） */
    data class Storage(
        override val message: String,
        override val cause: Throwable? = null
    ) : AppError(message, cause)

    companion object {
        fun from(e: Throwable): AppError = when (e) {
            is AppError -> e
            is IOException -> Network(e.message ?: "IO Error", cause = e)
            is JSONException -> Parse(e.message ?: "Parse Error", cause = e)
            else -> Backend(e.message ?: "Unknown Error")
        }
    }
}
```

**错误传播约定：**

| 来源 | 转换规则 | 终端处理 |
|------|---------|---------|
| OkHttp IOException | → `AppError.Network` | ViewModel 转为 UI 状态 (Snackbar / 错误卡片) |
| HTTP 4xx/5xx + JSON message | → `AppError.Backend` | 同上 |
| SSE parse 失败 (JSONException) | → `AppError.Parse` | 同上 |
| Room / File I/O 异常 | → `AppError.Storage` | 同上 |
| Health check 失败 | 静默重试 (≤ maxRetries)，超限后 → `AppError.Backend` | 通知用户后端不可用 |

### 12.2 OkHttpClient 配置标准

```kotlin
/** 全局唯一 HTTP 客户端配置 */
fun createHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)       // 建连 3s
    .readTimeout(3600, TimeUnit.SECONDS)        // 读取 60min（生成耗时）
    .writeTimeout(30, TimeUnit.SECONDS)         // 写入 30s
    .callTimeout(0, TimeUnit.SECONDS)           // 不限总时间
    .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
    .retryOnConnectionFailure(true)
    .addInterceptor(HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) Level.BODY else Level.NONE
    })
    .build()
```

**端点超时覆盖（特殊端点需要更短超时）：**

| 端点 | readTimeout 覆盖 |
|------|-----------------|
| `GET /health` | 3s (短连接，纯状态返回) |
| `POST /tokenize` | 5s |
| `POST /generate` | 3600s (默认) |
| `POST /upscale` | 300s |

### 12.3 协程管理规范

```kotlin
// ✅ 正确：Service 层使用明确的 Job + 生命周期取消
class QueueProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override fun onDestroy() {
        scope.cancel()  // 必须取消
        super.onDestroy()
    }
}

// ✅ 正确：ViewModel 使用 viewModelScope
class GenerateViewModel : ViewModel() {
    fun load() {
        viewModelScope.launch(Dispatchers.IO) { /* ... */ }
    }
}

// ❌ 禁止：无 Job 的 fire-and-forget scope
// private val scope = CoroutineScope(Dispatchers.IO)  // 无法取消！

// ❌ 禁止：主线程调用 runBlocking
// fun onClick() { runBlocking { dao.delete() } }  // ANR!

// ✅ 正确：主线程 → launch 协程
// fun onClick() { viewModelScope.launch { dao.delete() } }
```

### 12.4 Bitmap 生命周期规范

```
生成/超分 Bitmap 生命周期：
  创建 (decode base64/bytes)
    ↓
  使用 (UI 渲染)
    ↓
  消费后 → bitmap.recycle() + 置 null

规则：
1. 每个 Bitmap 只有一个生产者，一个消费者
2. QueueProcessingService 产生的 Bitmap：UI 渲染后由 ViewModel 负责 recycle()
3. Upscale 产生的 Bitmap：渲染后由 UpscaleViewModel 负责 recycle()
4. 所有中间 Bitmap（SSE progress preview）立即 recycle()
5. LruCache 最大缓存 3 个全分辨率 Bitmap (3 × 64MB = 192MB)
```

### 12.5 原生进程生命周期规范

```
进程启动流程：
  1. prepareRuntimeDir()                         [一次性，Application.onCreate]
  2. ProcessBuilder.start()                      [IO 线程]
  3. 等待 GET /health 返回 200                   [轮询，最多 30s]
  4. state = Running(mode, modelId)              [发出 StateFlow]

进程停止流程（优雅关闭）：
  1. process.destroy()                           [SIGTERM]
  2. process.waitFor(5, TimeUnit.SECONDS)        [等待 5s]
  3. if 超时: process.destroyForcibly()          [SIGKILL]
  4. process.waitFor()                           [确认退出]
  5. process = null                              [清空引用]
  6. state = Idle                                [发出 StateFlow]

规则：
- 启动/停止全程在 IO 线程执行
- 所有 StateFlow 更新必须是原子操作
- 进程退出后才允许下一次启动
```

### 12.6 Preferences → DataStore 迁移规范

```
当前: 3 个 SharedPreferences 文件
  - app_prefs         (GenerationPreferences)
  - upscaler_prefs    (UpscaleScreen)
  - default           (HistoryManager etc.)

目标: 1 个 Preferences DataStore
  - 统一 key 命名空间
  - 类型安全访问
  - Flow-based 观察

迁移策略：
  1. Phase 1: 新建 PreferencesManager，平行写入旧 SP + 新 DataStore
  2. Phase 2: 读全部切换为 DataStore
  3. Phase 3: 去除旧 SP 写入
  4. Phase 4: 清理旧 SP 文件
```

---

## 13. 目标文件结构

```
app/src/main/java/io/github/dreamandroid/local/
├── DreamAndroidApplication.kt          # DI 容器，持有全部 Service 引用
│
├── core/                               # 核心接口与类型
│   ├── error/
│   │   └── AppError.kt                 # 统一错误密封类
│   └── model/
│       ├── GenerateParams.kt           # 生成参数 DTO
│       ├── QueueModels.kt              # GenerationTask/BatchGroupDisplay
│       └── Constants.kt                # 全局常量 (端口、路径等)
│
├── service/                            # 服务层（业务逻辑）
│   ├── backend/
│   │   ├── BackendManager.kt           # 统一后端管理器（接口 + 实现）
│   │   └── RuntimeDirPreparer.kt       # QNN 运行时准备（单份代码）
│   ├── queue/
│   │   ├── QueueProcessingService.kt   # 队列处理前台服务
│   │   ├── QueueRepository.kt          # 队列状态管理（Room 持久化）
│   │   └── SseStreamParser.kt          # SSE 流解析器（可单测）
│   ├── http/
│   │   └── HttpClientProvider.kt       # OkHttpClient 单例工厂
│   └── download/
│       └── ModelDownloadService.kt     # 模型下载服务
│
├── data/                               # 数据层
│   ├── db/
│   │   ├── AppDatabase.kt             # Room Database
│   │   ├── HistoryDao.kt              # 历史记录 DAO
│   │   ├── QueueDao.kt                # 队列持久化 DAO
│   │   └── ModelDao.kt                # 模型元数据 DAO
│   ├── repository/
│   │   ├── HistoryRepository.kt       # 历史记录仓库
│   │   ├── ModelRepository.kt         # 模型仓库 (SSOT: Room)
│   │   └── PreferencesManager.kt      # DataStore 统一管理
│   └── entity/
│       ├── HistoryEntity.kt
│       ├── QueueEntity.kt
│       └── ModelEntity.kt
│
├── ui/                                 # 表现层
│   ├── MainActivity.kt                 # 入口 Activity（轻量，仅导航）
│   ├── navigation/
│   │   └── Navigation.kt              # 路由定义
│   ├── viewmodel/
│   │   ├── MainViewModel.kt
│   │   ├── ModelsViewModel.kt
│   │   ├── GenerateViewModel.kt
│   │   ├── QueueViewModel.kt
│   │   ├── UpscaleViewModel.kt
│   │   └── BrowseViewModel.kt
│   ├── screens/
│   │   ├── models/
│   │   ├── generate/
│   │   ├── queue/
│   │   ├── upscale/
│   │   └── browse/
│   ├── components/                     # 通用 Compose 组件
│   └── theme/                          # Material 3 主题
│
└── utils/
    ├── ImageUtils.kt                   # Bitmap 处理工具
    └── LogCapture.kt                   # 日志捕获
```

---

## 14. 静态审查发现 — 修复状态

> 2026-06-15 对 WorkManager 迁移代码的静态审查。已修复问题标记为 ✅。

| ID | 严重度 | 文件 | 问题 | 修复方案 | 状态 |
|----|--------|------|------|---------|------|
| **SR-1** | 严重 | `GenerationWorker.kt` | `CancellationException` 未导入 | 添加 `import kotlinx.coroutines.CancellationException` | ✅ 已修复 |
| **SR-2** | 严重 | `QueueRepository.kt` | `_tasks.value = _tasks.value + newTasks` 非原子 read-then-write | 迁移为 `_tasks.update { it + newTasks }` | ✅ 已修复 |
| **SR-3** | 中等 | `QueueController.kt` | doc 注释说 "Emits null" 但 `mapNotNull` 过滤了 null | 修正 doc | ✅ 已修复 |
| **SR-4** | 中等 | `GenerationWorker.kt` + `QueueProcessingService.kt` | 两个类包含重复的 `processLoop()` 逻辑 | 统一为 GenerationWorker，QueueProcessingService 保留兼容 | ✅ 已处理 (v3.1) |
| **SR-5** | 中等 | `QueueRepository.kt` | `markTaskComplete` 将 Bitmap 存入内存队列，无后续释放机制 | 增加 Bitmap 消费超时保护 | 📋 待处理 |
| **SR-6** | 中等 | `app/proguard-rules.pro` | `isMinifyEnabled = true` 时 WorkManager 反射可能被混淆 | 添加 `-keep class androidx.work.** { *; }` | 📋 待处理 |
| **SR-7** | 低 | `AndroidManifest.xml` | 移除 `requestLegacyExternalStorage` | 无需修复 | ✅ 确认 |
| **SR-8** | 严重 | `GenerationWorker.kt` | health check 失败时标记任务 ERROR → 后端离线导致全部任务失败 | 新增 `waitForBackend()` 暂停-轮询-恢复机制 | ✅ 已修复 (v3.1) |
| **SR-9** | 严重 | `GenerationWorker.kt` | 后端崩溃时 `catch(Exception)` → `markTaskError()` → 不应为永久性错误 | 改为 `resetTaskToPending()` + 重返 `waitForBackend()` | ✅ 已修复 (v3.1) |
| **SR-10** | 中等 | `GenerationWorker.kt` | `doWork()` catch 不区分 `CancellationException` → 用户取消后可能 retry | 单独 catch `CancellationException` → `Result.success()` | ✅ 已修复 (v3.1) |

> 已修复项总结: SR-1~SR-3 为代码质量修复；SR-4/SR-8/SR-9/SR-10 为 v3.1 架构修正，确保 Queue 不负责后端生命周期管理。

---

## 16. 架构修复方案设计 (ArchitectureReview §9 对应)

> 本章为 ArchitectureReview.md §9 中发现的 15 项数据流完整性问题设计修复方案。
> 方案按依赖关系分组，修复顺序必须按 A→B→C→D→E 执行。

---

### 16.A 数据完整性修复 (对应 §9.1 + §9.5)

#### 16.A.1 HistoryManager.save 返回值被忽略 → 任务标记与持久化解耦断裂 (§9.1.1, P0)

**根因分析：**
`GenerationWorker` 和 `QueueProcessingService` 均调用 `historyManager.saveGeneratedImage(...)` 后丢弃返回值（`HistoryItem?`），直接执行 `markTaskComplete()`。若磁盘满或 Room 写入失败，`saveGeneratedImage()` 返回 null，但任务仍被标记为 COMPLETED，图片数据永久丢失。

**解决方案：事务性保存-标记模式**

```kotlin
// ✅ 修复: GenerationWorker/QueueProcessingService 中替换当前逻辑

// Before (❌ 返回值被忽略):
historyManager.saveGeneratedImage(modelId, bitmap, task, mode)
queueRepository.markTaskComplete(task.id, bitmap, event.seed)

// After (✅ 事务性检查):
val historyItem = historyManager.saveGeneratedImage(modelId, bitmap, task, mode)
if (historyItem != null) {
    queueRepository.markTaskComplete(task.id, bitmap, event.seed)
} else {
    // 保存失败 → 标记为 ERROR 并保留 bitmap 引用供重试
    queueRepository.markTaskError(task.id, AppError.Storage("Failed to save generated image"))
    // recycle bitmap 避免内存泄漏（保存失败后不可恢复）
    bitmap.recycle()
}
```

**实现清单：**
| 步骤 | 文件 | 修改内容 |
|------|------|---------|
| 1 | `GenerationWorker.kt` line ~169 | 检查 `saveGeneratedImage()` 返回值，null 时标记 ERROR + recycle |
| 2 | `QueueProcessingService.kt` line ~188 | 同上 |
| 3 | `HistoryManager.kt` | 确保 `saveGeneratedImage()` 返回 null 时内部已完成文件清理（不留孤儿 PNG） |

**成功标准：**
- 磁盘满场景：任务标记为 ERROR，不标记 COMPLETED
- Room 写入失败场景：任务标记为 ERROR，磁盘无孤儿 PNG
- 正常场景：行为不变（任务标记 COMPLETED）

---

#### 16.A.2 文件-DB 写入不一致 (§9.1.2, P2)

**根因分析：**
`HistoryManager.saveGeneratedImage()` 先写 PNG/JPEG 文件到磁盘，再插入 Room DB。若文件成功但 Room 插入失败，磁盘留有孤儿 PNG 文件，不清理。

**解决方案：文件后写模式 + 失败回滚**

```kotlin
// ✅ 修复: HistoryManager.saveGeneratedImage() 内部调整写入顺序

suspend fun saveGeneratedImage(...): HistoryItem? {
    return withContext(Dispatchers.IO) {
        // Step 1: 先写入 Room (作为 Single Source of Truth)
        val historyItem = insertHistoryRecord(...)  // ← 先 DB
        if (historyItem == null) return@withContext null
        
        // Step 2: 再写入文件系统 (基于 Room 返回的 id）
        try {
            writeImageFile(historyItem.id, bitmap, format)
        } catch (e: IOException) {
            // 文件写入失败 → 回滚 Room 记录
            deleteHistoryRecord(historyItem.id)
            return@withContext null
        }
        
        historyItem
    }
}
```

**实现清单：**
| 步骤 | 文件 | 修改内容 |
|------|------|---------|
| 1 | `HistoryManager.kt` | 调整写入顺序：Room 先于文件；文件失败时回滚 Room 记录 |
| 2 | `HistoryManager.kt` | 新增 `deleteHistoryRecord(id)` 私有方法用于回滚 |

**成功标准：**
- Room 成功 + 文件成功：正常写入
- Room 成功 + 文件失败：回滚 Room 记录，无孤儿文件
- Room 失败：直接返回 null，无文件残留

---

#### 16.A.3 RecordRepository JSON 损坏 → 全部记录丢弃 (§9.5.1, P2)

**根因分析：**
`RecordRepository.loadFromDisk()` 在 `catch` 中执行 `_records.value = emptyList()`，下一次 `persist()` 会用空列表覆盖损坏的文件，导致所有记录永久丢失。

**解决方案：损坏文件备份 + 降级恢复**

```kotlin
// ✅ 修复: RecordRepository.loadFromDisk()

private fun loadFromDisk() {
    try {
        val content = recordsFile.readText()
        if (content.isBlank()) {
            _records.value = emptyList()
            return
        }
        val parsed = parseRecords(content)
        _records.value = parsed
    } catch (e: Exception) {
        // 1. 备份损坏文件（不加 .bak 后缀防止覆盖已有的 .bak）
        val backupFile = File(recordsFile.parent, "generate_records.json.corrupted.${System.currentTimeMillis()}")
        recordsFile.copyTo(backupFile, overwrite = false)
        
        // 2. 尝试逐条恢复（正则提取每个 JSONObject）
        val recovered = attemptPartialRecovery(recordsFile.readText())
        
        // 3. 使用恢复的记录（可能为空，但不覆盖原文件）
        _records.value = recovered
        
        // 4. 仅当成功恢复部分记录时 persist
        if (recovered.isNotEmpty()) {
            persist()
        }
        // 5. 通知用户
        Log.e(TAG, "Records file corrupted, backed up to ${backupFile.name}, recovered ${recovered.size} records")
    }
}
```

**实现清单：**
| 步骤 | 文件 | 修改内容 |
|------|------|---------|
| 1 | `RecordRepository.kt` | 实现损坏备份逻辑（`.corrupted.{timestamp}` 后缀） |
| 2 | `RecordRepository.kt` | 实现 `attemptPartialRecovery()` 逐条 JSONObject 解析 |
| 3 | `RecordRepository.kt` | 添加 `TAG` 常量和日志 |

**成功标准：**
- JSON 完整损坏 → 备份原文件，记录为空，下次 persist 不写空覆盖
- JSON 部分损坏 → 逐条恢复有效记录，保留到新文件
- JSON 正常 → 行为不变

---

#### 16.A.4 RecordRepository 并发写入不安全 (§9.5.2, P3)

**根因分析：**
`persist()` 在 `Dispatchers.IO` 中执行，`writeText()` 非原子操作。两个协程同时调用 `persist()` 可能导致交错写入产生损坏的 JSON。

**解决方案：Mutex 保护 + 原子写入**

```kotlin
// ✅ 修复: RecordRepository

private val writeMutex = Mutex()

private suspend fun persist() {
    writeMutex.withLock {
        withContext(Dispatchers.IO) {
            // 原子写入: 先写临时文件 → 重命名 (POSIX 原子操作)
            val tempFile = File(recordsFile.parent, "generate_records.json.tmp")
            tempFile.writeText(serializeRecords(_records.value))
            tempFile.renameTo(recordsFile)  // atomic on same filesystem
        }
    }
}
```

**实现清单：**
| 步骤 | 文件 | 修改内容 |
|------|------|---------|
| 1 | `RecordRepository.kt` | 添加 `writeMutex` (kotlinx.coroutines.sync.Mutex) |
| 2 | `RecordRepository.kt` | 将 `persist()` 中 `writeText()` 改为临时文件 + rename 模式 |

**成功标准：**
- 并发调用 `addRecord()` + `deleteRecord()`：文件内容完整，无交错损坏
- 写入过程崩溃：临时文件残留，下次读取仍使用旧文件（因为 rename 未发生）

---

### 16.B 协程与并发修复 (对应 §9.2 + §9.6)

#### 16.B.1 SseStreamParser: flow{} 内 withContext 反模式 (§9.2.1, P1)

**根因分析：**
`SseStreamParser.events()` 在 `flow {}` 构建器内使用 `withContext(Dispatchers.IO)` 包裹整个解析逻辑。这违反了 Kotlin Flow 的 "context preservation" 原则。虽然在此用例中不会直接崩溃（因为 Flow 在 `Dispatchers.IO` 上收集），但当收集端切换上下文时可能抛出 `IllegalStateException: Flow invariant is violated`。

**解决方案：使用 channelFlow 替代 flow{} + withContext**

```kotlin
// ✅ 修复: SseStreamParser.events()

fun events(): Flow<SseEvent> = channelFlow {
    // channelFlow 设计上支持在内部切换上下文
    val reader = BufferedReader(InputStreamReader(inputStream))
    var currentLine: String?
    
    try {
        while (isActive && reader.readLine().also { currentLine = it } != null) {
            val line = currentLine ?: continue
            if (line.startsWith("data: ")) {
                val json = line.removePrefix("data: ")
                if (json == "[DONE]") break
                
                val event: SseEvent = withContext(Dispatchers.Default) {
                    parseEvent(json)  // JSON 解析在 Default 线程
                }
                send(event)
            }
        }
    } finally {
        reader.close()  // 确保资源释放
    }
}.flowOn(Dispatchers.IO)  // 上游在 IO 线程执行
```

**选择 `channelFlow` 的理由：**
- `channelFlow` 内部允许任意上下文切换，不会违反 Flow context preservation
- 支持 `isActive` 检查实现协作式取消（解决 §16.B.2）
- 资源清理在 `finally` 块中保证执行
- `flowOn(Dispatchers.IO)` 确保上游（读取+解析）在 IO 线程执行

**实现清单：**
| 步骤 | 文件 | 修改内容 |
|------|------|---------|
| 1 | `SseStreamParser.kt` | 将 `flow{}` + `withContext` 改为 `channelFlow{}` + `flowOn(Dispatchers.IO)` |
| 2 | `SseStreamParser.kt` | 添加 `isActive` 取消检查 + `finally` 资源关闭 |
| 3 | `GenerationWorker.kt` | 验证 `collect` 在取消后立即停止（无需额外修改） |

---

#### 16.B.2 BufferedReader.readLine() 阻塞不响应取消 (§9.2.2, P1)

**根因分析：**
`BufferedReader.readLine()` 是阻塞 I/O 调用。当用户点击 Stop 按钮、Worker 收到 `CancellationException` 时，当前正在进行的 `readLine()` 不会立即返回，必须等待后端发送下一行数据或连接超时。这导致取消延迟可达数十秒。

**解决方案（在 16.B.1 中一并解决）：**

```kotlin
// channelFlow 内部:
while (isActive && reader.readLine().also { currentLine = it } != null) {
    // isActive 检查只在每次 readLine() 返回时生效
    // 对长时间无数据的 SSE 连接，仍需配合超时机制
}

// 补充: 为读取设置超时
val response = backendManager.httpClient
    .newBuilder()
    .readTimeout(5, TimeUnit.SECONDS)  // SSE 行间超时 5s
    .build()
    .newCall(request).execute()
```

**实际上 `readLine()` 阻塞是 OkHttp 响应体读取的特性。** 当 WorkManager 取消 `GenerationWorker` 时，协程作用域被取消，`channelFlow` 的 `isActive` 变为 false，循环退出。`readLine()` 在 IO 线程阻塞，协程取消不会中断它，但 `channelFlow` 的 channel 被关闭后 `send()` 会失败。

**更可靠的方案：利用 OkHttp `Call.cancel()`**

```kotlin
// ✅ 修复: GenerationWorker 中为 generate 调用添加取消联动

val call = backendManager.generateCall(params)  // 暴露 OkHttp Call
val job = coroutineContext[Job]!!

job.invokeOnCompletion { cause ->
    if (cause is CancellationException) {
        call.cancel()  // 取消底层 HTTP 请求 → readLine() 抛出 IOException
    }
}

SseStreamParser(call.execute().body!!.byteStream())
    .events()
    .collect { event -> ... }
```

**实现清单：**
| 步骤 | 文件 | 修改内容 |
|------|------|---------|
| 1 | `BackendManager.kt` | 新增 `generateCall(params): Call` 方法暴露底层 OkHttp Call |
| 2 | `GenerationWorker.kt` | 添加 `invokeOnCompletion { call.cancel() }` 取消联动 |
| 3 | `QueueProcessingService.kt` | 同上（如保留） |

**成功标准：**
- 用户点击 Stop → 最迟 3s 内 SSE 读取终止（OkHttp 连接关闭 + IOException）
- 取消不抛出 `CancellationException` 之外的异常到 Worker 顶层
- 取消后任务正确回退为 PENDING

---

#### 16.B.3 processingActive 双重数据源 (§9.6.1, P1)

**根因分析：**
`processingActive` 由两个独立代码路径设置：
- `GenerationWorker`: 在开始处理时设为 true，空闲/等待时设为 false
- `MainActivity LaunchedEffect`: 观察 `WorkInfo.State`，当非 RUNNING 时设为 false

这导致竞态：Worker 刚设为 true 开始处理，MainActivity 可能因 WorkInfo 延迟更新而覆盖为 false。

**解决方案：单一数据源 — QueueRepository 为唯一权威**

```kotlin
// ✅ 修复: 移除 MainActivity 中对 processingActive 的写入

// Before (❌ MainActivity):
LaunchedEffect(workInfo) {
    workInfo?.let {
        if (it.state != WorkInfo.State.RUNNING) {
            queueRepository.setProcessingActive(false)  // ← 删除此行
        }
    }
}

// After (✅ 仅 GenerationWorker 写入):
// processingActive 只在 GenerationWorker 中设置:
//   - setProcessingActive(true)  → 在 processLoop() 开始处理任务时
//   - setProcessingActive(false) → 在 (1) 队列为空 (2) waitForBackend 等待时 (3) doWork 返回前

// MainActivity 改为只读:
val workInfoState by QueueController.observeState(context)
    .map { it?.state }
    .collectAsState(initial = null)

// UI 层: processingActive 用于控制 Stop 按钮
// WorkInfo.State 用于日志/调试，不参与业务逻辑
```

**实现清单：**
| 步骤 | 文件 | 修改内容 |
|------|------|---------|
| 1 | `MainActivity.kt` | 删除 `LaunchedEffect` 中 `setProcessingActive(false)` 调用 |
| 2 | `GenerationWorker.kt` | 确保所有退出路径均调用 `setProcessingActive(false)` |
| 3 | `QueueRepository.kt` | 添加 doc 注释明确 `processingActive` 的唯一写入者是 GenerationWorker |

**成功标准：**
- `processingActive` 仅由 `GenerationWorker` 写入
- Stop 按钮显示/隐藏逻辑正确
- 无 WorkInfo 延迟导致的 UI 闪烁

---

#### 16.B.4 QueueController.stop() 竞态 (§9.6.2, P2)

**根因分析：**
```kotlin
fun stop(context: Context) {
    WorkManager.getInstance(context).cancelAllWorkByTag("generation-queue")
    QueueRepository.getInstance(context).cancelAllPending()
    QueueRepository.getInstance(context).setProcessingActive(false)
}
```

`cancelAllWorkByTag` 是异步的。在 `cancelAllPending()` 执行时，Worker 可能尚未收到取消信号，仍在处理某个 PENDING 任务。`cancelAllPending()` 将该任务标记为 CANCELLED，但 Worker 可能同时将其标记为 PROCESSING → 产生状态不一致。

**解决方案：先取消 Worker（等待确认）→ 再清理队列**

```kotlin
// ✅ 修复: QueueController.stop()

suspend fun stop(context: Context) {
    val workManager = WorkManager.getInstance(context)
    val repo = QueueRepository.getInstance(context)
    
    // Step 1: 先取消 Worker
    workManager.cancelAllWorkByTag(GENERATION_QUEUE_TAG)
    
    // Step 2: 等待 Worker 确认终止（观察 WorkInfo → CANCELLED）
    withTimeout(5000L) {
        workManager.getWorkInfosForUniqueWorkLiveData(GENERATION_QUEUE_TAG)
            .asFlow()
            .first { infos ->
                infos.all { it.state == WorkInfo.State.CANCELLED }
            }
    }
    
    // Step 3: 此时 Worker 已停止，安全清理队列
    repo.cancelAllPending()
    repo.setProcessingActive(false)
}
```

**异常处理：** 若 5s 内 Worker 未确认取消，仍执行清理操作（带日志告警）。

**实现清单：**
| 步骤 | 文件 | 修改内容 |
|------|------|---------|
| 1 | `QueueController.kt` | 将 `stop()` 改为 `suspend`，添加等待 Worker 终止逻辑 |
| 2 | `QueueController.kt` | 将 `cancelAllWorkByTag` + `cancelAllPending` 顺序改为先取消后等待再清理 |
| 3 | `QueueScreen.kt` / `QueueTopBar` | 确保 Stop 按钮在协程中调用 `stop()` |

**成功标准：**
- 停止后无 PROCESSING 状态遗留
- Worker 取消和队列清理之间无竞态
- 5s 超时场景至少记录日志

---

#### 16.B.5 进程被杀 → PENDING 全丢 (§9.6.3 / §2.2.5, P0)

**根因分析：**
`QueueRepository` 为纯内存 `MutableStateFlow`，无任何持久化。进程被杀后所有 PENDING/PROCESSING 任务永久丢失。

**解决方案：Room 持久化队列**

```kotlin
// ✅ 修复: QueueRepository 添加 Room 持久化

@Entity(tableName = "queue_tasks")
data class QueueTaskEntity(
    @PrimaryKey val id: String,
    val batchGroupId: String?,
    val batchIndex: Int,
    val modelId: String,
    val prompt: String,
    val negativePrompt: String,
    val steps: Int,
    val cfg: Float,
    val seed: Long?,
    val width: Int,
    val height: Int,
    val denoiseStrength: Float,
    val useOpenCL: Boolean,
    val scheduler: String,
    val aspectRatio: String,
    val timestamp: Long,
    val status: String,       // PENDING / PROCESSING / COMPLETED / ERROR / CANCELLED
    val progress: Float,
    val errorMessage: String?,
    val resultSeed: Long?
)

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_tasks WHERE status = 'PENDING' ORDER BY timestamp ASC")
    suspend fun getPendingTasks(): List<QueueTaskEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<QueueTaskEntity>)
    
    @Query("UPDATE queue_tasks SET status = :status, progress = :progress WHERE id = :taskId")
    suspend fun updateStatus(taskId: String, status: String, progress: Float = 0f)
    
    @Query("UPDATE queue_tasks SET status = 'CANCELLED' WHERE status = 'PENDING'")
    suspend fun cancelAllPending()
    
    @Query("DELETE FROM queue_tasks WHERE status IN ('COMPLETED', 'CANCELLED', 'ERROR')")
    suspend fun deleteFinished()
    
    @Query("SELECT * FROM queue_tasks ORDER BY timestamp ASC")
    fun observeAll(): Flow<List<QueueTaskEntity>>
}
```

**QueueRepository 改造：**

```kotlin
class QueueRepository private constructor(
    private val context: Context,
    private val db: AppDatabase
) {
    private val _tasks = MutableStateFlow<List<GenerationTask>>(emptyList())
    val tasks: StateFlow<List<GenerationTask>> = _tasks.asStateFlow()
    private val _processingActive = MutableStateFlow(false)
    val processingActive: StateFlow<Boolean> = _processingActive.asStateFlow()
    
    init {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            // 启动时恢复 PROCESSING 任务为 PENDING（进程被杀导致）
            db.queueDao().resetProcessingToPending()
            // 加载所有任务到内存
            db.queueDao().observeAll().collect { entities ->
                _tasks.value = entities.map { it.toDomainModel() }
            }
        }
    }
    
    // ... CRUD 操作同时写入 Room + 更新 StateFlow
}
```

**实现清单：**
| 步骤 | 文件 | 修改内容 |
|------|------|---------|
| 1 | `data/db/AppDatabase.kt` | 添加 `QueueDao`、`QueueTaskEntity` |
| 2 | `data/db/QueueDao.kt` | 新建，定义队列持久化操作 |
| 3 | `data/db/QueueTaskEntity.kt` | 新建，Room Entity |
| 4 | `service/QueueRepository.kt` | 改造为 Room 持久化 + StateFlow 桥接 |
| 5 | `service/QueueRepository.kt` | 新增 `resetProcessingToPending()` 启动恢复逻辑 |
| 6 | `app/build.gradle.kts` | Room schema 升级 (增加 `queue_tasks` 表) |

**成功标准：**
- 进程被杀后重启：PENDING 任务恢复，PROCESSING 任务回退为 PENDING
- 队列读写性能无明显退化（SQLite 查询 < 10ms）
- COMPLETED/CANCELLED/ERROR 任务定期清理（每次 `deleteFinished()`）

---

### 16.C 双队列路径统一修复 (对应 §9.3)

#### 16.C.1 Health Check 失败策略不一致 (§9.3.1, P1)

**问题：**
- `GenerationWorker`: health check 失败 → `waitForBackend()` 暂停轮询（温和策略）✅
- `QueueProcessingService`: health check 失败 → `markTaskError()` 永久失败（激进策略）❌

**解决方案：统一为 GenerationWorker 的 waitForBackend 策略**

```kotlin
// ✅ 修复: QueueProcessingService 废弃独立的 health check 逻辑

// QueueProcessingService.processLoop() 中:
// Before (❌):
if (!backendManager.healthCheck()) {
    queueRepository.markTaskError(task.id, AppError.Backend("Health check failed"))
    continue
}

// After (✅): 复用 waitForBackend 模式
if (!waitForBackend()) {
    // 被取消 → 退出循环
    break
}
// 后端已上线 → 继续处理
```

**或者更激进的方案：彻底移除 QueueProcessingService**

鉴于 `GenerationWorker` 已是主路径且实现了完整功能，`QueueProcessingService` 可标记为 `@Deprecated` 并计划移除。短期方案：将 QueueProcessingService 的 health check 和异常处理策略与 GenerationWorker 对齐。

**实现清单：**
| 步骤 | 文件 | 修改内容 |
|------|------|---------|
| 1 | `QueueProcessingService.kt` | health check 失败改为等待-轮询-恢复（非永久失败） |
| 2 | `QueueProcessingService.kt` | 添加 `@Deprecated` 注解，注释说明迁移路径 |

**成功标准：**
- 两条路径 health check 失败行为一致（暂停等待 vs 轮询）
- 两条路径异常恢复行为一致（重置为 PENDING vs 标记 ERROR）

---

#### 16.C.2 生成异常策略不一致 (§9.3.2, P1)

**问题：**
- `GenerationWorker`: 生成异常 → `resetTaskToPending()` 重试 ✅
- `QueueProcessingService`: 生成异常 → `markTaskError(AppError.from(e))` 永久失败 ❌

**解决方案：统一为 GenerationWorker 的重试策略**

```kotlin
// ✅ 修复: QueueProcessingService 异常处理对齐

// QueueProcessingService 的 catch 块:
// Before (❌):
} catch (e: Exception) {
    queueRepository.markTaskError(task.id, AppError.from(e))
}

// After (✅):
} catch (e: CancellationException) {
    queueRepository.resetTaskToPending(task.id)
    throw e  // 传播取消
} catch (e: Exception) {
    // 后端崩溃/网络错误 → 退避为 PENDING，等待后端恢复
    queueRepository.resetTaskToPending(task.id)
    // 重新进入等待循环
    if (!waitForBackend()) break
    // 不 continue — 循环自动回到顶部重新获取 PENDING 任务
}
```

**实现清单：**
| 步骤 | 文件 | 修改内容 |
|------|------|---------|
| 1 | `QueueProcessingService.kt` | 异常处理对齐 GenerationWorker 策略 |
| 2 | `ArchitectureReview.md` | 标记 9.3.1 + 9.3.2 修复完成 |

---

### 16.D 内存与资源修复 (对应 §9.4)

#### 16.D.1 resultBitmap 内存累积 (§9.4.1, P2)

**根因分析：**
`markTaskComplete()` 将 `Bitmap` 存入 `GenerationTask.resultBitmap`。2048×2048 ARGB_8888 ≈ 16MB/张。累积多个 COMPLETED 任务时可导致 OOM。

**解决方案：文件路径引用替代内存 Bitmap**

```kotlin
// ✅ 修复: 不存储 Bitmap，存储文件路径

// GenerationTask 修改:
data class GenerationTask(
    // ... 其他字段
    val resultBitmapPath: String? = null,  // ← 替代 resultBitmap: Bitmap?
    // ...
)

// markTaskComplete 修改:
suspend fun markTaskComplete(taskId: String, resultSeed: Long) {
    _tasks.update { tasks ->
        tasks.map { task ->
            if (task.id == taskId) {
                task.copy(
                    status = TaskStatus.COMPLETED,
                    resultSeed = resultSeed,
                    progress = 1f,
                    // resultBitmapPath 已在 saveGeneratedImage 时设置
                )
            } else task
        }
    }
}

// 调用方 (GenerationWorker):
// saveGeneratedImage 返回 historyItem 后，其 imageFilePath 即为结果路径
val historyItem = historyManager.saveGeneratedImage(...)
if (historyItem != null) {
    queueRepository.setTaskResultPath(task.id, historyItem.imageFilePath)
    queueRepository.markTaskComplete(task.id, event.seed)
}

// UI 层 (QueueScreen):
// 加载缩略图时从文件路径读取并解码为小尺寸缩略图
AsyncImage(
    model = ImageRequest.Builder(context)
        .data(File(task.resultBitmapPath))
        .size(256, 256)  // 缩略图尺寸
        .build(),
    ...
)
```

**实现清单：**
| 步骤 | 文件 | 修改内容 |
|------|------|---------|
| 1 | `data/QueueModels.kt` | `GenerationTask.resultBitmap` 改为 `resultBitmapPath: String?` |
| 2 | `service/QueueRepository.kt` | `markTaskComplete` 移除 Bitmap 参数 |
| 3 | `service/queue/GenerationWorker.kt` | 调用方传递文件路径而非 Bitmap |
| 4 | `ui/screens/QueueScreen.kt` | 使用文件路径加载缩略图 |

**成功标准：**
- 10+ 个 COMPLETED 任务不触发 OOM
- 缩略图正常显示
- 内存中无全分辨率 Bitmap 驻留

---

### 16.E UX 与参数修复 (对应 §9.7)

#### 16.E.1 生成参数双重加载 (§9.7.1, P2)

**根因分析：**
`MainActivity.AppContent()` 和 `GenerateScreen` 均在各自的 `LaunchedEffect(modelId)` 中调用 `GenerationPreferences.loadForModel(modelId)` 加载参数。这导致两次 I/O 操作和潜在的状态覆盖竞态。

**解决方案：单一数据源 — ViewModel 加载，Screen 只读**

```kotlin
// ✅ 修复: MainActivity 中移除参数加载，集中在 GenerateViewModel

// MainActivity: 删除参数加载 LaunchedEffect
// Before (❌):
LaunchedEffect(selectedModelId) {
    selectedModelId?.let { id ->
        val prefs = GenerationPreferences.getInstance(context)
        // ... 加载 genSteps, genCfg, genSeed... 等
    }
}

// After (✅): 删除 MainActivity 中的参数加载
// 参数加载完全由 GenerateViewModel 负责

// GenerateViewModel:
class GenerateViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = GenerationPreferences.getInstance(application)
    
    // 单一加载点
    fun loadModelParams(modelId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val params = prefs.loadForModel(modelId)
            // Emit 到 StateFlow
            _steps.value = params.steps
            _cfg.value = params.cfg
            // ...
        }
    }
}
```

**实现清单：**
| 步骤 | 文件 | 修改内容 |
|------|------|---------|
| 1 | `MainActivity.kt` | 删除 `LaunchedEffect(modelId)` 中的 `GenerationPreferences.loadForModel()` 调用 |
| 2 | `ui/screens/GenerateScreen.kt` | 确保参数加载仅在 GenerateViewModel 中发生 |
| 3 | `ui/screens/GenerateScreen.kt` | 全局参数（prompt、batchCount 等）从 ViewModel 读取 |

**成功标准：**
- 参数加载仅发生一次（在 ViewModel 中）
- 切换模型时参数正确更新
- 无竞态导致参数回退

---

#### 16.E.2 未加载模型时 Add to Queue 静默失败 (§9.7.2, P3)

**根因分析：**
`GenerateTopBar` 的 `onAddToQueue` 在 `selectedModelId == null` 时直接返回，无任何用户反馈。

**解决方案：Toast 或 Dialog 反馈**

```kotlin
// ✅ 修复: GenerateTopBar 或调用方添加用户反馈

fun onAddToQueueClicked() {
    if (selectedModelId == null) {
        // Feedback 选项 A: Toast
        Toast.makeText(context, R.string.no_model_loaded, Toast.LENGTH_SHORT).show()
        
        // Feedback 选项 B: AlertDialog (更明显)
        // showNoModelDialog = true
        
        return
    }
    // 正常添加逻辑...
}

// strings.xml
// <string name="no_model_loaded">Please load a model first</string>
```

**实现清单：**
| 步骤 | 文件 | 修改内容 |
|------|------|---------|
| 1 | `ui/screens/GenerateScreen.kt` 或 `GenerateTopBar` | 添加 Toast 反馈 |
| 2 | `res/values/strings.xml` + 多语言 | 添加 `no_model_loaded` 字符串资源 |

---

#### 16.E.3 无效 Seed 格式静默忽略 (§9.7.3, P3)

**根因分析：**
`seed.toLongOrNull()` 为 null 时静默使用随机种子。用户输入了无效格式（如字母、特殊字符）但毫不知情。

**解决方案：输入验证 + 视觉反馈**

```kotlin
// ✅ 修复: Seed 输入字段添加验证

// GenerateScreen 中:
var seedText by remember { mutableStateOf("") }
var isSeedValid by remember { mutableStateOf(true) }

OutlinedTextField(
    value = seedText,
    onValueChange = { newValue ->
        // 允许空值（随机种子）和纯数字
        seedText = newValue
        isSeedValid = newValue.isEmpty() || newValue.toLongOrNull() != null
    },
    label = { Text("Seed") },
    isError = !isSeedValid,
    supportingText = {
        if (!isSeedValid) {
            Text(
                "Invalid seed — must be empty or a number",
                color = MaterialTheme.colorScheme.error
            )
        }
    },
    // ...
)

// 添加队列时:
val seed = seedText.toLongOrNull()  // null = 随机种子
```

**实现清单：**
| 步骤 | 文件 | 修改内容 |
|------|------|---------|
| 1 | `ui/screens/GenerateScreen.kt` | 添加 `isSeedValid` 状态 + `isError` / `supportingText` |
| 2 | `res/values/strings.xml` | 添加错误提示文案 |

---

### 16.F 修复执行顺序

各方案之间存在依赖关系，必须按以下顺序执行：

```
Phase 1: 基础设施 (B.5)
  └─ 16.B.5 队列 Room 持久化 ← 最底层依赖，必须先完成

Phase 2: 数据完整性 (A)
  ├─ 16.A.1 HistoryManager.save 返回值检查
  ├─ 16.A.2 文件-DB 写入顺序
  ├─ 16.A.3 RecordRepository 损坏恢复
  └─ 16.A.4 RecordRepository 并发安全

Phase 3: 协程与并发 (B)
  ├─ 16.B.1 SseStreamParser channelFlow 迁移
  ├─ 16.B.2 阻塞读取取消联动
  ├─ 16.B.3 processingActive 单源化
  ├─ 16.B.4 QueueController.stop() 竞态修复

Phase 4: 双路径统一 (C)
  ├─ 16.C.1 Health Check 策略统一
  └─ 16.C.2 异常策略统一

Phase 5: 内存与UX (D + E，可并行)
  ├─ 16.D.1 resultBitmap → 文件路径
  ├─ 16.E.1 参数双重加载清理
  ├─ 16.E.2 静默失败反馈
  └─ 16.E.3 Seed 输入验证
```

---

## 17. 后端生命周期管理规范

> 本节明确 BackendManager、Queue Worker Runner、C++ 后端服务器之间的启停管理规则和职责边界。

### 17.1 BackendManager — 统一启停规则

`BackendManager` 是 **唯一合法的 C++ 后端进程管理者**。所有启动/停止/重启 C++ 进程的操作必须通过 `BackendManager` 的公开 API：

```kotlin
class BackendManager(private val context: Context) {
    // ── 启动：内部保证 only-one-process，自动停止旧进程 ──
    suspend fun startDiffusion(modelId, width, height, useOpenCL): Result<Unit>
    suspend fun startUpscaler(upscalerId): Result<Unit>

    // ── 停止：SIGTERM → waitFor(5s) → destroyForcibly() → waitFor() ──
    suspend fun stop()

    // ── 状态观察（单一 StateFlow） ──
    val state: StateFlow<State>  // Idle | Starting(mode,id) | Running(mode,id) | Error(msg)
}
```

**核心规则：**

| 规则 | 说明 |
|------|------|
| **唯一进程管理者** | 仅 `BackendManager` 有权调用 `ProcessBuilder.start()` 和 `Process.destroy()` |
| **端口互斥保证** | `startDiffusion()` / `startUpscaler()` 内部自动 `stopProcess()` 清除旧进程，保证端口 8081 唯一性 |
| **优雅关闭** | 3 步流程：`destroy()` (SIGTERM) → `waitFor(5s)` → 超时 `destroyForcibly()` → `waitFor()` |
| **单一状态源** | 所有 UI 和 Queue 通过 `backendManager.state` 观察后端状态 |
| **禁止直接Intent** | 禁止通过 `startForegroundService(Intent(BackendService))` 或 `stopService()` 操作后端进程 |

### 17.2 Queue Worker Runner — 启停角色

`GenerationWorker` (WorkManager CoroutineWorker) 和 `QueueProcessingService` (Foreground Service) 是**后端 HTTP API 的纯消费者**，**绝不**启动、停止或重启 C++ 后端进程。

```kotlin
// GenerationWorker / QueueProcessingService 中的 waitForBackend():
private suspend fun waitForBackend(): Boolean {
    if (backendManager.healthCheck()) return true          // 快速路径
    // 后端不可用 → 暂停轮询，不试图重启
    while (isActive) {
        delay(3000L)
        if (backendManager.healthCheck()) return true      // 后端恢复
    }
    return false  // Worker/Service 被取消
}
```

**规则：**

| 规则 | 说明 |
|------|------|
| **Queue 不启动后端** | 若 health check 失败 → `waitForBackend()` 暂停轮询，等待外部恢复 |
| **Queue 不停止后端** | Queue 停止时仅取消自身任务，不调用 `backendManager.stop()` |
| **Queue 不重启后端** | 后端崩溃 → `resetTaskToPending()` 重试，不调用 `startDiffusion()` |
| **Health Check 只读** | `backendManager.healthCheck()` 仅读 HTTP 状态，不操作进程 |

### 17.3 C++ 后端服务器启停契约

```
                   ┌──────────────────────────┐
                   │   BackendManager          │
                   │   (唯一进程管理者)          │
                   │                           │
                   │  startDiffusion(…) ──────► ProcessBuilder.start()
                   │  startUpscaler(…)  ──────►     ↓
                   │  stop()            ──────► Process.destroy()
                   │                           │
                   │  state: StateFlow<State>   │
                   └──────────┬────────────────┘
                              │
              ┌───────────────┼───────────────┐
              │               │               │
              ▼               ▼               ▼
    ┌─────────────┐  ┌──────────────┐  ┌──────────────┐
    │ ModelScreen │  │GenerationWkr │  │QueueProcSvc  │
    │ (启动/停止)  │  │ (纯消费者)   │  │ (纯消费者)    │
    │             │  │              │  │              │
    │ loadModel() │  │waitForBackend│  │waitForBackend│
    │  → start    │  │  → poll only │  │  → poll only │
    │unloadModel()│  │generate()    │  │generate()    │
    │  → stop     │  │  → API call  │  │  → API call  │
    └─────────────┘  └──────────────┘  └──────────────┘
```

**启停所有权：**

| 操作 | 所有者 | 触发方式 |
|------|--------|---------|
| 启动 Diffusion 后端 | `ModelScreen` (用户点击 Load) | `backendManager.startDiffusion(modelId, w, h, openCL)` |
| 启动 Upscaler 后端 | `ModelScreen` (用户点击 Load Upscaler) | `backendManager.startUpscaler(upscalerId)` |
| 停止后端 | `ModelScreen` (用户点击 Unload) | `backendManager.stop()` |
| 系统杀死时自动停止 | 系统 (Activity.onStop / Service.onDestroy) | `backendManager.stop()` |

### 17.4 ModelScreen 后端启停管理方法

`ModelListTab` (定义于 `MainActivity.kt` 的 `AppContent()` 中) 和 `ModelRunScreen` 是管理后端启停的 UI 层。

#### 17.4.1 目标架构：通过 BackendManager

```kotlin
// ✅ 目标: ModelScreen 通过 BackendManager 管理后端
fun loadModel(modelId: String) {
    scope.launch {
        // BackendManager 内部自动处理旧进程清理 + 端口互斥
        val result = backendManager.startDiffusion(modelId, genWidth, genHeight, genUseOpenCL)
        result.onSuccess {
            selectedModelId = modelId
            snackbarHostState.showSnackbar("Model loaded")
        }.onFailure { error ->
            snackbarHostState.showSnackbar("Failed: ${error.message}")
        }
    }
}

fun unloadModel() {
    scope.launch {
        backendManager.stop()
        selectedModelId = null
    }
}

fun loadUpscaleModel(upscalerId: String) {
    scope.launch {
        val result = backendManager.startUpscaler(upscalerId)
        result.onSuccess {
            upscalerPreferences.edit { putString("upscaler_standalone_selected_upscaler", upscalerId) }
        }.onFailure { error ->
            snackbarHostState.showSnackbar("Failed: ${error.message}")
        }
    }
}

fun unloadUpscaleModel() {
    scope.launch {
        backendManager.stop()
    }
}
```

#### 17.4.2 当前架构（待修复）：通过 BackendService + UpscaleBackendManager（绕过 BackendManager）

```kotlin
// ❌ 当前: 绕过 BackendManager，直接操作 BackendService / UpscaleBackendManager
fun loadModel(mId: String) {
    scope.launch {
        // 直接 stopService + startForegroundService，绕过 BackendManager
        context.stopService(Intent(context, BackendService::class.java))
        context.startForegroundService(Intent(context, BackendService::class.java).apply {
            putExtra("modelId", mId); putExtra("width", genWidth); ...
        })
    }
}

fun loadUpscaleModel(upscalerId: String) {
    // 直接调用 UpscaleBackendManager.start()，绕过 BackendManager.startUpscaler()
    UpscaleBackendManager.start(context, upscalerId)
}
```

#### 17.4.3 迁移路径

| 步骤 | 当前状态 | 目标状态 |
|------|---------|---------|
| 1 | `loadModel()` → `startForegroundService(BackendService)` | `loadModel()` → `backendManager.startDiffusion(...)` |
| 2 | `unloadModel()` → `stopService(BackendService)` | `unloadModel()` → `backendManager.stop()` |
| 3 | `loadUpscaleModel()` → `UpscaleBackendManager.start()` | `loadUpscaleModel()` → `backendManager.startUpscaler(...)` |
| 4 | `unloadUpscaleModel()` → `UpscaleBackendManager.stop()` | `unloadUpscaleModel()` → `backendManager.stop()` |
| 5 | `isModelLoaded` → `BackendService.backendState` | `isModelLoaded` → `backendManager.state` |
| 6 | `isUpscaleModelLoaded` → `UpscaleBackendManager.state` | `isUpscaleModelLoaded` → `backendManager.state` |
| 7 | `ModelRunScreen` 直接 `startForegroundService(BackendService)` | `ModelRunScreen` 通过 `backendManager.startDiffusion()` |
| 8 | `ModelRunScreen.cleanup()` → `stopService(BackendService)` | `ModelRunScreen.cleanup()` → `backendManager.stop()` |

---

## 15. 变更记录

| 日期 | 版本 | 描述 |
|------|------|------|
| 2026-06-13 | 1.0 | 初始版本，完整架构文档 |
| 2026-06-13 | 1.1 | 将架构评审内容独立为 ArchitectureReview.md |
| 2026-06-13 | 2.0 | 新增目标架构设计(§10)、模块接口标准(§11)、技术规范(§12)、目标文件结构(§13) |
| 2026-06-15 | 2.1 | 完善侧边栏设计(§7)，含 6 分组布局、OLED 纯黑模式、可复用组件规范；Queue Tab 承接生成控制(§4.3.5-4.3.6)，分离 Generate Tab 职责(§4.4.3) |
| 2026-06-15 | 2.2 | 重写 Browse/Gallery 设计(§4.6): 方形画布统一缩略图、LazyVerticalGrid 列数配置、全选/全不选/反选、SelectionBar 布局、选中视觉反馈 |
| 2026-06-15 | 2.3 | 重构 Generate Tab 操作流程(§4.4): "开始生成"按钮移至 TopAppBar 右上角、新增飞行动画(TopBar→Queue Tab)替代 Snackbar、移除 queueAddMessage Card |
| 2026-06-15 | 2.4 | 重构 Browse Gallery 交互(§4.6): ImageViewer 全屏查看器(复用 ZoomableImageOverlay)、SelectionMode TopBar 操作按钮(全选/反选/全不选/下载/保存/删除)、三点溢出菜单(分享/壁纸/详情/复制提示词)、明确 Download vs Save Info 语义 |
| 2026-06-15 | 2.5 | Queue Tab 新增右滑保存功能(§4.3.8): 双方向滑动(左滑删除+右滑保存)、保存提示词和模型参数到 Documents/DreamHub/、与 Gallery Save Info 统一路径 |
| 2026-06-15 | 2.6 | Generate Tab 拆分子Tab(§4.4.7): Parameters(参数编辑) + Records(记录管理); 新增 Record Manager 设计(§4.4.8): RecordRepository 集中存储、Queue/Gallery 保存统一入口、记录与源数据解耦、一键加载到编辑器; 更新 §4.3.8.4 和 §4.6.4.4 改为通过 RecordRepository 保存 |
| 2026-06-15 | 3.0 | **WorkManager 队列架构迁移:** 引入 `androidx.work:work-runtime-ktx:2.10.0`；新增 `GenerationWorker` (CoroutineWorker)、`QueueController`、`QueueNotificationHelper`；重构 `MainActivity` 移除 500ms 轮询循环，改用 `QueueController.observeState()` + `LaunchedEffect`；`QueueRepository` 改为进程级单例 `getInstance()`；`QueueTopBar` 增加 Stop 按钮；更新架构图(§2)、数据流(§2.1)、队列调度(§4.3.1-§4.3.6)、服务层(§5)、目标架构(§10.4)、文件结构(§9)；新增静态审查发现(§14) |
| 2026-06-15 | 3.1 | **Queue-Backend 职责分离规范:** 明确 Queue 不负责启动/重启 Model Service 后端(§4.3.8)；新增 `waitForBackend()` 暂停-轮询-恢复机制(3s 间隔)；后端掉线时 `resetTaskToPending()` 替代 `markTaskError()`；区分 `SseEvent.Error`(参数级) vs Exception(基础设施级) 的处理策略(§4.3.8.4)；更新数据流(§2.1)、Worker 处理流程(§4.3.4)、服务层(§5.2.1/§5.3)、交互流(§10.4)；新增静态审查 SR-8~SR-10 |
| 2026-06-15 | 3.2 | **数据流完整性修复方案设计与实施:** 新增 §16 架构修复方案设计(15 项解决方案，按 A→E 5 阶段排序)；实施 12/15 项修复：SseStreamParser channelFlow 迁移、HistoryManager DB-first 写入+回滚、RecordRepository 损坏恢复+Mutex 原子写、双队列路径策略统一(waitForBackend+resetTaskToPending)、save 返回值检查+bitmap.recycle、processingActive 单一数据源、QueueController.stop() 竞态消除、seed 格式验证 4 语言本地化；遗留 3 项(resultBitmap/队列持久化/参数双重加载)方案设计已完成，待后续迭代 |
| 2026-06-15 | 4.0 | **后端生命周期管理规范:** 新增 §17 后端生命周期管理规范，明确 BackendManager 统一启停规则(§17.1)、Queue Worker Runner 纯消费者角色(§17.2)、C++ 后端启停契约(§17.3)、ModelScreen 后端管理方法的目标架构与迁移路径(§17.4) |
