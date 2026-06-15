# FEND-BROW-0001: 图库页面 (Browse Tab)

| 属性 | 值 |
|------|-----|
| 分类 | Frontend — Browse |
| 对应章节 | §4.6 |
| 依赖 | ARCH-OVER-0001, DATA-PERS-0001 |

## 功能定位

以网格形式浏览所有生成历史的图片，支持筛选、选择、下载、删除。等同于相册画廊。

## 导航

底部导航栏 (Bottom Navigation Bar) 包含 5 个 Tab，Browse 为第 5 个：

| 顺序 | Tab | 路由 | 图标 | 功能简述 |
|------|-----|------|------|---------|
| 5 | Browse | `browse` | PhotoLibrary (图库) | 图片画廊 |

## 网格布局

- **容器:** `LazyVerticalGrid`，使用 `GridCells.Fixed(columnCount)`
- **列数:** 通过 SegmentedButton 切换 1 / 2 / 3 / 4 列，默认 3 列
- **持久化:** 列数设置存入 `SharedPreferences` (`app_prefs`)，key: `browse_grid_columns`
- **间距:** 网格水平与垂直间距统一为 `8.dp`，容器外边距 `12.dp`

## 方形画布缩略图 (Uniform Square Canvas)

所有图片缩略图必须展示在统一的方形画布中：

| 属性 | 值 | 说明 |
|------|-----|------|
| 单元格 Modifier | `.fillMaxWidth().aspectRatio(1f)` | 强制正方形 |
| 图片 `contentScale` | `ContentScale.Fit` | 完整展示，保持原始宽高比，不裁切 |
| 单元格背景 | `MaterialTheme.colorScheme.surfaceVariant` | letterbox 区域填充 |
| 图片修饰 | `.clip(RoundedCornerShape(8.dp))` | 圆角裁剪 |
| 加载方式 | Coil `AsyncImage` + crossfade | 本地文件异步加载 |

信息行显示提示词 (单行省略) 和模型信息。

## 模型筛选

- **位置:** 网格上方、列数控件上方
- **组件:** 横向滚动 `Row` + `FilterChip`
- **选项:** "All" (默认) + 各已知 modelId

## Image Viewer — 全屏图片查看器

| 属性 | 实现 | 说明 |
|------|------|------|
| **组件** | `ZoomableImageOverlay` | 复用现有组件 |
| **缩放范围** | 0.5× – 5.0× | 双指捏合缩放 |
| **双击行为** | 双击在 1× ↔ 2.5× 之间切换 | 智能复位 |
| **背景** | 黑色 scrim (`0.9f` alpha) | 沉浸式全屏 |

### ImageViewer TopBar

```
┌─ ImageViewer TopBar ───────────────────────────────────────┐
│  [← 返回]              [Download] [Save Info] [🗑 Delete]  [⋮] │
└────────────────────────────────────────────────────────────┘
```

### Download vs Save Info 语义区分

| 操作 | 保存内容 | 目标 | 格式 |
|------|---------|------|------|
| **Download** | 图片像素数据 (.png) | `Pictures/DreamHub/` 系统相册 | PNG |
| **Save Info** | 提示词 + 参数 → RecordRepository | `generate_records.json` | `GenerateParameterRecord` (source=GALLERY) |

### 溢出菜单 (⋮)

| 菜单项 | 功能 |
|--------|------|
| 分享 (Share) | `Intent.ACTION_SEND` 分享图片 |
| 设置为壁纸 | 通过 `WallpaperManager` 设置 |
| 查看详情 | BottomSheet 展示完整生成参数 |
| 以其他应用打开 | `Intent.ACTION_VIEW` 调起系统查看器 |
| 复制提示词 | 将 Prompt 复制到剪贴板 |

## 选择模式 (Selection Mode)

- **进入:** 长按任意单元格
- **退出:** 点击 TopBar 的 ✕ / ← 按钮

### SelectionMode BrowseTopBar

```
┌─ BrowseTopBar (Selection Mode) ───────────────────────────┐
│  [✕]  "N selected"  [Select All] [Invert] [Deselect] [Download] [Save] [🗑] │
└───────────────────────────────────────────────────────────┘
```

| 按钮 | 功能 |
|------|------|
| ✕ Close | 退出选择模式 |
| Select All | 全选当前筛选下所有图片 |
| Invert | 已选 ↔ 未选 互换 |
| Deselect | 全不选 |
| Download | 批量下载图片到相册 |
| Save | 批量保存生成信息 |
| 🗑 Delete | 批量删除 (二次确认) |

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §4.6 内容，创建独立文件 |
