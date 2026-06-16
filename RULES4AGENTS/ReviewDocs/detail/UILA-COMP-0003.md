# UILA-COMP-0003: 错误处理不一致

| 属性 | 值 |
|------|-----|
| 优先级 | P2 |
| 分类 | UI Layer |
| 关联 | UILA-COMP-0001, UILA-COMP-0004, DFLW-INTG-0005, DFLW-INTG-0006 |

## 问题描述

错误处理在应用各处不一致：
- 部分位置用 try-catch 直接处理
- 部分位置依赖 StateFlow Error 状态
- 双队列处理路径 (GenerationWorker vs QueueProcessingService) 错误策略曾不一致

## 当前进展

- `AppError` 密封类已创建 (`core/error/AppError.kt`)
- 双队列处理路径错误策略已统一 (DFLW-INTG-0005, DFLW-INTG-0006)
- UI 层错误展示尚未统一为 `AppError` 模式

## 涉及文件

- `core/error/AppError.kt`
- `ui/screens/*.kt`

## 修复方案

统一使用 `AppError` 密封类：

```kotlin
sealed class AppError {
    data class Network(override val message: String) : AppError()
    data class Backend(override val message: String) : AppError()
    data class Parse(override val message: String) : AppError()
    data class Storage(override val message: String) : AppError()
}
```

ViewModel 暴露 `errorState: StateFlow<AppError?>`，UI 层通过统一错误面板渲染。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | AppError 密封类创建；双路径错误策略统一 → 🔧 Partial |
| 2026-06-16 | Phase E 评估：UI 层错误统一依赖 ViewModel 拆分 (Phase D) → Blocked on Phase D |
| 2026-06-16 | Phase E5: GenerateViewModel 集成 AppError；tokenize 错误通过 `tokenizeError: AppError?` 暴露；UI 层不再静默吞异常 |
