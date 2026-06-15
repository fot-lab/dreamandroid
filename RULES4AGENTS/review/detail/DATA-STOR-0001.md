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

## 修复方案


Room 作为唯一数据源，文件系统仅作存储位置：

```kotlin
@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val modelId: String,
    val name: String,
    val type: ModelType,
    val filePath: String,
    val sizeBytes: Long,
    val downloaded: Boolean
)

class ModelRepository(db: AppDatabase) {
    suspend fun deleteModel(modelId: String) {
        db.withTransaction {
            modelDao.delete(modelId)
            historyDao.clearForModel(modelId)  // 同一事务
        }
        File(modelsDir, modelId).deleteRecursively()  // 事务成功后再清理
    }
}
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-13 | 初始发现 |
| 2026-06-15 | 方案设计完成，待 Room 集成 |
