# FEND-GENR-0001: 参数组合页面 (Generate Tab)

| 属性 | 值 |
|------|-----|
| 分类 | Frontend — Generate |
| 对应章节 | §4.4 |
| 依赖 | ARCH-OVER-0001, BKND-APIS-0001, FEND-QUEU-0001 |

## 功能定位

负责组合生成请求的所有参数，并提供一键添加到队列。Generate Tab 仅负责参数组合与"添加到队列"操作，不承担任何运行时生成控制职责。

## 参数列表

| 参数 | 类型 | 范围/选项 | 默认值 | 持久化 | 说明 |
|------|------|-----------|--------|--------|------|
| Batch Count | int | 1 - 60 | 1 | 全局 | 批量生成数量 |
| Prompt | string | — | 模型默认 | 全局 | 正向提示词，CLIP 77 token 限制 |
| Negative Prompt | string | — | 模型默认 | 全局 | 负面提示词 |
| Width | int | 64 - 4096 | 512 | 全局 | 图片宽度 |
| Height | int | 64 - 4096 | 512 | 全局 | 图片高度 |
| Steps | int | 1 - 50 | 20 | 按模型 | 采样步数 |
| CFG Scale | float | 1.0 - 30.0 | 7.0 | 按模型 | 引导强度 |
| Scheduler | string | dpm/dpm_sde/euler_a/euler/lcm | dpm | 按模型 | 采样调度器 |
| Karras | bool | on/off | off | 按模型 | Karras 噪声调度 |
| Seed | long | 任意数字 | 空 | 按模型 | 随机种子 |
| OpenCL | bool | CPU/GPU | CPU | 按模型 | 仅 CPU 模型可见 |
| Denoise | float | — | 0.6 | 按模型 | 去噪强度 |

## Token 计数功能

- 输入提示词后 400ms 防抖发送到 `POST /tokenize`
- 实时显示 `当前token数/最大token数(77)`
- 超出 CLIP 限制的字符以 38% 透明度灰显
- 超出限制时显示 ⚠️ 警告图标

## TopAppBar — "Add to Queue" 按钮

"Add to Queue" 按钮位于 GenerateTopBar 右上角 actions 区域：

```
┌─ GenerateTopBar ────────────────────────────────────────┐
│  [☰ Menu]  {Model Name / "未加载模型"}     [⏸ Add to Queue] │
└──────────────────────────────────────────────────────────┘
```

| 场景 | 行为 |
|------|------|
| **未加载模型** | 按钮灰显 (disabled) |
| **已加载模型** | 按钮可点击，触发添加队列 + 飞行动画 |

## 飞行动画

"开始生成"按钮被点击后，从 TopAppBar 的 PlayArrow 位置发射一个动画元素飞向底部 NavigationBar 的 Queue Tab 图标：

| 属性 | 值 |
|------|-----|
| **动画元素** | 半透明 PlayArrow 图标 |
| **动画时长** | 400-600ms |
| **运动路径** | 贝塞尔曲线 (弧线) |
| **缓动** | `FastOutSlowInEasing` |

动画到达后 Queue Tab 图标弹跳/脉冲，自动更新徽章。无 Snackbar。

## 参数持久化

- **全局参数** (Prompt, Negative Prompt, Batch Count, Width, Height): 通过 GenerationPreferences 全局持久化
- **模型参数** (Steps, CFG, Seed, Scheduler, Denoise, OpenCL): 按 modelId 持久化
- 切换模型时自动加载该模型的保存参数；全局参数保留

## Generate 子 Tab 结构

Generate Screen 内部分为两个子 Tab：

```
┌─ GenerateTopBar ────────────────────────────────────────┐
│  [☰ Menu]  {Model Name}                    [⏸ Add to Queue] │
├─ Generate TabRow ──────────────────────────────────────┤
│  [ Parameters ]  [ Records ]                            │
└──────────────────────────────────────────────────────────┘
```

| Tab | 名称 | 功能 | 图标 |
|-----|------|------|------|
| **Tab 1** | **Parameters** | 现有参数编辑界面 | `Tune` |
| **Tab 2** | **Records** | 管理从 Queue/Gallery 保存的记录，可加载到编辑器或删除 | `Bookmarks` |

## Record Manager — 记录管理器

集中管理所有从 Queue 和 Gallery 保存的提示词与生成参数记录。

### 数据模型

```kotlin
enum class RecordSource { QUEUE, GALLERY }

data class GenerateParameterRecord(
    val id: String, val prompt: String, val negativePrompt: String,
    val modelId: String, val steps: Int, val cfg: Float,
    val seed: Long?, val width: Int, val height: Int,
    val scheduler: String, val timestamp: Long, val source: RecordSource,
)
```

### 持久化

| 属性 | 值 |
|------|-----|
| **存储引擎** | JSON 文件 (`generate_records.json`) |
| **存储位置** | `context.filesDir` / `generate_records.json` |
| **Repository** | `RecordRepository(context)` |

### 保存来源

```
Queue Tab (右滑保存) ──→ RecordRepository.addRecord(...) ──→ generate_records.json
Gallery Save Info     ──→ RecordRepository.addRecord(...) ──→ generate_records.json
```

### UI 功能

- 记录列表，每项显示 Prompt、Model、时间、来源标签
- 左滑删除记录
- 点击记录 → 加载该记录的全部参数到 Parameters Tab
- 空记录提示

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §4.4 内容，创建独立文件 |
