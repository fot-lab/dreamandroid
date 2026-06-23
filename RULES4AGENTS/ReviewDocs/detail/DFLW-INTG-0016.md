# DFLW-INTG-0016: SSE 流前后端字段对齐与兼容性修正

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Data Flow Integrity |
| 关联 | DFLW-INTG-0003, DFLW-INTG-0004, QUEU-SYST-0006 |

## 问题描述

C++ 后端（`GenerateHandler` → `HttpUtils::sseWrite*`）与 Kotlin 前端（`SseStreamParser` → `GenerationWorker` / `QueueProcessingService`）通过 SSE 流传输生成进度/结果/错误事件。两端独立演进后存在以下兼容性风险：

1. **错误事件缺少 `type` 字段**：`ErrorJson.hpp` 的 `busyErrorJson()` 和部分错误路径产出的 JSON 格式为 `{"id":"...","name":"...","errors":["msg"]}`，不包含 `"type":"error"` 字段。旧版 `SseStreamParser` 仅匹配 `eventType == "error"`，导致此类错误被静默丢弃。
2. **C++ 发送冗余字段**：`Complete` 事件额外包含 `channels`、`generation_time_ms`、`first_step_time_ms` 三个字段，Kotlin 端未解析。
3. **缺少显式 guard**：`has("errors")` 兜底解析缺少对 `progress`/`complete` 类型的显式防护，存在分支重排风险。

## 涉及文件

| 文件 | 角色 |
|------|------|
| `app/src/main/cpp/src/HttpUtils.hpp` | C++ SSE 写入工具（`sseWrite*` 系列函数） |
| `app/src/main/cpp/src/ErrorJson.hpp` | C++ 错误 JSON 构造（`busyErrorJson()`） |
| `app/src/main/cpp/src/main.cpp` | C++ 生成完成事件组装 |
| `app/src/main/java/.../service/queue/SseStreamParser.kt` | Kotlin SSE 解析器 |
| `app/src/main/java/.../service/queue/GenerationWorker.kt` | 消费者主路径 |
| `app/src/main/java/.../service/queue/QueueProcessingService.kt` | 消费者备路径 |
| `app/src/main/java/.../service/backend/BackendManager.kt` | HTTP 发起 + 503 拦截 |

## 根因分析

1. **C++ 错误格式不统一**：`HttpUtils::sseWriteError()` 产生带 `"type":"error"` 的标准格式，但 `ErrorJson::busyErrorJson()` 返回的是 Stability AI 兼容的错误包（`{"errors":["msg"]}`），两者缺乏统一契约。
2. **Kotlin 解析不够宽容**：早期版本的 SSE 解析对字段缺失敏感，没有使用全量 `optXxx` 且缺少对无 `type` 字段错误包的兜底识别。

## 修复方案

### 1. 兜底错误识别（SseStreamParser）

在 `parseEvent()` 的 `when{}` 末尾新增兜底分支，捕获不含 `type` 字段但包含 `errors` 数组的错误 JSON：

```kotlin
// 兜底：Stability-AI 错误包（无 "type" 字段）
eventType != "progress" && eventType != "complete" && obj.has("errors") -> {
    val arr = obj.getJSONArray("errors")
    val msg = if (arr.length() > 0) arr.getString(0) else "Unknown error"
    SseEvent.Error(msg)
}
```

**Guard 设计**：显式检查 `eventType != "progress" && eventType != "complete"`，防止 `progress`/`complete` 事件中意外出现的 `errors` key 被误判为错误。虽然 `when{}` 的短路特性已保证这些分支先匹配，但该 guard 提供了防御性编程保护，防止未来分支重排引入回归。

### 2. 全量 Lenient 解析

所有字段统一使用 `JSONObject.optString/optInt/optLong/optDouble`，不存在字段时提供合理默认值：

| 事件 | 字段 | 默认值 |
|------|------|--------|
| Progress | `step` | `0` |
| Progress | `totalSteps` | `0` |
| Progress | `progress` | `0.0f` |
| Progress | `image` | `""` |
| Complete | `image` | `""` |
| Complete | `seed` | `-1L` |
| Complete | `width` | `512` |
| Complete | `height` | `512` |
| Complete | `finish_reason` | `"SUCCESS"` |
| Error | `message` | `errors[0]` 或 `"Unknown error"` |

### 3. C++ 冗余字段容忍

C++ 端 `Complete` 事件发送的 `channels`、`generation_time_ms`、`first_step_time_ms` 三个字段 Kotlin 不解析。由于使用 `optXxx`，不会导致解析崩溃，仅静默忽略。如需展示耗时信息，可在 `SseEvent.Complete` 中新增对应字段。

### 4. HTTP 503 提前拦截

`BackendManager.generate()` 在 `response.code == 503` 时直接抛出 `BackendBusy`，不进入 SSE 解析路径，因此 `busyErrorJson()` 不会被送进 `SseStreamParser`。`SseStreamParser` 的兜底错误解析覆盖的是 SSE 流内嵌入的错误事件。

## 前后端字段对齐矩阵

### Progress 事件

| C++ 发送 (`HttpUtils.hpp`) | Kotlin 解析 | 状态 |
|---|---|---|
| `"type": "progress"` | `eventType == "progress"` 路由 | ✅ |
| `"step": <int>` | `event.step` | ✅ |
| `"total_steps": <int>` | `event.totalSteps` | ✅ |
| `"progress": <float>` | `event.progress`（消费者自行计算 `step/totalSteps`） | ✅ 冗余无害 |
| `"image": "..."` (optional) | `event.imageBase64`（未使用） | ✅ |

### Complete 事件

| C++ 发送 (`main.cpp`) | Kotlin 解析 | 状态 |
|---|---|---|
| `"type": "complete"` | `eventType == "complete"` 路由 | ✅ |
| `"image": <base64>` | `event.imageBase64` | ✅ |
| `"seed": <long>` | `event.seed` | ✅ |
| `"width": <int>` | `event.width` | ✅ |
| `"height": <int>` | `event.height` | ✅ |
| `"finish_reason": "SUCCESS"` | `event.finishReason`（解析但未使用） | ✅ |
| `"channels": 3` | — | ⚠️ C++ 发送，Kotlin 不解析 |
| `"generation_time_ms": <int>` | — | ⚠️ C++ 发送，Kotlin 不解析 |
| `"first_step_time_ms": <int>` | — | ⚠️ C++ 发送，Kotlin 不解析 |

### Error 事件

| C++ 发送 | Kotlin 解析 | 状态 |
|---|---|---|
| `"type": "error"` (`HttpUtils`) | `eventType == "error"` 路由 | ✅ |
| `"message": "<msg>"` | `event.message`（`optString("message")`） | ✅ |
| `"errors": ["<msg>"]` (`ErrorJson`) | 兜底 `has("errors")` 解析 | ✅ |
| `"id": "..."` / `"name": "..."` | — | ✅ 不必要解析 |

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-23 | 初始创建。记录前后端 SSE 字段对齐验证结果及 SseStreamParser 兜底错误识别修正 |
