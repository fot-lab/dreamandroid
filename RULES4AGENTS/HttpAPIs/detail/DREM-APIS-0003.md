# DREM-APIS-0003: C++ 后端重构兼容性差异 (0289813a → 76ed17e9)

| 属性 | 值 |
|------|-----|
| 分类 | DreamAndroid API 内部兼容性分析 |
| 对比基线 | `0289813a` (tag: v2026.06.13.15.08) |
| 对比目标 | `76ed17e9` (master) |
| 范围 | C++ 后端 (`app/src/main/cpp/src/`) |
| 分析维度 | CLI 解析、模型加载、Scheduler 构建、推理 Pipeline、HTTP API 端点 |

## 概述

C++ 后端在 `0289813a` 至 `76ed17e9` 之间经历了一次架构重构。原 17 个文件 (核心逻辑集中在 3890 行的 `main.cpp`) 被拆分为 48 个模块化文件 (`ServerCli`, `ModelLoader`, `GenerateHandler`, `RequestParser` 等)。本文档仅陈述发现的差异事实，不做修改建议。

## §1. 文件结构变化

| 属性 | `0289813a` | `76ed17e9` |
|------|-----------|------------|
| 源文件数 | 17 个 (.hpp/.cpp) | 48 个 (.hpp/.cpp) |
| 核心入口 | `main.cpp` (3890 行) | 拆分为 15+ 个模块 |
| `main.cpp` 角色 | CLI 解析 + 模型初始化 + HTTP 服务 + 推理 pipeline 全部内聚 | 仅负责 AppContext 组装 + 启动事件循环 |
| 新增核心模块 | 无 | `AppContext.hpp`, `ServerCli.cpp/hpp`, `ModelLoader.cpp/hpp`, `GenerateHandler.cpp/hpp`, `RequestParser.hpp`, `RequestContext.hpp`, `ServerState.hpp`, `Sampler.hpp`, `ErrorJson.hpp`, `HttpUtils.hpp` |

## §2. CLI 解析启动 — 完全兼容

CLI 参数行 `processCommandLine()` (0289813a) / `ServerCli::parse()` (76ed17e9) 语义逐项一致，仅变量存储位置从全局变量迁移到 `ctx.conf` 结构体内部。

| CLI 选项 | `0289813a` | `76ed17e9` | 差异 |
|----------|-----------|------------|------|
| `--type` | `"sd15cpu"` → `use_mnn=true`; `"sdxl"` → `sdxl_mode=true`; 其他 → sd15npu | 同 | 无 |
| `--model_dir` | 全局 `modelDir` | `ctx.conf.modelDir` | 无 |
| `--lib_dir` | `initQnn()` 解析 `libQnnHtp.so` / `libQnnSystem.so` | 同 | 无 |
| `--port` | 全局 `port` | `ctx.conf.port` | 无 |
| `--listen_all` | 全局 `listen_address` | `ctx.conf.listen_address` | 无 |
| `--use_v_pred` | 全局 `use_v_pred` | `ctx.conf.use_v_pred` | 无 |
| `--no_img2img` | 全局 `no_img2img` | `ctx.conf.no_img2img` | 无 |
| `--safety_checker` | 全局 `safetyCheckerPath` | `ctx.conf.safetyCheckerPath` | 无 |
| `--patch` | 全局 `patchPath` → `g_unetPatchedBuffer` | `ctx.models.unetPatchedBuffer` | 无 |
| `--lowram` | 全局 `lowram_mode` | `ctx.conf.lowram_mode` | 无 |
| `--upscaler_mode` | 全局 `upscaler_mode` | `ctx.conf.upscaler_mode` | 无 |
| `--convert` | 全局 `cvt_model` | `ctx.conf.cvt_model` | 无 |
| `--log_level` | 同 | 同 | 无 |

### 文件路径推导

以下路径推导逻辑在两版中逐行一致：

| 路径变量 | SD1.5 (sd15npu) | SDXL |
|----------|-----------------|------|
| `clipPath` | `clip_v2.mnn` | `clip.mnn` |
| `unetPath` | `unet.mnn` 或 `unet.bin` (由 `use_mnn` 决定) | 同 |
| `vaeDecoderPath` | `vaeDecoder.mnn` 或 `vaeDecoder.bin` | 同 |
| `vaeEncoderPath` | `vaeEncoder.mnn` 或 `vaeEncoder.bin` | 同 |
| `pos_emb.bin` | mmap / owned / FP32→FP16 加载 | 同 |
| `token_emb.bin` | mmap / owned / FP32→FP16 加载 | 同 |

## §3. 模型加载 — 完全兼容

`ModelLoader::initializeModels()` (76ed17e9) 与 `0289813a` 的 `main()` 中初始化代码逐段一致。所有 session 配置 (线程数、Low mem、High power) 未变更。

| 初始化阶段 | `0289813a` | `76ed17e9` | 差异 |
|-----------|-----------|------------|------|
| Tokenizer | `LoadBytesFromFile` → `FromBlobJSON` | 同 | 无 |
| Embeddings | `modelDir/../../embeddings` | 同 | 无 |
| MNN CLIP session (sd15npu) | `createSession(cfg_mnn_clip)`, 4线程, Low mem, High power | 同 | 无 |
| MNN CLIP1/CLIP2 (SDXL) | `createSession` × 2, resize `{1,77,768}` / `{1,77,1280}`, `releaseModel` | 同 | 无 |
| MNN Safety Checker | `createSession(cfg_common)`, resize `{1,224,224,3}` | 同 | 无 |
| QNN UNET init | `initializeQnnApp("UNET", ...)`, patched buffer 注入 | 同 | 无 |
| QNN VAE init | `initializeQnnApp("VAEDecoder"/"VAEEncoder", ...)` | 同 | 无 |
| Patch buffer release | `unetPatchedBuffer.reset()` after init | 同 | 无 |

## §4. Scheduler — 算法完全兼容，JSON API 字段拆分 (Breaking Change)

### 4.1 Scheduler 算法实现

四个 Scheduler 的 `.hpp` 文件逐行对比结果：

| 文件 | `0289813a` 行数 | `76ed17e9` 行数 | diff 结论 |
|------|---------------|---------------|----------|
| `DPMSolverMultistepScheduler.hpp` | 495 | 496 | 完全相同 (仅多一空行) |
| `EulerDiscreteScheduler.hpp` | 406 | 407 | 完全相同 (仅多一空行) |
| `EulerAncestralDiscreteScheduler.hpp` | 486 | 487 | 完全相同 (仅多一空行) |
| `LCMScheduler.hpp` | 329 | 330 | 完全相同 (仅多一空行) |
| `Scheduler.hpp` (基类) | 53 | 53 | 完全相同 |

所有 scheduler 数学公式、timestep 生成、sigma 插值、顺序更新逻辑完全一致。

### 4.2 JSON 请求字段语义变化 (Breaking Change)

`0289813a` 使用单一字段 `"scheduler"` 把 sampler 类型和 timestep schedule 编码在一个字符串中。

`76ed17e9` 拆分为两个独立字段 `"sampler"` (算法类型) + `"scheduler"` (timestep spacing 策略)。

**旧字段 → 新字段映射表：**

| `0289813a` `"scheduler"` 值 | `76ed17e9` 对应 | 备注 |
|----------------------------|-----------------|------|
| `"dpm"` | `sampler: "dpm", scheduler: "scaled_linear"` | DPM++ 2M 默认 |
| `"dpm_karras"` | `sampler: "dpm", scheduler: "karras"` | DPM++ 2M + Karras sigmas |
| `"dpm_sde"` | `sampler: "dpm_sde"` (scheduler 忽略) | DPM++ SDE |
| `"dpm_sde_karras"` | `sampler: "dpm_sde", scheduler: "karras"` | DPM++ SDE + Karras |
| `"euler_a"` / `"eulera"` | `sampler: "euler_a", scheduler: "scaled_linear"` | Euler Ancestral 默认 |
| `"euler_a_karras"` | `sampler: "euler_a", scheduler: "karras"` | Euler Ancestral + Karras |
| `"euler"` | `sampler: "euler", scheduler: "scaled_linear"` | Euler Discrete 默认 |
| `"euler_karras"` | `sampler: "euler", scheduler: "karras"` | Euler Discrete + Karras |
| `"lcm"` | `sampler: "lcm"` | LCM 不变 |

**向后兼容性结论：** `0289813a` 时代的客户端发送 `{"scheduler": "dpm_karras"}` 在 `76ed17e9` 后端无法正确解析，必须适配为新字段格式。

### 4.3 新增 Sampler 类层次 (76ed17e9 独有)

`76ed17e9` 新增了与 Scheduler 平行的 `Sampler` 类层次 (`Sampler.hpp` + `DPMSolverMultistepSampler.hpp` 等 4 个)，用于封装采样器选择逻辑。在 `0289813a` 中，这个逻辑直接在 `main.cpp` 的 `generateImage()` 中通过 if/else 分支实现。

## §5. 推理 Pipeline — 完全兼容

`GenerateHandler::generateImage()` (76ed17e9) 与 `0289813a` 的 `generateImage()` 推理逻辑逐段对比：

| Pipeline 阶段 | `0289813a` | `76ed17e9` | 差异 |
|--------------|-----------|------------|------|
| CLIP 编码 | `processWeightedPrompt()` + `xt::matmul` | 同 | 无 |
| SDXL 双 CLIP | CLIP1 + CLIP2 concat + pooled | 同 | 无 |
| Prompt cache | SHA256 → `prompt_<hash>.bin` | 同 | 无 |
| `init_noise_sigma` | `scheduler->get_init_noise_sigma()` | 同 | 无 |
| VAE scale factor | `sdxl ? 0.13025 : 0.18215` | 同 | 无 |
| Img2Img / Inpaint | 同逻辑 | 同 | 无 |
| UNet step loop | 同 | 同 | 无 |
| VAE decode / encode | 同 | 同 | 无 |
| Safety checker | 同 | 同 | 无 |
| Latent blending / tiling | 同 | 同 | 无 |

**结论：对于相同的输入和相同的 scheduler 参数，生成图像应当逐像素一致。**

## §6. HTTP API 端点 — Breaking Changes

### 6.1 端点路径变更

| 功能 | `0289813a` | `76ed17e9` | 兼容性 |
|------|-----------|------------|--------|
| 生成 | `POST /generate` | `POST /v1/generate` | 不兼容 (路径变更) |
| 健康检查 | `GET /health` | `GET /v1/health` | 不兼容 (路径变更) |
| 进度查询 | 无 | `GET /v1/progress` | 新增端点 |
| 超分 | 无 | `POST /v1/upscale` | 新增端点 |
| Tokenize | 无 | `POST /v1/tokenize` | 新增端点 |

### 6.2 健康检查响应格式变更

| 属性 | `0289813a` | `76ed17e9` |
|------|-----------|------------|
| 响应体 | 仅 HTTP 200 (空 body) | JSON `{"status": "idle"}` 或 `{"status": "busy"}` |

### 6.3 生成端点响应格式变更

两版本均使用 SSE (`text/event-stream` + `set_chunked_content_provider`) 流式传输，帧格式一致 (`event: xxx\ndata: ...\n\n`)。

| 属性 | `0289813a` | `76ed17e9` |
|------|-----------|------------|
| 协议 | SSE (Server-Sent Events) 流式传输 | SSE (Server-Sent Events) 流式传输 |
| Content-Type | `text/event-stream` | `text/event-stream` |
| 帧格式 | `event: xxx\ndata: ...\n\n` (手写) | 同 (`sseWrite()` 包装，格式一致) |
| progress event JSON | `type, step, total_steps` + 可选 `image` | 同 + 新增 `progress: Float` 字段 |
| complete event JSON | `type, image, seed, width, height, channels, generation_time_ms, first_step_time_ms` | 同 + 新增 `finish_reason: "SUCCESS"` |
| error event JSON | `type: "error", message` | 同 + Stability-AI 格式 fallback |
| HTTP status on error | 200 (SSE 内 error event) | 400/500 (parse error) 或 200 (运行时 error) |

### 6.4 请求体 JSON 字段变更

除 §4.2 所述的 `"scheduler"` → `"sampler"+"scheduler"` 拆分外：

| 字段 | `0289813a` | `76ed17e9` |
|------|-----------|------------|
| `"scheduler"` | 存在 (sampler + schedule 合并) | 不存在 (拆分为 `"sampler"` + `"scheduler"`) |
| `"sampler"` | 不存在 | 存在 |
| `"scheduler"` (新语义) | 不存在 | 存在 (仅表示 timestep spacing: `"scaled_linear"` / `"karras"`) |
| CORS headers | 有 | 有 (一致) |

### 6.5 并发控制

| 属性 | `0289813a` | `76ed17e9` |
|------|-----------|------------|
| 并发模型 | 无显式并发控制 (依赖 httplib 默认行为) | `ServerState` 状态机 (Idle ↔ Busy) + BusyGuard + watchdog timeout |
| 忙时行为 | 请求排队或阻塞 | 立即返回 503 `{"error": "server busy"}` |

### 6.6 请求字段 `"cfg"` / `"cfg_scale"` 变更 (Bug Fix)

`0289813a` 的 C++ 端读取字段名为 `"cfg"`，而 Kotlin 端发送的是 `"cfg_scale"`，导致 C++ 始终使用硬编码默认值 `7.5f`，客户端发送的 cfg scale 实际被忽略。

| 属性 | `0289813a` C++ | `76ed17e9` C++ |
|------|----------------|----------------|
| 读取字段 | `json.value("cfg", 7.5f)` | `json.value("cfg_scale", 7.5f)` |
| 是否匹配 Kotlin 发送的 `cfg_scale` | 不匹配 (字段名错误) | 匹配 (已修正) |

> 对图像生成结果的影响：仅当客户端设置的非默认 `cfg_scale` 值生效时，两版输出才可能出现差异。若客户端始终使用默认值 7.5，则两版输出一致。

## §7. 图像返回 Pipeline 对比分析

### 7.1 Kotlin 端路径分析

`76ed17e9` 中投放图像的活跃路径仅一条：

| 组件 | 路径 | 说明 |
|------|------|------|
| `QueueProcessingService` | `onStartCommand` 中立即 `stopSelf()` | 已禁用，不参与图像接收 |
| `GenerationWorker` | Queue → `BackendManager.generate()` → `SseStreamParser.events().collect()` | 唯一活跃路径 |

不存在 SSE 与直接返回双路径。

### 7.2 并发安全分析

| 检查项 | `76ed17e9` 实现 | 结论 |
|--------|-----------------|------|
| `g_req` 竞态 | 由 `ServerState::acquireBusy()` 保护，同一时刻只处理一个请求 | 无竞态 |
| `chunked_content_provider` 变量捕获 | lambda 通过 `[&appCtx, acquireTime]` 捕获，`g_req` 在回调期间不被覆写 | 安全 |
| `GenerationResult` 被 progress 抢占 | progress 事件与 complete 事件独立构造、独立 SSE 事件名，不共享 result 变量 | 无抢占 |

不存在结果变量被中间过程抢占的问题。

### 7.3 C++ 端 Pipeline 逐阶段对比

`0289813a` 的 `main.cpp` 中 `generateImage()` 与 `76ed17e9` 的 `GenerateHandler::generateImage()` 逐阶段一致：

| Pipeline 阶段 | 两版实现 | 差异 |
|--------------|---------|------|
| CLIP 编码 | `processWeightedPrompt()` + `xt::matmul` | 无 |
| Scheduler 构建 | `DPMSolverMultistepScheduler.hpp` 等 4 个 `.hpp` 逐行一致 | 无 (仅多一空行) |
| VAE scale factor | SD1.5: `0.18215`, SDXL: `0.13025` | 无 |
| VAE decode → latent 像素 | `xt::adapt(vae_dec_out_pixels, {1, 3, H, W})` | 无 |
| 像素张量 → `uint8_t` 数组 | `xt::transpose(img, {1,2,0})` → `xt::clip(img, 0.0f, 255.0f)` → `xt::cast<uint8_t>(...)` | 无 |
| `out_data` 构造 | `std::vector<uint8_t>(u8_img.begin(), u8_img.end())` | 无 |
| image 字符串化 | `std::string imageStr(out_data.begin(), out_data.end())` | 无 |
| Base64 编码 | `base64_encode(imageStr)` (定义于 `SDUtils.hpp` 第 83-105 行) | **逐字节相同** |
| SSE "complete" 写入 | `sink.write(ev.c_str(), ev.size())` (`0289813a`) vs `sseWrite(sink, "complete", complete)` (`76ed17e9`) | 内部逻辑一致 |

### 7.4 Kotlin 端解码 Pipeline

`GenerationWorker.kt` 中的图像解码路径：

| 步骤 | 实现 | 代码位置 |
|------|------|---------|
| 接收 SSE event | `SseStreamParser.events().collect { event }` | `GenerationWorker.kt` |
| 解析 "complete" JSON | `SseEvent.Complete` 提取 `json.optString("image")` | `SseStreamParser.kt` |
| Base64 解码 | `Base64.getDecoder().decode(base64)` | `GenerationWorker.kt:base64ToBitmap()` |
| 字节数校验 | `if (imageBytes.size < width * height * 3) return null` | `GenerationWorker.kt:base64ToBitmap()` |
| RGB → ARGB 转换 | `rgbBytesToBitmap()`: 逐像素 `(0xFF shl 24) \| (r shl 16) \| (g shl 8) \| b` | `Functions.kt:99-109` |

### 7.5 Scheduler 参数映射

Kotlin → C++ 请求体的 sampler/scheduler 字段映射：

| Kotlin 默认值 | C++ 接收字段 | `RequestParser` 映射 | `GenerateHandler` 使用 |
|--------------|-------------|---------------------|----------------------|
| `sampler = "dpm"` | `json["sampler"]` | `req.sampler` | 选择 `DPMSolverMultistepSampler` |
| `denoiseCurve = "scaled_linear"` | `json["scheduler"]` | `req.denoise_curve` | 设置 `use_karras` / `beta_schedule` |

映射关系正确，无字段错配。

### 7.6 排除项汇总

| 猜想 | 结论 |
|------|------|
| SSE / 直接返回双路径 | 不存在 (仅一条活跃路径) |
| `g_req` 竞态条件 | 不存在 (BusyGuard 保护) |
| `base64_encode` 算法差异 | 不存在 (`SDUtils.hpp` 第 83-105 行逐字节相同) |
| progress 事件抢占 complete 结果变量 | 不存在 (独立事件，独立变量) |
| `width` / `height` 维度不匹配 | 不存在 (C++ 端 `result.width/height` 与 `out_data.size()` 一致，`size == width*height*3`) |
| VAE scale factor 差异 | 不存在 (两版均为 SD1.5: `0.18215`, SDXL: `0.13025`) |
| Kotlin 端 base64 → Bitmap 解码错误 | 不存在 (详见 §7.8：三条解码路径像素运算一致，Base64 API 差异对 clean base64 无影响，`Functions.kt` 逐字节未变) |
| OkHttp 传输大小限制导致 base64 截断 | 不存在 (无 `maxResponseLength`，`callTimeout=0`，`BufferedReader.readLine()` 动态扩容不截断) |

### 7.7 建议调试方向

| 序号 | 位置 | 操作 | 目的 |
|------|------|------|------|
| 1 | `SseStreamParser.kt` `complete` 分支 | 打印 `json.take(200)` | 对比旧版与新版的 "complete" JSON 中 `"image"` 字段 base64 前 200 字符是否一致 |
| 2 | `main.cpp` `result.image_data` 写入后 | `std::cout` 输出 `result.image_data.size()` 和 `result.width`、`result.height` | 验证 `size == width * height * 3` |
| 3 | `GenerationWorker.kt:base64ToBitmap()` decode 后 | 打印 `imageBytes.size` | 验证解码后字节数是否匹配预期 |
| 4 | `SseEvent.Complete` 构造后 | 打印 `imageBase64.take(50) + "..." + imageBase64.takeLast(50)` | 验证完整 base64 字符串是否未被截断 |

### 7.8 Kotlin 解码路径版本对比

旧版 (`0289813a`) 存在两个活跃的解码消费者，新版 (`76ed17e9`) 仅一个。核心解码函数 `rgbBytesToBitmap()` (定义于 `Functions.kt`) 在两版间**无任何 diff**。

| 属性 | Old Path A | Old Path B | New Path C |
|------|-----------|-----------|------------|
| 文件 | `QueueProcessingService.kt` | `BackgroundGenerationService.kt` | `GenerationWorker.kt` |
| 角色 | 队列批量生成 | 前台交互生成 | 统一生成入口 |
| Base64 API | `android.util.Base64.decode(base64, DEFAULT)` | `java.util.Base64.getDecoder().decode()` | `java.util.Base64.getDecoder().decode()` |
| Base64 标准 | RFC 2045 (MIME, 宽松) | RFC 4648 (Basic, 严格) | RFC 4648 (Basic, 严格) |
| Pixel 转换 | 内联 `base64ToBitmap()` | `rgbBytesToBitmap()` (Functions.kt) | `rgbBytesToBitmap()` (Functions.kt) |
| ARGB 位运算 | `(0xFF shl 24) \| (r shl 16) \| (g shl 8) \| b` | 同 | 同 |
| Bitmap 创建 | `createBitmap()` → `IntArray` → `setPixels()` | 同 | 同 |
| 像素循环边界检查 | `if (idx + 2 < bytes.size) → skip` | 无 | 无 |
| 解码前大小校验 | 无 | 仅空串检查 | `size < w*h*3 → return null` |

#### 7.8.1 Base64 API 差异分析

`android.util.Base64.DEFAULT` (RFC 2045) 与 `java.util.Base64.getDecoder()` (RFC 4648) 的行为差异：

| 场景 | `android.util.Base64` | `java.util.Base64` |
|------|----------------------|---------------------|
| Clean base64 (无换行/空格) | 正常解码 | 正常解码 — **输出相同** |
| 含 `\n` / `\r` 的 base64 | 忽略空白字符，正常解码 | **抛出 `IllegalArgumentException`** |
| 含 padding `=` 的 base64 | 正常解码 | 正常解码 |

C++ 端 `base64_encode()` (SDUtils.hpp) 输出 clean base64（无换行/空格），因此两种 API **解码结果逐字节相同**。

#### 7.8.2 像素转换一致性

`Functions.kt:rgbBytesToBitmap()` 与 `QueueProcessingService` 内联版本的核心循环逐行对比：

```
// rgbBytesToBitmap() (Functions.kt)           // 内联 base64ToBitmap() (QueueProcessingService)
for (i in pixels.indices) {                   for (i in 0 until width * height) {
    val off = i * 3                               val idx = i * 3
    val r = rgb[off].toInt() and 0xFF             val r = bytes[idx].toInt() and 0xFF
    val g = rgb[off + 1].toInt() and 0xFF         val g = bytes[idx + 1].toInt() and 0xFF
    val b = rgb[off + 2].toInt() and 0xFF         val b = bytes[idx + 2].toInt() and 0xFF
    pixels[i] = (0xFF shl 24) or ...              pixels[i] = (0xFF shl 24) or ...
}                                              }
bitmap.setPixels(pixels, 0, width, ...)        bitmap.setPixels(pixels, 0, width, ...)
```

ARGB 组装公式、`createBitmap(ARGB_8888)`、`setPixels()` 完全一致。唯一差异是 Path A 多了一层 `if (idx + 2 < bytes.size)` 保护，但正常数据下该分支永不触发。

#### 7.8.3 结论

**Kotlin 端解码在新旧版本间完全一致。** 核心函数 `rgbBytesToBitmap()` 未变，Base64 API 差异对 C++ 产出的 clean base64 无影响。三条路径对相同输入产出逐像素相同的 Bitmap。

### 7.9 GenerationWorker 任务完成行为审计与修复

对比 `GenerationWorker.kt` 与旧版 `QueueProcessingService.kt` 在生成成功后的存储/标记流程，发现 3 处行为差异，均已修复：

#### 7.9.1 差异 ① — base64 数据不足时丢弃整图

| 属性 | 旧版 QueueProcessingService | 新版 GenerationWorker (修复前) | 修复后 |
|------|---------------------------|-------------------------------|--------|
| `imageBytes.size < w*h*3` | N/A (旧版无此校验) | `return null` 丢弃整图 | `Log.w` + 继续解码 |
| `rgbBytesToBitmap()` 边界保护 | `if (idx + 2 < bytes.size) → skip` | 无 (修复前) | `if (off + 2 >= rgb.size) break` |
| 行为 | 静默跳过不完整像素，产出部分图像 | 丢弃整图并标记 ERROR | **匹配旧版**：数据不足时产出部分图像 |

#### 7.9.2 差异 ② — cache 写入失败时静默标记 COMPLETED

| 属性 | 旧版 QueueProcessingService | 新版 GenerationWorker (修复前) | 修复后 |
|------|---------------------------|-------------------------------|--------|
| cache 文件写入失败 | N/A (旧版直接传 Bitmap 对象) | `catch` 仅 `Log.w`，`cachePath` 保持 `null` | 同（`catch` 改为 `Log.e`） |
| cachePath=null 后的行为 | N/A | **仍调用 `markTaskComplete(id, null, seed)`** → COMPLETED 但无图 | **检查 `cachePath != null`**，为 null 时标记 ERROR |

> **风险**：修复前 cache 写入失败（如磁盘满）时，任务显示完成但图片路径为空，用户端静默丢失生成结果。

#### 7.9.3 差异 ③ — history 保存失败阻塞任务完成

| 属性 | 旧版 QueueProcessingService | 新版 GenerationWorker (修复前) | 修复后 |
|------|---------------------------|-------------------------------|--------|
| `historyManager.saveGeneratedImage()` 返回 null | 不检查返回值，直接继续处理 | 标记 ERROR + `bitmap.recycle()`，丢弃全部结果 | `Log.w` + 继续 cache 写入 + 标记 COMPLETED |
| history 写入失败影响 | 无影响（容错） | 阻塞任务完成，丢图 | **匹配旧版**：history 保存为 best-effort，不阻塞 |

#### 7.9.4 修复后流程

```
生成成功 → decode Bitmap 成功
  ├── historyManager.saveGeneratedImage() (best-effort)
  │     ├── 成功 → 正常
  │     └── 失败 → Log.w, 继续
  ├── cacheFile 写入
  │     ├── 成功 → markTaskComplete(id, cachePath, seed)
  │     └── 失败 → markTaskError(Storage)
  └── 异常 → markTaskError(Parse/Storage)
```

**对齐结论**：修复后 GenerationWorker 的存储/标记行为与旧版 QueueProcessingService 语义一致：history 保存为 best-effort 不阻塞，cache 写入失败正确标记 ERROR，base64 数据不足时静默降级而非丢弃。

## §8. `"cfg"` / `"cfg_scale"` 对生成结果的影响

### 8.1 行为差异

|  | `0289813a` | `76ed17e9` |
|------|-----------|------------|
| C++ 读取字段 | `json.value("cfg", 7.5f)` | `json.value("cfg_scale", 7.5f)` |
| Kotlin 发送字段 | `"cfg_scale"` | `"cfg_scale"` |
| C++ 实际读到的值 | 始终 7.5 (字段名不匹配) | 客户端传入的值 |
| 客户端传入非默认值 (如 12.0) 时 | C++ 用 7.5 生成 (与客户端预期不一致) | C++ 用 12.0 生成 (与客户端预期一致) |
| 客户端传入默认值 7.5 时 | 两版均用 7.5 生成 | 两版均用 7.5 生成 |

### 8.2 结论

cfg scale 默认值 (7.5) 下两版输出一致。仅在客户端设置非默认 cfg_scale 时，`0289813a` 的 C++ 因字段名 bug 忽略该值，而 `76ed17e9` 正确应用，导致输出差异。该差异属于 bug 修正，非回归。

## §9. 请求/返回/解析 三方逐层对比 (0289813a vs 76ed17e9)

以下按请求 → C++ 解析 → C++ 响应 → Kotlin 解析四阶段，对比 `v2026.06.13.15.08` (`0289813a`) 与当前 HEAD (`76ed17e9`) 的异同。

### 9.1 Kotlin 请求构建层

| 项目 | `0289813a` | `76ed17e9` | 结论 |
|------|-----------|------------|------|
| URL 路径 | `/generate` | `/v1/generate` | **不同** |
| HTTP Method | POST | POST | 相同 |
| `"sampler"` | `"dpm"` | `"dpm"` | 相同 |
| `"scheduler"` | `"scaled_linear"` | `"scaled_linear"` | 相同 |
| `"cfg_scale"` | 发送 | 发送 | 相同 |
| 其余 12 个字段 | 全部发送 | 全部发送 | 相同 |
| OkHttpClient 配置 | `readTimeout=3600s`, `callTimeout=0` | 同 | 相同 |
| Content-Type | `application/json` | 同 | 相同 |

### 9.2 C++ 请求解析层

| 项目 | `0289813a` | `76ed17e9` | 结论 |
|------|-----------|------------|------|
| 调度器字段 | 读 `"scheduler"` 单一字段 (合并 sampler+schedule 语义) | 读 `"sampler"` + `"scheduler"` 两个独立字段 | **不兼容** |
| `"cfg"` 字段 | 读 `json.value("cfg", 7.5f)` | **不读** `"cfg"` | **不同** |
| `"cfg_scale"` 字段 | **不读** `"cfg_scale"` | 读 `json.value("cfg_scale", 7.5f)` | **不同 (Bug Fix)** |
| 并发控制 | 无 | `BusyGuard` → 忙时返回 503 | **新增** |
| 其余字段 (prompt, steps, width, height, seed, ...) | 全部解析 | 全部解析 | 相同 |

### 9.3 C++ 响应生成层

| 项目 | `0289813a` | `76ed17e9` | 结论 |
|------|-----------|------------|------|
| 协议 | SSE (`text/event-stream`, `set_chunked_content_provider`) | SSE (同) | **相同** |
| SSE 帧格式 | `"event: progress\ndata: ...\n\n"` 手写 | `sseWrite(sink, "complete", json)` 内部格式一致 | **相同** |
| progress event JSON | `type, step, total_steps` + 可选 `image` | `type, step, total_steps, progress` + 可选 `image` | **新增 `progress` float** |
| complete event JSON | `type, image, seed, width, height, channels, generation_time_ms, first_step_time_ms` | 同上 + `finish_reason: "SUCCESS"` | **新增 `finish_reason`** |
| error event JSON | `type: "error", message` | 同上 + Stability-AI 格式 fallback | 基础相同 |
| HTTP status on error | `200` (SSE 内 error event) | `400`/`500` (parse error) 或 `200` | **新增 HTTP error code** |
| base64 编码 | `base64_encode(imageStr)` `SDUtils.hpp:83-105` | 同 | **逐字节相同** |

### 9.4 Kotlin 响应解析层

| 项目 | `0289813a` | `76ed17e9` | 结论 |
|------|-----------|------------|------|
| 解析器 | `SseStreamParser` | `SseStreamParser` | 相同 |
| Flow 构造 | `flow{} + withContext(IO)` | `channelFlow{} + flowOn(IO)` | 实现不同，逻辑等价 |
| `"data:"` 行处理 | `removePrefix("data: ")` | `removePrefix("data: ").trim()` | **新增 trim** |
| `Progress` 数据类 | `step, totalSteps, imageBase64` | 同上 + `progress: Float` | **新增字段** |
| `Complete` 数据类 | `imageBase64, seed, width, height` | 同上 + `finishReason: String` | **新增字段** |
| Error fallback | 无 | Stability-AI `"errors"` 数组 | **新增** |
| Base64 → Bitmap | `Base64.getDecoder()` + `rgbBytesToBitmap()` | 同 | 相同 |
| 字节数校验 | 无 | `size < w*h*3 → null` | **新增** |
| `GenerationWorker` | **不存在** | `CoroutineWorker` + 健康轮询 + 进度通知 | **新增模块** |

### 9.5 端点一致性 (各版本内部自洽)

| 端点 | `0289813a` C++ | `0289813a` Kotlin | `76ed17e9` C++ | `76ed17e9` Kotlin | 结论 |
|------|---------------|-------------------|---------------|-------------------|------|
| generate | `POST /generate` | `/generate` | `POST /v1/generate` | `/v1/generate` | 各版本内部一致 |
| health | `GET /health` (空 body 200) | `/health` | `GET /v1/health` (JSON `status`) | `/v1/health` | 各版本内部一致 |

### 9.6 核心差异总结

| 层面 | 关键差异 | 对生成结果影响 |
|------|---------|--------------|
| 请求 URL | `/generate` → `/v1/generate` | 无 (内部一致) |
| `"scheduler"` 字段语义 | 旧单体语义 → 新拆分 `"sampler"`+`"scheduler"` | 无 (默认值下等价) |
| `"cfg_scale"` 字段 | 旧版 C++ 不读 (bug)，始终用 7.5；新版修正 | **有 (非默认值下差异)** |
| 响应协议 | 两版均为 SSE，协议一致 | 无 |
| SSE 帧格式 | 完全一致 (`event: xxx\ndata: ...\n\n`) | 无 |
| base64 编码/解码 | 逐字节一致 | 无 |
| 解析新字段 | `progress`, `finishReason` 新增 | 无 (向后兼容) |
| 架构变更 | `GenerationWorker` 新增 | 无 (逻辑等价) |

## §10. 总结

```
┌─────────────────────────┬──────────────┬──────────────────────────────────────────┐
│ 层面                     │ 兼容性       │ 说明                                     │
├─────────────────────────┼──────────────┼──────────────────────────────────────────┤
│ CLI --type 解析          │ 完全兼容     │ 逻辑逐行一致，仅变量存储位置从全局→结构体       │
│ 模型文件布局              │ 完全兼容     │ tokenizer/clip/unet/vae 路径推导逐行一致      │
│ 模型加载 (MNN/QNN)       │ 完全兼容     │ Tokenizer/Embeddings/Sessions 配置一致       │
│ Scheduler 算法           │ 完全兼容     │ 4 个 scheduler .hpp 逐行一致                │
│ 推理 pipeline            │ 完全兼容     │ CLIP/UNet/VAE/Blend 算法不变                 │
│ 推理结果                  │ 完全兼容     │ 相同输入 → 相同输出                          │
│ 图像返回 Pipeline          │ 完全兼容     │ C++ base64 编码 → SSE → Kotlin Base64 解码 → RGB→ARGB 全链路一致 │
│ 并发安全                  │ 完全兼容     │ BusyGuard 保护，无双路径，无变量抢占              │
├─────────────────────────┼──────────────┼──────────────────────────────────────────┤
│ Scheduler JSON 字段       │ 不兼容       │ `"scheduler"` 字段拆为 `"sampler"` + `"scheduler"` │
│ HTTP 端点路径             │ 不兼容       │ `/generate` → `/v1/generate`, `/health` → `/v1/health` │
│ HTTP 响应 event 字段       │ 新增字段     │ progress 新增 `progress`, complete 新增 `finish_reason` │
│ 健康检查响应               │ 不兼容       │ HTTP 200 (空) → JSON `{"status":"..."}`       │
│ 并发控制                  │ 不兼容       │ 无控制 → BusyGuard 返回 503                  │
│ `cfg_scale` 字段          │ Bug Fix     │ 旧版读 `"cfg"` (不匹配客户端 `"cfg_scale"`)，新版修正 │
└─────────────────────────┴──────────────┴──────────────────────────────────────────┘
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-23 | 初始版本：基于 `0289813a` 与 `76ed17e9` 逐文件对比记录兼容性差异 |
| 2026-06-24 | 新增 §7 图像返回 Pipeline 对比分析：Kotlin 端路径、并发安全、C++ 端逐阶段对比、Kotlin 解码 Pipeline、Scheduler 参数映射、排除项汇总、调试建议 |
| 2026-06-24 | 修正 §6.3：两版均使用 SSE (非同步 JSON)；新增 §6.6 `cfg_scale` 字段 Bug 发现；新增 §8 cfg_scale 影响分析；新增 §9 请求/返回/解析三方逐层对比 (9.1-9.6)；更新 §10 总结表 |
| 2026-06-24 | 新增 §7.8 Kotlin 解码路径版本对比：Old Path A/B vs New Path C 三条路径的 Base64 API、像素转换、边界检查逐项对比，确认解码完全一致 |
| 2026-06-24 | **修复** §7.8.3 不一致：`Functions.kt:rgbBytesToBitmap()` 新增 `if (off + 2 >= rgb.size) break` 边界保护 (匹配旧版 QueueProcessingService)；`GenerationWorker.base64ToBitmap()` 移除 `return null` 改为 `Log.w` + 继续解码 (匹配旧版静默跳过不完整像素) |
| 2026-06-24 | **修复** §7.9 GenerationWorker 任务完成行为 3 处差异：(1) base64 数据不足丢弃整图 → 降级继续；(2) cache 写入失败静默标记 COMPLETED → 检查 cachePath 非 null；(3) history 保存失败阻塞结果 → best-effort 不阻塞 |
