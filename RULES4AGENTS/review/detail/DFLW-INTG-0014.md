# DFLW-INTG-0014: Add to Queue 静默失败

| 属性 | 值 |
|------|-----|
| 优先级 | P3 |
| 分类 | Data Flow Integrity |
| 关联 | DFLW-INTG-0015 |

## 问题描述

未加载模型时 "Add to Queue" 操作静默失败：无任何 UI 反馈，用户不知道为何队列没有任务。

## 涉及文件

- `ui/screens/GenerateScreen.kt`

## 修复方案

添加 `showNoModelWarning` AlertDialog，在未加载模型时弹出提示。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | showNoModelWarning AlertDialog → ✅ Fixed |
