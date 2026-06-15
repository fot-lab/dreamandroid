# QUEU-SYST-0006: SSE 解析不可复用

| 属性 | 值 |
|------|-----|
| 优先级 | P2 |
| 分类 | Queue Processing |
| 关联 | DFLW-INTG-0003, DFLW-INTG-0004 |

## 问题描述

旧架构中 SSE 解析逻辑 ~100 行内联在 `BackgroundGenerationService` 中，无法独立测试和复用。

## 涉及文件

- `service/BackgroundGenerationService.kt` — 旧实现
- `service/queue/SseStreamParser.kt` — 新实现

## 修复方案

`SseStreamParser` 独立类，可单测：

```kotlin
class SseStreamParser(inputStream: InputStream) {
    fun events(): Flow<SseEvent> // sealed: Progress / Complete / Error
}
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | SseStreamParser 独立类实现并验证 → ✅ Fixed |
