# PERM-REQS-0001: 权限要求

| 属性 | 值 |
|------|-----|
| 分类 | Permission Requirements |
| 对应章节 | §8 |
| 依赖 | 无 (基础设施) |

## 权限清单

| 权限 | 用途 | 适用条件 |
|------|------|---------|
| POST_NOTIFICATIONS | 后台生成通知 | Android 13+ |
| WRITE_EXTERNAL_STORAGE | 保存图片到相册 | Android < 10 |
| INTERNET | HTTP 通信 (localhost) | 所有 |

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §8 内容，创建独立文件 |
