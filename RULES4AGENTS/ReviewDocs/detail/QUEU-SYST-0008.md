# QUEU-SYST-0008: QueueProcessingService 与 GenerationWorker 代码重复

| 属性 | 值 |
|------|-----|
| 优先级 | P2 |
| 分类 | Queue — System |
| 状态 | Resolution Planned |
| 关联 | QUEU-SYST-0001, BKND-LFCY-0001 |

## 问题描述

`QueueProcessingService`（Android 前台 Service）和 `GenerationWorker`（WorkManager CoroutineWorker）实现了**几乎完全相同的队列处理循环**，存在显著代码重复：

两者都实现：
1. `while` 循环: `getNextPending()` → `markProcessing()` → `waitForBackend()` → `generate()` → 保存
2. 相同的 SSE 事件处理: `Progress` / `Complete` / `Error`
3. 相同的 `waitForBackend()` 逻辑（3 秒轮询 `/health`）
4. 相同的异常处理: 重置为 PENDING
5. 相同的 base64 → Bitmap 解码
6. 相同的 `HistoryManager.saveGeneratedImage()` 保存逻辑

代码注释明确标注了策略一致性（"Aligned with GenerationWorker"）。

## 差异对比

| 方面 | GenerationWorker | QueueProcessingService |
|------|------------------|------------------------|
| 框架 | WorkManager `CoroutineWorker` | Android `Service`（前台） |
| 生命周期 | 进程重启后仍存活 | 仅进程内 |
| 进度 API | WorkManager `setProgress()` | 自定义 `_currentProgress` StateFlow |
| 位图存储 | 缓存文件路径 | 直接传递 Bitmap |
| 取消 | `WorkManager.cancelAllWorkByTag()` | `ACTION_STOP` Intent |

## 涉及文件

- `service/queue/GenerationWorker.kt`
- `service/queue/QueueProcessingService.kt`

## 当前状态

**Resolution Planned — 计划已就绪，暂不执行。**

两条路径均保留，主路径为 `GenerationWorker`。`QueueProcessingService` 在 AndroidManifest 中注册但当前代码路径偏向 `GenerationWorker`（通过 `QueueController`）。

两条路径在当前架构下均工作正常，不存在功能性缺陷。代码重复属结构性改善范畴。

## 触发条件

满足以下**任一条件**时方可执行解决：

1. **明确指示**：用户明确指示"解决 QUEU-SYST-0008 双路径代码重复"
2. **强制场景**：遇到以下场景时必须解决：
   - 需要修改队列处理核心逻辑且修改两处导致 bug
   - 两条路径因维护不善出现行为分歧
   - QueueProcessingService 在新 Android 版本中触发兼容性问题
   - WorkManager 行为变更导致 GenerationWorker 不再适合当前需求

## 修复方案 (待执行)

建议统一为单一队列处理器：
- 提取公共处理逻辑到独立类（如 `QueueTaskProcessor`）
- 保留 `GenerationWorker` 作为唯一路径，删除 `QueueProcessingService`
- 或反之，取决于 WorkManager vs Android Service 的运行时行为需求

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-16 | 初始发现。BackendService 调查中确认双路径存在，用户决定暂时保留两条路径 |
| 2026-06-16 | 明确决策：标记 Resolution Planned，暂不解决。等待明确指示或强制场景触发 |
