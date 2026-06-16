# DFLW-INTG-0008: RecordRepository JSON 损坏全丢

| 属性 | 值 |
|------|-----|
| 优先级 | P2 |
| 分类 | Data Flow Integrity |
| 关联 | DFLW-INTG-0009 |

## 问题描述

`RecordRepository` 读取 JSON 文件时，如果 JSON 损坏，会丢弃文件中所有记录（而非部分恢复）。

## 涉及文件

- `data/RecordRepository.kt`
- `data/GenerateParameterRecord.kt`
- `data/db/TaskEntity.kt`
- `data/db/TaskDao.kt`

## 修复方案 (2026-06-16 最终方案)

**根本修复**: 废弃 JSON 文件存储，迁移至 Room (`TaskEntity` 统一表)。

### 为什么 Room 优于 JSON 文件

| 维度 | JSON 文件 (旧) | Room/SQLite (新) |
|------|---------------|-------------------|
| 损坏恢复 | 手工 regex 部分恢复 (脆弱) | WAL journal 自动 crash recovery |
| 并发安全 | Mutex 串行化 | WAL 模式 (并发读 + 串行写) |
| 事务 | tmp+rename (尽力) | 完整 ACID |
| 查询 | 全量读入内存 | 索引 SQL |
| Android 集成 | 原始 File I/O | 原生生命周期感知 |

### 实施内容

1. **`TaskEntity`**: 新增 `TYPE_RECORD = "RECORD"` + `isRecord` 属性
2. **`TaskDao`**: 新增 `observeRecords()` / `getAllRecords()` / `deleteRecordById()` / `deleteAllRecords()`
3. **`GenerateParameterRecord`**: 新增 `toEntity()` / `fromEntity()` — `source` 存入 `tags` JSON 袋
4. **`RecordRepository`**: 完全重写
   - 去除所有 JSON File I/O、Mutex、备份/恢复代码
   - 使用 `AppDatabase.taskDao()` 进行 CRUD
   - `init{}` 中异步从 Room 加载 + 旧 JSON 文件一次性迁移
5. **旧数据迁移**: 检测旧 `generate_records.json` → 导入 Room → rename 为 `.migrated.{ts}`

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 备份 + 逐条恢复机制实现 → ✅ Fixed |
| 2026-06-16 | 根本修复：迁移至 Room TaskEntity 统一表，废弃 JSON 文件 → ✅ Fully Fixed (Room) |
