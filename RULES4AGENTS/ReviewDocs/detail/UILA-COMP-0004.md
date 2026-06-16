# UILA-COMP-0004: 无 DI 框架

| 属性 | 值 |
|------|-----|
| 优先级 | P2 |
| 分类 | UI Layer |
| 关联 | UILA-COMP-0001, UILA-COMP-0002 |

## 问题描述

无正式依赖注入框架 (Hilt/Koin)，依赖通过手动传递或 Application 单例获取。

## 当前进展

`DreamAndroidApplication` 统一持有核心依赖：

```kotlin
class DreamAndroidApplication : Application() {
    lateinit var backendManager: BackendManager
    lateinit var queueRepository: QueueRepository
    lateinit var historyManager: HistoryManager
}
```

但无正式 DI 框架，仍依赖 `(context.applicationContext as DreamAndroidApplication).xxx` 获取。

## 涉及文件

- `DreamAndroidApplication.kt`
- `MainActivity.kt`
- `ui/screens/*.kt`

## 修复方案

推荐引入 Hilt 或 Koin：
- **Hilt**: 编译期 DI，性能好，Google 官方推荐
- **Koin**: 运行时 DI，配置简单，Kotlin 原生

配合 ViewModel 拆分 (UILA-COMP-0001)，通过构造函数注入。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | Application 统一持有核心依赖 → 🔧 Partial |
| 2026-06-16 | Phase E 评估：DI 框架引入依赖 ViewModel 拆分 (Phase D) → Blocked on Phase D |
