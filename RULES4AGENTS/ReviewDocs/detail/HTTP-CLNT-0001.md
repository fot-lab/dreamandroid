# HTTP-CLNT-0001: 4 个 OkHttpClient 无复用

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | HTTP/Network |
| 关联 | HTTP-CLNT-0002, HTTP-CLNT-0003 |

## 问题描述

应用中存在 4 个独立的 OkHttpClient 实例，各自维护连接池：

| 位置 | 变量名 | 用途 |
|------|--------|------|
| `BackgroundGenerationService` | `sharedClient` (lazy) | POST /generate |
| `GenerateScreen` | `generateScreenTokenizeClient` (lazy) | POST /tokenize |
| `ImageUtils` | `upscaleClient` (lazy) | POST /upscale |
| `MainActivity` health check | 每次 `OkHttpClient.Builder().build()` | GET /health |

连接池无法共享，资源浪费。

## 当前进展 (Phase E)

OkHttpClient 使用现状：

| 位置 | 状态 |
|------|------|
| `BackendManager` → `HttpClientProvider.create()` | ✅ 共享 client，含连接池 |
| ~~`BackgroundGenerationService`~~ | ✅ Phase A2 已删除 |
| `ModelDownloadService.client` | ✅ 独立 client (下载需不同超时，合理) |
| `ImageUtils.reportImage()` | ✅ 已改用 `HttpClientProvider.create()` |

**结论**: 4 处已减少至 2 处合理使用 (共享 + 下载)，无重复 → Fully Fixed。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-13 | 初始发现 |
| 2026-06-15 | HttpClientProvider 创建；仍有 4 处独立实例 → 🔧 Partial |
| 2026-06-16 | Phase E: `reportImage()` 改用 `HttpClientProvider.create()`，BackgroundGenerationService 已删除 → ✅ Fully Fixed |
