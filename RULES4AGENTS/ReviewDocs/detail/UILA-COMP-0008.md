# UILA-COMP-0008: MainActivity.onCreate() 中未使用的 val app 变量 (重构遗留)

| 属性 | 值 |
|------|-----|
| 优先级 | P3 |
| 分类 | UI Layer |
| 关联 | UILA-COMP-0001 |

## 问题描述

`MainActivity.onCreate()` 中声明了 `val app = application as DreamAndroidApplication` 但从未使用：

```kotlin:83:83:app/src/main/java/io/github/dreamandroid/local/MainActivity.kt
val app = application as DreamAndroidApplication  // ← 声明后未使用
```

这是 Phase D 重构 (`UILA-COMP-0001`) 的遗留代码。重构前 `app` 被传给 `AppContent(app)`；重构后 `AppContent()` 内部通过 `context.applicationContext as DreamAndroidApplication` 自行获取 Application 实例，导致此变量成为死代码。

### 附加影响

1. **冗余强转**: 每次 `onCreate` 执行时多余的 `as` 强转（虽然 ClassCastException 在此场景不可能，但语义上多余）
2. **代码意图混淆**: 新开发者看到此变量会误以为它被后续代码使用，实际 `AppContent()` 独立访问 Application
3. **编译警告**: IDE/CI 可能报告 "Variable 'app' is never used" 警告

## 涉及文件

- `MainActivity.kt:83` — `val app = application as DreamAndroidApplication`

## 根因

Phase D 重构将 `AppContent()` 的 Application 参数移除（改为内部获取），但未清理 `MainActivity` 中的对应变量。

## 初始化未就绪处理检查

N/A — 此问题不涉及异步依赖初始化。

## 修复方案

删除 `MainActivity.kt:83` 的 `val app = application as DreamAndroidApplication` 行。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-16 | 初始发现 |
