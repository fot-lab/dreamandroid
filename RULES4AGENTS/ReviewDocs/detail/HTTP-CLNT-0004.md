# HTTP-CLNT-0004: UI 层处理 HTTP 错误

| 属性 | 值 |
|------|-----|
| 优先级 | P3 |
| 分类 | HTTP/Network |
| 关联 | UILA-COMP-0005 |

## 问题描述

`GenerateScreen` 仍通过 `backendManager.tokenize()` 直接调用 HTTP，HTTP 错误在 UI 层处理（绕过 ViewModel/错误统一层）。

## 涉及文件

- `ui/screens/GenerateScreen.kt`

## 修复方案

配合 ViewModel 拆分 (UILA-COMP-0001) 和 UILA-COMP-0005 解决：
- `GenerateViewModel` 封装 tokenize 调用
- 错误通过 `AppError` 密封类统一处理 (UILA-COMP-0003)
- UI 只渲染 `errorState: StateFlow<AppError?>`

## 执行结果

**Phase E5 (2026-06-16)**: GenerateScreen 不再在 UI 层处理 HTTP 错误：
- tokenize HTTP 调用已移入 GenerateViewModel（UILA-COMP-0005 修复）
- GenerateViewModel 通过 `tokenizeError: AppError?` 统一暴露错误（UILA-COMP-0003 修复）
- UI 层只需读取 `tokenizeError` 状态，不再直接 catch HTTP 异常

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 依赖 ViewModel 拆分 (UILA-COMP-0001) 解决 |
| 2026-06-16 | Phase E: HTTP 错误统一处理依赖 GenerateViewModel → Blocked on Phase D |
| 2026-06-16 | Phase E5: 随 UILA-COMP-0003 + UILA-COMP-0005 自动解决 → Fully Fixed |
