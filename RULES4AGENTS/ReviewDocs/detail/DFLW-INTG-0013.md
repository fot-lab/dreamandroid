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

## 执行结果

**Phase E5 (2026-06-16)**: 双重加载已消除：
- 删除 GenerateScreen.kt 中 `LaunchedEffect(modelId)` 内的二次偏好加载
- 偏好加载现在单一由 AppContent.kt → GenerateViewModel.loadModelPrefs() 完成
- 当 modelId 切换时，GenerateViewModel 统一加载模型偏好，无竞态窗口

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 方案设计完成，待 ViewModel 迁移 → 📅 TODO |
| 2026-06-16 | Phase E: 标记 blocked on Phase D (GenerateViewModel) |
| 2026-06-16 | Phase E5: 删除 GenerateScreen 重复加载，单一加载点为 GenerateViewModel → Fully Fixed |
