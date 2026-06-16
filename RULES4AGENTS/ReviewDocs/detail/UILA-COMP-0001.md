# UILA-COMP-0001: God Object — AppContent()

| 属性 | 值 |
|------|-----|
| 优先级 | P0 |
| 分类 | UI Layer |
| 关联 | UILA-COMP-0002, UILA-COMP-0004, UILA-COMP-0005 |

## 问题描述

`AppContent()` 是一个 ~1800 行的 Composable 函数，包含：

```
AppContent()
├── UI 状态管理 (selectedTab, selectedModelId, 全部gen参数...)
├── 队列处理循环 (LaunchedEffect)
├── 模型加载/卸载/导入 (loadModel, unloadModel, convertCustomModel...)
├── 对话框/删除/重命名 (showRenameDialog, showDeleteConfirm)
├── 参数持久化 (LaunchedEffect 读 GenerationPreferences)
└── 后端重启逻辑
```

30+ `remember`/`mutableStateOf` 散落，无 ViewModel。

## 涉及文件

- `MainActivity.kt` — AppContent()

## 修复方案


**ViewModel 拆分建议：**

| ViewModel | 管理内容 |
|-----------|---------|
| `MainViewModel` | 导航状态、全局标志 |
| `ModelsViewModel` | 模型列表、加载/卸载/导入/删除 |
| `QueueViewModel` | 队列状态观察（只读） |
| `GenerateViewModel` | 生成参数、tokenize 调用 |
| `UpscaleViewModel` | 超分图片选择、执行、结果 |
| `BrowseViewModel` | 历史记录浏览、筛选、多选 |

## 执行结果

**Phase D (2026-06-16)**: AppContent God Object 已完全拆解为 6 个 AndroidX ViewModel：

| ViewModel | 文件 | 行数 | 管理内容 |
|-----------|------|------|---------|
| `MainViewModel` | `ui/viewmodel/MainViewModel.kt` | 20 | selectedTab, showNoModelWarning |
| `ModelsViewModel` | `ui/viewmodel/ModelsViewModel.kt` | 210 | model CRUD, load/unload, import/rename/delete, upscaler |
| `QueueViewModel` | `ui/viewmodel/QueueViewModel.kt` | 55 | queue observation, auto-start, WorkManager logging |
| `GenerateViewModel` | `ui/viewmodel/GenerateViewModel.kt` | 130 | generation params, tokenize, addToQueue, prefs load/save |
| `UpscaleViewModel` | `ui/viewmodel/UpscaleViewModel.kt` | 150 | image selection, upscale execution, bitmap lifecycle |
| `BrowseViewModel` | `ui/viewmodel/BrowseViewModel.kt` | 180 | history browsing, selection mode, batch operations |

**关键变更**:
- `AppContent.kt`: 570→310 行 (-46%) — 纯编排器，状态委托给 ViewModels
- `AppContentState.kt`: @Stable 类已退役（状态移入 ViewModels）
- `BrowseScreen.kt`: 使用 `BrowseViewModel` 替代 10+ 内部 `mutableStateOf`
- `UpscaleScreen.kt`: 使用 `UpscaleViewModel`，`onCleared()` 管理 bitmap 生命周期
- 新增依赖: `androidx.lifecycle:lifecycle-viewmodel-compose`

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-13 | 初始发现 |
| 2026-06-15 | ViewModel 拆分方案设计完成，待实施 |
| 2026-06-16 | Phase D 完成：6 个 ViewModel 创建，AppContent 精简 46%，UpscaleScreen/BrowseScreen 重构 |
