# DFLW-INTG-0010: processingActive 双重数据源

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Data Flow Integrity |
| 关联 | DFLW-INTG-0011 |

## 问题描述

`processingActive` 状态被两个数据源写入：
- `MainActivity` — UI 层写入
- `GenerationWorker` — Worker 写入

双重写入可能导致状态不一致。

## 涉及文件

- `MainActivity.kt`
- `service/queue/GenerationWorker.kt`

## 修复方案

MainActivity 不再写入 `processingActive`，唯一写入者为 `GenerationWorker`。UI 层只读观察。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | MainActivity 移除写入 → 单一写入者 → ✅ Fixed |
