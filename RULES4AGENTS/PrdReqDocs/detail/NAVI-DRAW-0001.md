# NAVI-DRAW-0001: 导航抽屉与设置

| 属性 | 值 |
|------|-----|
| 分类 | Navigation Drawer |
| 对应章节 | §7 |
| 依赖 | ARCH-OVER-0001 |

## 概述

左侧导航抽屉通过 TopAppBar 左侧菜单按钮（☰）打开，包含完整的应用设置。抽屉标题为 "Settings"，右侧有关闭按钮（✕）。设置项按功能分为 6 个分组。

## 侧边栏结构

```
┌─ Settings ────────────────────────────────────── [✕] ─┐
│  Appearance                                            │
│    Dynamic Color / Dark Mode / OLED / Color theme       │
│  Backend                                               │
│    Allow LAN access / SDXL low RAM / Capture logs       │
│  Generation                                            │
│    Show process / Preview stride / Timeouts             │
│  Health Check                                          │
│    Retry interval / Max failures                        │
│  Downloads                                             │
│    Download from HF/Mirror/Custom / Custom URL          │
│  About                                                 │
│    Version / 须知/免责声明                               │
└────────────────────────────────────────────────────────┘
```

## 通用组件

| 组件 | 说明 |
|------|------|
| `SectionHeader(title)` | 分组标题，使用 `primary` 颜色 |
| `SwitchSetting(title, hint, checked, onCheckedChange)` | 行布局：标题 + 描述 + Switch |
| `ChipSetting<T>(title, hint, options, selected, onSelect)` | 标题 + 描述 + FilterChip 行 |
| `SliderSetting(title, hint, value, range, steps, suffix, ...)` | 标题 + 描述 + 值显示 + Slider |

## 分组详情

### Appearance

| 配置项 | 组件 | 存储位置 | 默认值 |
|--------|------|---------|--------|
| Dynamic Color | SwitchSetting | `ThemePreferences` | 开 |
| Dark Mode | ChipSetting (3 选项) | `ThemePreferences` | System |
| OLED Pure Black | SwitchSetting | `ThemePreferences` | 关 |
| Color Theme | ChipSetting | `ThemePreferences` | Tangerine |

### Backend

| 配置项 | SP Key | 默认值 |
|--------|--------|--------|
| Allow LAN Access | `listen_on_all_addresses` | 否 |
| SDXL Low RAM | `sdxl_lowram` | 是 |
| Capture Logs | `enable_log_capture` | 否 |

### Generation

| 配置项 | SP Key | 范围 | 默认值 |
|--------|--------|------|--------|
| Show Process | `show_diffusion_process` | on/off | 否 |
| Preview Stride | `show_diffusion_stride` | 1-10 | 1 |
| Generation Timeout | `generation_timeout_s` | 15-600s | 60s |
| Bitmap Consumed Timeout | `bitmap_consumed_timeout_s` | 5-120s | 30s |

### Health Check

| 配置项 | SP Key | 范围 | 默认值 |
|--------|--------|------|--------|
| Retry Interval | `health_check_retry_interval_s` | 5-120s | 20s |
| Max Failures | `health_check_max_failures` | 1-20 | 4 |

### Downloads

| 配置项 | SP Key | 默认值 |
|--------|--------|--------|
| Download From | `download_source` | HuggingFace |
| Custom URL | `hf_base_url` | -- |

### About

- 版本号显示：`Version: {VERSION_NAME}`
- 须知/免责声明 (`must_read` 字符串)

## 持久化架构

| 存储方式 | 用途 |
|---------|------|
| `SharedPreferences` `"app_prefs"` | 后端、生成、健康检查、下载设置 |
| `ThemeController` → `ThemePreferences` | 外观设置 |
| `GenerationPreferences` (DataStore) | 下载源选择与自定义 URL |

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §7 内容，创建独立文件 |
