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

**未发现参数传递或颜色编码 Bug。** `cfg_scale` / `sampler` / `scheduler` 三条参数链路从前端 `GenerateParams` → JSON → `RequestContext` → Scheduler 构造全程对齐。图片输出为 raw RGB bytes 而非 PNG，不存在 PNG 编码深度问题。MNN/QNN 颜色差异源于模型量化精度而非布局错误。全局状态 Bug 已于前次 commit 修复。固定 canvas + mask 裁剪方法与参考版本一致。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-24 | 初始审计。完整验证 cfg_scale / sampler / scheduler 参数全链路、CLI 参数、全局状态、MNN/QNN 颜色编码对齐、固定 canvas + mask 裁剪方法，未发现 Bug |
