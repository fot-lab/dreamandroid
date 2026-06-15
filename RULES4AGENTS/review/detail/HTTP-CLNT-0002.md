# HTTP-CLNT-0002: Health check 每次新建 client

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | HTTP/Network |
| 关联 | HTTP-CLNT-0001, DFLW-INTG-0005 |

## 问题描述

旧架构中 MainActivity 每次 health check 创建新 OkHttpClient：

```kotlin
// MainActivity
val client = OkHttpClient.Builder()
    .connectTimeout(3, SECONDS)
    .build()
// 每次 health check → 新建 client → 新建连接池 → 浪费资源
```

## 涉及文件

- `MainActivity.kt` — 旧代码已移除
- `service/backend/BackendManager.kt` — 新实现

## 修复方案

`BackendManager.healthCheck()` 复用 `httpClient` (来自 `HttpClientProvider`)，不再每次新建。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | BackendManager.healthCheck() 复用共享 client → ✅ Fixed |
