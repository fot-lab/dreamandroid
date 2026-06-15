# BKND-LFCY-0001: 后端生命周期管理规范

| 属性 | 值 |
|------|-----|
| 分类 | Backend Lifecycle |
| 对应章节 | §17 |
| 依赖 | ARCH-OVER-0001, SERV-BACK-0001, ARCH-TARG-0001 |

## 概述

明确 BackendManager、Queue Worker Runner、C++ 后端服务器之间的启停管理规则和职责边界。

## 17.1 BackendManager — 统一启停规则

`BackendManager` 是 **唯一合法的 C++ 后端进程管理者**：

```kotlin
class BackendManager(private val context: Context) {
    suspend fun startDiffusion(modelId, width, height, useOpenCL): Result<Unit>
    suspend fun startUpscaler(upscalerId): Result<Unit>
    suspend fun stop()
    val state: StateFlow<State>
}
```

**核心规则：**

| 规则 | 说明 |
|------|------|
| **唯一进程管理者** | 仅 `BackendManager` 有权调用 `ProcessBuilder.start()` 和 `Process.destroy()` |
| **端口互斥保证** | `startDiffusion()` / `startUpscaler()` 内部自动 `stopProcess()` |
| **优雅关闭** | destroy() → waitFor(5s) → destroyForcibly() → waitFor() |
| **单一状态源** | 所有 UI 和 Queue 通过 `backendManager.state` 观察后端状态 |
| **禁止直接Intent** | 禁止通过 `startForegroundService()` / `stopService()` 操作后端进程 |

## 17.2 Queue Worker Runner — 启停角色

`GenerationWorker` 和 `QueueProcessingService` 是**后端 HTTP API 的纯消费者**，**绝不**启动、停止或重启 C++ 后端进程。

| 规则 | 说明 |
|------|------|
| **Queue 不启动后端** | health check 失败 → `waitForBackend()` 暂停轮询 |
| **Queue 不停止后端** | Queue 停止时仅取消自身任务，不调用 `backendManager.stop()` |
| **Queue 不重启后端** | 后端崩溃 → `resetTaskToPending()` 重试 |
| **Health Check 只读** | `backendManager.healthCheck()` 仅读 HTTP 状态 |

## 17.3 C++ 后端服务器启停契约

```
                   ┌──────────────────────────┐
                   │   BackendManager          │
                   │   (唯一进程管理者)          │
                   │  startDiffusion(…) ──────► ProcessBuilder.start()
                   │  startUpscaler(…)  ──────►     ↓
                   │  stop()            ──────► Process.destroy()
                   │  state: StateFlow<State>   │
                   └──────────┬────────────────┘
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
    ┌─────────────┐  ┌──────────────┐  ┌──────────────┐
    │ ModelScreen │  │GenerationWkr │  │QueueProcSvc  │
    │ (启动/停止)  │  │ (纯消费者)   │  │ (纯消费者)    │
    └─────────────┘  └──────────────┘  └──────────────┘
```

**启停所有权：**

| 操作 | 所有者 | 触发方式 |
|------|--------|---------|
| 启动 Diffusion 后端 | ModelScreen | `backendManager.startDiffusion()` |
| 启动 Upscaler 后端 | ModelScreen | `backendManager.startUpscaler()` |
| 停止后端 | ModelScreen | `backendManager.stop()` |
| 系统杀死时自动停止 | 系统 | `backendManager.stop()` |

## 17.4 ModelScreen 后端启停管理

### 目标架构（通过 BackendManager）

```kotlin
fun loadModel(modelId: String) {
    scope.launch {
        val result = backendManager.startDiffusion(modelId, genWidth, genHeight, genUseOpenCL)
        result.onSuccess { selectedModelId = modelId }
              .onFailure { error -> snackbarHostState.showSnackbar("Failed: ${error.message}") }
    }
}

fun unloadModel() {
    scope.launch { backendManager.stop(); selectedModelId = null }
}
```

### 当前架构（待修复：绕过 BackendManager）

```kotlin
// ❌ 当前: 直接操作 BackendService / UpscaleBackendManager
fun loadModel(mId: String) {
    context.stopService(Intent(context, BackendService::class.java))
    context.startForegroundService(Intent(context, BackendService::class.java).apply {
        putExtra("modelId", mId); putExtra("width", genWidth); ...
    })
}
fun loadUpscaleModel(upscalerId: String) {
    UpscaleBackendManager.start(context, upscalerId)
}
```

### 迁移路径

| 步骤 | 当前状态 | 目标状态 |
|------|---------|---------|
| 1 | `loadModel()` → `startForegroundService(BackendService)` | → `backendManager.startDiffusion(...)` |
| 2 | `unloadModel()` → `stopService(BackendService)` | → `backendManager.stop()` |
| 3 | `loadUpscaleModel()` → `UpscaleBackendManager.start()` | → `backendManager.startUpscaler(...)` |
| 4 | `unloadUpscaleModel()` → `UpscaleBackendManager.stop()` | → `backendManager.stop()` |
| 5 | `isModelLoaded` → `BackendService.backendState` | → `backendManager.state` |
| 6 | `isUpscaleModelLoaded` → `UpscaleBackendManager.state` | → `backendManager.state` |
| 7 | `ModelRunScreen` 直接 `startForegroundService` | → `backendManager.startDiffusion()` |
| 8 | `ModelRunScreen.cleanup()` → `stopService` | → `backendManager.stop()` |

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §17 内容，创建独立文件 |
