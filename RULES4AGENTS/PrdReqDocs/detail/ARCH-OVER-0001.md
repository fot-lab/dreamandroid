# ARCH-OVER-0001: 产品概述与系统架构

| 属性 | 值 |
|------|-----|
| 分类 | Architecture Overview |
| 对应章节 | §1-2 |
| 依赖 | 无 (根模块) |

## 产品概述

DreamHub 是一款本地 AI 图像生成 Android 应用。它通过原生 C++ 后端（cpp-httplib HTTP Server，端口 8081）在设备本地运行 Stable Diffusion 模型和 Real-ESRGAN 超分辨率模型，提供文生图、图生图、超分辨率放大等功能。前端使用 Jetpack Compose + Material 3 构建。

## 分层架构

```
┌──────────────────────────────────────────────────────────────┐
│  Presentation Layer (Jetpack Compose)                        │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │ Models   │  │  Queue   │  │ Generate │  │ Upscale  │   │
│  │ Tab      │  │ Tab      │  │  Tab     │  │ Tab      │   │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘   │
│       │              │             │             │           │
│  ┌────┴──────────────┴─────────────┴─────────────┴───────┐  │
│  │            MainActivity (Orchestrator / God Class)     │  │
│  └────────────────────┬──────────────────────────────────┘  │
└───────────────────────┼──────────────────────────────────────┘
                        │
┌───────────────────────┼──────────────────────────────────────┐
│  Service Layer        │                                      │
│  ┌────────────────────┴──────────────────────────────────┐  │
│  │  Queue Processing (2 parallel paths)                   │  │
│  │  ┌─ GenerationWorker (WorkManager, primary)           │  │
│  │  └─ QueueProcessingService (Foreground, legacy)        │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Backend Management (2 parallel systems)             │  │
│  │  ┌─ BackendService (legacy Foreground Service)       │  │
│  │  └─ BackendManager (new unified manager)             │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌─ QueueRepository (process-wide singleton, StateFlow) ─┐  │
│  └─ HistoryManager (file + Room DB) ─────────────────────┘  │
└───────────────────────┬──────────────────────────────────────┘
                        │ HTTP (OkHttp, single shared client)
┌───────────────────────┴──────────────────────────────────────┐
│  C++ Backend (libstable_diffusion_core.so)                   │
│  HTTP Server: http://localhost:8081                           │
│  - /health      (GET)  — health check, 3s timeout            │
│  - /generate    (POST) — SSE streaming generation, 3600s     │
│  - /upscale     (POST) — raw RGB → 4× upscaled, 300s         │
│  - /tokenize    (POST) — CLIP token count, 5s                │
└──────────────────────────────────────────────────────────────┘
```

## 核心数据流

### 生成任务主路径: Generate → Queue → Worker → Backend → History

1. User fills params → MainActivity gen* StateFlow variables
2. GenerateScreen calls back to MainActivity → `QueueRepository.addBatch()`
3. LaunchedEffect detects hasPendingTasks()
4. `QueueController.start(context)` → WorkManager
5. `GenerationWorker.doWork()` → `processLoop()` → `waitForBackend()` → `generate()` → `HistoryManager.save()`

### 模型加载与后端生命周期

- User clicks Load Model → `MainActivity.loadModel()` → Stop current BackendService → Start Foreground Service with model params
- User clicks Unload → Send ACTION_STOP broadcast → `stopService()`
- ⚠️ Dual Backend System: `BackendService` (legacy) vs `BackendManager` (new)

## 状态管理全景

| 状态 | 存储位置 | 观察者 |
|------|---------|--------|
| 队列任务列表 | QueueRepository `_tasks` StateFlow | MainActivity → QueueScreen |
| 队列处理中 | QueueRepository `processingActive` + WorkInfo.State | MainActivity |
| 生成参数 | ~20 `gen*` 变量在 MainActivity.kt | GenerateScreen |
| 后端扩散状态 | BackendService `backendState` (static) | MainActivity |
| 后端管理器状态 | BackendManager `state` (instance) | (无 UI 观察者) |
| 超分辨率状态 | UpscaleBackendManager `state` (static) | MainActivity |
| 模型列表 | ModelRepository | MainActivity → ModelsTab |
| 参数偏好 | GenerationPreferences (SharedPreferences) | MainActivity + GenerateScreen |
| 历史记录 | HistoryManager (Room DB + files) | BrowseScreen |
| 参数记录 | RecordRepository (JSON file) | GenerateScreen Records Tab |

## 错误处理体系

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
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §1-2 内容，创建独立文件 |
