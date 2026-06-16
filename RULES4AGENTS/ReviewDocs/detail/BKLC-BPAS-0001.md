# BKLC-BPAS-0001: ModelRunScreen startForegroundService 绕过

| 属性 | 值 |
|------|-----|
| 优先级 | P0 |
| 分类 | Backend Lifecycle Bypass |
| 关联 | BKLC-BPAS-0002, BKLC-BPAS-0003, BKLC-BPAS-0004, BKND-PROC-0003 |

## 问题描述

`ModelRunScreen` 初始化时直接 `startForegroundService(BackendService)`，绕过 `BackendManager.startDiffusion()`。

位置：`ModelRunScreen.kt` L1442

```kotlin
// ModelRunScreen — 绕过 BackendManager
val intent = Intent(context, BackendService::class.java).apply {
    putExtra("modelId", modelId)
    // ...
}
context.startForegroundService(intent)
```

## 涉及文件

- `ui/screens/ModelRunScreen.kt`

## 根因

`ModelRunScreen` 在 `BackendManager` 引入前编写，至今未迁移。它同时导入了 `BackendManager`（用于 tokenize/health check）但从未调用 `startDiffusion()` / `stop()` / `generate()`。

## 修复方案


```kotlin
// ModelRunScreen — 迁移后
backendManager.startDiffusion(modelId, width, height, useOpenCL)
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 初始发现 |
| 2026-06-16 | Phase A1 完成：`ModelRunScreen.kt` + `ModelRunBackend.kt` 迁移至 `backendManager.startDiffusion()` → ✅ Fully Fixed |
