# DATA-STOR-0002: SharedPreferences 碎片化

| 属性 | 值 |
|------|-----|
| 优先级 | P2 |
| 分类 | Data Layer |
| 关联 | DATA-STOR-0001 |

## 问题描述

SharedPreferences 使用分散在多处：
- `Preferences.kt` — 部分已迁移至 DataStore (`generation_prefs`)
- `ThemePreferences` — 仍使用 SharedPreferences，未迁移

## 当前进展

- `Preferences.kt` 已迁移至 DataStore
- `ThemePreferences` 仍为 SharedPreferences

## 涉及文件

- `data/ThemePreferences.kt`
- `data/Preferences.kt`

## 修复方案


统一使用 DataStore，集中管理所有 key：

```kotlin
object PrefKeys {
    const val DYNAMIC_COLOR = "dynamic_color"
    const val DARK_MODE = "dark_mode"
    const val PROMPT = "gen_prompt"
    // ... 所有 key 集中管理
}

class PreferencesManager(context: Context) {
    private val dataStore = context.dataStore
    // typed accessors: Flow / suspend set
}
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | Preferences 已迁移至 DataStore；ThemePreferences 待迁移 → 🔧 Partial |
