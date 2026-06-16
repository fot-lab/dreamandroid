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

## 当前状态 (Phase E)

- `Preferences.kt`: ✅ 已迁移至 DataStore (`generation_prefs`)
- `ThemePreferences`: 仍使用 SharedPreferences

**评估**: 主题设置数据极小（4 个 key：dynamicColor/preset/darkMode/oledBlack），SharedPreferences 对此类场景足够且更简单。`read()` 为同步调用（Composable 初始化链中使用），迁移至 DataStore 需引入 `runBlocking` 或异步初始化，增加的复杂度不匹配 P2 优先级。

**结论**: 保持 SharedPreferences → Won't fix (acceptable for small theme data)。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | Preferences 已迁移至 DataStore；ThemePreferences 待迁移 → 🔧 Partial |
| 2026-06-16 | Phase E: ThemePreferences 评估为可接受的 SharedPreferences 使用 → ✅ Fully Fixed (won't fix, acceptable) |
