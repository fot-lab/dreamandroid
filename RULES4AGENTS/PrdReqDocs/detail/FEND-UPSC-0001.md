# FEND-UPSC-0001: 超分辨率页面 (Upscale Tab)

| 属性 | 值 |
|------|-----|
| 分类 | Frontend — Upscale |
| 对应章节 | §4.5 |
| 依赖 | ARCH-OVER-0001, BKND-APIS-0001 |

## 导航

底部导航栏 (Bottom Navigation Bar) 包含 5 个 Tab，Upscale 为第 4 个：

| 顺序 | Tab | 路由 | 图标 | 功能简述 |
|------|-----|------|------|---------|
| 4 | Upscale | `upscale` | ImageSearch (放大镜图片) | 超分辨率 |

## 功能定位

对图片进行超分辨率放大。需要 Upscaler 后端进程支持。

## 工作流程

```
User selects image → UpscaleScreen → upscaleViewModel.upscale()
    → backendManager.startUpscaler(id)
    → backendManager.httpClient.newCall(POST /upscale, body=RGB bytes)
    → OK → Bitmap → UpscaleViewModel.result
    → Error → AppError.Network/Backend
```

## 依赖

- Upscaler Backend 必须在运行中
- Upscaler 模型已在 Models Tab 中加载
- 输入图片解码为 RGB 原始字节

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §4.5 内容，创建独立文件 |
