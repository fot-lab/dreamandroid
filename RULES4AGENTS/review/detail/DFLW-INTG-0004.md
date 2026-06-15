# DFLW-INTG-0004: readLine() 阻塞不响应取消

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Data Flow Integrity |
| 关联 | DFLW-INTG-0003, QUEU-SYST-0006 |

## 问题描述

`SseStreamParser` 中 `BufferedReader.readLine()` 是阻塞调用，不响应协程取消：

```kotlin
// Before: 不响应取消
while (true) {
    val line = reader.readLine() ?: break  // 阻塞，不检查 isActive
}
```

服务停止时 SSE 流无法及时关闭 → 资源泄漏。

## 涉及文件

- `service/queue/SseStreamParser.kt`

## 修复方案

添加 `isActive` 协作式取消检查 + `finally` 资源关闭：

```kotlin
try {
    while (isActive) {
        val line = reader.readLine() ?: break
        // process line
    }
} finally {
    reader.close()
    inputStream.close()
}
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 添加 isActive 检查 + finally 资源关闭 → ✅ Fixed |
