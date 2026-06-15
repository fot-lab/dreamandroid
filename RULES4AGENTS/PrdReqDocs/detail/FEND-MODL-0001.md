# FEND-MODL-0001: 模型管理页面 (Models Tab)

| 属性 | 值 |
|------|-----|
| 分类 | Frontend — Models |
| 对应章节 | §4.2 |
| 依赖 | ARCH-OVER-0001, BKND-APIS-0001 |

## 功能定位

管理后端服务的启停、模型选取与加载、模型导入/删除/重命名。

## 导航

底部导航栏 (Bottom Navigation Bar) 包含 5 个 Tab，Models 为第 1 个：

| 顺序 | Tab | 路由 | 图标 | 功能简述 |
|------|-----|------|------|---------|
| 1 | Models | `models` | Memory (内存芯片) | 模型管理 |

## 模型列表

- 显示已下载/导入的生成模型（Diffusion Models）
- 显示已下载/导入的超分辨率模型（Upscale Models）
- 每张卡片显示: 模型名称、描述、类型标签
- 选中状态视觉反馈 (secondaryContainer 背景色)
- 已加载状态视觉反馈 (primaryContainer + primary 边框)

## 模型类型

### 生成模型 (Diffusion Models) — 三种子类型

| 类型 | backendType | 运行时 | 分辨率 | 说明 |
|------|-------------|--------|--------|------|
| SD 1.5 NPU | `sd15npu` | Qualcomm 芯片 NPU | 128-512 | 需要 QNN SDK |
| SD 1.5 CPU | `sd15cpu` | MNN CPU Runtime | 128-512 | 可切换 GPU(OpenCL) |
| SDXL NPU | `sdxl` | Qualcomm 8Gen3+ NPU | 1024 | SDXL 大分辨率 |

### 预置模型列表

| 模型 ID | 名称 | 类型 | 大小 |
|---------|------|------|------|
| `sdxl_base` | SDXL Base 1.0 | SDXL NPU | 4.2GB |
| `illustrious_v16` | Illustrious v16 | SDXL NPU | 4.2GB |
| `anythingv5` | Anything V5.0 | SD1.5 NPU | 1.1GB |
| `anythingv5cpu` | Anything V5.0 | SD1.5 CPU | 1.2GB |
| `qteamix` | QteaMix | SD1.5 NPU | 1.1GB |
| `qteamixcpu` | QteaMix | SD1.5 CPU | 1.2GB |
| `absolutereality` | Absolute Reality | SD1.5 NPU | 1.1GB |
| `absoluterealitycpu` | Absolute Reality | SD1.5 CPU | 1.2GB |
| `cuteyukimix` | CuteYukiMix | SD1.5 NPU | 1.1GB |
| `cuteyukimixcpu` | CuteYukiMix | SD1.5 CPU | 1.2GB |
| `chilloutmix` | ChilloutMix | SD1.5 NPU | 1.1GB |
| `chilloutmixcpu` | ChilloutMix | SD1.5 CPU | 1.2GB |

### 超分辨率模型 (Upscale Models)

| 模型 ID | 名称 | 说明 |
|---------|------|------|
| `upscaler_anime` | Anime Upscaler | Real-ESRGAN 4x 动漫 |
| `upscaler_realistic` | Realistic Upscaler | UltraSharpV2 Lite 4x 写实 |

## 模型操作

- **加载模型:** 停止当前后端 → 启动新 BackendService (传 modelId、width、height、use_opencl)
- **卸载模型:** 停止所有生成服务 → 发送 ACTION_STOP 广播
- **下载模型:** 从 HuggingFace (或镜像站) 下载，显示进度
- **导入自定义模型:**
  - CPU 模型: 选择文件 → convertCustomModel() 转换 → 标记 `finished`
  - NPU 模型: 选择 ZIP → extractNpuModel() → 标记 `npucustom`
  - SDXL 模型: 标记 `SDXL` + `npucustom`
- **导入 Upscale 模型:** 选择 .bin 文件 → 复制到 models/{id}/ 目录
- **重命名模型:** 重命名模型目录 → 更新 selectedModelId
- **删除模型:** 删除模型目录 + 清除历史记录 + 清除偏好设置。如已加载则先卸载

## TopAppBar 操作

- Menu: 打开导航抽屉
- Load Model 按钮 (模型已选且未加载时显示)
- Unload Model 按钮 (模型已加载时显示)
- Loading 进度指示器 (模型加载中)
- 重命名按钮 (✏️)
- 删除按钮 (🗑️)
- 导入按钮 (+)，下拉菜单: 导入模型 / 导入NPU模型 / 导入Upscale模型

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §4.2 内容，创建独立文件 |
