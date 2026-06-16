# UILA-COMP-0006: 超大 Kotlin 文件拆分 (ModelListScreen + ModelRunScreen + 其他)

> 优先级: P1
> 创建日期: 2026-06-15

## 描述

全项目 75 个 `.kt` 文件共 24,673 行。其中超大文件严重阻碍 Agent 理解和修改：

| 文件 | 行数 | 说明 |
|------|------|------|
| `ui/screens/ModelRunScreen.kt` | 4704 | 占总量 19.1%，最大单文件 |
| `ui/screens/ModelListScreen.kt` | 3706 | 占总量 15.0% |
| `ui/screens/GenerateScreen.kt` | 955 | |
| `data/TagAutocompleteRepository.kt` | 891 | |
| `ui/screens/InpaintScreen.kt` | 816 | |
| `data/Model.kt` | 769 | |
| `ui/screens/BrowseScreen.kt` | 688 | |
| `ui/components/PromptTagTextField.kt` | 684 | |
| `service/BackgroundGenerationService.kt` | 665 | |
| `ui/screens/HistoryFilterUI.kt` | 643 | |
| `ui/orchestrator/AppContent.kt` | 627 | |
| `ui/screens/UpscaleScreen.kt` | 543 | |
| `ui/screens/QueueScreen.kt` | 514 | |

## 根因

1. 历史债务：功能迭代中所有逻辑堆砌在单一 Composable 文件内
2. `ModelRunScreen` 和 `ModelListScreen` 混合了状态管理、UI 渲染、转换逻辑、对话框、后端交互

## 拆分目标

每个文件 ≤ 500 行，按功能域隔离，Agent 可在 1 次上下文中完整理解单文件。

## 执行进度

### ✅ Phase 1: MainActivity 拆分 (已完成)

- 拆分为 4 模块 9 文件（orchestrator/backend/queue/frontend）
- `MainActivity.kt`: 1825 → 108 行 (-94%)
- 已 commit: `67166b5`

### ✅ Phase 2: ModelListScreen 拆分 (已完成)

**拆分结果** (1 主文件 + 5 子文件):

| 文件 | 行数 | 内容 |
|------|------|------|
| `ModelListScreen.kt` | 1643 (原3707) | 主 Composable + 状态管理 + Settings 抽屉 |
| `model/ModelUtils.kt` | 63 | `ExtractByteProgress`, `LoRAFile`, `formatBytes`, `formatFileSize`, `getFileNameFromUri`, `getCleanFileName` |
| `model/ModelConversion.kt` | 427 | `convertCustomModel`, `extractNpuModel`, `importEmbedding`, `CountingInputStream` |
| `model/ModelComponents.kt` | 358 | `ModelCard`, `TabPageIndicator`, `AddCustomModelButton`, `AddCustomNpuModelButton`, `DeleteConfirmDialog`, `InfoChip` |
| `model/ModelSettings.kt` | 264 | `AppearanceSection`, `SettingNavCard`, `SwitchSettingRow`, `ThemeSwatch` |
| `model/ModelDialogs.kt` | 1023 | `FileManagerDialog`, `CustomNpuModelDialog`, `CustomUpscaleModelDialog`, `CustomModelDialog`, `EmbeddingManagerDialog` |

- `ModelListScreen.kt`: 3707 → **1643 行** (-56%)
- 总代码量: 3707 → 3778 (+71 行 for import boilerplate)
- Lint: **0 errors**

> **注意**: `ModelListScreen.kt` 仍含内联 Settings 抽屉 (~700行)，可进一步提取但涉及大量 `scope`/`snackbarHostState` 闭包传递，建议后续迭代处理。

### ✅ Phase 3: ModelRunScreen 拆分 (已完成)

参见 [UILA-COMP-0007](UILA-COMP-0007.md) 详细分析。

**第一阶段** (1 主文件 + 3 子文件):

| 文件 | 行数 | 内容 |
|------|------|------|
| `ModelRunScreen.kt` | 4470 (原4704) | 主 Composable + 全部状态 + 内嵌页面/对话框 |
| `run/ModelRunUtils.kt` | 99 | 工具函数 + GenerationParameters + 常量 |
| `run/ModelRunBackend.kt` | 125 | 后端生命周期帮助函数 |
| `run/ModelRunSatellites.kt` | 167 | UpscalerSelectDialog, UpscalerModelCard, PromptCountLabel |

- Lint: **0 errors**
- 进一步拆分受限于 UILA-COMP-0002 (无 ViewModel/StateHolder)

### ✅ Phase 4: ModelRunScreen 深度拆分 (已完成)

参见 [UILA-COMP-0007](UILA-COMP-0007.md) §6 详细结果。

**第二阶段** — 引入 `ModelRunState` @Stable 状态持有类 + 全面提取:

| 文件 | 行数 | 内容 |
|------|------|------|
| `ModelRunScreen.kt` | 610 (原4471) | 编排器: 状态实例化 + Scaffold + Pager + Dialog 调用 |
| `run/ModelRunState.kt` | 179 | @Stable 状态持有类 (~60 mutableStateOf) |
| `run/ModelRunPrompt.kt` | 673 | ModelRunPromptPage() + AdvancedSettingsDialog() |
| `run/ModelRunResult.kt` | 233 | ModelRunResultPage() |
| `run/ModelRunHistory.kt` | 226 | ModelRunHistoryPage() + HistoryFilterBar() |
| `run/ModelRunGeneration.kt` | 627 | 生成逻辑 (saveAllFields, handleServiceState, startBatchGeneration, crop/inpaint, cleanup) |
| `run/ModelRunDialogs.kt` | 673 | 所有对话框 (含原 Satellites 合并) |
| `run/ModelRunUtils.kt` | 99 | (保留) 工具函数 |
| `run/ModelRunBackend.kt` | 125 | (保留) 后端生命周期 |
| `run/ModelRunSatellites.kt` | **删除** | 合并入 Dialogs |

- `ModelRunScreen.kt`: 4471 → **610 行 (-86%)**
- ModelRun 模块总代码: 4862 → **3445 行 (-29%)**
- Lint: **0 errors**

### ⏳ Phase 5: 其他大型文件 (低优先级，待后续)

**待拆分文件** (按行数):
| 文件 | 行数 | 优先级 |
|------|------|--------|
| `GenerateScreen.kt` | 955 | P2 (依赖 ViewModel) |
| `TagAutocompleteRepository.kt` | 891 | P2 (独立) |
| `InpaintScreen.kt` | 816 | P2 (独立) |
| `BrowseScreen.kt` | 688 | P3 (独立) |

Phase 5 不需要另开 UILA-COMP issue，后续在 Phase D (ViewModel) 完成后自然推进。

## 变更历史

| 日期 | 变更 |
|------|------|
| 2026-06-15 | 创建：记录全项目 Kotlin 文件行数统计 + 拆分执行进度 |
| 2026-06-15 | Phase 1 (MainActivity) 完成；Phase 2 (ModelListScreen) 创建 model/ 子包 5 文件 |
| 2026-06-15 | Phase 2 完成：ModelListScreen.kt 重写为 1643 行，引用 model.* 包，lint 0 errors |
| 2026-06-16 | Phase E 更新：Phase 1-4 全部完成；Phase 5 低优先级推迟至 Phase D+ |
