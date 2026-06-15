# ACTV-SPLT-0001: MainActivity 模块化拆分方案

| 属性 | 值 |
|------|-----|
| 分类 | Activity Split |
| 对应章节 | §18 |
| 依赖 | ARCH-OVER-0001 |

> 版本: 1.0 | 创建日期: 2026-06-15
> 目标: 将 MainActivity.kt (~1825 行) 按功能大类拆分为 4 个模块

## 18.1 现状分析

| 区域 | 行范围 | 行数 | 职责 |
|------|--------|------|------|
| MainActivity 类 | 1-138 | ~138 | Activity 生命周期、权限请求、Migration 路由 |
| AppContent 编排器 | 150-762 | ~610 | Tab 选择、全部状态、所有对话框、Scaffold 布局 |
| Top App Bars | 764-1013 | ~250 | 5 个 TopBar Composable |
| Model Tab 组件 | 1015-1317 | ~303 | ModelListTab + 卡片组件 |
| Generate Tab 包装 | 1319-1376 | ~58 | TabGenerateScreen 参数转发 |
| Queue Tab 包装 | 1378-1422 | ~45 | TabQueueScreen 参数转发 |
| Settings Drawer | 1424-1721 | ~298 | 设置抽屉 |
| 可复用组件 | 1723-1824 | ~102 | SectionHeader/SwitchSetting/ChipSetting/SliderSetting |

**核心问题**: AppContent (~610 行) 混合了 4 类职责：Orchestrator, Backend, Queue, Frontend。

## 18.2 拆分策略

按系统前端功能域拆分为 **4 个模块、9 个文件**：

```
ui/
├── orchestrator/               ← Module 1: 总编排层
│   ├── MainActivity.kt         (保留，~80 lines)
│   └── AppContent.kt           (新建，~400 lines)
├── backend/                    ← Module 2: 后端状态层
│   └── BackendSection.kt       (新建，~130 lines)
├── queue/                      ← Module 3: 队列状态层
│   └── QueueSection.kt         (新建，~80 lines)
└── frontend/                   ← Module 4: 前端 UI 层
    ├── GenerateSection.kt      (新建，~110 lines)
    ├── ModelSection.kt         (新建，~200 lines)
    ├── TopBars.kt              (新建，~250 lines)
    ├── SettingsDrawer.kt       (新建，~300 lines)
    └── SharedComponents.kt     (新建，~110 lines)
```

## 18.3 模块职责

### Module 1: Orchestrator
- MainActivity.kt: 权限请求、Migration 路由、主题
- AppContent.kt: 全局状态、编排 4 个子模块、对话模态、Scaffold 渲染

### Module 2: Backend
- BackendSection.kt: `rememberBackendState()`、loadModel/unloadModel

### Module 3: Queue
- QueueSection.kt: `rememberQueueState()`、TabQueueScreen 包装、自动启停

### Module 4: Frontend
- GenerateSection.kt: `rememberGenerateState()`、所有 `gen*` 参数
- ModelSection.kt: ModelListTab + 卡片组件
- TopBars.kt: 5 个 TopBar
- SettingsDrawer.kt: 设置抽屉
- SharedComponents.kt: 可复用组件

## 18.4 数据流设计

```
MainActivity (setContent)
  └─ AppContent (orchestrator)
       ├─ rememberBackendState()     ← Module 2
       ├─ rememberQueueState()       ← Module 3
       ├─ rememberGenerateState()    ← Module 4
       └─ Scaffold
            ├─ TopBar: ModelsTopBar / QueueTopBar / ...
            ├─ Content: ModelListTab / TabQueueScreen / TabGenerateScreen / ...
            └─ BottomBar: NavigationBar
```

## 18.5 代码量预估

| 文件 | 预估行数 |
|------|---------|
| `MainActivity.kt` | ~80 |
| `orchestrator/AppContent.kt` | ~400 |
| `backend/BackendSection.kt` | ~130 |
| `queue/QueueSection.kt` | ~80 |
| `frontend/GenerateSection.kt` | ~110 |
| `frontend/ModelSection.kt` | ~200 |
| `frontend/TopBars.kt` | ~250 |
| `frontend/SettingsDrawer.kt` | ~300 |
| `frontend/SharedComponents.kt` | ~110 |
| **合计** | **~1660** |

## 18.6 执行步骤

1. 创建 4 个包目录: `ui/orchestrator/`, `ui/backend/`, `ui/queue/`, `ui/frontend/`
2. 创建 8 个新文件，暂为空壳
3. 从 `MainActivity.kt` 逐段提取代码到对应文件
4. 更新 `MainActivity.kt` 的 import 和引用
5. 编译验证 (`./gradlew assembleDebug`)
6. 审计审查各文件是否符合模块职责

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §18 内容，创建独立文件 |
