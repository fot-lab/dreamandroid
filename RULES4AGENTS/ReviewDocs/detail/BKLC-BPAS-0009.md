# BKLC-BPAS-0009: BackendService.prepareRuntimeDir 重复

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Backend Lifecycle Bypass |
| 关联 | BKND-PROC-0004 |

## 问题描述

`BackendService.prepareRuntimeDir()` (L155-226) 独立复制 QNN 库 + safety_checker.mnn，与 `RuntimeDirPreparer.prepare()` 逻辑重复。

`BackendService` 是唯一使用自己 `prepareRuntimeDir` 的文件。

## 涉及文件

- `service/BackendService.kt`
- `service/backend/RuntimeDirPreparer.kt`

## 修复方案


此问题在 `BackendService.kt` 删除后自然解决。`RuntimeDirPreparer.prepare(context)` 是唯一实现。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 初始发现 |
| 2026-06-16 | Phase A2 完成：`BackendService.kt` 已删除，`RuntimeDirPreparer.prepare()` 为唯一实现 → ✅ Fully Fixed |
