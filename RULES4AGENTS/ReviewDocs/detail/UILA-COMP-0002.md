# UILA-COMP-0002: 无法单独测试

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | UI Layer |
| 关联 | UILA-COMP-0001, UILA-COMP-0004 |

## 问题描述

所有业务逻辑耦合在 Composable 生命周期中，无法对 ViewModel/Repository/Service 进行独立单元测试。

## 涉及文件

- `MainActivity.kt`
- `ui/screens/*.kt`

## 修复方案

引入 ViewModel 层（参见 UILA-COMP-0001），业务逻辑从 Composable 移入 ViewModel：

```
UI (Composable) → ViewModel → Service → Backend
     ↑ 可单测        ↑ 可单测
```

配合 DI (UILA-COMP-0004)，可通过 mock 进行 ViewModel 单元测试。

## 执行结果

**Phase D (2026-06-16)**: ViewModel 拆分完成（UILA-COMP-0001），业务逻辑已从 Composable 移入 ViewModel，形成可测试分层架构：

```
UI (Composable) → ViewModel → Service → Backend
     ↑ 可单测        ↑ 可单测
```

6 个 ViewModel 均可通过 mock `DreamAndroidApplication` 依赖进行独立单元测试：
- `MainViewModel` — 纯状态，无外部依赖
- `ModelsViewModel` — 可通过 mock `BackendManager` / `ModelRepository` 测试
- `QueueViewModel` — 可通过 mock `QueueRepository` 测试
- `GenerateViewModel` — 可通过 mock `BackendManager` / `GenerationPreferences` 测试
- `UpscaleViewModel` — 可通过 mock `performUpscale` 测试
- `BrowseViewModel` — 可通过 mock `HistoryManager` 测试

**D2 测试文件**: 基础测试结构已就绪 (`androidTestImplementation` 已配置)，具体测试用例留待后续补充。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-13 | 初始发现 |
| 2026-06-15 | 依赖 ViewModel 拆分 (UILA-COMP-0001) 和 DI (UILA-COMP-0004) |
| 2026-06-16 | Phase D 完成：6 个 ViewModel 使分层测试成为可能 |
