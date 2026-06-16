# BKLC-BPAS-0005: UpscaleBackendManager 重复启动逻辑

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Backend Lifecycle Bypass |
| 关联 | BKLC-BPAS-0006, BKLC-BPAS-0007, BKND-PROC-0001 |

## 问题描述

`UpscaleBackendManager.start()` (L126-160) 与 `BackendManager.startUpscaler()` (L140-184) 几乎完全重复：

- 命令行构建重复
- env 环境变量构建重复
- ProcessBuilder 启动重复
- stdout 监控线程重复

## 涉及文件

- `service/UpscaleBackendManager.kt`
- `service/backend/BackendManager.kt`

## 修复方案


删除 `UpscaleBackendManager.kt`，统一使用 `BackendManager.startUpscaler()`。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 初始发现 |
| 2026-06-16 | Phase A2 完成：`UpscaleBackendManager.kt` 已删除，统一使用 `BackendManager.startUpscaler()` → ✅ Fully Fixed |
