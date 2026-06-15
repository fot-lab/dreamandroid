# DATA-PERS-0001: 数据持久化规范

| 属性 | 值 |
|------|-----|
| 分类 | Data Persistence |
| 对应章节 | §6 |
| 依赖 | ARCH-OVER-0001 |

## 6.1 HistoryManager (Room Database)

- 存储生成的图片记录
- 字段: modelId, imageFile, params (Steps/CFG/Seed/Prompt/NegativePrompt/Width/Height/GenerationTime), mode
- 支持按 modelId 筛选
- 支持观察 (Flow)

## 6.2 GenerationPreferences (SharedPreferences `app_prefs`)

- 全局参数持久化: prompt, negativePrompt, batchCounts, width, height
- 模型参数持久化 (按 modelId): steps, cfg, seed, scheduler, denoiseStrength, useOpenCL
- HuggingFace Base URL 配置

## 6.3 Upscaler Preferences (SharedPreferences `upscaler_prefs`)

- `upscaler_standalone_selected_upscaler`: 最后选择的 Upscaler 模型 ID

## 持久化架构总览

| 存储方式 | 文件/类 | 用途 |
|---------|---------|------|
| `SharedPreferences` | `"app_prefs"` | 后端、生成、健康检查、下载设置 |
| `SharedPreferences` | `"upscaler_prefs"` | Upscaler 模型选择 |
| `SharedPreferences` | `"theme_prefs"` | 外观设置 (Dynamic Color、Dark Mode、OLED) |
| `Room DB` | `AppDatabase` | 生成历史记录 |
| `DataStore` | `GenerationPreferences` | 下载源选择与自定义 URL |
| JSON File | `generate_records.json` | 生成参数记录 (RecordRepository) |

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §6 内容，创建独立文件 |
