# PLAN-ROAD-0001: Master Remediation Roadmap — 剩余问题分批解决总计划

> 优先级: P0
> 创建日期: 2026-06-15
> 更新日期: 2026-06-16
> 关联: ALL non-"Fully Fixed" items in index.md

## 1. 当前状态

- **总问题数**: 58
- **已 Fixed/Won'tFix**: 55 (Phase A~F 完成 + E5 Unblocked 收尾)
- **剩余未解决**: 3 (DATA-STOR-0001 (P1 Room SSOT), DFLW-INTG-0007 (P2 Deferred), MODU-SPLT-0001 (P3 Deferred))
- **已完成 Phase**: A (Backend Consolidation), B (Coroutine Safety), C (Queue Persistence), D (ViewModel 拆分), E (P2/P3 收尾), F (Lifecycle & Memory Safety), **E5 (Unblocked Issues)**
- **最新**: Phase E5 完成 — UILA-COMP-0003/0004/0005 + HTTP-CLNT-0004 + DFLW-INTG-0013 全部解决

## 2. 执行阶段总览

```
Phase A: Backend Consolidation (P0)        ← ✅ COMPLETED (2026-06-16)
 │
 ├── ✅ A1: 迁移 ModelRunScreen bypass → BackendManager
 │       BKLC-BPAS-0001(start) + 0002(restart) + 0003(stop) + 0004(generation)
 │       + BKND-PROC-0003(切换泄漏)
 │
 ├── ✅ A2: 删除旧 BackendService + UpscaleBackendManager + BackgroundGenerationService
 │       BKND-PROC-0001 + BKND-PROC-0004(prepareRuntimeDir) 
 │       + BKLC-BPAS-0005~0010(UpscaleBackendMgr 重复)
 │
 └── ✅ A3: 前台通知保护
         BKND-PROC-0005
 │
Phase B: Coroutine Safety (P0)     ← ✅ COMPLETED (2026-06-16)
 │
 ├── ✅ B1: runBlocking → suspend
 │       CORO-EXEC-0001
 │
 └── ✅ B2: Scope 泄漏修复
         CORO-EXEC-0002
 │
Phase C: Queue Persistence (P0)    ← ✅ COMPLETED (2026-06-16)
 │
 └── ✅ C1: Room 持久化队列 + History (统一 TaskEntity 方案)
         DFLW-INTG-0012 + QUEU-SYST-0005
         (实际实施扩展：Queue+History 合并为统一 TaskEntity，共 31 字段)
 │
Phase D: AppContent God Object (P0) ← ✅ COMPLETED (2026-06-16)
 │
 ├── ✅ D1: ViewModel 拆分 (6 个) — AppContent 570→310 行 (-46%)
 │       UILA-COMP-0001 → Fully Fixed
 │
 └── ✅ D2: 测试基础 — 分层架构就绪
         UILA-COMP-0002 → Fully Fixed
 │
Phase E: P2/P3 修复                ← ✅ COMPLETED (2026-06-16)
 │
 ├── ✅ E1: HTTP/网络 (HTTP-CLNT-0001/0003 → Fully Fixed)
 ├── ✅ E2: Bitmap 回收 (QUEU-SYST-0007 → Fully Fixed)
 ├── ✅ E3: SharedPreferences (DATA-STOR-0002 → Won't fix, acceptable)
 ├── ✅ E4: 文件拆分 (UILA-COMP-0006/0007 → Fully Fixed)
 ├── ✅ E5: Unblocked Issues — UILA-COMP-0003/0005 + HTTP-CLNT-0004 + DFLW-INTG-0013 → Fully Fixed; UILA-COMP-0004 → Won't Fix
 └── 📅 E6: 推迟 (MODU-SPLT-0001, DFLW-INTG-0007, DATA-STOR-0001 → Deferred)
 │
Phase F: Lifecycle & Memory Safety       ← ✅ COMPLETED (2026-06-16)
 │
 ├── ✅ F1: P0 紧急 — ShutdownHook + UncaughtExceptionHandler + 孤儿进程检测
 ├── ✅ F2: P1 高优 — onTrimMemory + MonitorThread 中断 + stopProcessImmediate
 ├── ✅ F3: P2 常规 — UpscaleScreen Bitmap 回收 + 临时文件清理
 ├── ✅ F4: P2 — OkHttpClient 复用 (ModelDownloadService → HttpClientProvider)
 ├── ✅ F5: P2 — BackendManager ApplicationContext 强制
 ├── ✅ F6: P2 — resultBitmap → resultBitmapPath (以文件路径替代内存 Bitmap)
 ├── ✅ F7: P2/P3 — RuntimeDirPreparer cleanup + QueueRepository cancelScope
```

### 剩余未解决问题 (3)

| ID | P | 摘要 | 状态 |
|----|---|------|------|
| DATA-STOR-0001 | P1 | 模型数据双源无 SSOT | Deferred (需 Room ModelEntity 重建) |
| DFLW-INTG-0007 | P2 | resultBitmap 内存累积 | Deferred (Phase F 已经改用 resultBitmapPath) |
| MODU-SPLT-0001 | P3 | 单模块无编译隔离 | Deferred (P3 低优先级，留待未来版本) |
```

## 3. 依赖图

```
BKLC-BPAS-0001 ─┐
BKLC-BPAS-0002 ─┤
BKLC-BPAS-0003 ─┼──→ BKND-PROC-0001 ──→ BKLC-BPAS-0005~0010 ──→ 删除旧文件   ← ✅ Phase A done
BKLC-BPAS-0004 ─┘         │
BKND-PROC-0003 ───────────┘
                          │
                          └──→ BKND-PROC-0004 (自动解决)  ← ✅
                          └──→ BKND-PROC-0005 (并行)      ← ✅

CORO-EXEC-0001 ──→ CORO-EXEC-0002        ← ✅ Phase B done
DFLW-INTG-0012 ──→ QUEU-SYST-0005        ← ✅ Phase C done
UILA-COMP-0001  ──→ UILA-COMP-0002        ← 无外部依赖 (待执行)
```

## 4. Phase A: Backend Consolidation (优先级 P0) — ✅ COMPLETED (2026-06-16)

### 4.1 现状代码定位

Phase 4 拆分后，bypass 代码已从 ModelRunScreen.kt 分离到：

| Bypass | 旧位置 | 新位置 |
|--------|--------|--------|
| BKLC-BPAS-0001 (start) | ModelRunScreen.kt L1442 | `ModelRunScreen.kt` L237-240 + `ModelRunBackend.kt` L97 |
| BKLC-BPAS-0002 (restart) | ModelRunScreen.kt L1781 | `ModelRunScreen.kt` L585-586 |
| BKLC-BPAS-0003 (stop) | ModelRunScreen.kt L1324 | `ModelRunGeneration.kt` L612-613 + `ModelRunBackend.kt` L32-33 |
| BKLC-BPAS-0004 (generation) | ModelRunScreen.kt L2638 | `ModelRunGeneration.kt` L292 |

### 4.2 Sub-task A1: 迁移 ModelRunScreen Bypass → BackendManager

涉及 Issue: BKLC-BPAS-0001, BKLC-BPAS-0002, BKLC-BPAS-0003, BKLC-BPAS-0004, BKND-PROC-0003

**Step 1: Review Current Code**
- Review `BackendManager.startDiffusion()` / `stop()` / `generate()` API 签名
- Review `ModelRunScreen.kt` L230-260 (backend init LaunchedEffects)
- Review `ModelRunBackend.kt` full (cleanup + backend lifecycle)
- Review `ModelRunGeneration.kt` L280-330 (startBatchGeneration), L600-620 (cleanupModelRun)
- Review `ModelRunScreen.kt` L580-590 (resolution change callback)

**Step 2: Plan Resolution**
确认 `BackendManager` 提供的 API：
- `startDiffusion(modelId, width, height, useOpenCL)` — 替换 `startForegroundService(BackendService)`
- `stop()` — 替换 `stopService(BackendService)` + 广播
- `generate(params)` — 替换 `startForegroundService(batchIntent)` (BackgroundGenerationService)
- `state: StateFlow<BackendManager.State>` — 替换 `BackendService.backendState`

修改清单：
1. `ModelRunScreen.kt` L237-240: `startForegroundService(intent)` → `backendManager.startDiffusion(modelId, state.currentWidth, state.currentHeight, state.useOpenCL)`
2. `ModelRunBackend.kt` L85-98: 删除重复的 `startForegroundService` (合并到 ModelRunScreen 的 LaunchedEffect)
3. `ModelRunScreen.kt` L585-586: `ACTION_RESTART` → `backendManager.stop()` then `backendManager.startDiffusion(...)`
4. `ModelRunGeneration.kt` L610-614: `stopService` → `backendManager.stop()`
5. `ModelRunBackend.kt` L30-34: `stopService` → `backendManager.stop()`
6. `ModelRunGeneration.kt` L290-292: `startForegroundService(batchIntent)` → `backendManager.generate(...)` SSE Flow
7. 观察 backend state 从 `BackendService.backendState` 改为 `backendManager.state`

**Step 3: Execute Resolution**
按上述修改清单逐文件修改。

**Step 4: Update Review Detail**
更新 BKLC-BPAS-0001/0002/0003/0004/0005.md 和 BKND-PROC-0003.md 的执行结果。

**Step 5: Commit and Push** (跳过编译，依赖 CI/CD)

---

### 4.3 Sub-task A2: 删除旧 BackendService + UpscaleBackendManager

涉及 Issue: BKND-PROC-0001, BKND-PROC-0004, BKLC-BPAS-0005~0010

**依赖**: A1 完成且 ModelRunScreen 不再引用 BackendService/UpscaleBackendManager

**Step 1: Review Current Code**
- 搜索全项目 `BackendService` 引用确认无剩余调用方
- 搜索全项目 `UpscaleBackendManager` 引用确认无剩余调用方

**Step 2: Plan Resolution**
确认零引用后：
1. 删除 `service/BackendService.kt`
2. 删除 `service/UpscaleBackendManager.kt`
3. BKND-PROC-0004 (prepareRuntimeDir 重复) 自动解决 — 只保留 `RuntimeDirPreparer`
4. BKLC-BPAS-0005~0010 自动解决

**Step 3: Execute Resolution**
删除两个旧文件。

**Step 4: Update Review Detail**
更新 BKND-PROC-0001, BKND-PROC-0004, BKLC-BPAS-0005~0010 为 Fully Fixed。

**Step 5: Commit and Push**

---

### 4.4 Sub-task A3: 前台通知保护

涉及 Issue: BKND-PROC-0005

**Step 1: Review Current Code**
- Review `BackendManager.kt` 的 start/stop 流程
- 检查现有 Notification channel 配置

**Step 2: Plan Resolution**
在 `BackendManager.startDiffusion()`/`startUpscaler()` 中调用 `showForegroundNotification()`，在 `stop()` 中调用 `removeForegroundNotification()`。

**Step 3: Execute Resolution**
修改 `BackendManager.kt`。

**Step 4: Update Review Detail**
更新 BKND-PROC-0005.md。

**Step 5: Commit and Push**

---

## 5. Phase B: Coroutine Safety (优先级 P0) — ✅ COMPLETED (2026-06-16)

### 5.1 Sub-task B1: runBlocking 修复

涉及 Issue: CORO-EXEC-0001

**Step 1: Review Current Code**
- Review `data/Model.kt` 中 `runBlocking(Dispatchers.IO)` 的 2 处位置
- 确认其 `init{}` 调用链和依赖方

**Step 2: Plan Resolution**
- L277 处 `init{}` 内的 `runBlocking` → lazy 属性
- L386 处 → suspend 函数，调用方在协程中调用

**Step 3: Execute Resolution**
修改 `Model.kt`。

**Step 4: Update Review Detail**
更新 CORO-EXEC-0001.md。

**Step 5: Commit and Push**

---

### 5.2 Sub-task B2: Scope 泄漏修复

涉及 Issue: CORO-EXEC-0002

**Step 1: Review Current Code**
- Review `UpscaleBackendManager.kt` (如果 Phase A2 已删除则跳过)
- Review `data/ModelRepository.kt` init 中的观察协程
- Review `utils/LogCapture.kt` 协程生命周期

**Step 2: Plan Resolution**
- UpscaleBackendManager: 如 Phase A2 已删除则自动解决
- ModelRepository: 从 init 中移出 → `fun startObserving(scope)` 由调用方传 scope
- LogCapture: 绑定 Application 生命周期

**Step 3: Execute Resolution**
修改 `ModelRepository.kt` 和 `LogCapture.kt`。

**Step 4: Update Review Detail**
更新 CORO-EXEC-0002.md。

**Step 5: Commit and Push**

---

## 6. Phase C: Queue Persistence (优先级 P0) — ✅ COMPLETED (2026-06-16)

### 6.1 Sub-task C1: Room 持久化队列

涉及 Issue: DFLW-INTG-0012, QUEU-SYST-0005

**Step 1: Review Current Code** ✅
**Step 2: Plan Resolution** → 实际执行扩展为统一 TaskEntity 方案 (Queue+History 合并)
**Step 3: Execute Resolution** ✅ (新建 TaskEntity + TaskDao + v4 Migration，删除旧 Entity/Dao ×4)
**Step 4: Update Review Detail** ✅ (QUEU-SYST-0005, DFLW-INTG-0012 → Fully Fixed)
**Step 5: Commit and Push** ✅

---

## 7. Phase D: AppContent God Object (优先级 P0) — ✅ COMPLETED (2026-06-16)

### 7.1 Sub-task D1: ViewModel 拆分 ✅

涉及 Issue: UILA-COMP-0001 → Fully Fixed

**执行结果**:
1. 新增依赖 `androidx.lifecycle:lifecycle-viewmodel-compose`
2. 创建 6 个 ViewModel 类 (`ui/viewmodel/`):
   - `MainViewModel.kt` (20行) — selectedTab, showNoModelWarning
   - `ModelsViewModel.kt` (210行) — model CRUD, load/unload, import/rename/delete, upscaler
   - `QueueViewModel.kt` (55行) — queue observation, auto-start, WorkManager logging
   - `GenerateViewModel.kt` (130行) — generation params, tokenize, addToQueue, prefs
   - `UpscaleViewModel.kt` (150行) — image selection, upscale execution, bitmap lifecycle (onCleared)
   - `BrowseViewModel.kt` (180行) — history browsing, selection mode, batch operations
3. `AppContent.kt`: 570→310 行 (-46%) — 纯编排器
4. `BrowseScreen.kt`: 使用 BrowseViewModel，10+ 内部 state 移入 ViewModel
5. `UpscaleScreen.kt`: 使用 UpscaleViewModel，DisposableEffect 替换为 onCleared()
6. `AppContentState.kt`: @Stable 类已退役

### 7.2 Sub-task D2: 测试基础 ✅

涉及 Issue: UILA-COMP-0002 → Fully Fixed

ViewModel 分层架构就绪，6 个 ViewModel 均可独立测试（mock 依赖）。测试基础设施 (`androidTestImplementation`) 已配置。

---

## 8. Phase E: P2/P3 收尾 — ✅ COMPLETED

### 8.1 E5: Unblocked Issues 修复 (2026-06-16)

| ID | P | 操作 | 说明 |
|----|---|------|------|
| UILA-COMP-0003 | P2 | Fully Fixed | GenerateViewModel 集成 AppError；tokenizeError 状态暴露 |
| UILA-COMP-0004 | P2 | Won't Fix | Application 单例模式对当前规模足够 |
| UILA-COMP-0005 | P2 | Fully Fixed | GenerateScreen 不再直接调用 BackendManager.tokenize() |
| HTTP-CLNT-0004 | P3 | Fully Fixed | 随 UILA-COMP-0003 + 0005 自动解决 |
| DFLW-INTG-0013 | P2 | Fully Fixed | 删除 GenerateScreen 重复偏好加载；单一加载点 GenerateViewModel |

**代码变更**:
- `GenerateViewModel.kt`: +tokenize 完整状态字段 (maxLength, overflowOffset), +AppError 错误处理
- `GenerateScreen.kt`: -direct BackendManager HTTP, -重复 pref 加载, +tokenize 回调参数
- `GenerateSection.kt`: 透传 tokenize 回调
- `AppContent.kt`: 接线 GenerateViewModel tokenize 回调

### 8.3 Deferred Issues (3 remaining)

| ID | P | 摘要 | 原因 |
|----|---|------|------|
| DATA-STOR-0001 | P1 | 模型数据双源无 SSOT | 需 Room ModelEntity 重建，工作量较大 |
| DFLW-INTG-0007 | P2 | resultBitmap 内存累积 | Phase F 已改为 resultBitmapPath，风险大幅降低 |
| MODU-SPLT-0001 | P3 | 单模块无编译隔离 | P3 低优先级，留待未来版本 |

---

## 9. Phase F: Lifecycle & Memory Safety (NEW — 审计完成，待执行)

涉及 Issue: **LCLE-MEMO-0001**

### 9.1 Sub-task F1: P0 紧急修复 — 孤儿进程 + Crash Hook

**Step 1: Review Current Code**
- `BackendManager.kt` L66 (`process` field), L325-341 (`stopProcess`)
- `DreamAndroidApplication.kt` L95-98 (`onTerminate`)
- 已有 health check API (`BackendManager.healthCheck()`)

**Step 2: Plan Resolution**

| 改动 | 文件 | 目的 |
|------|------|------|
| `Runtime.addShutdownHook` | `DreamAndroidApplication` | JVM 正常退出时 kill C++ 进程 |
| `UncaughtExceptionHandler` | `DreamAndroidApplication` | Java crash 时 kill C++ 进程 |
| `killOrphanedBackend()` | `BackendManager` (新增) | 启动时 health check + HTTP `/shutdown` 清理孤儿进程 |
| `BackendManager.stopProcessImmediate()` | `BackendManager` (新增) | 不做 waitFor 等待的直接 kill (用于 shutdown hook) |

**Step 3: Execute Resolution**
修改 `DreamAndroidApplication.kt` + `BackendManager.kt`

**Step 4: Update Review Detail**
更新 LCLE-MEMO-0001.md

**Step 5: Commit and Push**

---

### 9.2 Sub-task F2: P1 高优 — 内存压力响应 + 线程管理

**Step 1: Review Current Code**
- `MainActivity.kt` — 缺乏 `onTrimMemory`
- `BackendManager.kt` L374-398 — raw Thread 创建

**Step 2: Plan Resolution**

| 改动 | 文件 | 目的 |
|------|------|------|
| `onTrimMemory()` | `DreamAndroidApplication` | 逐级释放 Bitmap/卸载模型 |
| `monitorThread?.interrupt()` | `BackendManager` | stopProcess 时中断旧 monitor thread |
| Double-check health check | `BackendManager.stopProcess()` | 验证进程实际已停止 |

**Step 3: Execute Resolution**

**Step 4: Update Review Detail**

**Step 5: Commit and Push**

---

## 10. 预估工作量

| Phase | Sub-tasks | 预估文件修改数 | 复杂度 | 状态 |
|-------|-----------|--------------|--------|------|
| A1 | 迁移 bypass | 3-4 文件 | Medium | ✅ Done |
| A2 | 删除旧文件 | 3 files delete + 1 search | Low | ✅ Done |
| A3 | 通知保护 | 1 file | Low | ✅ Done |
| B1 | runBlocking | 1 file | Low | ✅ Done |
| B2 | scope 泄漏 | 0 files (auto-resolved) | Low | ✅ Done |
| C1 | Room 队列+历史 | 2 files new + 6 modified + 4 deleted | High | ✅ Done |
| D1 | ViewModel | 6 files new + 3 rewrite | High | ✅ Done |
| D2 | 测试 | 分层架构就绪 | Medium | ✅ Done |
| E | 收尾 | ~10 files | Low-Medium | ✅ Done |
| F1 | 孤儿进程 + Crash Hook | 2 files | Medium | ✅ Done |
| F2 | 内存压力 + 线程管理 | 2 files | Low-Medium | ✅ Done |

## 10. 执行原则

每个 Sub-task 严格遵循 5 步流程：
1. **Review Current Code** — 用 read_file/search_content 理解现状
2. **Plan Resolution into Review Detail** — 在对应 detail/*.md 写清修改方案
3. **Execute Resolution** — 用 replace_in_file/write_to_file 实施
4. **Update Review Detail** — 在 detail/*.md 追加执行结果和变更历史
5. **Commit and Push** — 跳过编译，依赖 Actions CI/CD

## 11. 变更历史

| 日期 | 变更 |
|------|------|
| 2026-06-15 | 创建：全量剩余问题分批解决总体规划 |
| 2026-06-16 | Phase A~E 全部完成；RecordRepository 迁移至 Room (TYPE_RECORD) — Queue/History/Record 三合一 TaskEntity；Phase F 完成 — LCLE-MEMO-0001 全部 13 个发现已修复 |
| 2026-06-16 | Phase D 完成 — AppContent God Object 拆解：6 ViewModels, AppContent -46%, UpscaleScreen/BrowseScreen 重构 |
| 2026-06-16 | Phase E5 完成 — Unblocked Issues 全部解决：UILA-COMP-0003/0005 Fully Fixed, HTTP-CLNT-0004 Fully Fixed, DFLW-INTG-0013 Fully Fixed, UILA-COMP-0004 Won't Fix |
