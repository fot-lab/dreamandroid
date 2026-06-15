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

## 当前进展

- `HttpClientProvider` 创建已存在
- 仍有 4 处独立 `OkHttpClient` 实例：`HttpClientProvider` (共享)、`BackgroundGenerationService` (lazy singleton)、`ModelDownloadService` (实例字段)、`ImageUtils` (telemetry 函数内新建)

## 涉及文件

- `service/http/HttpClientProvider.kt`
- `service/BackgroundGenerationService.kt`
- `service/ModelDownloadService.kt`
- `utils/ImageUtils.kt`

## 修复方案


统一为 `BackendManager.httpClient` 作为唯一 OkHttpClient 来源，所有端点通过 `BackendManager` 方法调用：

```kotlin
class BackendManager {
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, SECONDS)
        .readTimeout(3600, SECONDS)
        .writeTimeout(30, SECONDS)
        .connectionPool(ConnectionPool(5, 1, MINUTES))
        .build()

    suspend fun healthCheck(): Boolean
    fun tokenize(prompt: String): TokenizeResult
    fun generate(params: GenerateParams): Flow<SseEvent>
    fun upscale(input: ByteArray, ...): ByteArray
}
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-13 | 初始发现 |
| 2026-06-15 | HttpClientProvider 创建；仍有 4 处独立实例 → 🔧 Partial |
