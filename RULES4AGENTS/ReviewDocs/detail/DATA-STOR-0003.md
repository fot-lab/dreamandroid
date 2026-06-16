# DATA-STOR-0003: RecordRepository 在 ViewModel 构造函数中同步触发 Room DB

| 属性 | 值 |
|------|-----|
| 优先级 | P0 |
| 分类 | Data Storage |
| 关联 | CORO-EXEC-0003, BKND-PROC-0007 |

## 问题描述

`QueueViewModel` 构造函数中直接实例化 `RecordRepository(application)`，触发 `AppDatabase.get(context)` 在主线程同步创建/打开 Room 数据库：

```
AppContent() → viewModel<QueueViewModel>() → QueueViewModel(application)
  → RecordRepository(application) → AppDatabase.get(context).taskDao()
  → Room.databaseBuilder(...).build()  // ← 主线程同步 I/O
```

虽然 `AppDatabase.get()` 自身是线程安全的（`@Volatile` + `synchronized`），但如果 Room 数据库迁移失败（v1→v2→v3→v4 多步迁移），`build()` 会抛出运行时异常。由于 `fallbackToDestructiveMigration(dropAllTables = true)` 存在，常规版本不匹配会被兜底；**但如果迁移 SQL 执行失败或文件损坏，异常会直接在主线程抛出，导致应用瞬间崩溃**。

## 涉及文件

- `ui/viewmodel/QueueViewModel.kt:28` — `val recordRepository = RecordRepository(application)`
- `data/RecordRepository.kt:38` — `private val dao = AppDatabase.get(context).taskDao()`
- `data/db/AppDatabase.kt:207-217` — `fun get(context)` — Room builder

## 根因分析

1. **即时构造反模式**: ViewModel 构造函数在 `AppContent()` 首次组装时执行（主线程），此时尚未渲染任何 UI，发生异常 → 崩溃无 UI 提示
2. **不必要的数据源**: `QueueViewModel` 只使用了 `queueRepository`（队列观察+自动启动），`recordRepository` 仅用于「如果用户在 Queue Tab 保存记录到 Records Tab」，而 Records Tab 的 ViewModel 可以独立管理 `RecordRepository`
3. **灾难级失败范围**: 一个次要的 Records 功能失败会阻止整个 App 启动

## 修复方案

**方案 A (推荐) — 移除不必要的依赖**:
从 `QueueViewModel` 中移除 `recordRepository` 字段。Records 保存应由 Generate/Browse ViewModel 独立管理 `RecordRepository`，不与 Queue 耦合。

**方案 B (防御性) — 懒惰 + try-catch**:
```kotlin
val recordRepository: RecordRepository by lazy {
    try { RecordRepository(application) } catch (e: Exception) { /* fallback */ }
}
```

## 初始化未就绪处理检查

| 依赖 | 处理方式 | 安全性 |
|------|----------|--------|
| `AppDatabase.get(context)` | Double-checked locking + `fallbackToDestructiveMigration` | ⚠️ 迁移失败仍会抛异常 |
| RecordRepository.dao | 同步获取，无 try-catch | ❌ 致命 |
| RecordRepository 文件迁移 | `init{}` launch 到协程，有 try-catch | ✅ 安全 |
| 文件不存在时 | `if (!legacyFile.exists()) return` | ✅ 安全 |

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-16 | 初始发现 |
