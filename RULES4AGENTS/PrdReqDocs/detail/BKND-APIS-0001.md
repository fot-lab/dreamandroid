# BKND-APIS-0001: 后端 HTTP 接口规范

| 属性 | 值 |
|------|-----|
| 分类 | Backend APIs |
| 对应章节 | §3 |
| 依赖 | ARCH-OVER-0001 |

## 概述

后端为 C++ 原生进程，通过 `cpp-httplib` 在端口 8081 上提供 HTTP 服务。

## 接口定义

### 1. 健康检查

| 项目 | 描述 |
|------|------|
| **端点** | `GET http://localhost:8081/health` |
| **功能** | 验证后端 HTTP 服务是否在线可用 |
| **请求体** | 无 |
| **响应** | HTTP 200 (服务可用) 或连接拒绝/超时 (不可用) |
| **超时** | 连接/读取超时 3s |
| **调用方** | `GenerationWorker.waitForBackend()` (每 3s 轮询), `BackendManager.healthCheckWithRetry()` |
| **重试策略** | Queue 侧: 连续轮询直到后端上线 (3s 间隔)，不自动重启后端 |

> **重要:** Queue 不负责启动或重启后端 C++ 进程。后端生命周期由 Model Screen 通过 `BackendService` (Foreground Service) 手动管理。

### 2. 图片生成

| 项目 | 描述 |
|------|------|
| **端点** | `POST http://localhost:8081/generate` |
| **功能** | 提交图像生成请求，返回 SSE 流式响应 |
| **Content-Type** | `application/json` |
| **超时** | 连接/读/写/Call 超时 3600s |

**请求参数 (JSON Body):**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `prompt` | string | 是 | — | 正向提示词 |
| `negative_prompt` | string | 否 | `""` | 负面提示词 |
| `steps` | int | 是 | 28 | 采样步数 (1-50) |
| `cfg` | float | 是 | 7.0 | CFG 引导系数 (1.0-30.0) |
| `use_cfg` | bool | 是 | true | 是否使用 CFG |
| `width` | int | 是 | 512 | 生成宽度 (64-4096) |
| `height` | int | 是 | 512 | 生成高度 (64-4096) |
| `denoise_strength` | float | 是 | 0.6 | 去噪强度 (图生图) |
| `use_opencl` | bool | 是 | false | 是否使用GPU (CPU模型) |
| `scheduler` | string | 是 | `"dpm"` | 调度器选择 |
| `show_diffusion_process` | bool | 是 | false | 是否返回中间步骤预览 |
| `show_diffusion_stride` | int | 是 | 1 | 中间预览步长 |
| `aspect_ratio` | string | 是 | `"1:1"` | 宽高比 |
| `seed` | long | 否 | 随机 | 随机种子 |
| `image` | string (base64) | 否 | — | 输入图片 (图生图) |
| `mask` | string (base64) | 否 | — | 蒙版图片 (Inpainting) |

**调度器选项 (scheduler):**
- `dpm` — DPM++ 2M
- `dpm_sde` — DPM++ 2M SDE
- `euler_a` — Euler A
- `euler` — Euler
- `lcm` — LCM
- 以上均可附加 `_karras` 后缀 (LCM 除外)

**响应格式 (SSE Streaming):**
```
data: {"type":"progress","step":1,"total_steps":20,"image":"<base64>"}
data: {"type":"complete","image":"<base64>","seed":12345,"width":512,"height":512}
data: [DONE]
```

### 3. 超分辨率放大

| 项目 | 描述 |
|------|------|
| **端点** | `POST http://localhost:8081/upscale` |
| **功能** | 对图片进行超分辨率放大 |
| **Content-Type** | `application/octet-stream` |
| **请求体** | RGB 原始字节 (width×height×3 bytes) |
| **超时** | 连接/读取超时 300s |
| **请求头:** `X-Image-Width`, `X-Image-Height`, `X-Upscaler-Path` |
| **响应:** RGB 字节流 (4× 放大后尺寸) |

### 4. Token 计数

| 项目 | 描述 |
|------|------|
| **端点** | `POST http://localhost:8081/tokenize` |
| **功能** | 计算提示词的 CLIP Token 数量 |
| **Content-Type** | `application/json` |
| **超时** | 连接 2s，读取 5s |
| **请求:** `{"prompt": "string"}` |
| **响应:** `{"count": int, "max_length": 77, "overflow_offset": int}` |

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §3 内容，创建独立文件 |
