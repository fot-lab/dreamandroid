# ARCH-TARG-0001: 目标架构设计

| 属性 | 值 |
|------|-----|
| 分类 | Target Architecture |
| 对应章节 | §10 |
| 依赖 | ARCH-OVER-0001 |

## 架构全景图

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Android App Layer                            │
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
│  │  │  BackendService    │   │   │  QueueProcessingService  │   │   │
│  │  │  (HTTP middleware) │   │   │  (Android Service)       │   │   │
│  │  └────────┬───────────┘   │   └──────────────────────────┘   │   │
│  │           │               │   ┌──────────────────────────┐   │   │
│  │  ┌────────┴───────────┐   │   │  GenerationWorker        │   │   │
│  │  │  BackendManager    │←──┼───│  (WorkManager)           │   │   │
│  │  │  (process owner)   │   │   └──────────────────────────┘   │   │
│  │  └────────────────────┘   │                                   │   │
│  └───────────────────────────┼──────────────────────────────────┘   │
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
```

## 分层架构原则

| 层 | 职责 | 依赖方向 | 约束 |
|----|------|---------|------|
| **Presentation** | Compose UI + ViewModels | → Service Layer | 不直接调用 HTTP，不直接操作文件系统 |
| **Service** | 业务逻辑编排、进程管理、队列调度 | → Data Layer | 不持有 UI 引用，通过 StateFlow 暴露状态 |
| **Data** | Room DAO、Preferences、文件 I/O | → 无外部依赖 | 不引用 Service 或 UI 层 |

## 核心原则

1. **单向依赖:** Presentation → Service → Data。下层不知道上层的存在。
2. **StateFlow 通信:** Service 层通过 `StateFlow` 暴露状态给 ViewModel，ViewModel 通过 `collectAsState()` 驱动 UI。
3. **UI 不直接接触 BackendManager:** ViewModel/Screen 通过 `BackendService` (HTTP 中间件) 代理所有后端通信。
4. **Single Source of Truth:** Room 是模型/历史数据的唯一数据源。
5. **协程安全:** 所有 I/O 操作必须在 `Dispatchers.IO` 中执行；禁止 `runBlocking` 出现在主线程。
6. **统一错误模型:** 所有错误通过 `sealed class AppError` 体系传播。

## 数据流

```
┌──────────┐    StateFlow        ┌──────────┐    suspend/Flow    ┌──────────────┐
│  Compose │←───────────────────│ViewModel │←───────────────────│ Service      │
│  UI      │   collectAsState()  │          │   launch/call      │ (BackendSvc  │
│          │                     │          │                    │  BackendMgr  │
│          │  events             │          │  domain types      │  QueueProc)  │
│          │────────────────────→│          │───────────────────→│              │
└──────────┘                     └──────────┘                    └──────┬───────┘
                                                                       │
                                                                   suspend
                                                                       │
                                                                       ▼
                                                                ┌──────────────┐
                                                                │ Data Layer   │
                                                                │ Room / Files │
                                                                └──────────────┘
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §10 内容，创建独立文件 |
| 2026-06-16 | 架构图: 新增 BackendService (HTTP 中间件) 层于 BackendManager 之上；新增原则 §3 "UI 不直接接触 BackendManager" |
