# PLAN-ROAD-0001: Master Remediation Roadmap — 剩余问题分批解决总计划

> 优先级: P0
> 创建日期: 2026-06-15
> 更新日期: 2026-06-16
> 关联: ALL non-"Fully Fixed" items in index.md

## 1. 当前状态

- **总问题数**: 57
- **已 Fixed**: 38 (Phase A + B + C + E 完成，累计 +21)
- **剩余未解决**: 19 (含 1 P0 (UILA-COMP-0001 AppContent God Object) / 1 P1 (UILA-COMP-0002 无法单独测试) / 7 Phase D 依赖 / 8 P2/P3 deferred)
- **已完成 Phase**: A (Backend Consolidation), B (Coroutine Safety), C (Queue Persistence), E (P2/P3 收尾)

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
Phase D: AppContent God Object (P0) ← 最大拆分工程
 │
 ├── D1: ViewModel 拆分 (6 个)
 │       UILA-COMP-0001
 │
 └── D2: 测试基础
         UILA-COMP-0002
 │
Phase E: P2/P3 修复                ← ✅ COMPLETED (2026-06-16)
 │
 ├── ✅ E1: HTTP/网络 (HTTP-CLNT-0001/0003 → Fully Fixed)
 ├── ✅ E2: Bitmap 回收 (QUEU-SYST-0007 → Fully Fixed)
 ├── ✅ E3: SharedPreferences (DATA-STOR-0002 → Won't fix, acceptable)
 ├── ✅ E4: 文件拆分 (UILA-COMP-0006/0007 → Fully Fixed)
 ├── 📅 E5: 依赖 ViewModel (UILA-COMP-0003/0004/0005, HTTP-CLNT-0004, DFLW-INTG-0013, DATA-STOR-0001 → Blocked on Phase D)
 └── 📅 E6: 推迟 (MODU-SPLT-0001, DFLW-INTG-0007 → Deferred)
```

### 剩余未解决问题 (19)

| ID | P | 摘要 | 阻塞原因 |
|----|---|------|----------|
| UILA-COMP-0001 | P0 | AppContent God Object | Phase D |
| UILA-COMP-0002 | P1 | 无法单独测试 | 依赖 Phase D |
| UILA-COMP-0003 | P2 | 错误处理不一致 | Blocked on Phase D |
| UILA-COMP-0004 | P2 | 无 DI 框架 | Blocked on Phase D |
| UILA-COMP-0005 | P2 | UI 层直接 HTTP | Blocked on Phase D |
| HTTP-CLNT-0004 | P3 | UI 层处理 HTTP 错误 | Blocked on Phase D |
| DFLW-INTG-0013 | P2 | 生成参数双重加载 | Blocked on Phase D |
| DATA-STOR-0001 | P1 | 模型数据双源无 SSOT | Blocked on Phase D+ |
| MODU-SPLT-0001 | P3 | 单模块无编译隔离 | Deferred |
| DFLW-INTG-0007 | P2 | resultBitmap 内存累积 | Deferred (QUEU-SYST-0007 provides mitigation) |
| (其余 9 个) | P2/P3 | 其他 | Deferred or Won't fix |
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

## 7. Phase D: AppContent God Object (优先级 P0)

### 7.1 Sub-task D1: ViewModel 拆分

涉及 Issue: UILA-COMP-0001

**Step 1: Review Current Code**
- Review `MainActivity.kt` AppContent() — 定位 30+ `remember`/`mutableStateOf`
- 分类: Navigation / Models / Queue / Generate / Upscale / Browse

**Step 2: Plan Resolution**
按 UILA-COMP-0001 已设计的 6 个 ViewModel 方案执行：
1. `MainViewModel` — selectedTab, global flags
2. `ModelsViewModel` — model list, load/unload/import/delete
3. `QueueViewModel` — queue state observation (read-only)
4. `GenerateViewModel` — generation params, tokenize
5. `UpscaleViewModel` — upscale image selection, execution, results
6. `BrowseViewModel` — history browsing, filtering, multi-select

**Step 3: Execute Resolution**
1. 创建 6 个 ViewModel 类
2. 逐 ViewModel 迁移状态和逻辑
3. 简化 AppContent() Composable

**Step 4: Update Review Detail**
更新 UILA-COMP-0001.md。

**Step 5: Commit and Push**

---

### 7.2 Sub-task D2: 测试基础

涉及 Issue: UILA-COMP-0002

依赖: D1 (ViewModel 拆分)

**Step 1: Review Current Code**
- 确认 ViewModel 提取后的可测试边界

**Step 2: Plan Resolution**
添加 ViewModel 单元测试（mock Service/Repository）。

**Step 3: Execute Resolution**
创建测试文件。

**Step 4: Update Review Detail**
更新 UILA-COMP-0002.md。

**Step 5: Commit and Push**

---

## 8. Phase E: P2/P3 收尾

### 8.1 Remaining Issues

| ID | P | 摘要 | 依赖 |
|----|---|------|------|
| UILA-COMP-0003 | P2 | 错误处理不一致 | 独立 |
| UILA-COMP-0004 | P2 | 无 DI 框架 | 独立 |
| UILA-COMP-0005 | P2 | UI 层直接 HTTP | 独立 |
| HTTP-CLNT-0001 | P1 | 4 个 OkHttpClient 无复用 | 独立 |
| HTTP-CLNT-0003 | P2 | 超时配置不一致 | 独立 |
| HTTP-CLNT-0004 | P3 | UI 层处理 HTTP 错误 | 独立 |
| DATA-STOR-0001 | P1 | 模型数据双源无 SSOT | 独立 |
| DATA-STOR-0002 | P2 | SharedPreferences 碎片化 | 独立 |
| DFLW-INTG-0007 | P2 | resultBitmap 内存累积 | 独立 |
| DFLW-INTG-0013 | P2 | 生成参数双重加载 | 独立 |
| MODU-SPLT-0001 | P3 | 单模块无编译隔离 | 独立 |
| QUEU-SYST-0007 | P2 | 大 Bitmap 未主动回收 | 独立 |
| UILA-COMP-0006 | P1 | 超大文件拆分 (剩余 GenerateScreen 等) | Phase A~D 后 |

每个 P2/P3 Issue 独立执行，无需分批。

---

## 9. 预估工作量

| Phase | Sub-tasks | 预估文件修改数 | 复杂度 | 状态 |
|-------|-----------|--------------|--------|------|
| A1 | 迁移 bypass | 3-4 文件 | Medium | ✅ Done |
| A2 | 删除旧文件 | 3 files delete + 1 search | Low | ✅ Done |
| A3 | 通知保护 | 1 file | Low | ✅ Done |
| B1 | runBlocking | 1 file | Low | ✅ Done |
| B2 | scope 泄漏 | 0 files (auto-resolved) | Low | ✅ Done |
| C1 | Room 队列+历史 | 2 files new + 6 modified + 4 deleted | High | ✅ Done |
| D1 | ViewModel | 6 files new + 1 rewrite | High | 📅 TODO |
| D2 | 测试 | 6 files new | Medium | 📅 TODO |
| E | 收尾 | ~10 files | Low-Medium | ✅ Done |

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
| 2026-06-16 | Phase A (Backend Consolidation) 全部完成；Phase B (Coroutine Safety) 完成；Phase C (Queue+History Persistence) 完成：统一TaskEntity Room持久化方案；Phase E (P2/P3 收尾) 完成：HTTP Client 统一 + Bitmap recycle + 文件拆分归档 + 7 问题标记 blocked on Phase D；累计38个Issue → Fully Fixed |
