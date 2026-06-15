# STAT-REVW-0001: 静态审查发现

| 属性 | 值 |
|------|-----|
| 分类 | Static Review |
| 对应章节 | §14 |
| 依赖 | ARCH-OVER-0001 |

> 2026-06-15 对 WorkManager 迁移代码的静态审查。已修复问题标记为 ✅。

| ID | 严重度 | 文件 | 问题 | 状态 |
|----|--------|------|------|------|
| **SR-1** | 严重 | `GenerationWorker.kt` | `CancellationException` 未导入 | ✅ |
| **SR-2** | 严重 | `QueueRepository.kt` | `_tasks.value = _tasks.value + newTasks` 非原子 | ✅ |
| **SR-3** | 中等 | `QueueController.kt` | doc 注释错误 | ✅ |
| **SR-4** | 中等 | `GenerationWorker.kt` + `QueueProcessingService.kt` | 重复 `processLoop()` 逻辑 | ✅ |
| **SR-5** | 中等 | `QueueRepository.kt` | Bitmap 无后续释放机制 | 📋 |
| **SR-6** | 中等 | `app/proguard-rules.pro` | WorkManager 反射可能被混淆 | 📋 |
| **SR-7** | 低 | `AndroidManifest.xml` | 移除 `requestLegacyExternalStorage` | ✅ |
| **SR-8** | 严重 | `GenerationWorker.kt` | health check 失败标记 ERROR 不合理 | ✅ |
| **SR-9** | 严重 | `GenerationWorker.kt` | 后端崩溃标记为永久错误 | ✅ |
| **SR-10** | 中等 | `GenerationWorker.kt` | `doWork()` 不区分 `CancellationException` | ✅ |

> 已修复项总结: SR-1~SR-3 为代码质量修复；SR-4/SR-8/SR-9/SR-10 为 v3.1 架构修正。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §14 内容，创建独立文件 |
