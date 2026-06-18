# DREM-APIS-0002: DreamAndroid HTTP API 示例手册

| 属性 | 值 |
|------|-----|
| 分类 | DreamAndroid API Examples |
| 版本 | v2.1 |
| 协议 | HTTP REST + SSE |
| API 前缀 | `/v1/` |
| 基地址 | `http://127.0.0.1:8081` |

> 本文档是 DREM-APIS-0001 的配套示例手册，提供每个端点的完整请求/响应范例。
> 所有 JSON 示例均为实际可用的 payload。

---

## §1. CORS 预检

### OPTIONS * — 跨域预检

```bash
curl -X OPTIONS http://127.0.0.1:8081/ \
  -H "Origin: http://example.com" \
  -H "Access-Control-Request-Method: POST" \
  -i
```

```
HTTP/1.1 204 No Content
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, OPTIONS
Access-Control-Allow-Headers: Content-Type, Authorization
Access-Control-Max-Age: 86400
```

---

## §2. 健康检查

### GET /v1/health — 服务存活检测

**后端空闲时**:
```bash
curl -X GET http://127.0.0.1:8081/v1/health -i
```

```
HTTP/1.1 200 OK
Access-Control-Allow-Origin: *
Content-Type: application/json
Content-Length: 18

{"status":"idle"}
```

**后端正在生成时**:
```bash
curl -X GET http://127.0.0.1:8081/v1/health -i
```

```
HTTP/1.1 200 OK
Access-Control-Allow-Origin: *
Content-Type: application/json
Content-Length: 18

{"status":"busy"}
```

**Kotlin 客户端调用**:
```kotlin
val request = Request.Builder()
    .url("${DreamHubConstants.BASE_URL}/v1/health")
    .get()
    .build()
httpClient.newCall(request).execute().use { it.isSuccessful }
```

---

## §3. 进度查询

### GET /v1/progress — 查询当前生成进度

**后端空闲时**:
```bash
curl -X GET http://127.0.0.1:8081/v1/progress
```

```json
{
  "status": "idle",
  "current_step": 0,
  "total_steps": 0,
  "progress": 0.0
}
```

**后端正在生成时** (step 5 / 20):
```bash
curl -X GET http://127.0.0.1:8081/v1/progress
```

```json
{
  "status": "busy",
  "current_step": 5,
  "total_steps": 20,
  "progress": 0.25
}
```

**后端生成完成时** (step 20 / 20):
```bash
curl -X GET http://127.0.0.1:8081/v1/progress
```

```json
{
  "status": "busy",
  "current_step": 20,
  "total_steps": 20,
  "progress": 1.0
}
```

**字段说明**:
| 字段 | 类型 | 说明 |
|------|------|------|
| `status` | string | `"idle"` 或 `"busy"` |
| `current_step` | int | 当前推理步数 |
| `total_steps` | int | 总推理步数 (空闲时为 0) |
| `progress` | float | 进度百分比 0.0~1.0 (`current_step / total_steps`) |

**Kotlin 客户端调用**:
```kotlin
suspend fun queryProgress(): Pair<Int, Int>? = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("${DreamHubConstants.BASE_URL}/v1/progress")
        .get()
        .build()
    val response = httpClient.newCall(request).execute()
    val json = JSONObject(response.body?.string() ?: return@withContext null)
    Pair(json.optInt("current_step"), json.optInt("total_steps"))
}
```

---

## §4. 图像生成 (SSE)

### POST /v1/generate — 文生图 (txt2img)

最简请求:
```bash
curl -X POST http://127.0.0.1:8081/v1/generate \
  -H "Content-Type: application/json" \
  -d '{"prompt":"a cat sitting on a mat"}'
```

完整请求:
```bash
curl -X POST http://127.0.0.1:8081/v1/generate \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "a majestic dragon, fantasy art, volumetric lighting",
    "negative_prompt": "blurry, low quality, deformed",
    "steps": 20,
    "samples": 1,
    "cfg_scale": 7.0,
    "use_cfg": true,
    "width": 512,
    "height": 512,
    "denoising_strength": 1.0,
    "use_opencl": false,
    "sampler": "dpm",
    "scheduler": "karras",
    "show_diffusion_process": false,
    "show_diffusion_stride": 1,
    "aspect_ratio": "1:1",
    "seed": 0
  }'
```

**请求参数**:
| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `prompt` | string | ✅ | — | 正向提示词 |
| `negative_prompt` | string | ❌ | `""` | 负向提示词 |
| `steps` | int | ❌ | `20` | 推理步数 |
| `samples` | int | ❌ | `1` | 生成数量 (仅支持 1) |
| `cfg_scale` | float | ❌ | `7.5` | CFG 引导强度 |
| `use_cfg` | bool | ❌ | `true` | 客户端侧参数 |
| `width` | int | ❌ | `512` | 输出宽度 |
| `height` | int | ❌ | `512` | 输出高度 |
| `denoising_strength` | float | ❌ | `0.6` | 降噪强度 (img2img) |
| `use_opencl` | bool | ❌ | `false` | OpenCL 加速 |
| `sampler` | string | ❌ | `"dpm"` | 采样器：`dpm`、`euler`、`euler_a`、`lcm`、`dpm_sde` |
| `scheduler` | string | ❌ | `"scaled_linear"` | 噪声调度曲线：`scaled_linear`、`linear`、`karras` (大小写不敏感) |
| `show_diffusion_process` | bool | ❌ | `false` | 是否推送中间扩散图像 |
| `show_diffusion_stride` | int | ❌ | `1` | 扩散图像推送步长 |
| `aspect_ratio` | string | ❌ | `"1:1"` | 宽高比 (SDXL) |
| `seed` | uint | ❌ | `0` | 随机种子 (0=随机) |
| `image` | string | ❌ | — | 输入图片 base64 (img2img) |
| `mask` | string | ❌ | — | 遮罩 base64 (inpaint, 需 image) |

### SSE 事件流

**完整 SSE 会话 (20步, 无中间图像)**:

```
HTTP/1.1 200 OK
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive
Access-Control-Allow-Origin: *

event: progress
data: {"type":"progress","step":1,"total_steps":20,"progress":0.05}

event: progress
data: {"type":"progress","step":2,"total_steps":20,"progress":0.1}

... (step 3 ~ 19) ...

event: progress
data: {"type":"progress","step":20,"total_steps":20,"progress":1.0}

event: complete
data: {"type":"complete","image":"iVBORw0KGgo...base64...","seed":12345,"width":512,"height":512,"channels":3,"generation_time_ms":12345,"first_step_time_ms":567,"finish_reason":"SUCCESS"}
```

**SSE 事件 — 带中间扩散图像** (`show_diffusion_process: true`):

```
event: progress
data: {"type":"progress","step":5,"total_steps":20,"progress":0.25,"image":"/9j/4AAQ...base64..."}
```

**SSE 事件类型详解**:

| 事件 | 字段 | 类型 | 说明 |
|------|------|------|------|
| `progress` | `type` | string | `"progress"` |
| | `step` | int | 当前步数 |
| | `total_steps` | int | 总步数 |
| | `progress` | float | 进度 0.0~1.0 |
| | `image` | string? | 中间扩散图像 base64 (仅 `show_diffusion_process: true`) |
| `complete` | `type` | string | `"complete"` |
| | `image` | string | 生成图像 base64 (JPEG) |
| | `seed` | int | 实际使用的种子 |
| | `width` | int | 图像宽度 |
| | `height` | int | 图像高度 |
| | `channels` | int | 通道数 (3 = RGB) |
| | `generation_time_ms` | int | 总生成耗时 (ms) |
| | `first_step_time_ms` | int | 第一步耗时 (ms) |
| | `finish_reason` | string | `"SUCCESS"` |
| `error` | `id` | string | 错误 ID (如 `"generation_error-123"`) |
| | `name` | string | `"generation_error"` |
| | `errors` | [string] | 错误详情数组 |
| | `message` | string | 可读错误消息 |

**Kotlin 客户端调用**:
```kotlin
fun generate(params: GenerateParams): Flow<SseStreamParser.SseEvent> = flow {
    val jsonBody = JSONObject().apply {
        put("prompt", params.prompt)
        put("negative_prompt", params.negativePrompt)
        put("steps", params.steps)
        put("samples", 1)
        put("cfg_scale", params.cfgScale.toDouble())
        put("width", params.width)
        put("height", params.height)
        put("denoising_strength", params.denoisingStrength.toDouble())
        put("use_opencl", params.useOpenCL)
        put("sampler", params.sampler)
        put("seed", params.seed ?: 0)
        params.imageBase64?.let { put("image", it) }
        params.maskBase64?.let { put("mask", it) }
    }
    val request = Request.Builder()
        .url("${BASE_URL}/v1/generate")
        .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
        .build()
    val response = httpClient.newCall(request).execute()
    SseStreamParser(response.body!!.byteStream()).events().collect { emit(it) }
}
```

### POST /v1/generate — 图生图 (img2img)

```bash
curl -X POST http://127.0.0.1:8081/v1/generate \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "a watercolor painting of a cat",
    "negative_prompt": "photorealistic",
    "steps": 20,
    "cfg_scale": 7.0,
    "width": 512,
    "height": 512,
    "denoising_strength": 0.6,
    "sampler": "euler_a",
    "seed": 0,
    "image": "/9j/4AAQSkZJRg...base64..."
  }'
```

> `denoising_strength` 控制保留原图的程度: `0.0` 完全保留, `1.0` 完全重绘。

### POST /v1/generate — 局部重绘 (inpaint)

```bash
curl -X POST http://127.0.0.1:8081/v1/generate \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "a glowing crystal",
    "steps": 20,
    "cfg_scale": 7.0,
    "width": 512,
    "height": 512,
    "denoising_strength": 0.75,
    "sampler": "dpm",
    "seed": 12345,
    "image": "/9j/4AAQ...base64...",
    "mask": "iVBORw0KGgo...base64..."
  }'
```

> `mask` 为黑白遮罩：白色区域将被重绘，黑色区域保留原图。`mask` 必须配合 `image` 使用。

### POST /v1/generate — SDXL 宽高比

```bash
curl -X POST http://127.0.0.1:8081/v1/generate \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "a breathtaking landscape, mountains and lake at sunset",
    "steps": 20,
    "cfg_scale": 7.0,
    "width": 1024,
    "height": 1024,
    "sampler": "dpm",
    "aspect_ratio": "16:9",
    "seed": 0
  }'
```

> SDXL 模式下 `width`/`height` 固定为 1024x1024，实际输出由 `aspect_ratio` 控制裁剪区域。

### POST /v1/generate — 指定种子

```bash
curl -X POST http://127.0.0.1:8081/v1/generate \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "a cat",
    "steps": 20,
    "cfg_scale": 7.0,
    "sampler": "dpm",
    "seed": 12345
  }'
```

> `seed: 0` 表示使用随机种子 (Stability-AI 规范)。返回的 `complete` 事件中 `seed` 字段为实际使用的种子值。

### POST /v1/generate — OpenCL 加速

```bash
curl -X POST http://127.0.0.1:8081/v1/generate \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "a futuristic cityscape",
    "steps": 20,
    "cfg_scale": 7.0,
    "use_opencl": true,
    "sampler": "dpm",
    "seed": 0
  }'
```

---

## §5. 超分辨率放大

### POST /v1/upscale — 图像超分

```bash
curl -X POST http://127.0.0.1:8081/v1/upscale \
  -H "X-Image-Width: 512" \
  -H "X-Image-Height: 512" \
  -H "X-Upscaler-Path: /data/models/RealESRGAN_x4.mnn" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @input_512x512.rgb \
  -o output_upscaled.jpg
```

**请求头**:
| 头 | 必填 | 说明 |
|----|------|------|
| `X-Image-Width` | ✅ | 输入图片宽度 (像素) |
| `X-Image-Height` | ✅ | 输入图片高度 (像素) |
| `X-Upscaler-Path` | ✅ | 超分模型文件绝对路径 (.mnn 或 .bin) |
| `X-Use-OpenCL` | ❌ | `"true"` 或 `"1"` 启用 OpenCL (仅 MNN 类型) |
| `Content-Type` | ✅ | `application/octet-stream` |

**请求体**: 原始 RGB 字节流 (`width × height × 3` 字节)。

**响应**:
```
HTTP/1.1 200 OK
Content-Type: image/jpeg
X-Output-Width: 2048
X-Output-Height: 2048
X-Duration-Ms: 3456
Access-Control-Expose-Headers: X-Output-Width,X-Output-Height,X-Duration-Ms
```

| 响应头 | 说明 |
|--------|------|
| `Content-Type` | `image/jpeg` |
| `X-Output-Width` | 输出图片宽度 (4× 输入) |
| `X-Output-Height` | 输出图片高度 (4× 输入) |
| `X-Duration-Ms` | 超分耗时 (毫秒) |

**响应体**: JPEG 编码的放大图像。

**Kotlin 客户端调用**:
```kotlin
suspend fun upscale(rgbBytes: ByteArray, width: Int, height: Int,
                    upscalerPath: String): ByteArray = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("${DreamHubConstants.BASE_URL}/v1/upscale")
        .header("X-Image-Width", width.toString())
        .header("X-Image-Height", height.toString())
        .header("X-Upscaler-Path", upscalerPath)
        .post(rgbBytes.toRequestBody("application/octet-stream".toMediaType()))
        .build()
    val response = httpClient.newCall(request).execute()
    response.body?.bytes() ?: throw AppError.Backend("Empty upscale response")
}
```

### POST /v1/upscale — 小图预放大

```bash
curl -X POST http://127.0.0.1:8081/v1/upscale \
  -H "X-Image-Width: 64" \
  -H "X-Image-Height: 64" \
  -H "X-Upscaler-Path: /data/models/RealESRGAN_x4.mnn" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @tiny_64x64.rgb \
  -o output.jpg
```

> 短边 < 192px 的图像在超分前会自动拉伸至最小边 192px (线性插值)，最终输出保持 4× 原始尺寸。

### POST /v1/upscale — MNN + OpenCL 加速

```bash
curl -X POST http://127.0.0.1:8081/v1/upscale \
  -H "X-Image-Width: 512" \
  -H "X-Image-Height: 512" \
  -H "X-Upscaler-Path: /data/models/RealESRGAN_x4.mnn" \
  -H "X-Use-OpenCL: true" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @input.rgb \
  -o output.jpg
```

> 仅 `.mnn` 模型支持 OpenCL；`.bin` (QNN) 模型忽略此参数。

---

## §6. 分词

### POST /v1/tokenize — 提示词分词计数

**简单提示词**:
```bash
curl -X POST http://127.0.0.1:8081/v1/tokenize \
  -H "Content-Type: application/json" \
  -d '{"prompt":"a cat sitting on a mat"}'
```

```json
{
  "count": 7,
  "max_length": 77,
  "overflow_offset": -1
}
```

**超长提示词** (超过 75 个 content token):
```bash
curl -X POST http://127.0.0.1:8081/v1/tokenize \
  -H "Content-Type: application/json" \
  -d '{"prompt":"a detailed and intricate painting of a mystical forest with glowing mushrooms, ancient ruins covered in vines, a crystal clear stream flowing through moss-covered stones, rays of sunlight piercing through the dense canopy, ethereal glowing particles floating in the air, a majestic stag with luminescent antlers standing by the water, highly detailed digital art, trending on artstation, 8k resolution"}'
```

```json
{
  "count": 82,
  "max_length": 77,
  "overflow_offset": 284
}
```

**空提示词**:
```bash
curl -X POST http://127.0.0.1:8081/v1/tokenize \
  -H "Content-Type: application/json" \
  -d '{"prompt":""}'
```

```json
{
  "count": 2,
  "max_length": 77,
  "overflow_offset": -1
}
```

> 空提示词 `count: 2` 因为 CLIP tokenizer 始终包含 BOS (Begin-of-Sequence) 和 EOS (End-of-Sequence) token。

**字段说明**:
| 字段 | 类型 | 说明 |
|------|------|------|
| `count` | int | 总 token 数 (含 BOS + EOS) |
| `max_length` | int | CLIP 最大 token 长度 (固定 77) |
| `overflow_offset` | int | 溢出起始偏移 (UTF-16 码点)，`-1` = 未溢出 |

> `count > 77` 时提示词被截断，`overflow_offset` 指向被截断的第一个字符在原始 prompt 中的 UTF-16 偏移，客户端可据此高亮溢出区域。

**Kotlin 客户端调用**:
```kotlin
suspend fun tokenize(prompt: String): TokenizeResult = withContext(Dispatchers.IO) {
    val jsonBody = JSONObject().apply { put("prompt", prompt) }
    val request = Request.Builder()
        .url("${DreamHubConstants.BASE_URL}/v1/tokenize")
        .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
        .build()
    val response = httpClient.newCall(request).execute()
    val json = JSONObject(response.body?.string()!!)
    TokenizeResult(
        count = json.optInt("count", 0),
        maxLength = json.optInt("max_length", 77),
        overflowOffset = json.optInt("overflow_offset", -1),
    )
}
```

---

## §7. 错误响应

### 400 — 缺少必填参数

```bash
curl -X POST http://127.0.0.1:8081/v1/generate \
  -H "Content-Type: application/json" \
  -d '{"steps":20}'
```

```
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "id": "invalid_argument-123456789",
  "name": "invalid_argument",
  "errors": ["Missing 'prompt'"]
}
```

### 400 — JSON 解析错误

```bash
curl -X POST http://127.0.0.1:8081/v1/generate \
  -H "Content-Type: application/json" \
  -d '{invalid json}'
```

```
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "id": "invalid_json-123456789",
  "name": "invalid_json",
  "errors": ["parse error - unexpected 'i'; expected string"]
}
```

### 400 — samples 越界

```bash
curl -X POST http://127.0.0.1:8081/v1/generate \
  -H "Content-Type: application/json" \
  -d '{"prompt":"a cat","samples":4}'
```

```
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "id": "invalid_argument-123456789",
  "name": "invalid_argument",
  "errors": ["samples must be 1 (batch>1 not supported)"]
}
```

### 400 — scheduler 不支持的值

```bash
curl -X POST http://127.0.0.1:8081/v1/generate \
  -H "Content-Type: application/json" \
  -d '{"prompt":"a cat","scheduler":"exponential"}'
```

```
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "id": "invalid_argument-123456789",
  "name": "invalid_argument",
  "errors": ["Unsupported scheduler 'exponential'. Supported: scaled_linear, linear, karras"]
}
```

> `scheduler` 参数大小写不敏感；`KARRAS`、`Karras`、`karras` 均有效。不传入时默认使用 `scaled_linear`。

### 400 — mask 缺少 image

```bash
curl -X POST http://127.0.0.1:8081/v1/generate \
  -H "Content-Type: application/json" \
  -d '{"prompt":"a cat","mask":"iVBORw0..."}'
```

```
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "id": "invalid_argument-123456789",
  "name": "invalid_argument",
  "errors": ["mask requires image"]
}
```

### 400 — 分词错误

```bash
curl -X POST http://127.0.0.1:8081/v1/tokenize \
  -H "Content-Type: application/json" \
  -d '{}'
```

```
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "id": "tok-123456789",
  "name": "tokenize_error",
  "errors": ["Tokenizer not initialized"]
}
```

### 500 — 生成运行时错误

```bash
curl -X POST http://127.0.0.1:8081/v1/generate \
  -H "Content-Type: application/json" \
  -d '{"prompt":"a cat"}'
```

(假设 UNET 执行失败)

```
HTTP/1.1 500 Internal Server Error
Content-Type: application/json

{
  "id": "server_error-123456789",
  "name": "server_error",
  "errors": ["QNN UNET exec failed (cond)"]
}
```

### 503 — 后端忙 (并发冲突)

```bash
curl -X POST http://127.0.0.1:8081/v1/generate \
  -H "Content-Type: application/json" \
  -d '{"prompt":"a cat"}'
```

(当另一个生成请求正在进行时)

```
HTTP/1.1 503 Service Unavailable
Retry-After: 3
Content-Type: application/json

{
  "id": "busy-123456789",
  "name": "busy",
  "errors": ["Server is currently processing another request"],
  "error": "busy-123456789",
  "detail": "busy",
  "message": "Server is busy",
  "body": "Server is busy",
  "messages": ["Server is currently processing another request"]
}
```

> 503 响应包含 `Retry-After` 头，客户端应在该秒数后重试。

### SSE 流中错误 — 生成超时

```
event: error
data: {"id":"generation_error-123456789","name":"generation_error","errors":["Generation timed out after 300s"],"message":"Generation timed out after 300s"}
```

> 超时默认 300 秒，由 `ServerState.generation_timeout_secs` 控制。

### 400 — Upscale 缺少请求头

```bash
curl -X POST http://127.0.0.1:8081/v1/upscale \
  -H "Content-Type: application/octet-stream" \
  --data-binary @input.rgb
```

```
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "id": "invalid_argument-123456789",
  "name": "invalid_argument",
  "errors": ["Missing 'X-Image-Width' header"]
}
```

### 400 — Upscale 图像尺寸不匹配

```bash
curl -X POST http://127.0.0.1:8081/v1/upscale \
  -H "X-Image-Width: 512" \
  -H "X-Image-Height: 512" \
  -H "X-Upscaler-Path: /data/models/RealESRGAN_x4.mnn" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @wrong_size.rgb
```

```
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "id": "invalid_argument-123456789",
  "name": "invalid_argument",
  "errors": ["Image data size mismatch. Expected 786432 bytes, got 262144"]
}
```

---

## §8. 错误响应格式总览

### HTTP 状态码 (200 以外)

| 状态码 | 含义 | JSON 格式 |
|--------|------|-----------|
| 400 | 请求参数错误 | `{"id":"<category>-<ts>","name":"<category>","errors":["<message>"]}` |
| 500 | 服务器内部错误 | `{"id":"server_error-<ts>","name":"server_error","errors":["<message>"]}` |
| 503 | 并发冲突 (忙) | busy 格式，含 `Retry-After` 头 |

### JSON 错误信封字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 唯一错误 ID (`<category>-<timestamp>`) |
| `name` | string | 错误类别名 (与 `id` 前缀一致) |
| `errors` | [string] | 错误详情数组 |
| `message` | string? | 可读消息 (SSE error 和 503 busy 响应) |
| `error` | string? | 503 响应的 `id` 副本 |
| `detail` | string? | 503 响应的 `name` 副本 |
| `body` | string? | 503 响应的摘要消息 |
| `messages` | [string]? | 503 响应的 `errors` 副本 |

---

## §9. 端点速查

| 方法 | 路径 | 请求 Content-Type | 响应 Content-Type | 流式? |
|------|------|-------------------|-------------------|-------|
| `OPTIONS` | `*` | — | — | ❌ |
| `GET` | `/v1/health` | — | `application/json` | ❌ |
| `GET` | `/v1/progress` | — | `application/json` | ❌ |
| `POST` | `/v1/generate` | `application/json` | `text/event-stream` | ✅ SSE |
| `POST` | `/v1/upscale` | `application/octet-stream` | `image/jpeg` | ❌ |
| `POST` | `/v1/tokenize` | `application/json` | `application/json` | ❌ |

## §10. CORS 头 (所有响应)

```
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, OPTIONS
Access-Control-Allow-Headers: Content-Type, Authorization
Access-Control-Max-Age: 86400
```

Upscale 响应额外包含:
```
Access-Control-Expose-Headers: X-Output-Width,X-Output-Height,X-Duration-Ms
```

---

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-18 | v1.2: `denoise_curve` → `scheduler`；更新所有请求示例参数 |
| 2026-06-18 | v1.1: `/v1/health` 更新为返回 `{"status":"idle"/"busy"}` JSON；`/v1/progress` 新增 `status` 字段 |
