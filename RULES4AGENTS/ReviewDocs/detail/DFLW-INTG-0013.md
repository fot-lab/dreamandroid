# DFLW-INTG-0013: 生成参数双重加载

| 属性 | 值 |
|------|-----|
| 优先级 | P2 |
| 分类 | Data Flow Integrity |
| 关联 | UILA-COMP-0001, DFLW-INTG-0014 |

## 问题描述

生成参数从持久化存储被两处加载：
- `MainActivity` 的 `LaunchedEffect`
- `GenerateScreen` 内部加载

导致参数覆盖/不一致的竞态窗口。

## 涉及文件

- `MainActivity.kt`
- `ui/screens/GenerateScreen.kt`

## 修复方案

配合 ViewModel 拆分 (UILA-COMP-0001)：移除 MainActivity 参数加载 LaunchedEffect，集中到 `GenerateViewModel` 作为单一加载点。

方案设计已完成，待后续 ViewModel 迁移。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 方案设计完成，待 ViewModel 迁移 → 📅 TODO |
