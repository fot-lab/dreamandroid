# UILA-COMP-0007: ModelRunScreen 遗留代码分析与拆分

> 优先级: P1
> 创建日期: 2026-06-15
> 父问题: UILA-COMP-0006 (Phase 3: ModelRunScreen 拆分)

## 1. 功能清单 (4704 行)

### 1.1 顶层工具函数 (lines 234-296, ~60行)

| 函数 | 行 | 用途 |
|------|-----|------|
| `checkStoragePermission()` | 234 | Android 存储权限检查 |
| `computeAspectTargetSize()` | 250 | SDXL 非1:1 比例裁剪区域计算 (C++ 后端需要 1024 固定 canvas) |
| `inferAspectRatioString()` | 270 | GCD 约分宽高比，用于 reproduce/import 路径 |
| `padBitmapToCanvas()` | 287 | 居中位图到 1024x1024 canvas，填充黑边 |
| `GenerationParameters` | 299 | 不可变参数快照，用于 History 持久化和参数分享 |

### 1.2 ModelRunScreen 主 Composable (lines 318-4555, ~4238行)

| 区域 | 行范围 | ~行数 | 功能 |
|------|--------|-------|------|
| 状态声明 | 319-530 | 210 | **~170 个状态变量** (generation, prompt, history, inpaint, upscale, pref, etc.) |
| Prompt 管理 | 531-960 | 430 | Tag autocomplete, 嵌入建议, undo/redo, 字段同步 |
| 图像处理 | 962-1198 | 236 | Crop/Inpaint 完成处理, img2img bitmap 缩放/编码 |
| 图像选择器 | 1200-1250 | 50 | PhotoPicker + ContentPicker 启动器 |
| 图像保存 (stitch) | 1252-1316 | 64 | handleSaveImage() — Inpaint 结果拼回原图 |
| 后端生命周期 (LEGACY) | 1318-1467 | 150 | cleanup(), handleExit(), DisposableEffects |
| 生成状态处理 | 1470-1593 | 123 | 监听 BackgroundGenerationService 状态 |
| 对话框: 退出/OpenCL/分辨率/重置 | 1597-1805 | 208 | Exit, OpenCL Warning, Resolution Change, Reset 对话框 |
| PromptPage() | 1899-2973 | 1074 | 提示词输入 + 高级设置 (steps/cfg/scheduler/aspect/seed/batch) + 生成按钮 + 进度条 + img2img UI |
| 生成触发 (LEGACY) | 2547-2708 | 161 | 直接启动 BackgroundGenerationService, 含重试/超时/批量循环 |
| ResultPage() | 2975-3365 | 390 | 结果展示 + 保存/upscale/report + params card + 历史缩略图 |
| HistoryPage() | 3367-3648 | 281 | 历史网格 + 多选模式 + 批量操作浮动栏 |
| Scaffold + Pager | 3650-3765 | 115 | LargeTopAppBar + TabRow (Prompt/Result/History) + HorizontalPager |
| 浮层: Crop/Inpaint/Zoom | 3766-3812 | 46 | CropImageScreen, InpaintScreen, ZoomableImageOverlay |
| Upscaler Dialog | 3814-3973 | 159 | Upscaler 选择对话框 + 下载状态管理 |
| BlockingProgressOverlay | 3976-3992 | 16 | 后端加载/upscale 进行中遮罩 |
| History 对话框 | 3994-4387 | 393 | Filter sheet, Detail dialog, Parameters dialog, Reproduce, Delete, Batch save/delete |
| Share/Import 对话框 | 4389-4554 | 165 | ShareParametersDialog + ImportParametersDialog + 剪贴板检测 |

### 1.3 附属 Composable (lines 4557-4704, ~147行)

| Composable | 行 | 用途 |
|------------|-----|------|
| `UpscalerSelectDialog()` | 4557 | Upscaler 模型选择对话框 |
| `UpscalerModelCard()` | 4603 | Upscaler 模型卡片 (含下载/进度) |
| `PromptCountLabel()` | 4686 | Token 计数标签 |

## 2. 遗留代码分析

### 2.1 对照 PRDReqDoc 发现的问题

根据 PrdReqDoc §17.3-§17.4, 后端生命周期应由 **BackendManager** 统一管理, 生成任务应由 **QueueController → GenerationWorker** 处理:

| PrdReqDoc 规范 | ModelRunScreen 实际行为 | 偏差 |
|----------------|------------------------|------|
| BackendManager.startDiffusion() | 直接 `context.startForegroundService(BackendService::class)` | 绕过 BackendManager |
| BackendManager.stop() | 直接 `context.stopService()` + `ACTION_STOP` 广播 | 绕过统一停止 |
| QueueController.start() → GenerationWorker | 自行实现生成循环 (batch+retry+timeout) | 绕过队列 |
| BackendManager.generate() | 通过 `BackgroundGenerationService` Intent 传参 | 绕过 API 网关 |

### 2.2 已记录的 bypass 问题

这些 bypass 已被 index.md 中的 BKLC-BPAS 系列记录:
- BKLC-BPAS-0001: startForegroundService 绕过 BackendManager
- BKLC-BPAS-0002: ACTION_RESTART 绕过
- BKLC-BPAS-0003: cleanup() stopService 绕过
- BKLC-BPAS-0004: BackgroundGenerationService 绕过

### 2.3 遗留原因分析

ModelRunScreen 的 bypass 路径是**有意为之**的"交互式生成"模式:
- 用户从 ModelListScreen 点击模型后进入 ModelRunScreen，期望的是**即时交互式生成**
- 需要实时进度、中间预览、批量重试
- 这是传统的"打开模型 → 输入 prompt → 立即生成"路径
- Queue 路径 (Generate Tab → Add to Queue → Worker) 适用于批量任务

**结论**: `BackgroundGenerationService` + `BackendService` 是 LEGACY 但仍在用的兼容路径。代码不可删除, 但应清晰标注并与新 BackendManager 路径区分。

## 3. 拆分方案

### 3.1 目标文件结构

```
ui/screens/run/
├── ModelRunUtils.kt          ~80行  顶层工具函数 + GenerationParameters
├── ModelRunPrompt.kt         ~550行 Prompt 管理 (undo/redo, tag autocomplete, 嵌入建议)
├── ModelRunImage.kt          ~400行 图像处理 (crop, inpaint, img2img, stitch save)
├── ModelRunBackend.kt        ~200行 后端生命周期 (cleanup, handleExit, health check)
├── ModelRunGeneration.kt     ~400行 生成逻辑 (生成触发, batch loop, 生成状态处理)
├── ModelRunDialogs.kt        ~1100行 所有对话框 (exit, aspect, upscaler, history, share/import)
├── ModelRunPages.kt          ~1800行 3 个页面 Composables (PromptPage, ResultPage, HistoryPage)
```

`ModelRunScreen.kt` 重建为 ~550 行: 主 Composable + 状态声明 + Scaffold + 页面调度。

### 3.2 详细文件分配

**`run/ModelRunUtils.kt`**:
- `HISTORY_LIMIT`, `HISTORY_COALESCE_MS` 常量
- `checkStoragePermission()`
- `computeAspectTargetSize()`
- `inferAspectRatioString()`
- `padBitmapToCanvas()`
- `GenerationParameters` data class

**`run/ModelRunPrompt.kt`**:
- `embeddingSuggestionsFor()`
- `pushPromptHistory()`, `updatePromptField()`
- `pushNegativePromptHistory()`, `updateNegativePromptField()`
- `applyPromptSuggestion()`, `applyNegativePromptSuggestion()`
- `runPromptTagAction()`, `runNegativePromptTagAction()`
- `undoPrompt()`, `redoPrompt()`, `undoNegativePrompt()`, `redoNegativePrompt()`
- `PromptCountLabel()` composable

**`run/ModelRunImage.kt`**:
- `processSelectedImage()`
- `handleCropComplete()`
- `handleInpaintComplete()`
- `sendBitmapToImg2img()`
- `clearImg2imgState()`
- `handleSaveImage()`
- `onSelectImageClick()`
- Photo picker launchers

**`run/ModelRunBackend.kt`**:
- `cleanup()`
- `handleExit()`
- Backend lifecycle LaunchedEffects (start, restart, health check)
- Lifecycle observers (ON_DESTROY)
- DisposableEffects
- `isCheckingBackend` health check logic

**`run/ModelRunGeneration.kt`**:
- `saveAllFields()` + 参数变更回调
- `onStepsChange`, `onCfgChange`, `onSizeChange`, `onDenoiseStrengthChange`, `onSeedChange`, `onBatchCountsChange`
- 生成按钮 onClick 逻辑 (batch generation job, retry loop, timeout)
- `generationState` LaunchedEffect (Progress/Complete/Error)

**`run/ModelRunDialogs.kt`**:
- Exit confirm dialog
- OpenCL warning dialog
- Custom aspect ratio dialog
- Resolution change dialog
- Reset confirm dialog
- Upscaler dialog (含 download state 管理)
- History detail dialog (含 ZoomableImageOverlay)
- History parameters dialog
- Reproduce parameters dialog
- Delete history dialog
- Batch save confirm/progress dialogs
- Batch delete dialog
- Share parameters dialog
- Import parameters dialog
- `UpscalerSelectDialog()` (独立 composable)
- `UpscalerModelCard()` (独立 composable)

**`run/ModelRunPages.kt`**:
- `PromptPage()` composable — 完整提示词页面 + 高级设置
- `ResultPage()` composable — 完整结果页面
- `HistoryPage()` composable — 完整历史页面 + 选中模式

**`ModelRunScreen.kt`** (重建):
- 所有状态变量声明 (~170 个)
- `ModelRunScreen` 主 Composable
- Tokenize LaunchedEffects
- 初始化 LaunchedEffect
- `effectiveSize` 计算
- BackHandler
- Scaffold + TabRow + HorizontalPager
- Crop/Inpaint 浮层调度
- BlockingProgressOverlay 调度
- 剪贴板检测 LaunchedEffect
- 按阶段调用提取文件中的组件

### 3.3 预估行数变化

| 文件 | 行数 |
|------|------|
| ModelRunScreen.kt (原) | 4704 |
| ModelRunScreen.kt (新) | ~550 |
| run/ModelRunUtils.kt | ~80 |
| run/ModelRunPrompt.kt | ~550 |
| run/ModelRunImage.kt | ~400 |
| run/ModelRunBackend.kt | ~200 |
| run/ModelRunGeneration.kt | ~400 |
| run/ModelRunDialogs.kt | ~1100 |
| run/ModelRunPages.kt | ~1800 |
| **总计** | ~5080 (+376, import boilerplate) |

## 4. 拆分执行结果

### 实际拆分 (Phase 3)

| 文件 | 行数 | 内容 |
|------|------|------|
| `ModelRunScreen.kt` | 4470 (原4704) | 主 Composable + 全部状态 + 内嵌页面/对话框/生成逻辑 |
| `run/ModelRunUtils.kt` | 99 | 工具函数 + GenerationParameters + 常量 |
| `run/ModelRunBackend.kt` | 125 | 后端生命周期 (cleanup, exit, health check, lifecycle observer) |
| `run/ModelRunSatellites.kt` | 167 | UpscalerSelectDialog, UpscalerModelCard, PromptCountLabel |

- `ModelRunScreen.kt`: 4704 → **4470 行** (-234)
- 提取代码: 391 行
- 总代码量: 4704 → 4861 (+157, import boilerplate)
- Lint: **0 errors**

### 为什么只减少了 234 行

ModelRunScreen 的 ~170 个状态变量和所有 inner functions (PromptPage, ResultPage, HistoryPage, 生成触发, 对话框) 之间紧密耦合。每个 inner function 关闭 30-60 个状态变量。提取它们意味着需要百级参数的 Composable 函数签名，这比内联更难维护。

**提取内容总结**:
- ✅ 顶层工具函数 (checkStoragePermission, computeAspectTargetSize, inferAspectRatioString, padBitmapToCanvas)
- ✅ GenerationParameters data class
- ✅ 史常量 (HISTORY_LIMIT, HISTORY_COALESCE_MS)
- ✅ 3 个独立 Composable (UpscalerSelectDialog, UpscalerModelCard, PromptCountLabel)
- ✅ 后端生命周期帮助函数 (cleanup, handleExit, BackendLifecycleEffects)
- ⚠️ inner @Composable 函数 (PromptPage, ResultPage, HistoryPage) — 未提取 (紧密耦合于状态)
- ⚠️ 对话框 composable — 未提取 (紧密耦合于状态)
- ⚠️ 生成触发逻辑 — 未提取 (紧密耦合于状态)

### 遗留问题

核心瓶颈是 **UILA-COMP-0002** (无法单独测试): 需要引入 ViewModel/StateHolder 模式将状态与 UI 解耦，然后才能进一步拆分页面和对话框。

## 5. Phase 4 — 引入 ModelRunState 状态持有类完成全面拆分

### 5.1 核心思路

Phase 3 只减少了 234 行的根因是 ~170 个状态变量的紧密耦合。Phase 4 引入 `ModelRunState` — 一个 `@Stable` 类，将所有 mutable 状态集中管理：

```kotlin
@Stable
class ModelRunState {
    // Generation state
    var currentBitmap by mutableStateOf<Bitmap?>(null)
    var intermediateBitmap by mutableStateOf<Bitmap?>(null)
    // ... ~60 个属性
}
```

这**不是** AndroidX ViewModel，而是纯 Compose 的 state hoisting 模式。主 Composable 中 `remember { ModelRunState() }` 创建实例，然后将 `state` 传递给提取的 Composable：

```kotlin
@Composable
fun ModelRunScreen(...) {
    val state = remember { ModelRunState() }
    // ...
    PromptPage(state = state, ...)
    ResultPage(state = state, ...)
    HistoryPage(state = state, ...)
}
```

### 5.2 目标文件结构 (Phase 4)

```
ui/screens/run/
├── ModelRunState.kt          ~200行  @Stable 状态持有类 (所有 mutableStateOf)
├── ModelRunUtils.kt          ~99行   (保留) 工具函数 + GenerationParameters
├── ModelRunBackend.kt        ~125行  (保留) 后端生命周期
├── ModelRunSatellites.kt     ~167行  (保留 → 合并到 Dialogs)
├── ModelRunPrompt.kt         ~1074行 PromptPage() Composable
├── ModelRunResult.kt         ~390行  ResultPage() Composable
├── ModelRunHistory.kt        ~281行  HistoryPage() Composable
├── ModelRunGeneration.kt     ~300行  生成触发 + batch loop + 状态观察
├── ModelRunDialogs.kt        ~1100行 所有对话框 (exit/upscaler/history/share/import等)
```

`ModelRunScreen.kt`: **4471 → ~400 行** — 状态实例化 + Scaffold 编排。

### 5.3 预估行数变化

| 文件 | 旧行数 | 新行数 |
|------|--------|--------|
| ModelRunScreen.kt | 4471 | ~400 |
| run/ModelRunState.kt | — | ~200 |
| run/ModelRunPrompt.kt | — | ~1074 |
| run/ModelRunResult.kt | — | ~390 |
| run/ModelRunHistory.kt | — | ~281 |
| run/ModelRunGeneration.kt | — | ~300 |
| run/ModelRunDialogs.kt | — | ~1100 |
| run/ModelRunUtils.kt | 99 | 99 |
| run/ModelRunBackend.kt | 125 | 125 |
| run/ModelRunSatellites.kt | 167 | (合并到 Dialogs, 删除) |
| **总计** | **4862** | **~3969** (-893, 含 import boilerplate) |

### 5.4 关键收益

- `ModelRunScreen.kt` 从 4471 → ~400 行 (**-91%**)
- 每文件职责单一，Agent 可直接阅读目标文件
- 状态持有类提供单一真相源，避免参数爆炸
- 对话框文件可独立测试和修改

## 6. Phase 4 执行结果

### 6.1 实际文件结构

```
ui/screens/run/
├── ModelRunState.kt          179行  @Stable 状态持有类 (~60 mutableStateOf 属性)
├── ModelRunUtils.kt          99行   (保留) 工具函数 + GenerationParameters + 常量
├── ModelRunBackend.kt        125行  (保留) 后端生命周期
├── ModelRunPrompt.kt         673行  ModelRunPromptPage() + AdvancedSettingsDialog()
├── ModelRunResult.kt         233行  ModelRunResultPage()
├── ModelRunHistory.kt        226行  ModelRunHistoryPage() + HistoryFilterBar()
├── ModelRunGeneration.kt     627行  生成逻辑 (saveAllFields, 参数回调, handleServiceState, startBatchGeneration, crop/inpaint, image save, cleanup)
├── ModelRunDialogs.kt        673行  所有对话框 (exit/opencl/resolution/history/share/import/upscaler) + UpscalerSelectDialog + UpscalerModelCard + PromptCountLabel + BlockingOverlays
```

`ModelRunScreen.kt`: **4471 → 610 行 (-86%)** — 状态实例化 + Scaffold 编排 + Dialog 调用。

### 6.2 行数变化

| 文件 | 旧行数 | 新行数 | 变化 |
|------|--------|--------|------|
| ModelRunScreen.kt | 4471 | 610 | -3861 (-86%) |
| run/ModelRunState.kt | — | 179 | +179 |
| run/ModelRunPrompt.kt | — | 673 | +673 |
| run/ModelRunResult.kt | — | 233 | +233 |
| run/ModelRunHistory.kt | — | 226 | +226 |
| run/ModelRunGeneration.kt | — | 627 | +627 |
| run/ModelRunDialogs.kt | — | 673 | +673 |
| run/ModelRunUtils.kt | 99 | 99 | 0 |
| run/ModelRunBackend.kt | 125 | 125 | 0 |
| run/ModelRunSatellites.kt | 167 | **删除** | -167 |
| **总计** | **4862** | **3445** | **-1417 (-29%)** |

### 6.3 Phase 4 vs 原始方案对比

| 指标 | Phase 3 | Phase 4 | 改进 |
|------|---------|---------|------|
| ModelRunScreen 行数 | 4471 | 610 | **-86%** |
| 含状态变量的 Composable | 1 巨大 Composable | 1 编排器 + 8 子文件 | 完全拆分 |
| 测试可行性 (UILA-COMP-0002) | 不可测 | State holder 独立可测 | ✅ |
| Agent 可读性 | 需读 4471 行 | 按功能读 100-700 行文件 | ✅ |
| 状态管理 | ~170 个闭包变量 | 1 个 @Stable 类 | ✅ |

### 6.4 关键设计决策

1. **ModelRunState @Stable 而非 ViewModel**: 纯 Compose 的 state hoisting 模式，无需 AndroidX lifecycle 依赖。所有提取的 Composable 通过 `state: ModelRunState` 参数访问状态，避免百参数签名。
2. **Generation 提取为 top-level 函数而非 Composable**: 生成逻辑 (batch loop + retry/timeout) 本质是 suspend 函数，放在 coroutine scope 上更合适。
3. **Dialog 合并**: `ModelRunSatellites.kt` 的 3 个 Composable (UpscalerSelectDialog, UpscalerModelCard, PromptCountLabel) 合并入 `ModelRunDialogs.kt`，统一对话框入口。
4. **保留 bypass 路径**: `BackgroundGenerationService` + `BackendService` 的 LEGACY 兼容路径完整保留，标注为"交互式生成"模式。

## 7. 变更历史

| 日期 | 变更 |
|------|------|
| 2026-06-15 | 创建: ModelRunScreen 功能分析和拆分方案 |
| 2026-06-15 | Phase 3 执行: 提取 3 文件 (Utils/Backend/Satellites), 共 391 行, lint 0 errors |
| 2026-06-15 | Phase 4 设计: ModelRunState 状态持有类方案，目标 -91% 主文件行数 |
| 2026-06-15 | Phase 4 执行: 创建 6 新文件 (State/Prompt/Result/History/Generation/Dialogs), 删除 Satellites, ModelRunScreen 4471→610 (-86%), 总代码 -1417 行 |
| 2026-06-16 | Phase A/B/C 完成：bypass 路径全部迁移至 BackendManager, 旧 BackendService/UpscaleBackendManager/BackgroundGenerationService 已删除 → ✅ Fully Fixed |
