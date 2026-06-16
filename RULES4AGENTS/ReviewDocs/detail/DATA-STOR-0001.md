# DATA-STOR-0001: 模型数据双源无 SSOT

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Data Layer |
| 关联 | QUEU-SYST-0005 |

## 问题描述

模型数据存在两个独立数据源，无 Single Source of Truth：
- 文件系统扫描模型列表
- Room 存历史记录

删除时二者无事务一致性 → 可能产生孤儿记录或垃圾文件。

## 涉及文件

- `data/Model.kt`
- `data/HistoryManager.kt`
- Room DAO/Entities

## 当前状态 (Phase E)

模型数据存在两个独立数据源：文件系统扫描 + Room (History/Queue via TaskEntity)。

**修复方案** (已有设计): 引入 `ModelEntity` Room 表作为 SSOT，`ModelRepository` 通过 Room 管理模型状态，文件系统仅作存储位置。

**阻塞**: 需要 Phase D 后配合 ViewModel 重构实施。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-13 | 初始发现 |
| 2026-06-15 | 方案设计完成，待 Room 集成 |
| 2026-06-16 | Phase E: 更新状态 → Blocked on Phase D+ (需 ModelEntity Room 表 + ModelRepository 重构) |
