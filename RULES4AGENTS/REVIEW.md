# REVIEW.md — Architecture Review 入口

> 版本: 4.0
> 更新日期: 2026-06-15

## 目录层次设计

```
RULES4AGENTS/
├── REVIEW.md                  ← 本文件：入口指南 + 写入规范
└── ReviewDocs/
    ├── index.md               ← 问题总索引
    └── detail/                ← 每个问题独立文件
        ├── BKND-PROC-0001.md
        ├── BKND-PROC-0002.md
        └── ...
```

## 设计理念

1. **分散存储** — 每个架构问题独立成文件，避免单文件膨胀
2. **等长编码可检索** — `grep "BKND-PROC-0001"` 精准定位，`XXXX-XXXX-NNNN` 12 位等长，方便对齐排序
3. **Agent 友好** — 新发现问题 → 创建 1 个新文件 + 更新 `index.md`，无需重写大文档
4. **索引驱动** — `ReviewDocs/index.md` 提供完整问题清单，`detail/` 提供深度分析

## Agent 使用指南

### 查找问题

1. **快速定位**: 打开 `ReviewDocs/index.md`，查看问题总表，找到目标 ID (`XXXX-XXXX-NNNN`)
2. **深入详情**: 打开 `ReviewDocs/detail/{XXXX-XXXX-NNNN}.md` 阅读完整分析
3. **文本搜索**: `grep "关键词" ReviewDocs/index.md` 或 `grep -l "关键词" ReviewDocs/detail/*.md`

### 写入规范 (CRITICAL)

Agent 在发现新问题或更新现有问题时，**必须**遵循以下规范：

#### 发现新问题 → 创建新文件

1. **分配 ID**: 根据问题领域选择 Category + Sub-category 前缀（见速查表），在 `ReviewDocs/index.md` 中确认下一个可用 NNNN 编号
2. **创建详情文件**: `ReviewDocs/detail/{XXXX-XXXX-NNNN}.md`，必须包含以下章节：
   - 问题标题 + 元数据表 (优先级/状态/分类/涉及文件)
   - 问题描述
   - 根因分析
   - 修复方案 (若已知)
   - **变更历史** (每次变更必须对人来可读)
3. **更新索引**: 在 `ReviewDocs/index.md` 的 §1 总表中追加一行

#### 更新已有问题

1. **更新详情文件**: 修改 `ReviewDocs/detail/{XXXX-XXXX-NNNN}.md` 的状态/描述/方案，在 **变更历史** 追加记录
2. **更新索引**: 同步 `ReviewDocs/index.md` 中的状态

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
| `BKND-PROC` | Backend | Process | 后端进程生命周期管理 |
| `BKLC-BPAS` | BackendLC | Bypass | 绕过 BackendManager 的旧代码路径 |
| `QUEU-SYST` | Queue | System | 队列处理系统 |
| `UILA-COMP` | UI Layer | Component | UI 组件层 (Activity/Screen/ViewModel) |
| `HTTP-CLNT` | HTTP | Client | 网络客户端 (OkHttpClient, HTTP) |
| `DATA-STOR` | Data | Storage | 数据持久化 (Room, SharedPreferences) |
| `CORO-EXEC` | Coroutine | Execution | 协程执行与生命周期 |
| `MODU-SPLT` | Module | Split | 模块化拆分 |
| `DFLW-INTG` | DataFlow | Integrity | 数据流完整性问题 |

#### 问题状态定义

| 状态 | 含义 |
|------|------|
| Fully Fixed | 已修复落地，代码已验证 |
| Partially Fixed | 部分修复，仍有残留 |
| Newly Discovered | 新发现问题，未开始处理 |
| Resolution Planned | 方案设计完成，待后续迭代 |

> **重要**: 状态进展**仅在 `ReviewDocs/index.md` 中维护**。`detail/{ID}.md` 只包含问题细节（描述/根因/方案/变更历史），不写状态标签。

## 通用规则

- **已有分类覆盖**: 新增问题时，优先使用已有的分类编码；如果已有分类可以覆盖，用已有分类。如有必要，可新增 `XXXX-XXXX` 分类编码。
- **分类速查**: 所有分类速查和编码规范维护在本入口文件中，不在 `index.md` 中维护。
- **index.md 不需要统计数量**: 不维护统计计数表。index.md 仅维护问题总表。
- **index.md 不需要变更记录**: 变更历史由 git 管理，index.md 自身不维护变更记录。
- **detail 文件必须有变更记录**: 每个 `detail/{ID}.md` 文件末尾**必须**包含 "变更历史" 章节，记录每次更新日期和描述，确保直接对人类可读。
