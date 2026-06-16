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
│  │  ViewModels (GenerateVM · ModelsVM · UpscaleVM · ...) │  │
│  └────────────────────────┬──────────────────────────────┘  │
└───────────────────────────┼──────────────────────────────────┘
                            │
┌───────────────────────────┼──────────────────────────────────┐
│  Service Layer            │                                  │
│  ┌────────────────────────┴──────────────────────────────┐  │
│  │  BackendService (HTTP middleware, UI boundary)        │  │
│  │  Thin proxy — ViewModels never touch BackendManager   │  │
│  └────────────────────────┬──────────────────────────────┘  │
│                           │                                  │
│  ┌────────────────────────┴──────────────────────────────┐  │
│  │  BackendManager (C++ process lifecycle owner)         │  │
│  │  ┌─ startDiffusion / startUpscaler / stop             │  │
│  │  └─ healthCheck / generate / tokenize / upscale       │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Queue Processing (2 parallel paths)                   │  │
│  │  ┌─ GenerationWorker (WorkManager, primary)           │  │
│  │  └─ QueueProcessingService (Foreground, legacy)        │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌─ QueueRepository (process-wide singleton, StateFlow) ─┐  │
│  └─ HistoryManager (file + Room DB) ─────────────────────┘  │
└───────────────────────┬──────────────────────────────────────┘
                        │ HTTP (OkHttp, single shared client)
┌───────────────────────┴──────────────────────────────────────┐
│  C++ Backend (libstable_diffusion_core.so)                   │
│  HTTP Server: http://localhost:8081 (or 0.0.0.0:8081 LAN)   │
│  - /health      (GET)  — health check, 3s timeout            │
│  - /generate    (POST) — SSE streaming generation, 3600s     │
│  - /upscale     (POST) — raw RGB → 4× upscaled, 300s         │
│  - /tokenize    (POST) — CLIP token count, 5s                │
│  - /shutdown    (POST) — graceful shutdown (orphan cleanup)  │
└──────────────────────────────────────────────────────────────┘
```

## 核心数据流

### 生成任务主路径: Generate → Queue → Worker → BackendManager → C++ HTTP

1. User fills params → ViewModel state
2. GenerateSection calls → `QueueRepository.addBatch()`
3. `QueueController.start(context)` → WorkManager
4. `GenerationWorker.doWork()` → `processLoop()` → `waitForBackend()` → `backendManager.generate()` → `HistoryManager.save()`

### 模型加载与后端生命周期

- User clicks Load Model → `ModelsViewModel.loadModel()` → `BackendService.startDiffusion()` → `BackendManager.startDiffusion()` → ProcessBuilder.start(C++ binary)
- User clicks Unload → `ModelsViewModel.unloadModel()` → `BackendService.stop()` → `BackendManager.stop()` → SIGTERM → destroyForcibly()
- BackendManager 为 C++ 进程的**唯一管理者**，UI/Queue 层不直接操作进程

## 状态管理全景

| 状态 | 存储位置 | 观察者 |
|------|---------|--------|
| 队列任务列表 | QueueRepository `_tasks` StateFlow | QueueViewModel → QueueScreen |
| 队列处理中 | QueueRepository `processingActive` + WorkInfo.State | QueueViewModel |
| 生成参数 | GenerateViewModel StateFlow | GenerateScreen |
| 后端状态 | BackendManager `state` (instance) → BackendService proxy | AppContent, ModelRunScreen, UpscaleScreen |
| 模型列表 | ModelRepository | ModelsViewModel → ModelsTab |
| 参数偏好 | GenerationPreferences (SharedPreferences) | GenerateViewModel |
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
| 2026-06-16 | 更新架构图: 删除旧 BackendService (前台Service) / UpscaleBackendManager；新增 BackendService (HTTP 中间件) 层；ViewModel 层替代 MainActivity 状态管理；LAN 暴露说明；/shutdown 端点 |
