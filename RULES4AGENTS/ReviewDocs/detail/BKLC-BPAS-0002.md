# BKLC-BPAS-0002: ModelRunScreen ACTION_RESTART 绕过

| 属性 | 值 |
|------|-----|
| 优先级 | P0 |
| 分类 | Backend Lifecycle Bypass |
| 关联 | BKLC-BPAS-0001, BKND-PROC-0003 |

## 问题描述

`ModelRunScreen` 分辨率切换时发送 `ACTION_RESTART` Intent 到 `BackendService`，而非调用 `backendManager.startDiffusion()` 重新配置。

位置：`ModelRunScreen.kt` L1781

## 涉及文件

- `ui/screens/ModelRunScreen.kt`

## 修复方案


```kotlin
// 分辨率变更 → 重新调用 BackendManager
backendManager.stop()
backendManager.startDiffusion(modelId, newWidth, newHeight, useOpenCL)
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 初始发现 |
| 2026-06-16 | Phase A1 完成：`ACTION_RESTART` 迁移至 `backendManager.stop()` + `startDiffusion()` → ✅ Fully Fixed |
