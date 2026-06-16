# BKND-PROC-0003: 切换逻辑泄漏到 UI

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Backend Process Management |
| 关联 | BKND-PROC-0001, BKLC-BPAS-0001, BKLC-BPAS-0003 |

## 问题描述

后端进程切换逻辑泄漏到 UI 层：

```
MainActivity 手动协调:
  loadUpscaleModel() { stopService(BackendService) → UpscaleBackendManager.start() }
  loadModel()       { UpscaleBackendManager.stop()  → startForegroundService(BackendService) }
```

## 当前进展

- `MainActivity` 已于 v4.0 迁移至 `BackendManager`，不再手动协调切换
- `ModelRunScreen.kt` 仍直接操作 `BackendService` Intent（绕过 `BackendManager`）

## 涉及文件

- `MainActivity.kt` — 已修复
- `ui/screens/ModelRunScreen.kt` — 待修复

## 修复方案

所有后端操作统一通过 `BackendManager`：
- `startDiffusion(...)` / `startUpscaler(...)` — 内部自动 stop 旧进程 → start 新进程
- `stop()` — 优雅关闭
- `state: StateFlow<State>` — 单一状态观察

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-13 | 初始发现 |
| 2026-06-15 | v4.0: MainActivity 迁移完成 |
| 2026-06-16 | Phase A1 完成：ModelRunScreen 全部后端操作统一通过 BackendManager API → ✅ Fully Fixed |
