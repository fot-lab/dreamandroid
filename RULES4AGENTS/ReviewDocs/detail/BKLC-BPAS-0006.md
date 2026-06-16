# BKLC-BPAS-0006: UpscaleBackendManager 重复 stop 逻辑

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Backend Lifecycle Bypass |
| 关联 | BKLC-BPAS-0005, BKND-PROC-0006 |

## 问题描述

`UpscaleBackendManager.stopInternal()` (L182-196) 的关闭流程与 `BackendManager.stopProcess()` (L313-328) 完全一致：

```
destroy() → waitFor(5s) → destroyForcibly() → waitFor()
```

两处独立维护相同的进程终止逻辑。

## 涉及文件

- `service/UpscaleBackendManager.kt`
- `service/backend/BackendManager.kt`

## 修复方案


删除 `UpscaleBackendManager.kt`，统一使用 `BackendManager.stop()`。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 初始发现 |
| 2026-06-16 | Phase A2 完成：`UpscaleBackendManager.kt` 已删除，统一使用 `BackendManager.stop()` → ✅ Fully Fixed |
