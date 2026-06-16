# HTTP-CLNT-0003: 超时配置不一致

| 属性 | 值 |
|------|-----|
| 优先级 | P2 |
| 分类 | HTTP/Network |
| 关联 | HTTP-CLNT-0001 |

## 问题描述

`HttpClientProvider` 定义了统一超时配置，但其他独立 OkHttpClient 实例可能使用不同超时配置。

## 涉及文件

- `service/http/HttpClientProvider.kt`
- ~~`service/BackgroundGenerationService.kt`~~ (Phase A2 已删除)
- `utils/ImageUtils.kt` (Phase E 已修复)
- `service/ModelDownloadService.kt` (下载合理独立超时)

## 修复方案

所有 HTTP 调用统一使用 `HttpClientProvider.create()` (共享连接池 + 统一超时)：
- BackendManager → `HttpClientProvider.create()`
- `ImageUtils.reportImage()` → `HttpClientProvider.create()` (Phase E 修复)
- `ModelDownloadService` → 独立 client (下载超时合理不同)

统一超时配置：

```kotlin
.connectTimeout(3, TimeUnit.SECONDS)      // 快速判定不可达
.readTimeout(3600, TimeUnit.SECONDS)      // 生成耗时最长 1h
.writeTimeout(30, TimeUnit.SECONDS)       // 上传图片/参数
```

**结论**: 超时配置已统一 → Fully Fixed。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 依赖 HTTP-CLNT-0001 统一 client 解决 |
| 2026-06-16 | Phase E: 所有非下载端点统一使用 HttpClientProvider → ✅ Fully Fixed |
