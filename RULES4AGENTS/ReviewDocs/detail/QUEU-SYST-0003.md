# QUEU-SYST-0003: 静态 companion 共享状态

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Queue Processing |
| 关联 | QUEU-SYST-0001 |

## 问题描述

旧 `BackgroundGenerationService` 使用 `companion object` 维护全局可变状态：

```kotlin
companion object {
    var isRunning = false
    var currentTask: GenerationTask? = null
    val generationState = MutableStateFlow<GenState>(GenState.Idle)
}
```

问题：
- 全局可变，多 Service 实例可能冲突
- 难以测试
- 内存泄漏风险

## 涉及文件

- `service/BackgroundGenerationService.kt` — 旧实现
- `service/queue/QueueProcessingService.kt` — 新实现

## 修复方案

`QueueProcessingService` 使用实例字段替代 companion object：

```kotlin
class QueueProcessingService : Service() {
    private var isRunning = false  // 实例字段
    private var currentTask: GenerationTask? = null
    // ...
}
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | QueueProcessingService 实例字段替代 companion object → ✅ Fixed |
