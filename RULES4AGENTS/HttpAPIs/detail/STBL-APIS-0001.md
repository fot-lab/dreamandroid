# STBL-APIS-0001: Stability AI HTTP API 参考

| 属性 | 值 |
|------|-----|
| 分类 | Stability AI API Reference |
| 版本 | v2 (基于 stability-sdk v0.8.6 + Platform Docs) |
| 来源 | GitHub: Stability-AI/stability-sdk, PyPI, api.stability.ai |
| 协议 | v1: gRPC (protobuf) + REST 桥 | v2beta: REST + JSON/multipart |
| Base URL | `https://api.stability.ai` |

## 概述

Stability AI 提供云端图像生成 API，有 **v1** (传统) 和 **v2beta** (当前最新) 两个版本。
v1 基于 gRPC 协议 (有 REST 桥接)，v2beta 是纯 REST API。
认证统一使用 Bearer Token。

## §1. 认证

所有请求需携带 API Key:

```
Authorization: Bearer sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

获取 API Key: [platform.stability.ai](https://platform.stability.ai) → 账户 → API Keys

SDK 中通过环境变量设置: `STABILITY_KEY` (和可选 `STABILITY_HOST`，默认 `grpc.stability.ai:443`)

---

## §2. V1 API (传统 gRPC-bridged REST)

V1 的 REST 端点路径格式: `/v1/generation/{engine_id}/{action}`

### 2.1 引擎列表

```
GET /v1/engines/list
```

响应 (`list[EngineInfo]`):

```json
[
  {
    "id": "stable-diffusion-xl-1024-v1-0",
    "name": "Stable Diffusion XL 1.0",
    "type": "PICTURE",
    "description": "..."
  }
]
```

常用引擎 ID:

| Engine ID | 说明 |
|-----------|------|
| `stable-diffusion-v1-6` | SD 1.6 (512×512) |
| `stable-diffusion-512-v2-1` | SD 2.1 512 |
| `stable-diffusion-768-v2-1` | SD 2.1 768 |
| `stable-diffusion-xl-1024-v0-9` | SDXL 0.9 |
| `stable-diffusion-xl-1024-v1-0` | SDXL 1.0 |
| `stable-diffusion-xl-beta-v2-2-2` | SDXL Beta |
| `esrgan-v1-x2plus` | ESRGAN 2× 超分 |

### 2.2 文生图

```
POST /v1/generation/{engine_id}/text-to-image
Content-Type: application/json
Accept: application/json (默认) 或 image/png (返回原始图片)
```

请求体:

```json
{
  "text_prompts": [
    { "text": "a beautiful sunset over mountains", "weight": 1.0 },
    { "text": "blurry, low quality, distorted", "weight": -1.0 }
  ],
  "cfg_scale": 7,
  "clip_guidance_preset": "NONE",
  "sampler": "K_DPMPP_2M",
  "samples": 1,
  "seed": 0,
  "steps": 30,
  "width": 1024,
  "height": 1024,
  "style_preset": "photographic",
  "extras": {}
}
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `text_prompts` | array | ✅ | 提示词数组，`weight>0` 为正面，`weight<0` 为负面 |
| `text_prompts[].text` | string | ✅ | 提示词文本 (上限 2000 字符) |
| `text_prompts[].weight` | float | — | 权重，默认 1.0，范围 [-1, 1] |
| `cfg_scale` | int | — | CFG 引导强度 (1~35，默认 7) |
| `clip_guidance_preset` | string | — | CLIP 引导预设: `FAST_BLUE`, `FAST_GREEN`, `NONE`, `SIMPLE`, `SLOW`, `SLOWER`, `SLOWEST` |
| `sampler` | string | — | 采样器 (默认自动选择) |
| `samples` | int | — | 生成图片数 (1~10，默认 1) |
| `seed` | int | — | 随机种子 (0=随机) |
| `steps` | int | — | 采样步数 (10~150，默认引擎决定) |
| `width` | int | — | 宽度 (需为 64 的倍数，引擎决定范围) |
| `height` | int | — | 高度 (需为 64 的倍数，引擎决定范围) |
| `style_preset` | string | — | 风格预设 (见 §2.6) |
| `extras` | object | — | 引擎特定扩展参数 |

响应 (`application/json`):

```json
{
  "artifacts": [
    {
      "base64": "iVBORw0KGgoAAAANSUhEUgAA...",
      "seed": 3737694887,
      "finishReason": "SUCCESS"
    }
  ]
}
```

| 字段 | 说明 |
|------|------|
| `artifacts[].base64` | PNG base64 图片 (无 `data:` 前缀) |
| `artifacts[].seed` | 该图片使用的实际种子 |
| `artifacts[].finishReason` | `SUCCESS` 或 `CONTENT_FILTERED` (内容过滤) |

若 `Accept: image/png`，直接返回 PNG 二进制。

---

### 2.3 图生图

```
POST /v1/generation/{engine_id}/image-to-image
Content-Type: multipart/form-data
```

请求 (multipart/form-data):

| 字段 | 类型 | 说明 |
|------|------|------|
| `init_image` | file | ✅ 输入图片 (PNG/JPEG/WEBP，≤5MB) |
| `init_image_mode` | string | `IMAGE_STRENGTH` (默认) 或 `STEP_SCHEDULE` |
| `image_strength` | float | 降噪强度 (0~1，默认 0.35)。仅在 `IMAGE_STRENGTH` 模式下使用。0=完全保留原图，1=完全忽略原图 |
| `step_schedule_start` | float | 步数调度起始 (0~1，默认 0.5)。仅在 `STEP_SCHEDULE` 模式下使用 |
| `step_schedule_end` | float | 步数调度结束 (0~1，默认 0.01)。仅在 `STEP_SCHEDULE` 模式下使用 |
| `text_prompts[0][text]` | string | ✅ 提示词 |
| `text_prompts[0][weight]` | float | 权重 |
| `cfg_scale` | int | CFG 引导 |
| `sampler` | string | 采样器 |
| `samples` | int | 数量 |
| `seed` | int | 种子 |
| `steps` | int | 步数 |
| `style_preset` | string | 风格预设 |

**init_image_mode 说明**:

- `IMAGE_STRENGTH`: 整个生成过程以固定强度融合原图
  - `image_strength=0`: 输出几乎等于原图
  - `image_strength=1`: 输出几乎完全由提示词决定
  
- `STEP_SCHEDULE`: 在前期步骤中保留原图结构，后期步骤中自由发挥
  - `step_schedule_start` 接近 1 (如 0.9) → Control Sketch (线稿引导)
  - `step_schedule_start` 中等 (如 0.5) → Control Structure (深度图引导)
  - `step_schedule_start` 接近 0.35 + 较低 `image_strength` → Control Style (风格迁移)

---

### 2.4 遮罩/修复 (Inpaint)

```
POST /v1/generation/{engine_id}/image-to-image/masking
Content-Type: multipart/form-data
```

在图生图基础上增加:

| 字段 | 说明 |
|------|------|
| `mask_image` | ✅ 遮罩图片 (白色区域被修复) |
| `mask_source` | `MASK_IMAGE_BLACK` / `MASK_IMAGE_WHITE` / `INIT_IMAGE_ALPHA` |

---

### 2.5 超分

```
POST /v1/generation/{engine_id}/image-to-image/upscale
```

或使用专用引擎:

```
POST /v1/generation/esrgan-v1-x2plus/image-to-image
Content-Type: multipart/form-data
```

请求 (multipart/form-data):

| 字段 | 说明 |
|------|------|
| `image` | ✅ 输入图片 |
| `width` | 目标宽度 (可省略，自动按比例计算) |
| `height` | 目标高度 (可省略，自动按比例计算) |

SDK 中执行: `stability_sdk upscale -i image.png -W 2048` (steps/seed/cfg_scale 在 ESRGAN 引擎中被忽略)

响应: 同图生图格式，返回放大后的 base64 图片。

---

### 2.6 采样器

| 采样器 ID | 类型 |
|-----------|------|
| `DDIM` | 确定性 |
| `DDPM` | 随机 |
| `K_DPMPP_2M` | 确定性 (推荐) |
| `K_DPMPP_2S_ANCESTRAL` | 随机 |
| `K_DPM_2` | 确定性 |
| `K_DPM_2_ANCESTRAL` | 随机 |
| `K_EULER` | 确定性 |
| `K_EULER_ANCESTRAL` | 随机 |
| `K_HEUN` | 确定性 |
| `K_LMS` | 确定性 |
| `PLMS` | 确定性 |

### 2.7 风格预设 (`style_preset`)

| 预设值 | 效果 |
|--------|------|
| `3d-model` | 3D 模型风格 |
| `analog-film` | 模拟胶片 |
| `anime` | 动漫 |
| `cinematic` | 电影感 |
| `comic-book` | 漫画 |
| `digital-art` | 数字艺术 |
| `enhance` | 增强 (类照片) |
| `fantasy-art` | 奇幻艺术 |
| `isometric` | 等距视角 |
| `line-art` | 线条艺术 |
| `low-poly` | 低多边形 |
| `modeling-compound` | 黏土模型 |
| `neon-punk` | 霓虹朋克 |
| `origami` | 折纸 |
| `photographic` | 摄影 |
| `pixel-art` | 像素艺术 |
| `tile-texture` | 平铺纹理 |

---

### 2.8 账户信息

```
GET /v1/user/account
GET /v1/user/balance
```

---

## §3. V2Beta API (当前最新)

V2Beta 是纯 REST API，路径格式: `/v2beta/stable-image/{category}/{action}`

Base URL: `https://api.stability.ai`

### 3.1 Ultra (最高质量)

```
POST /v2beta/stable-image/generate/ultra
Content-Type: multipart/form-data
```

请求 (multipart/form-data):

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `prompt` | string | ✅ | 正面提示词 |
| `negative_prompt` | string | — | 负面提示词 |
| `aspect_ratio` | string | — | 宽高比: `1:1`, `16:9`, `9:16`, `4:3`, `3:4` 等 |
| `seed` | int | — | 种子 (0=随机) |
| `output_format` | string | — | `png` (默认), `jpeg`, `webp` |
| `style_preset` | string | — | 风格预设 (同 v1 列表) |

响应:

```json
{
  "image": "base64...",
  "seed": 1234567890,
  "finish_reason": "SUCCESS"
}
```

### 3.2 Core (快速生成)

```
POST /v2beta/stable-image/generate/core
Content-Type: multipart/form-data
```

请求 (multipart/form-data):

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `prompt` | string | ✅ | 正面提示词 |
| `negative_prompt` | string | — | 负面提示词 |
| `aspect_ratio` | string | — | 宽高比 |
| `seed` | int | — | 种子 |
| `output_format` | string | — | `png`, `jpeg`, `webp` |
| `style_preset` | string | — | 风格预设 |

响应: 同 Ultra 格式。

### 3.3 SD3 / SD3.5 (Stable Diffusion 3.x)

```
POST /v2beta/stable-image/generate/sd3
Content-Type: multipart/form-data
```

请求 (multipart/form-data):

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `prompt` | string | ✅ | 正面提示词 |
| `negative_prompt` | string | — | 负面提示词 |
| `aspect_ratio` | string | — | 宽高比 |
| `seed` | int | — | 种子 |
| `output_format` | string | — | `png`, `jpeg`, `webp` |
| `model` | string | — | 模型选择: `sd3.5-medium`, `sd3.5-large`, `sd3.5-large-turbo`, `sd3-medium`, `sd3-large`, `sd3-large-turbo` |
| `mode` | string | — | `text-to-image` (默认) |
| `strength` | float | — | 图生图降噪强度 (0~1)，需配合 `image` 字段 |

v2beta 图生图需在 SD3 端点中添加 `image` 文件字段。

---

### 3.4 图像编辑

```
POST /v2beta/stable-image/edit/search-and-replace
Content-Type: multipart/form-data
```

请求:

| 字段 | 说明 |
|------|------|
| `image` | ✅ 输入图片 |
| `prompt` | ✅ 描述要替换的目标 |
| `search_prompt` | ✅ 描述要查找的区域 |
| `output_format` | string | 输出格式 |

```
POST /v2beta/stable-image/edit/remove-background
Content-Type: multipart/form-data
```

请求:

| 字段 | 说明 |
|------|------|
| `image` | ✅ 输入图片 |
| `output_format` | string | 输出格式 |

```
POST /v2beta/stable-image/edit/inpaint
Content-Type: multipart/form-data
```

请求:

| 字段 | 说明 |
|------|------|
| `image` | ✅ 输入图片 |
| `mask` | ✅ 遮罩图片 |
| `prompt` | — | 修复提示词 |
| `output_format` | string | 输出格式 |

```
POST /v2beta/stable-image/edit/outpaint
Content-Type: multipart/form-data
```

请求:

| 字段 | 说明 |
|------|------|
| `image` | ✅ 输入图片 |
| `left` | int | 向左扩展像素 |
| `right` | int | 向右扩展像素 |
| `up` | int | 向上扩展像素 |
| `down` | int | 向下扩展像素 |
| `prompt` | — | 提示词 |
| `output_format` | string | 输出格式 |

---

### 3.5 Control (条件控制)

```
POST /v2beta/stable-image/control/sketch
POST /v2beta/stable-image/control/structure
POST /v2beta/stable-image/control/style
```

请求 (multipart/form-data):

| 字段 | 说明 |
|------|------|
| `image` | ✅ 条件图片 |
| `prompt` | ✅ 提示词 |
| `negative_prompt` | — | 负面提示词 |
| `control_strength` | float | 控制强度 (0~1，默认根据模式决定) |
| `output_format` | string | 输出格式 |
| `seed` | int | 种子 |

---

### 3.6 视频生成

```
POST /v2beta/stable-video/generate
Content-Type: multipart/form-data
```

请求:

| 字段 | 说明 |
|------|------|
| `image` | ✅ 起始图片 |
| `seed` | int | 种子 |
| `cfg_scale` | float | CFG 引导强度 |
| `motion_bucket_id` | int | 运动幅度 (1~255，越高运动越大) |
| `fps` | int | 帧率 |

---

### 3.7 3D 生成

```
POST /v2beta/3d/stable-fast-3d/generate
Content-Type: multipart/form-data
```

请求:

| 字段 | 说明 |
|------|------|
| `image` | ✅ 输入图片 |
| `texture_resolution` | string | 纹理分辨率 |

---

## §4. 错误响应格式

Stability AI 的错误响应格式是 DreamAndroid 后端错误格式的设计参考。

### 4.1 标准错误响应

```json
{
  "id": "parse-1734567890",
  "name": "parse",
  "errors": ["Invalid JSON in request body"]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 唯一错误 ID，格式 `{category}-{timestamp}` |
| `name` | string | 错误类别 (e.g. `parse`, `busy`, `unauthorized`, `rate_limit_exceeded`, `generation_error`) |
| `errors` | string[] | 错误详情列表 (人类可读) |

### 4.2 HTTP 状态码含义

| 状态码 | 说明 |
|--------|------|
| 200 | 成功 |
| 400 | 请求参数错误 (`parse`) |
| 401 | 未认证 / API Key 无效 (`unauthorized`) |
| 402 | 账户余额不足 |
| 403 | 无权访问该引擎或功能 |
| 404 | 引擎不存在 |
| 413 | 图片过大 |
| 429 | 速率限制 (`rate_limit_exceeded`) |
| 500 | 服务器内部错误 (`generation_error`) |
| 503 | 服务不可用 / 并发繁忙 (`busy`) |

### 4.3 503 Busy 响应

```json
{
  "id": "busy-1734567890",
  "name": "busy",
  "errors": ["Server is currently processing another request"]
}
```

携带 HTTP 头: `Retry-After: 3` (秒)

### 4.4 内容过滤

当生成内容被安全过滤器拦截时:

```json
{
  "artifacts": [
    {
      "base64": "...",
      "seed": 12345,
      "finishReason": "CONTENT_FILTERED"
    }
  ]
}
```

---

## §5. V1 vs V2Beta 对比

| 维度 | V1 | V2Beta |
|------|-----|--------|
| 协议 | gRPC (REST 桥接) | 纯 REST |
| 提示词 | `text_prompts` 数组 (weight±) | `prompt` + `negative_prompt` 字段 |
| 图片大小 | `width` + `height` (64 的倍数) | `aspect_ratio` 比例 |
| 输出格式 | PNG base64 (固定) | `png` / `jpeg` / `webp` |
| 图生图 | `init_image` + `init_image_mode` | `image` + `strength` (SD3 端点) |
| 采样器 | 需指定 | 内部自动选择 |
| 步数 | 需指定 | 内部自动选择 |
| 引擎 | URL 路径 `{engine_id}` | 无引擎概念，由端点决定 |
| 功能范围 | 生成、超分、修复 | 生成、编辑、控制、视频、3D |

---

## 关键特征

| 特征 | 说明 |
|------|------|
| **云端 API** | 无需本地 GPU，通过 HTTPS 调用 |
| **Bearer Token 认证** | `Authorization: Bearer sk-...` 头 |
| **V1: gRPC 底层** | 通过 protobuf 定义接口，支持多语言 stub |
| **V2Beta: 纯 REST** | multipart/form-data，参数简化 |
| **统一提示词数组** | V1: `text_prompts` 数组同时承载正/负面提示词 (weight 符号区分) |
| **结构化错误** | `id/name/errors` 三字段错误模型 |
| **503 + Retry-After** | 标准并发控制模式 |
| **Base64 传输** | V1 图片通过 JSON base64 字符串传输 |
| **内容安全过滤** | 内置 NSFW 过滤，`finishReason: CONTENT_FILTERED` |
| **风格预设** | 17 种预定义风格，跨 v1/v2beta 通用 |

---

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-18 | v2 全面重写：基于 stability-sdk v0.8.6 README & PyPI 官方文档，补充 v2beta API (Ultra/Core/SD3/Edit/Control/Video/3D)、init_image_mode、style_preset、采样器完整列表、引擎ID、内容过滤、HTTP状态码、v1/v2 对比 |
| 2026-06-18 | 初始创建 (v1) |
