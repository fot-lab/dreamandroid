# HTTP API 文档索引

> 完整写作规范见 [HTTPAPI.md](../HTTPAPI.md)

## §1. API 文档总表

| ID | API 来源 | 文档摘要 | 详情 |
|----|----------|----------|------|
| A111-APIS-0001 | A1111 WebUI | 完整 40+ 端点：生成/超分/进度/中断/查询/训练/模型管理/服务器管理/配置 等 | [A111-APIS-0001](detail/A111-APIS-0001.md) |
| STBL-APIS-0001 | Stability AI | v1 (gRPC/生成/超分/引擎/账户) + v2beta (Ultra/Core/SD3/Edit/Control/Video/3D) 完整 REST API | [STBL-APIS-0001](detail/STBL-APIS-0001.md) |
| DREM-APIS-0001 | DreamAndroid | v1 API 参考: /v1/{health,progress,generate(SSE),upscale,tokenize} — A1111参数命名 + Stability错误 + CORS | [DREM-APIS-0001](detail/DREM-APIS-0001.md) |
| DREM-APIS-0002 | DreamAndroid | v1 API 示例: 6端点完整请求/响应范例 — curl + JSON + SSE事件流 + Kotlin + 所有错误场景 | [DREM-APIS-0002](detail/DREM-APIS-0002.md) |
| DREM-APIS-0003 | DreamAndroid | C++ 后端重构兼容性分析: 0289813a → 76ed17e9 — CLI/模型加载/Scheduler/推理/HTTP API 差异事实 | [DREM-APIS-0003](detail/DREM-APIS-0003.md) |
| CMPR-APIS-0001 | 对比分析 | 三方 API 路径/参数/错误格式/并发策略对比 | [CMPR-APIS-0001](detail/CMPR-APIS-0001.md) |

## §2. 编码领域速查

| 前缀 | 含义 | 当前条目数 |
|------|------|------------|
| `A111-APIS` | A1111 WebUI API 参考 | 1 |
| `STBL-APIS` | Stability AI API 参考 | 1 |
| `DREM-APIS` | DreamAndroid API 参考 | 3 |
| `CMPR-APIS` | API 对比分析 | 1 |
