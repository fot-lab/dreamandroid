# CMPR-APIS-0001: HTTP API 三方对比分析

| 属性 | 值 |
|------|-----|
| 分类 | API Comparison & Analysis |
| 对比对象 | A1111 WebUI / Stability AI / DreamAndroid |

## 端点功能映射

| 功能 | A1111 WebUI | Stability AI (v1) | Stability AI (v2beta) | DreamAndroid |
|------|-------------|-------------------|-----------------------|-------------|
| 文生图 | `POST /sdapi/v1/txt2img` | `POST /v1/generation/{id}/text-to-image` | `POST /v2beta/stable-image/generate/ultra` 等 | `POST /v1/generate` (SSE) |
| 图生图 | `POST /sdapi/v1/img2img` | `POST /v1/generation/{id}/image-to-image` | SD3端点 + `image` 字段 | `POST /v1/generate` (+image) (SSE) |
| 超分 | `POST /sdapi/v1/extra-single-image` | `POST /v1/generation/{id}/image-to-image/upscale` | — | `POST /v1/upscale` (二进制) |
| 修复/遮罩 | img2img + mask | `POST .../image-to-image/masking` | `POST /v2beta/.../edit/inpaint` | — |
| 图像编辑 | — | — | `search-and-replace` / `remove-bg` / `outpaint` | — |
| Control | — | img2img + STEP_SCHEDULE | `POST /v2beta/.../control/{sketch,structure,style}` | — |
| 视频生成 | — | — | `POST /v2beta/stable-video/generate` | — |
| 3D 生成 | — | — | `POST /v2beta/3d/stable-fast-3d/generate` | — |
| 健康检查 | 无 | 无 | 无 | `GET /v1/health` |
| 进度查询 | `GET /sdapi/v1/progress` | 无 | 无 | `GET /v1/progress` |
| 中断/跳过 | `POST /sdapi/v1/interrupt` + `/skip` | 无 | 无 | 无 (Service 级) |
| 分词 | 无 | 无 | 无 | `POST /v1/tokenize` |
| 模型列表 | `GET /sdapi/v1/sd-models` | `GET /v1/engines/list` | 无 | 无 (Android 侧) |
| 采样器列表 | `GET /sdapi/v1/samplers` | 无 | 无 (自动) | 无 (Android 侧) |
| 账户/余额 | — | `GET /v1/user/account` | 同 v1 | — |

## 路径风格对比

| 维度 | A1111 | Stability AI (v1) | Stability AI (v2beta) | DreamAndroid |
|------|-------|-------------------|-----------------------|-------------|
| 路径前缀 | `/sdapi/v1/` | `/v1/generation/{engine_id}/` | `/v2beta/stable-image/` | `/v1/` |
| 命名风格 | snake_case | kebab-case | kebab-case | snake_case |
| 引擎指定 | 隐式 (当前加载模型) | URL 路径中显式指定 | 无引擎概念，端点决定模型 | 启动参数指定 |
| 路径层级 | 扁平 | 层级化 (engine/action) | 层级化 (category/action/type) | 扁平 |
| API 版本 | 隐式 (/v1/) | 显式 (/v1/) | 显式 (/v2beta/) | `/v1/` |

## 参数命名对比

### 通用参数

| 概念 | A1111 | Stability AI (v1) | Stability AI (v2beta) | DreamAndroid |
|------|-------|-------------------|-----------------------|-------------|
| 提示词 | `prompt` | `text_prompts[].text` (数组) | `prompt` | `prompt` |
| 负面提示词 | `negative_prompt` | `text_prompts[].weight: -1` | `negative_prompt` | `negative_prompt` |
| 步数 | `steps` | `steps` | 无 (自动) | `steps` |
| CFG | `cfg_scale` | `cfg_scale` | 无 (自动) | `cfg_scale` |
| 宽度 | `width` | `width` (64倍数) | — | `width` |
| 高度 | `height` | `height` (64倍数) | — | `height` |
| 宽高比 | — | — | `aspect_ratio` (1:1, 16:9...) | `aspect_ratio` |
| 种子 | `seed` | `seed` | `seed` | `seed` (0=随机) |
| 采样器 | `sampler_name` | `sampler` | 无 (自动) | `sampler` |
| 降噪强度 | `denoising_strength` | `image_strength` | `strength` (SD3 端点) | `denoising_strength` |
| 风格 | — | `style_preset` (17种) | `style_preset` (17种) | — (不支持) |
| 批量生成 | `batch_size` + `n_iter` | `samples` (1~10) | — | `samples` (仅1) |
| 输出格式 | 隐式 (samples_format) | PNG base64 固定 | `output_format` (png/jpeg/webp) | PNG base64 |
| 完成原因 | — | `finishReason: SUCCESS/CONTENT_FILTERED` | 同 v1 | `finish_reason: SUCCESS` |

### V1 独有概念

| 概念 | 说明 |
|------|------|
| `text_prompts` 数组 | 正/负面提示词统一数组，weight 正负区分 |
| `init_image_mode` | `IMAGE_STRENGTH` (固定强度) vs `STEP_SCHEDULE` (步进调度) |
| `clip_guidance_preset` | CLIP 引导预设 (NONE/FAST_BLUE/SIMPLE/SLOW 等) |
| `samples` | 一次请求生成多张图 (1~10) |

### DreamAndroid 独有参数

| 参数 | 说明 |
|------|------|
| `use_opencl` | 是否启用 OpenCL 加速 |
| `use_cfg` | 是否启用 CFG 引导 |
| `denoise_curve` | 降噪曲线控制 |
| `show_diffusion_process` | 是否展示扩散过程 |
| `show_diffusion_stride` | 扩散过程展示步长 |

## 错误格式对比

| 字段 | A1111 | Stability AI | DreamAndroid |
|------|-------|-------------|-------------|
| `id` | — | ✅ 错误 ID (`{category}-{ts}`) | ✅ 错误 ID (同格式) |
| `name` | — | ✅ 错误类别 | ✅ 错误类别 (同格式) |
| `errors` | ✅ 错误字符串 | ✅ 错误字符串数组 | ✅ 错误字符串数组 |
| `error` | ✅ 错误类型 | ✅ 扩展字段 | ✅ 扩展字段 |
| `detail` | ✅ 详情 | ✅ 扩展字段 | ✅ 扩展字段 |
| `message` | — | ✅ 扩展字段 | ✅ 扩展字段 |
| `body` | ✅ 消息体 | ✅ 扩展字段 | ✅ 扩展字段 |
| `messages` | — | ✅ 扩展字段 | ✅ 扩展字段 |

**结论**: DreamAndroid 的错误格式与 Stability AI 对齐，两者均比 A1111 拥有更结构化的 `id/name/errors` 核心字段，
并额外提供 `error/detail/message/body/messages` 扩展字段以保持兼容性。

## 并发策略对比

| 维度 | A1111 | Stability AI | DreamAndroid |
|------|-------|-------------|-------------|
| 并发模式 | queue_lock + Gradio 队列排队 | 503 拒绝 | 503 拒绝 |
| 重试指示 | 无 (自动排队等待) | `Retry-After` 头 | `Retry-After: 3` 头 |
| 状态查询 | `GET /progress` | 无 | `GET /progress` |
| 超时保护 | 无 (无单请求超时) | 云端管理 | 300s 超时自动释放 |
| 状态机 | threading.Lock + Gradio 队列 | 云端调度 | CAS Idle↔Busy |

## 传输方式对比

| 维度 | A1111 | Stability AI (v1) | Stability AI (v2beta) | DreamAndroid |
|------|-------|-------------------|-----------------------|-------------|
| 生成结果 | JSON base64 (同步) | JSON base64 (同步) 或 image/png 二进制 | JSON base64 (同步) | SSE base64 (流式, 含 progress% + finish_reason) |
| 超分输入 | JSON base64 | multipart 图片文件 | — | 原始 RGB 字节 |
| 超分输出 | JSON base64 | JSON base64 | — | JPEG 二进制 |
| 进度推送 | 轮询 (progress 0~1, eta_relative) | 无 | 无 | SSE 推送 + 轮询 (progress 0~1) |
| 图片输入 | base64 / data URI / URL | multipart 文件上传 | multipart 文件上传 | base64 |
| 认证 | HTTP Basic (--api-auth) | Bearer Token | Bearer Token | 无 (本地进程) |
| 内容过滤 | 无 | ✅ (finishReason: CONTENT_FILTERED) | ✅ (同 v1) | 无 |

## 设计决策记录

### 为什么 DreamAndroid 采用 SSE 而非同步 REST？
- 移动端场景下生成时间长 (数分钟)，同步阻塞会导致 Android ANR
- SSE 允许逐 step 推送进度，UX 明显优于盲等
- 流式传输避免在内存中缓存完整 base64 响应 (对低内存设备友好)

### 为什么 DreamAndroid 错误格式对齐 Stability AI？
- Stability AI 的 `id/name/errors` 三字段模型结构清晰
- 扩展字段 `error/detail/message/body/messages` 提供与 A1111 的兼容性
- 统一的错误格式降低客户端适配成本

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-18 | v4 更新：DreamAndroid API v2 变更 — /v1/ 前缀、cfg→cfg_scale、denoise_strength→denoising_strength、samples(仅1)、seed=0随机、progress百分比、finish_reason、SSE error message字段 |
| 2026-06-18 | v3 更新：Stability AI 拆分为 v1/v2beta 双版本对比，补充 v2beta 端点 (Ultra/Core/SD3/Edit/Control/Video/3D)、参数差异 (prompt vs text_prompts、aspect_ratio vs width/height)、内容过滤 |
| 2026-06-18 | v2 更新：基于 A1111 官方源码修正并发策略 (queue_lock)、中断端点 (+/skip)、图片输入格式 (URL 支持)、认证机制 |
| 2026-06-18 | 初始创建 (v1) |
