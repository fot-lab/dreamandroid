# BKND-PROC-0001: 进程所有权混乱

| 属性 | 值 |
|------|-----|
| 优先级 | P0 |
| 分类 | Backend Process Management |
| 关联 | BKND-PROC-0003, BKLC-BPAS-0001, BKLC-BPAS-0002, BKLC-BPAS-0005 |

## 问题描述

两个独立的管理器操作同一个端口 8081，进程所有权混乱：

```
BackendService (Diffusion)          UpscaleBackendManager (单例)
├── Foreground Service              ├── object 单例（非 Service）
├── 端口: 8081                      ├── 端口: 8081
├── 状态: Idle/Starting/Running/Err ├── 状态: Idle/Starting/Running/Error
└── 管理: Process lifecycle          └── 管理: Process lifecycle

MainActivity 手动协调:
  loadUpscaleModel() { stopService(BackendService) → UpscaleBackendManager.start() }
  loadModel()       { UpscaleBackendManager.stop()  → startForegroundService(BackendService) }
```

## 当前进展

- `BackendManager` 已统一 `startDiffusion()`/`startUpscaler()`/`stop()`
- `MainActivity` 已于 v4.0 迁移至 `BackendManager`
- 旧 `BackendService.kt` 和 `UpscaleBackendManager.kt` 未删除（已标记 `@Deprecated`）
- `ModelRunScreen` 仍绕过 `BackendManager`，直接使用旧 API

## 涉及文件

- `service/BackendService.kt`
- `service/UpscaleBackendManager.kt`
- `service/backend/BackendManager.kt`
- `MainActivity.kt`
- `ui/screens/ModelRunScreen.kt`

## 根因

`BackendService` 和 `UpscaleBackendManager` 在 `BackendManager` 引入前编写，是旧架构产物。

## 修复方案


**关键步骤：**
1. `ModelRunScreen` 迁移至 `BackendManager` API (BKLC-BPAS-0001~004)
2. 确认无调用方后删除 `BackendService.kt` 和 `UpscaleBackendManager.kt`

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-13 | 初始发现 |
| 2026-06-15 | v4.0: MainActivity 迁移至 BackendManager；旧文件标记 @Deprecated |
| 2026-06-16 | Phase A 完成：ModelRunScreen 迁移至 BackendManager API；`BackendService.kt`、`UpscaleBackendManager.kt` 已删除 → ✅ Fully Fixed |
