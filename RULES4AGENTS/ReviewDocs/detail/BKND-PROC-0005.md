# BKND-PROC-0005: Upscale 无前台通知保护

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Backend Process Management |
| 关联 | BKND-PROC-0001 |

## 问题描述

`UpscaleBackendManager` (object 单例，非 Service) 直接管理 C++ 进程但不提供 Android 前台通知保护。系统可能在内存压力下杀死进程。

`BackendManager` 当前也直接管理 C++ 进程但没有前台通知保护。

## 涉及文件

- `service/UpscaleBackendManager.kt`
- `service/backend/BackendManager.kt`

## 修复方案


`BackendManager` 本身不需要 Foreground Service（C++ 进程的生命周期由其自身管理），但需要在启动进程前展示前台通知，在停止后移除。

```kotlin
class BackendManager(
    private val context: Context,
    private val notificationManager: NotificationManager  // 新增参数
) {
    fun startDiffusion(...) {
        showForegroundNotification()  // 启动前
        // ... launch process
    }
    fun stop() {
        removeForegroundNotification()  // 停止后
        // ... kill process
    }
}
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-13 | 初始发现 |
| 2026-06-15 | 方案设计完成 |
| 2026-06-16 | Phase A3 完成：`BackendManager` 在 `startDiffusion()`/`startUpscaler()` 启动时调用 `showBackendNotification()`，`stop()` 时调用 `cancelBackendNotification()` → ✅ Fully Fixed |
