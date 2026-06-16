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

**决议 (2026-06-16)**: Won't Fix — Application 单例模式对当前项目规模足够：
- 依赖数量有限 (~5 核心对象)
- ViewModel 通过 `(application as DreamAndroidApplication)` 获取依赖，模式一致
- 引入 Hilt/Koin 增加 KAPT/KSP 编译开销和配置复杂度，P2 优先级下不划算
- 如果未来模块化拆分 (MODU-SPLT-0001)，届时再引入 DI 框架

## 涉及文件

- `DreamAndroidApplication.kt`
- `MainActivity.kt`
- `ui/viewmodel/*.kt`

## 修复方案

推荐引入 Hilt 或 Koin：
- **Hilt**: 编译期 DI，性能好，Google 官方推荐
- **Koin**: 运行时 DI，配置简单，Kotlin 原生

配合 ViewModel 拆分 (UILA-COMP-0001)，通过构造函数注入。

## 状态: Won't Fix (P2 优先级下 acceptable)

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | Application 统一持有核心依赖 → 🔧 Partial |
| 2026-06-16 | Phase E 评估：DI 框架引入依赖 ViewModel 拆分 (Phase D) → Blocked on Phase D |
| 2026-06-16 | Phase E5: Won't Fix — Application 单例模式对当前项目规模足够，留待模块化拆分时引入 |
