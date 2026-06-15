# UILA-COMP-0002: 无法单独测试

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | UI Layer |
| 关联 | UILA-COMP-0001, UILA-COMP-0004 |

## 问题描述

所有业务逻辑耦合在 Composable 生命周期中，无法对 ViewModel/Repository/Service 进行独立单元测试。

## 涉及文件

- `MainActivity.kt`
- `ui/screens/*.kt`

## 修复方案

引入 ViewModel 层（参见 UILA-COMP-0001），业务逻辑从 Composable 移入 ViewModel：

```
UI (Composable) → ViewModel → Service → Backend
     ↑ 可单测        ↑ 可单测
```

配合 DI (UILA-COMP-0004)，可通过 mock 进行 ViewModel 单元测试。

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-13 | 初始发现 |
| 2026-06-15 | 依赖 ViewModel 拆分 (UILA-COMP-0001) 和 DI (UILA-COMP-0004) |
