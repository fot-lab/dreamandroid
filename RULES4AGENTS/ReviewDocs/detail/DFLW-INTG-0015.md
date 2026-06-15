# DFLW-INTG-0015: 无效 Seed 静默忽略

| 属性 | 值 |
|------|-----|
| 优先级 | P3 |
| 分类 | Data Flow Integrity |
| 关联 | DFLW-INTG-0014 |

## 问题描述

用户输入无效 Seed 格式时静默忽略，无校验反馈，用户不知道参数被丢弃。

## 涉及文件

- `ui/screens/GenerateScreen.kt`

## 修复方案

Seed `OutlinedTextField` 添加 `isError` + `supportingText`，4 语言本地化 (en/zh/ja/ko)。

```kotlin
OutlinedTextField(
    value = seedText,
    isError = seedError != null,
    supportingText = { seedError?.let { Text(it) } },
    // ...
)
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | Seed 输入校验 + 4 语言本地化 → ✅ Fixed |
