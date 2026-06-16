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
| **端口互斥保证** | `startDiffusion()` / `startUpscaler()` 内部自动 `ensureNoOrphanBackend()` → `stopProcess()` |
| **优雅关闭** | destroy() → waitFor(5s) → destroyForcibly() → waitFor() |
| **单一状态源** | 所有 UI 和 Queue 通过 `BackendService.state` (代理自 `BackendManager.state`) 观察后端状态 |
| **禁止直接Intent** | 已无 `startForegroundService()` / `stopService()` 操作后端进程的代码路径 |

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
    │ ModelsVM    │  │GenerationWkr │  │QueueProcSvc  │
    │ (启动/停止)  │  │ (纯消费者)   │  │ (纯消费者)    │
    └─────────────┘  └──────────────┘  └──────────────┘
         │                   │               │
         └───────────────────┴───────────────┘
                             │
                     BackendService
                     (HTTP middleware)
```

**启停所有权：**

| 操作 | 所有者 | 触发方式 |
|------|--------|---------|
| 启动 Diffusion 后端 | ModelsViewModel | `BackendService.startDiffusion()` → `BackendManager.startDiffusion()` |
| 启动 Upscaler 后端 | ModelsViewModel | `BackendService.startUpscaler()` → `BackendManager.startUpscaler()` |
| 停止后端 | ModelsViewModel / ModelRunScreen | `BackendService.stop()` → `BackendManager.stop()` |
| 系统杀死时自动停止 | 系统 | `Runtime.addShutdownHook` → `backendManager.stopProcessImmediate()` |

## 17.4 UI 层后端通信规范（当前已实现）

### 通过 BackendService 的合法路径

```kotlin
// ✅ ViewModel 通过 BackendService 操作后端
class ModelsViewModel(app: Application) : AndroidViewModel(app) {
    private val backendService = (app as DreamAndroidApplication).backendService

    suspend fun loadModel(...) {
        backendService.startDiffusion(modelId, width, height, useOpenCL)
    }
    suspend fun unloadModel() {
        backendService.stop()
    }
}

// ✅ Screen 通过 BackendService 观察状态
val backendService = remember { (context.applicationContext as DreamAndroidApplication).backendService }
val backendState by backendService.state.collectAsState()
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §17 内容，创建独立文件 |
| 2026-06-16 | 更新 §17.1: 端口互斥更新为 `ensureNoOrphanBackend`；§17.3 架构图新增 BackendService 层；§17.4 重写: 删除 "当前架构(待修复)" 章节 (所有绕过已 Fully Fixed)，替换为 BackendService 合法使用示例 |
