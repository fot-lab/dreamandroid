# BKND-PROC-0004: prepareRuntimeDir 代码重复

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Backend Process Management |
| 关联 | BKLC-BPAS-0009 |

## 问题描述

运行时目录准备逻辑在两处重复实现：
- `BackendService.prepareRuntimeDir()` (独立实现, 160+ 行)
- `RuntimeDirPreparer.prepare()` (已被 BackendManager 使用)

## 涉及文件

- `service/BackendService.kt`
- `service/backend/RuntimeDirPreparer.kt`

## 修复方案

在 `BackendService.kt` 删除后自然解决。`RuntimeDirPreparer.prepare(context)` 是唯一实现。


## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-13 | 初始发现 |
| 2026-06-15 | 确认修复路径 (BackendService 删除后自动解决) |
| 2026-06-16 | Phase A2 完成：`BackendService.kt` 已删除 → ✅ Fully Fixed |
