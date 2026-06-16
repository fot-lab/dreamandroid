# MODU-SPLT-0001: 单模块无编译隔离

| 属性 | 值 |
|------|-----|
| 优先级 | P3 |
| 分类 | Modularization |
| 关联 | 无 |

## 问题描述

所有 Kotlin 代码在单一 `:app` 模块中：
- UI 可直接 import Service companion
- 改一行 → 全量 recompile
- 无编译期隔离保证分层约定

## 涉及文件

- `app/build.gradle.kts`
- 整个 `app/` 目录

## 修复方案


```
:core:common      → Model/Error types, base interfaces
:core:backend     → BackendManager, health check, HttpClient, SSE parser
:core:data        → Room DAO/Entities, Repository, PreferencesManager
:core:queue       → QueueRepository, GenerationTask, task scheduling
:feature:models   → ModelListScreen, ModelRunScreen
:feature:generate → GenerateScreen
:feature:queue    → QueueScreen
:feature:upscale  → UpscaleScreen
:feature:browse   → BrowseScreen
:app              → MainActivity, Navigation, DI wiring
```

**渐进策略：** 先拆 `:core:common` + `:core:backend`，其余逐步推进。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-13 | 初始发现 |
| 2026-06-15 | 方案设计完成，渐进实施 |
| 2026-06-16 | Phase E 评估：P3 低优先级，推迟至未来版本 |
