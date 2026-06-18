# DREM-APIS-0001: DreamAndroid HTTP API 参考

| 属性 | 值 |
|------|-----|
| 分类 | DreamAndroid API Reference |
| 版本 | v1 (C++ httplib 本地后端) |
| 协议 | HTTP REST + SSE |
| API 前缀 | `/v1/` |

## 概述

DreamAndroid 使用内嵌 C++ httplib HTTP 服务器，监听本地端口 (默认 8081)。
所有 API 端点统一使用 `/v1/` 版本前缀。
生成端点使用 Server-Sent Events (SSE) 流式传输进度和结果。
参数命名主要参考 A1111 风格 (prompt/negative_prompt/denoising_strength/cfg_scale)，
错误格式采用 Stability-AI 兼容的 `id/name/errors` 信封。
并发控制使用单例 ServerState 状态机 (Idle ↔ Busy)。

## 端点列表

### 健康检查

```
GET /v1/health
```

**响应** (JSON):
```json
{ "status": "idle" }
```
或
```json
{ "status": "busy" }
```

### 进度查询

```
GET /v1/progress
```

**响应** (JSON):
```json
{
  "status": "busy",
  "current_step": 5,
  "total_steps": 20,
  "progress": 0.25
}
```

`progress` 为 0.0~1.0 的浮点数，`total_steps > 0` 时等于 `current_step / total_steps`。

### 图像生成 (SSE)

```
POST /v1/generate
```

**请求体** (JSON):
```json
{
  "prompt": "a cat",
  "negative_prompt": "",
  "steps": 20,
  "samples": 1,
  "cfg_scale": 7.0,
  "use_cfg": true,
  "width": 512,
  "height": 512,
  "denoising_strength": 1.0,
  "use_opencl": false,
  "sampler": "euler",
  "scheduler": "karras",
  "show_diffusion_process": false,
  "show_diffusion_stride": 0,
  "aspect_ratio": "1:1",
  "seed": 0,
  "image": "...",
  "mask": "..."
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `prompt` | string | **(必需)** | 正向提示词 |
| `negative_prompt` | string | `""` | 负向提示词 (A1111 风格) |
| `steps` | int | `20` | 推理步数 |
| `samples` | int | `1` | 生成数量，仅支持 `1` (不支持批量) |
| `cfg_scale` | float | `7.5` | CFG 引导强度 (Stability-AI 命名) |
| `use_cfg` | bool | `true` | 客户端侧参数，服务端不解析 (保留向前兼容) |
| `width` | int | `512` | 输出宽度 |
| `height` | int | `512` | 输出高度 |
| `denoising_strength` | float | `0.6` | 降噪强度 (A1111 命名，img2img 生效) |
| `use_opencl` | bool | `false` | 是否启用 OpenCL 加速 |
| `sampler` | string | `"dpm"` | 采样器算法：`dpm`、`euler`、`euler_a`、`lcm`、`dpm_sde` |
| `scheduler` | string | `"scaled_linear"` | 噪声调度曲线：`scaled_linear` (默认)、`linear`、`karras`。大小写不敏感 |
| `show_diffusion_process` | bool | `false` | 是否展示扩散中间过程 |
| `show_diffusion_stride` | int | `1` | 扩散过程展示步长 |
| `aspect_ratio` | string | `"1:1"` | 宽高比 (SDXL 模式生效) |
| `seed` | unsigned | `0` | 随机种子；`0` = 随机 (Stability-AI 规范) |
| `image` | string | — | 可选，图生图的输入图片 base64 |
| `mask` | string | — | 可选，inpaint 遮罩 base64 (需同时提供 image) |

**响应**: `Content-Type: text/event-stream` (SSE 流)

**SSE 事件类型**:

1. **Progress** — 生成进度:
```
event: progress
data: {"type":"progress","step":5,"total_steps":20,"progress":0.25,"image":"base64..."}
```
> `image` 字段仅在 `show_diffusion_process: true` 时返回。

2. **Complete** — 生成完成:
```
event: complete
data: {"type":"complete","image":"base64...","seed":12345,"width":512,"height":512,"channels":3,"generation_time_ms":1234,"first_step_time_ms":56,"finish_reason":"SUCCESS"}
```

3. **Error** — 生成错误:
```
event: error
data: {"id":"generation_error-12345","name":"generation_error","errors":["message"],"message":"message"}
```

### 超分辨率放大

```
POST /v1/upscale
```

**请求头**:
| 头 | 说明 |
|----|------|
| `X-Image-Width` | 输入图片宽度 |
| `X-Image-Height` | 输入图片高度 |
| `X-Upscaler-Path` | 超分模型路径 |
| `X-Use-OpenCL` | 是否使用 OpenCL (可选, `true`/`1`) |

**请求体**: 原始 RGB 字节流

**响应**: `200 OK`, `Content-Type: image/jpeg`
**响应头**: `X-Output-Width`, `X-Output-Height`, `X-Duration-Ms`

### 分词

```
POST /v1/tokenize
```

**请求体** (JSON):
```json
{ "prompt": "a cat sitting on a mat" }
```

**响应** (JSON):
```json
{
  "count": 7,
  "max_length": 77,
  "overflow_offset": -1
}
```

## 错误响应格式

所有错误使用 Stability-AI 兼容的 `id`/`name`/`errors` 信封，同时附带顶层 `message` 字段方便客户端解析。

### 请求错误 (400)

```json
{ "id": "invalid_argument-<ts>", "name": "invalid_argument", "errors": ["message"] }
```

### JSON 解析错误 (400)

```json
{ "id": "invalid_json-<ts>", "name": "invalid_json", "errors": ["message"] }
```

### 服务端错误 (500)

```json
{ "id": "server_error-<ts>", "name": "server_error", "errors": ["message"] }
```

### 并发冲突 (503)

```
HTTP 503 Service Unavailable
Retry-After: 3
```

```json
{
  "id": "busy-<timestamp>",
  "name": "busy",
  "errors": ["Server is currently processing another request"],
  "error": "busy-<timestamp>",
  "detail": "busy",
  "message": "Server is busy",
  "body": "Server is busy",
  "messages": ["Server is currently processing another request"]
}
```

## CORS

所有响应包含以下 CORS 头，支持跨域访问:

```
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, OPTIONS
Access-Control-Allow-Headers: Content-Type, Authorization
Access-Control-Max-Age: 86400
```

`OPTIONS *` 返回 `204 No Content`。

## 并发控制

ServerState 状态机:
```
Idle  ←→  Busy
```

- `acquireBusy()`: CAS 从 Idle→Busy，失败则返回 503
- `release()`: 生成完成后释放 (通过 BusyGuard RAII 或 chunked callback)
- `checkAndReleaseTimeout()`: 超时强制释放 (默认 300s)，防止死锁

## API 设计参考

| 维度 | 参考源 | 说明 |
|------|--------|------|
| 提示词字段 | **A1111** | `prompt` / `negative_prompt` 双字段 |
| 参数命名 | **A1111** | `denoising_strength` (含 ing) |
| CFG 参数名 | **Stability-AI v1** | `cfg_scale` |
| Seed 语义 | **Stability-AI** | `0` = 随机 |
| 错误格式 | **Stability-AI** | `id`/`name`/`errors` 信封 |
| 版本前缀 | **Stability-AI / A1111** | `/v1/` |
| 批量生成 | — | `samples` 字段，仅支持 `1` |
| 风格预设 | — | 不支持 (移动端场景过重) |

## 关键特征

- **本地后端**: C++ httplib，无外部依赖
- **SSE 流式**: `/generate` 使用 SSE 推送进度和结果，含 `progress` 百分比和 `finish_reason`
- **单例 Busy 锁**: CAS 原子操作保证并发安全
- **Stability-AI 兼容错误格式**: 错误响应遵循 Stability AI 的 id/name/errors 模式 + 顶层 message
- **原始字节超分**: `/upscale` 使用二进制 body + 自定义 header，效率高于 base64
- **CORS 支持**: 保留跨域场景

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-18 | v2.3: `denoise_curve` → `scheduler`，新增参数校验；支持 `scaled_linear`(默认)、`linear`、`karras`；大小写不敏感；不支持的值返回 400 错误 |
| 2026-06-18 | v2.2: `/v1/health` 新增 JSON body `{"status":"idle"/"busy"}`；`/v1/progress` 新增 `status` 字符串字段 |
| 2026-06-18 | v2.1: 文档校验 — `use_cfg` 标注为客户端侧参数(服务端不解析)；Progress SSE `image` 字段添加条件说明；验证全部端点/参数/SSE格式与代码一致 |
| 2026-06-18 | v2: 新增 `/v1/` 版本前缀；`cfg` → `cfg_scale`；`denoise_strength` → `denoising_strength`；新增 `samples` 参数(仅支持1)；seed=0 表示随机；progress 事件增加 `progress` 百分比；complete 事件增加 `finish_reason`；SSE error 增加 `message` 字段；CORS 保留 |
| 2026-06-18 | 初始创建 (v1) |
