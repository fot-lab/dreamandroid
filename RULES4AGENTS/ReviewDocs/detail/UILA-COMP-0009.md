# UILA-COMP-0009: MainViewModel 误用 AndroidViewModel (纯状态持有者)

| 属性 | 值 |
|------|-----|
| 优先级 | P3 |
| 分类 | UI Layer — ViewModel |
| 关联 | UILA-COMP-0001 |

## 问题描述

`MainViewModel` 继承 `AndroidViewModel(application)`，但其内部**从未使用** `application` 参数。该 ViewModel 是纯状态持有者，仅管理两个 Compose state 字段：

```kotlin:17:24:app/src/main/java/io/github/dreamandroid/local/ui/viewmodel/MainViewModel.kt
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // ── Navigation ────────────────────────────────────────────
    var selectedTab by mutableStateOf(BottomTab.Models)

    // ── Global Warnings ───────────────────────────────────────
    var showNoModelWarning by mutableStateOf(false)
}
```

- 不需要 `Context`
- 不需要 `Application` (无 Service Locator 访问、无 `DreamAndroidApplication` cast)
- 不需要 `viewModelScope`
- 不需要 `onCleared()` 清理

## 同期审计：其余 5 个 ViewModel 均合理

| ViewModel | 对 Application 的使用 | 判定 |
|---|---|---|
| `GenerateViewModel` | `application as DreamAndroidApplication` → `backendService` | ✅ 合理 |
| `BrowseViewModel` | `HistoryManager(application)` | ✅ 合理 |
| `ModelsViewModel` | `ModelRepository(application)` + `getApplication<…>()` → `backendService` | ✅ 合理 |
| `QueueViewModel` | `application as DreamAndroidApplication` → `queueRepository`；传入 `QueueController.observeState/start` | ✅ 合理 |
| `UpscaleViewModel` | `application as DreamAndroidApplication` → `backendService` | ✅ 合理 |

## 涉及文件

- `MainViewModel.kt` — class 声明 + import

## 根因

重构 Phase D (`UILA-COMP-0001`) 提取 ViewModel 时，`MainViewModel` 模板复制了其他 ViewModel 的 `AndroidViewModel(application)` 签名，但实际不需要 Application 引用。

## 影响

1. **不必要持有 Application 引用** — `AndroidViewModel` 内部持有 `Application` 的强引用，ViewModel 的生命周期可能长于预期
2. **语义误导** — 新开发者可能以为该 ViewModel 使用了 Application 上下文或 Service Locator
3. **无运行时故障** — 目前代码功能正常，无需紧急修复

## 修复方案

将 `MainViewModel` 改为继承 `ViewModel()`：

1. `import android.app.Application` → 删除
2. `import androidx.lifecycle.AndroidViewModel` → 改为 `import androidx.lifecycle.ViewModel`
3. `class MainViewModel(application: Application) : AndroidViewModel(application)` → `class MainViewModel : ViewModel()`

实例化方式 `viewModel()` 无需变更（默认 `ViewModelProvider.Factory` 对无参构造的 `ViewModel` 同样生效）。

## 初始化未就绪处理检查

N/A — 此问题不涉及异步依赖初始化。

---

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-29 | 初始发现：全量 ViewModel 审计，MainViewModel 为唯一误用 AndroidViewModel 案例 |
