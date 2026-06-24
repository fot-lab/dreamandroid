# TECH-SPEC-0002: C++ 后端 Generate Pipeline 审计

| 属性 | 值 |
|------|-----|
| 分类 | Technical Specifications |
| 对应章节 | §15 |
| 依赖 | ARCH-OVER-0001, BKND-LFCY-0001 |
| 参考版本 | v2026.06.13.15.08 |

## 功能描述

本文档审计 C++ 原生后端 (`GenerateHandler.cpp`) 中所有图像生成路径的 VAE Encoder / VAE Decoder 调用正确性，覆盖 QNN、MNN、SDXL 三种后端在 txt2img、img2img、inpaint 场景下的完整路径。

### 审计范围

- **后端**: QNN (Qualcomm Neural Network), MNN (Alibaba). SDXL 仅使用 QNN
- **场景**: txt2img, img2img, inpaint
- **关键检验点**: VAE Encoder 是否被错误应用到 txt2img 路径

## 路径总表

```
路径                          VAE Encoder         UNET            VAE Decoder
─────────────────────────────────────────────────────────────────────────────
txt2img + QNN                 ❌ 跳过 (guard)     ✅ QNN UNET     ✅ QNN VAE Dec (在 UNET 之后)
txt2img + MNN                 ❌ 跳过 (guard)     ✅ MNN UNET     ✅ MNN VAE Dec (在 UNET 之后)
txt2img + SDXL QNN            ❌ 跳过 (guard)     ✅ QNN SDXL     ✅ QNN SDXL VAE Dec (在 UNET 之后)
img2img + QNN                 ✅ QNN VAE Enc      ✅ QNN UNET     ✅ QNN VAE Dec (在 UNET 之后)
img2img + MNN                 ✅ MNN VAE Enc      ✅ MNN UNET     ✅ MNN VAE Dec (在 UNET 之后)
img2img + SDXL QNN            ✅ QNN SDXL VAE Enc ✅ QNN SDXL     ✅ QNN SDXL VAE Dec (在 UNET 之后)
inpaint + QNN                 ✅ QNN VAE Enc      ✅ QNN UNET     ✅ QNN VAE Dec (在 UNET 之后)
inpaint + MNN                 ✅ MNN VAE Enc      ✅ MNN UNET     ✅ MNN VAE Dec (在 UNET 之后)
```

## 各环节守卫机制

### VAE Encoder Guard

VAE Encoder 入口由 `req.request_img2img` 布尔值守卫，确保仅在 img2img / inpaint 场景触发：

```
GenerateHandler.cpp 第 405 行:
    if (req.request_img2img) {
        // VAE Encode 路径 (QNN / MNN / SDXL QNN)
    }
```

txt2img 时 `request_img2img == false`，VAE Encoder 绝不被调用。

VAE Encode 内部按后端分支：

- **QNN**: 调用 `executeVaeEncoderGraphs()` 分 tile 编码
- **MNN**: 调用 MNN VAE Encoder
- **SDXL QNN**: 调用 `executeVaeEncoderGraphsSDXL()`

### UNET Loop

所有路径在 VAE Encoder guard 之后执行 UNET 步进循环：

1. QNN 路径: 加载 QNN UNET 模型，迭代 `num_steps` 次
2. MNN 路径: 通过 JNI 回调 Kotlin 侧执行 MNN UNET
3. SDXL 路径: 加载 QNN SDXL UNET 模型，分阶段迭代

### VAE Decoder

VAE Decoder 在所有路径的 UNET 循环结束之后执行：

```
GenerateHandler.cpp 第 877 行:
    // UNET 已释放，VAE Decode 开始
```

- **QNN / MNN**: 分 tile 方式（8 个方向 + 原图）调用 `executeVaeDecoderGraphs()`
- **SDXL QNN**: 调用 `executeVaeDecoderGraphsSDXL()`

## 与参考版本 v2026.06.13.15.08 的差异

| 差异项 | v2026.06.13.15.08 | 当前版本 | 性质 | 影响 |
|--------|-------------------|----------|------|------|
| VAE Encoder 入口守卫 | `if (request_img2img)` 第 2239 行 | `if (req.request_img2img)` 第 405 行 | 等价 | 无变化 |
| VAE Decode 位置 | UNET 循环后 第 2869 行 | UNET 循环后 第 877 行 | 等价 | 无变化 |
| UNET 释放 → VAE Decode 时序 | 先释放后解码 第 2867→2869 行 | 先释放后解码 第 875→877 行 | 等价 | 无变化 |
| Preview VAE decode (循环内) | 存在，`show_diffusion_process` 第 2588 行 | 已删除，替换为 `progress_callback("")` | Bugfix | 消除 NPU 状态污染风险 |
| `img_data` const 限定 | `req.img_data.data()` | `const_cast<float*>(req.img_data.data())` 第 527 行 | const 正确性修复 | 无害 |
| `calculateVaeTilePositions` | 旧名称 | 重命名 | 纯重构 | 无影响 |
| 全局变量 → 结构体成员 | 全局状态 | `ServerState` 结构体成员 | 纯重构 | 无影响 |

## 技术约束

1. **VAE Encoder 绝不可在 txt2img 路径执行** — `req.request_img2img` 守卫是唯一入口，不可移除
2. **UNET 必须在 VAE Decode 前释放** — NPU 内存限制，不释放会导致 VAE Decode OOM
3. **Preview VAE decode 不可恢复** — 在 UNET 循环内使用 VAE Decoder 会导致 NPU 状态污染
4. **SDXL VAE 路径独立** — SDXL 使用独立的 VAE Encoder/Decoder graph，不可与非 SDXL 路径混用

## 变更历史

| 日期 | 变更描述 |
|------|---------|
| 2026-06-24 | 初始创建：审计全部 8 条 Generate 路径的 VAE 使用正确性，与 v2026.06.13.15.08 对比确认无退化 |
