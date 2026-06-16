# BKLC-BPAS-0007: UpscaleBackendManager 独立状态流

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Backend Lifecycle Bypass |
| 关联 | BKLC-BPAS-0005, BKND-PROC-0002 |

## 问题描述

`UpscaleBackendManager._state` 与 `BackendManager._state` 是两套独立状态机，均描述端口 8081 上的进程状态。

存在不一致窗口：同一时刻两个状态流可能显示不同状态。

## 涉及文件

- `service/UpscaleBackendManager.kt`
- `service/backend/BackendManager.kt`

## 修复方案


删除 `UpscaleBackendManager._state`，统一使用 `BackendManager.state: StateFlow<State>`。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 初始发现 |
| 2026-06-16 | Phase A2 完成：`UpscaleBackendManager.kt` 已删除，统一使用 `BackendManager.state: StateFlow<State>` → ✅ Fully Fixed |
