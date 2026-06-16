# DFLW-INTG-0009: RecordRepository 并发写入不安全

| 属性 | 值 |
|------|-----|
| 优先级 | P3 |
| 分类 | Data Flow Integrity |
| 关联 | DFLW-INTG-0008 |

## 问题描述

`RecordRepository` 写入 JSON 文件时不保护并发：多协程同时写入 → 数据交错损坏。

## 涉及文件

- ~~`data/RecordRepository.kt`~~ (已迁移至 Room)

## 修复方案 (2026-06-16 最终方案)

**根本修复**: 随 DFLW-INTG-0008 迁移至 Room。Room 的 WAL 模式原生提供并发安全：
- 并发读 → WAL 允许多读者
- 并发写 → SQLite 内置串行化，无需 Mutex
- 事务 → 完整 ACID 保护

旧方案 (Mutex + 原子写入) 被废弃，RecordRepository 中已无 JSON 文件写入代码。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | Mutex + 原子写入实现 → ✅ Fixed |
| 2026-06-16 | 随 DFLW-INTG-0008 迁移至 Room → 并发安全由 SQLite WAL 原生提供 → ✅ Fully Fixed |
