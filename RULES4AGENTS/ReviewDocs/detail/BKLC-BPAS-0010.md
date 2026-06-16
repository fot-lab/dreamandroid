# BKLC-BPAS-0010: BackendService flavor 检查不一致

| 属性 | 值 |
|------|-----|
| 优先级 | P2 |
| 分类 | Backend Lifecycle Bypass |
| 关联 | BKND-PROC-0004, BKLC-BPAS-0009 |

## 问题描述

`BackendService` 硬编码 `BuildConfig.FLAVOR == "filter"` 来复制 safety_checker，而 `RuntimeDirPreparer` 是否处理此逻辑未统一。

存在 flavor 检查逻辑分叉。

## 涉及文件

- `service/BackendService.kt`
- `service/backend/RuntimeDirPreparer.kt`

## 修复方案

`BackendService.kt` 删除后 (BKND-PROC-0001 步骤 5)，此分叉自然消失。`RuntimeDirPreparer` 统一处理 flavor 逻辑。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 初始发现 |
| 2026-06-16 | Phase A2 完成：`BackendService.kt` 已删除，`RuntimeDirPreparer` 统一处理 flavor → ✅ Fully Fixed |
