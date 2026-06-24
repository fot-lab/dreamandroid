# DFLW-INTG-0017: cfg_scale / sampler / scheduler 参数全链路审计 + 颜色编码对齐验证

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Data Flow Integrity |
| 关联 | DFLW-INTG-0016, BKND-PROC-0001, BKND-PROC-0003 |

## 问题描述

针对 `cfg_scale`、`sampler`、`scheduler`（denoise curve）三个核心生成参数，从前端 Kotlin 发送 → JSON 序列化 → C++ 后端解析 → Scheduler 构造 → UNET 推理全程做全链路审计。同时排查：

1. CLI 启动参数是否正确选取和传递给模型
2. MNN / QNN 两条推理路径的颜色编码是否对齐（像素格式、通道顺序、量化精度、输出编码）
3. 是否存在 C++ 全局状态变量被占用导致结果无法正常输出
4. 是否沿用 `v2026.06.13.15.08` 参考版本的"固定 canvas + mask 裁剪""方法

## 涉及文件

| 文件 | 角色 |
|------|------|
| `app/src/main/java/.../model/GenerateParams.kt` | Kotlin 前端参数模型 |
| `app/src/main/java/.../service/backend/BackendManager.kt` | Kotlin HTTP 请求构造 (L150-155, L315-323) |
| `app/src/main/java/.../utils/SamplerNames.kt` | sampler 名称映射 |
| `app/src/main/cpp/src/RequestParser.hpp` | C++ JSON 解析与校验 (L134-157) |
| `app/src/main/cpp/src/RequestContext.hpp` | C++ 请求上下文 (L29) |
| `app/src/main/cpp/src/Config.hpp` | C++ 全局配置 (sample_width/height, output_width/height) |
| `app/src/main/cpp/src/GenerateHandler.cpp` | C++ 生成核心逻辑 (L363-395 scheduler 映射, L813-874 CFG 推理) |
| `app/src/main/cpp/src/ServerCli.cpp` | C++ CLI 参数解析 |
| `app/src/main/cpp/src/main.cpp` | C++ 入口 + 图片输出 |
| `app/src/main/cpp/src/SDUtils.hpp` | 图片后处理 (transpose, normalize, base64 encode) |
| `app/src/main/cpp/src/QnnModel.hpp` | QNN 推理路径 (VAE Decode) |

## 审计结果

### 1. cfg_scale 参数全链路

| 环节 | 代码位置 | 行为 | 状态 |
|------|----------|------|------|
| 前端发送 | `BackendManager.kt:315` | `put("cfg_scale", params.cfgScale.toDouble())` | ✅ |
| 后端解析 | `RequestParser.hpp:134` | `req_.cfg_scale = json.value("cfg_scale", 7.5f)` | ✅ |
| 存储 | `RequestContext.hpp:29` | `float cfg_scale = 7.5f` | ✅ |
| UNET 使用 | `GenerateHandler.cpp:813,864,874` | `uncond + cfg_scale * (cond - uncond)` | ✅ |
| QNN CFG=1.0 优化 | `GenerateHandler.cpp:813,840` | CFG=1.0 时跳过 unconditional pass | ✅ |

### 2. sampler 参数全链路

| 环节 | 代码位置 | 行为 | 状态 |
|------|----------|------|------|
| 前端发送 | `BackendManager.kt:321` | `put("sampler", params.sampler)` → `"dpm"` | ✅ |
| 后端解析 | `RequestParser.hpp:135` | `req_.sampler_type = json.value("sampler", "dpm")` | ✅ |
| Scheduler 构造 | `GenerateHandler.cpp:373-395` | 映射表见下方 | ✅ |

sampler_type → Scheduler 映射：

| sampler_type | 构造的 Scheduler |
|---|---|
| `"euler_a"` / `"eulera"` | `EulerAncestralDiscreteScheduler` |
| `"euler"` | `EulerDiscreteScheduler` |
| `"lcm"` | `LCMScheduler` |
| `"dpm_sde"` | `DPMSolverMultistepScheduler` (sde-dpmsolver++) |
| 其他 (含 `"dpm"`) | `DPMSolverMultistepScheduler` |

### 3. scheduler (denoise curve) 参数全链路

| 环节 | 代码位置 | 行为 | 状态 |
|------|----------|------|------|
| 前端发送 | `BackendManager.kt:322` | `put("scheduler", params.denoiseCurve)` → `"scaled_linear"` | ✅ |
| 后端解析 | `RequestParser.hpp:137-157` | 转小写 + 校验 → `req_.denoise_curve` | ✅ |
| 映射 | `GenerateHandler.cpp:363-371` | `"karras"`→use_karras=true, `"linear"`→beta_schedule="linear" | ✅ |

**约束**：`"linear"` beta_schedule 只对 Euler/EulerA 生效；DPM 系列始终用 `"scaled_linear"`（diffusers 设计约束）。

### 4. CLI 启动参数

`BackendManager.kt:150-155` 构建的命令：

```
--type sd15npu --model_dir <dir> --port 8081 [--lib_dir <dir>] [--patch <file>]
```

`ServerCli.cpp` 解析映射：

| --type | use_mnn | sdxl_mode | model ext |
|--------|---------|-----------|-----------|
| `sd15cpu` | true | false | `.mnn` |
| `sd15npu` | false | false | `.bin` |
| `sdxl` | false | true | `.bin` |

**cfg_scale / sampler / scheduler 不是 CLI 参数**，全部通过 HTTP JSON 动态传入，无需 CLI 透传。

### 5. C++ 全局状态审计

前次 commit 已修复两个 P0 全局状态 Bug：

| Bug | 修复 commit | 状态 |
|-----|------------|------|
| `g_req` 全局 RequestContext 被并发覆盖 | e9335ac (含) | ✅ 已消除，改用局部 `reqCtx` |
| `Config.hpp` `sample_width/height/output_width/height` 未同步 | e9335ac (含) | ✅ 已在 5 个关键点同步 |

**当前无残留全局状态问题。**

### 6. MNN vs QNN 颜色编码对齐

图片输出管线（两条路径共用同一后处理）：

```
VAE Decode 输出 → NCHW float [-1,1]
  → transpose → HWC interleaved [H,W,3]
  → ((x+1)/2)*255 → [0,255] float
  → clip [0,255] → cast uint8_t
  → 原始字节 → base64 → SSE event:image
```

| 路径 | VAE Decoder | Tensor 格式 | 后处理 |
|------|------------|-------------|--------|
| MNN (sd15cpu) | `createMnnInterpreterMmap` → `MNN::Tensor::CAFFE` (NCHW) | `copyToHostTensor` → host NCHW float32 | 相同转置/归一化/cast |
| QNN (sd15npu) | `executeVaeDecoderGraphs` → `convertToFloatInto` | model native NCHW float32 | 相同转置/归一化/cast |

**布局层面无差异** — 两者均输出 NCHW float32，经过完全相同的 `transpose → normalize → cast` 管线。QNN 的 int16 量化精度可能导致输出值微小偏移，这是模型精度层面的差异而非编码 Bug。输出格式为 raw RGB bytes (H×W×3) 而非 PNG，不存在 PNG 24-bit 编码深度截断问题。

### 9. 三次审计：QNN 推理调用全量对比 v2026.06.13.15.08

针对用户反馈 `createQnnModel` / Sampler / Scheduler / `executeXXXGraphs` 的 QNN 调用是否与参考版本一致，逐段 diff 对比：

#### 9.1 createQnnModel 对比

| 对比项 | v2026.06.13.15.08 (旧) | 当前 HEAD | 结论 |
|--------|------------------------|-----------|------|
| 函数签名 | `createQnnModel(path, name)` 直接读全局变量 `g_backendPathCmd`, `g_qnnSystemFuncs` | `createQnnModel(path, name, ctx)` 通过 `ctx.conf` / `ctx.models` | ✅ 重构封装，值相同 |
| `getQnnFunctionPointers` 参数 | `gconf.backendPathCmd, modelPath, &funcs, &backendHandle, false, &modelHandle` | `ctx.conf.backendPathCmd, modelPath, &funcs, &backendHandle, false, &modelHandle` | ✅ 相同 |
| QnnModel 构造参数 | `OutputDataType::FLOAT_ONLY, InputDataType::FLOAT, ProfilingLevel::OFF, dumpOutputs=false, cachedBinaryPath=""` | 完全相同 | ✅ |
| dlclose modelHandle 移交 | `app->m_modelHandle = modelHandle` | 相同 | ✅ |

#### 9.2 QnnModel::execute 方法对比 (QnnModel.hpp)

逐方法行级 diff 对比（旧版 780 行 vs 当前 779 行，差异仅诊断日志添加）：

| 方法 | 行范围 (旧) | 行范围 (当前) | 差异 |
|------|-----------|------------|------|
| `executeUnetGraphs` | 164-249 | 164-249 | ✅ **0 行差异** |
| `executeVaeEncoderGraphs` | 251-322 | 251-322 | ✅ **0 行差异** |
| `executeVaeDecoderGraphs` | 324-389 | 324-389 | ✅ **0 行差异** |
| `executeUnetGraphsSDXL` | 391-481 | 391-481 | ✅ **0 行差异** |
| `executeVaeEncoderGraphsSDXL` | 483-545 | 483-545 | ✅ **0 行差异** |
| `executeVaeDecoderGraphsSDXL` | 547-605 | 547-605 | ✅ **0 行差异** |
| `executeUpscalerGraphs` | 607-682 | 607-682 | ✅ **0 行差异** |

**其中核心量化相关逻辑完全相同**：

- SD1.5 UNET/VAE: `floatToTfN(ptr, float_data, tensor.offset, tensor.scale, elementCount)` — offset/scale 来自 DLC 模型 metadata
- SD1.5 输出读取: `m_ioTensor.convertToFloatInto(dst, &outputs[0])` — 自动反量化
- SDXL 全部: `memcpy` fp32 直接拷贝
- UNET noise_pred 输出: 旧版 `convertToFloatInto(latents_pred, &outputs[0])` / 新版完全相同
- VAE Decoder 输出: 旧版 `convertToFloatInto(pixel_values, &outputs[0])` / 新版完全相同

#### 9.3 推理调用点对比 (GenerateHandler.cpp vs old main.cpp)

| 调用点 | 旧版代码 | 当前代码 | 差异 |
|--------|---------|---------|------|
| **UNET SD1.5 (uncond)** | `unetApp->executeUnetGraphs(latents_in_ptr, (int)current_ts, embed_ptr, latents_out_ptr)` | `models.unetApp->executeUnetGraphs(latents_in_ptr, static_cast<int>(current_ts), embed_ptr, latents_out_ptr)` | ✅ static_cast vs C-cast，语义相同 |
| **UNET SD1.5 (cond)** | `unetApp->executeUnetGraphs(latents_in_ptr+single_latent_size, (int)current_ts, embed_ptr+77*text_embedding_size, latents_out_ptr+single_latent_size)` | 相同 | ✅ |
| **UNET SDXL (uncond)** | `unetApp->executeUnetGraphsSDXL(latents_in_ptr, (int)current_ts, hidden_ptr, pooled_ptr, time_ids_ptr, latents_out_ptr)` | 相同 | ✅ |
| **UNET SDXL (cond)** | `unetApp->executeUnetGraphsSDXL(latents_in_ptr+size, (int)current_ts, hidden_ptr+hidden_stride, pooled_ptr+pooled_stride, time_ids_ptr+time_ids_stride, latents_out_ptr+size)` | 相同 | ✅ |
| **VAE Enc SD1.5** | `vaeEncoderApp->executeVaeEncoderGraphs(img_data.data(), mean, std)` | `models.vaeEncoderApp->executeVaeEncoderGraphs(const_cast<float*>(req.img_data.data()), ...)` | ✅ const_cast 仅适配 const 正确性 |
| **VAE Enc SDXL** | `vaeEncoderApp->executeVaeEncoderGraphsSDXL(img_data.data(), mean, std)` | `models.vaeEncoderApp->executeVaeEncoderGraphsSDXL(const_cast<float*>(req.img_data.data()), ...)` | ✅ |
| **VAE Enc Tiling** | `vaeEncoderApp->executeVaeEncoderGraphs(tile_img_vec.data(), mean, std)` | 相同 | ✅ |
| **VAE Dec SD1.5** | `vaeDecoderApp->executeVaeDecoderGraphs(vae_dec_in_vec.data(), pixels.data())` | 相同 | ✅ |
| **VAE Dec SDXL** | `vaeDecoderApp->executeVaeDecoderGraphsSDXL(vae_dec_in_vec.data(), pixels.data())` | 相同 | ✅ |
| **VAE Dec Tiling** | `vaeDecoderApp->executeVaeDecoderGraphs(tile_latent_vec.data(), tile_output.data())` | 相同 | ✅ |

#### 9.4 输入数据准备对比

| 数据 | 旧版 | 当前 | 结论 |
|------|------|------|------|
| SD1.5 latents_in_vec | `latents_scaled.begin→end` × batch=2 | 相同 | ✅ |
| SD1.5 text_embedding | `{neg: batch0, pos: batch1}`, 77×768 | 相同 | ✅ |
| SDXL latents_in_vec | 同上 batch=2 | 相同 | ✅ |
| SDXL encoder_hidden_states | `concatenate(clip1, clip2)` 77×2048 × batch=2 | 相同 | ✅ |
| SDXL text_embeds | clip2 pooled output, 1280 × batch=2 | 相同 | ✅ |
| SDXL time_ids | `{h,w,0,0,h,w}` × batch=2 | 相同 | ✅ |
| `cfg_scale==1.0` 跳过 uncond | 旧版为 `skip_uncond` 分支 | 相同逻辑 | ✅ |
| UNET CFG 后处理公式 | `uncond + cfg_scale * (txt - uncond)` | 相同 | ✅ |
| VAE Decoder 输入 latents | `(1.0/vae_scale) * latents`, vae_scale=0.18215(SD15)/0.13025(SDXL) | 相同 | ✅ |

#### 9.5 VAE Tiling 逻辑对比

| 对比项 | 旧版 | 当前 |
|--------|------|------|
| 函数名 | `calculate_vae_tile_positions` | `calculateVaeTilePositions` (camelCase) |
| vae_tile_size | 512 | 512 |
| vae_latent_tile_size | 64 | 64 |
| min_latent_overlap | 16 | 16 (now `min_overlap` param in VaeTilingHelper) |
| 维度同步模式 | 直接赋值 `output_width = vae_tile_size` | 赋值 + `Config.hpp` 宏同步 |
| Tile 创建 `latent_tile` | `xt::view(latents, 0, xt::all(), range(y,y+64), range(x,x+64))` | 相同 |
| tile latents vector | `vector<float>(latent_tile.begin(), latent_tile.end())` | 相同 |
| tile output buffer | `xt::zeros<float>({1,3,512,512})` | 相同 |

#### 9.6 QNN 模型创建时的量化配置

| 模型 | 旧版 InputDataType | 新版 InputDataType | 旧版 OutputDataType | 新版 OutputDataType |
|------|--------------------|--------------------|--------------------|-------------------|
| UNET (SD1.5) | FLOAT (uint16 quantized) | FLOAT | FLOAT_ONLY | FLOAT_ONLY |
| VAE Encoder (SD1.5) | FLOAT | FLOAT | FLOAT_ONLY | FLOAT_ONLY |
| VAE Decoder (SD1.5) | FLOAT | FLOAT | FLOAT_ONLY | FLOAT_ONLY |
| UNET (SDXL) | FLOAT (fp32 memcpy) | FLOAT | FLOAT_ONLY | FLOAT_ONLY |
| VAE Enc/Dec (SDXL) | FLOAT | FLOAT | FLOAT_ONLY | FLOAT_ONLY |

**完全相同。** 量化 scale/offset 由 DLC 模型自身的 metadata 决定（`inputs[0].v1.quantizeParams.scaleOffsetEncoding`），代码不硬编码这些值。

---

### 10. 二次审计：颜色异常诊断

用户反馈输出图像呈现低饱和度靛蓝/洋红/亮黄色块。经完整管线审查：

| 管线环节 | 对照 v2026.06.13.15.08 | 结论 |
|---------|----------------------|------|
| QNN createQnnModel 参数 | 相同 (OutputDataType::FLOAT_ONLY, InputDataType::FLOAT) | ✅ |
| executeVaeDecoderGraphs | 相同 (SD1.5 → convertToFloatInto; SDXL → memcpy) | ✅ |
| xt::adapt({1,3,H,W}) + transpose({1,2,0}) | 相同 | ✅ |
| base64_encode 传输 | 相同 | ✅ |
| Kotlin Base64.decode + rgbBytesToBitmap | 相同 | ✅ |
| Bitmap.compress(PNG, 100) | 相同 | ✅ |

**代码层面后处理管线与参考版本无回归差异。**

已添加 3 层诊断日志（`[DIAG]` tag）：

| 位置 | 诊断内容 |
|------|---------|
| `GenerateHandler.cpp`: VAE Decoder 后 | raw float 的 min/max/大小 + pixel(0,0) NCHW vs NHWC 采样 |
| `GenerateHandler.cpp`: post-process 后 | out_data 字节数 + top-left/center/bottom-right RGB 采样 |
| `GenerationWorker.kt`: base64 解码后 | 解码字节数 + 3 点 RGB 采样 |
| `GenerationWorker.kt`: Bitmap 创建后 | Bitmap.getPixel 3 点 ARGB 采样 |

#### 诊断对照方法

运行一次生成，对比以下输出：

1. **C++ `[DIAG] out_data`** vs **Kotlin `[DIAG] decoded`** — 同一像素的 RGB 值是否一致？
   - 若一致 → base64 传输正确，问题在 Bitmap/PNG 环节
   - 若不一致 → base64 编码/解码有问题
2. **C++ `[DIAG] VAE dec pixel(0,0) NCHW` vs NHWC** — 看 NCHW 读取的 R/G/B 是否合理（扩散模型正常应在 [-1, 1] 范围）
3. **Kotlin `[DIAG] decoded` vs `[DIAG] Bitmap`** — 同一像素的 RGB 值是否匹配？
   - 若一致 → Bitmap 创建正确，问题在 PNG compress
   - 若不一致 → rgbBytesToBitmap 有问题

#### 根因假说

| 假说 | 触发条件 | 验证方法 |
|------|---------|---------|
| QNN VAE Dec 量化 scale/offset 偏差 | dequantize 后值域不在 [-1,1] | C++ VAE dec 诊断：raw float range |
| QNN tensor NHWC 布局 | DLC 模型被转成 NHWC | C++ VAE dec 诊断：NCHW vs NHWC 采样哪个更像真实像素 |
| base64 编码字节截断 | 二进制 0x00 被当作字符串终止符 | 比对 C++ out_data.size vs Kotlin decoded.size |
| Bitmap ARGB 创建错误 | setPixels stride 不匹配 | 比对 Kotlin decoded RGB vs Bitmap ARGB |

### 7. 固定 canvas + mask 裁剪方法

与 `v2026.06.13.15.08` 参考版本一致：

1. `RequestParser.hpp:190-193` — 设置 `output_width/height = reqW/H`，`sample_width/height = reqW/8`
2. `RequestParser.hpp:196-340` — SDXL 非 1:1 aspect_ratio 时，在 1024×1024 canvas 内计算 `target_crop_width/height`，设置 `aspect_pad_inpaint=true`，创建合成 black canvas + white paint region
3. `GenerateHandler.cpp:1156-1172` — 生成后在 1024×1024 结果上 crop 出 `target_crop` 区域
4. UNET 始终在 `cur_samp_w × cur_samp_h`（如 128×128）维度上运行，mask 屏蔽区域外像素

### 8. v2026.06.13.15.08 字段名对比

| 对比项 | v2026.06.13.15.08 (旧) | 当前 HEAD |
|--------|------------------------|-----------|
| CFG JSON 字段 | `"cfg"` | `"cfg_scale"` ✅ 同步 |
| Sampler JSON 字段 | `"scheduler"` (含 karras 后缀) | `"sampler"` (不含) ✅ 同步 |
| Denoise curve | 无独立字段 | `"scheduler"` 独立字段 ✅ 同步 |
| 图片输出格式 | raw RGB bytes + base64 | 相同 ✅ |
| Aspect pad inpaint | 1024×1024 canvas + mask crop | 相同 ✅ |

前端 `BackendManager.kt:315,321-322` 已确认使用新字段名，前后端对齐。

## 结论

**三次审计结论：QNN 推理调用与 v2026.06.13.15.08 完全一致。**

| 审计轮次 | 范围 | 结论 |
|---------|------|------|
| 初次 | cfg_scale / sampler / scheduler 参数链路 | ✅ 无 Bug |
| 二次 | C++ → Kotlin → PNG 图片管线 | ✅ 与参考版本无回归差异 |
| 三次 | QNN 模型创建、execute 方法、调用点、量化配置 | ✅ **0 行实质性差异** |

**三次审计确认**：所有 QNN 推理调用（`createQnnModel` 参数、`executeUnetGraphs`/`executeVaeDecoderGraphs`/SDXL 变体 7 种方法、`floatToTfN` dequant、`convertToFloatInto` 输出、VAE Tiling 逻辑、量化配置）与 `v2026.06.13.15.08` 逐行一致。代码重构（文件拆分、AppContext 封装、const_cast、camelCase 重命名）均为零语义变化。

**颜色异常问题的根因不在代码调用层面**，最可能来自 DLC 模型文件本身的量化参数（scale/offset metadata）或 QNN SDK 运行时行为。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-24 | 初始审计。完整验证 cfg_scale / sampler / scheduler 参数全链路、CLI 参数、全局状态、MNN/QNN 颜色编码对齐、固定 canvas + mask 裁剪方法，未发现 Bug |
| 2026-06-24 | 二次审计：针对用户反馈的 CMYK 基准色低饱和度色块问题，完整审查 C++→Kotlin→PNG 全图片管线，代码层面与 v2026.06.13.15.08 无回归差异。添加 3 层诊断日志。提出 4 个根因假说和验证方法 |
| 2026-06-24 | 三次审计：逐行对比 QNN 推理调用（createQnnModel ×4 调用点、executeXXXGraphs 7 种方法 ×12 调用点、floatToTfN dequant、convertToFloatInto 输出、VAE Tiling 逻辑、量化配置）与 v2026.06.13.15.08，确认 0 行实质性差异。结论：颜色异常非代码调用回归，根因大概率在 DLC 模型量化参数或 QNN SDK 运行时行为 |
