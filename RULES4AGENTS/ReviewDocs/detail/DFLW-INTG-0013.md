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

## 当前状态 (Phase E)

依赖 ViewModel 拆分 (Phase D / UILA-COMP-0001)。移除 `MainActivity`/`AppContent` 的双重加载需要 `GenerateViewModel` 作为单一加载点。

**阻塞**: Blocked on Phase D (AppContent ViewModel extraction)

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 方案设计完成，待 ViewModel 迁移 → 📅 TODO |
| 2026-06-16 | Phase E: 标记 blocked on Phase D (GenerateViewModel) |
