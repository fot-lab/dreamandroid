# UILA-COMP-0005: UI 层直接 HTTP

| 属性 | 值 |
|------|-----|
| 优先级 | P2 |
| 分类 | UI Layer |
| 关联 | HTTP-CLNT-0004, UILA-COMP-0001 |

## 问题描述

`GenerateScreen` 直接持有 `BackendManager` 并调用 `tokenize()`；`UpscaleScreen` 仍有独立 HTTP 调用。

UI 层直接进行网络调用违反分层原则：
- 无错误处理统一
- 无重试/超时策略
- 难以测试

## 涉及文件

- `ui/screens/GenerateScreen.kt`
- `ui/screens/UpscaleScreen.kt`

## 修复方案

配合 ViewModel 拆分 (UILA-COMP-0001)：
- `GenerateViewModel` 封装 `tokenize()` 调用
- `UpscaleViewModel` 封装 HTTP 调用
- ViewModel 通过 Repository/Service 层间接访问 HTTP

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-13 | 初始发现 |
| 2026-06-15 | 依赖 ViewModel 拆分 (UILA-COMP-0001) 解决 |
| 2026-06-16 | Phase E 评估：UI 层 HTTP 调用依赖 GenerateViewModel/UpscaleViewModel → Blocked on Phase D |
