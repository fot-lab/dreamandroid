# PrdReqDoc.md — 产品需求文档入口

> 版本: 1.0
> 更新日期: 2026-06-15

## 目录层次设计

```
RULES4AGENTS/
├── PrdReqDoc.md                ← 本文件：入口指南 + 写入规范
└── PrdReqDocs/
    ├── index.md                ← 需求总索引
    └── detail/                 ← 每个模块功能独立文件
        ├── ARCH-OVER-0001.md
        ├── BKND-APIS-0001.md
        └── ...
```

## 设计理念

1. **分散存储** — 每个产品需求模块独立成文件，避免单文件膨胀
2. **等长编码可检索** — `grep "ARCH-OVER-0001"` 精准定位，`XXXX-XXXX-NNNN` 12 位等长，方便对齐排序
3. **Agent 友好** — 新增需求模块 → 创建 1 个新文件 + 更新 `index.md`，无需重写大文档
4. **索引驱动** — `PrdReqDocs/index.md` 提供完整需求清单，`detail/` 提供深度规范

## Agent 使用指南

### 查找需求

1. **快速定位**: 打开 `PrdReqDocs/index.md`，查看需求总表，找到目标 ID (`XXXX-XXXX-NNNN`)
2. **深入详情**: 打开 `PrdReqDocs/detail/{XXXX-XXXX-NNNN}.md` 阅读完整需求规范
3. **文本搜索**: `grep "关键词" PrdReqDocs/index.md` 或 `grep -l "关键词" PrdReqDocs/detail/*.md`

### 写入规范 (CRITICAL)

Agent 在新增或更新需求模块时，**必须**遵循以下规范：

#### 新增需求模块 → 创建新文件

1. **分配 ID**: 根据模块领域选择 Category + Sub-category 前缀（见速查表），在 `PrdReqDocs/index.md` 中确认下一个可用 NNNN 编号
2. **创建详情文件**: `PrdReqDocs/detail/{XXXX-XXXX-NNNN}.md`，必须包含以下章节：
   - 模块标题 + 元数据表 (所属章节/优先级/依赖)
   - 功能描述
   - 接口/数据模型规范
   - 技术约束
   - **变更历史** (每次变更必须对人类可读)
3. **更新索引**: 在 `PrdReqDocs/index.md` 的 §1 总表中追加一行

#### 更新已有需求

1. **更新详情文件**: 修改 `PrdReqDocs/detail/{XXXX-XXXX-NNNN}.md` 的内容/规范，在 **变更历史** 追加记录
2. **更新索引**: 同步 `PrdReqDocs/index.md` 中的版本/摘要信息

#### 编码规范

```
XXXX-XXXX-NNNN

格式: 4字母-4字母-4数字 = 12位等长编码
├── XXXX (前4位): 领域缩写 (Domain)
├── XXXX (中4位): 子领域缩写 (Sub-domain)
└── NNNN (后4位): 4位递增序号，每个子域独立编号
```

#### 领域速查表 (XXXX-XXXX 前缀)

| 前缀 | Domain | Sub-domain | 说明 |
|------|--------|------------|------|
| `ARCH-OVER` | Architecture | Overview | 产品概述与系统架构 |
| `BKND-APIS` | Backend | APIs | 后端 HTTP 接口规范 |
| `FEND-MODL` | Frontend | Models | 模型管理页面 |
| `FEND-QUEU` | Frontend | Queue | 任务队列页面 |
| `FEND-GENR` | Frontend | Generate | 参数组合页面 |
| `FEND-UPSC` | Frontend | Upscale | 超分辨率页面 |
| `FEND-BROW` | Frontend | Browse | 图库/画廊页面 |
| `SERV-BACK` | Service | Backend | 服务层 — 后端管理 |
| `SERV-QUEU` | Service | Queue | 服务层 — 队列处理 |
| `DATA-PERS` | Data | Persistence | 数据持久化 |
| `NAVI-DRAW` | Navigation | Drawer | 导航抽屉与设置 |
| `PERM-REQS` | Permission | Requirements | 权限要求 |
| `FILE-STRC` | File | Structure | 当前文件结构 |
| `ARCH-TARG` | Architecture | Target | 目标架构设计 |
| `MODU-IFCE` | Module | Interface | 核心模块接口标准 |
| `TECH-SPEC` | Technical | Specification | 技术规范 |
| `FILE-TARG` | File | Target | 目标文件结构 |
| `STAT-REVW` | Static | Review | 静态审查发现 |
| `FIXP-PLAN` | Fix Plan | Plan | 架构修复方案 |
| `BKND-LFCY` | Backend | Lifecycle | 后端生命周期管理 |
| `ACTV-SPLT` | Activity | Split | MainActivity 模块化拆分 |

## 通用规则

- **已有分类覆盖**: 新增需求时，优先使用已有的分类编码；如果已有分类可以覆盖，用已有分类。如有必要，可新增 `XXXX-XXXX` 分类编码。
- **分类速查**: 所有分类速查和编码规范维护在本入口文件中，不在 `index.md` 中维护。
- **index.md 不需要统计数量**: 不维护统计计数表。index.md 仅维护需求总表。
- **index.md 不需要变更记录**: 变更历史由 git 管理，index.md 自身不维护变更记录。
- **detail 文件必须有变更记录**: 每个 `detail/{ID}.md` 文件末尾**必须**包含 "变更历史" 章节，记录每次更新日期和描述，确保直接对人类可读。
