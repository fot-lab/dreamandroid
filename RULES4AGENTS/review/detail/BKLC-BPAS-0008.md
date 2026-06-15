# BKLC-BPAS-0008: MainActivity 直接使用 UpscaleBackendManager

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Backend Lifecycle Bypass |
| 关联 | BKLC-BPAS-0005, BKND-PROC-0003 |

## 问题描述

`MainActivity.loadUpscaleModel()` 曾直接调用 `UpscaleBackendManager.start()` / `stop()`，绕过 `BackendManager`。

## 涉及文件

- `MainActivity.kt`

## 修复方案

v4.0 修复：

- `MainActivity.loadUpscaleModel()` → `backendManager.startUpscaler()`
- `UpscaleScreen` 状态观察也迁移至 `backendManager.state`

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | v4.0: MainActivity/UpscaleScreen 迁移至 BackendManager → ✅ Fixed |
