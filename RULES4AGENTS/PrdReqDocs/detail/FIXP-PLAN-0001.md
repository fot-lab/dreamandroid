# FIXP-PLAN-0001: 架构修复方案设计

| 属性 | 值 |
|------|-----|
| 分类 | Fix Plan |
| 对应章节 | §16 |
| 依赖 | ARCH-OVER-0001, STAT-REVW-0001 |

> 本章为 ArchitectureReview.md §9 中发现的 15 项数据流完整性问题设计修复方案。
> 修复顺序必须按 A→B→C→D→E 执行。

## 16.A 数据完整性修复

### 16.A.1 HistoryManager.save 返回值被忽略 → 任务标记与持久化解耦断裂 (P0)

**根因:** `saveGeneratedImage()` 返回值被丢弃，磁盘满时任务仍标记 COMPLETED。

**解决方案：**
```kotlin
val historyItem = historyManager.saveGeneratedImage(modelId, bitmap, task, mode)
if (historyItem != null) {
    queueRepository.markTaskComplete(task.id, bitmap, event.seed)
} else {
    queueRepository.markTaskError(task.id, AppError.Storage("Failed to save generated image"))
    bitmap.recycle()
}
```

### 16.A.2 文件-DB 写入不一致 (P2)

**根因:** 先写文件后写 DB，文件成功但 DB 失败时留有孤儿文件。

**解决方案:** 先写入 Room → 再写文件 → 文件失败时回滚 Room 记录。

### 16.A.3 RecordRepository JSON 损坏 → 全部记录丢弃 (P2)

**解决方案:** 损坏文件备份（`.corrupted.{timestamp}`）+ 逐条 JSONObject 恢复。

### 16.A.4 RecordRepository 并发写入不安全 (P3)

**解决方案:** `Mutex` 保护 + 原子写入（临时文件 → rename）。

## 16.B 协程与并发修复

### 16.B.1 SseStreamParser: flow{} 内 withContext 反模式 (P1)
### 16.B.2 阻塞读取取消联动 (P1)
### 16.B.3 processingActive 双重数据源 (P1)
### 16.B.4 QueueController.stop() 竞态修复 (P2)

## 16.C 双路径统一

### 16.C.1 Health Check 策略统一 → 均采用 waitForBackend()
### 16.C.2 异常策略统一 → 统一采用 resetTaskToPending()

## 16.D 内存与性能

### 16.D.1 resultBitmap → 文件路径
### 16.E.1 参数双重加载清理
### 16.E.2 静默失败反馈
### 16.E.3 Seed 输入验证

## 执行优先级

```
Phase 2: 数据完整性 (A) → Phase 3: 协程与并发 (B) → Phase 4: 双路径统一 (C) → Phase 5: 内存与UX (D+E) → Phase F: BackendService 中间件
```

### 16.F BackendService HTTP 中间件（Phase F，已完成）

**目标:** UI 层不再直接引用 `BackendManager`，所有 HTTP 通过 `BackendService` 统一代理。

**涉及文件 (10 个):**
- 新建: `service/backend/BackendService.kt`
- 修改: `DreamAndroidApplication.kt`, `GenerateViewModel.kt`, `ModelsViewModel.kt`, `UpscaleViewModel.kt`, `AppContent.kt`, `ModelRunScreen.kt`, `UpscaleScreen.kt`, `ModelRunGeneration.kt`, `ImageUtils.kt`

**架构变化:**
```
旧: UI (Screen/ViewModel) → BackendManager (direct HTTP)
新: UI (Screen/ViewModel) → BackendService (middleware) → BackendManager (implementation)
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §16 内容，创建独立文件 |
| 2026-06-16 | 新增 §16.F: BackendService HTTP 中间件 (Phase F，已完成) |
