# HTTP API 文档

> **入口文件** — 完整索引见 [HttpAPIs/index.md](HttpAPIs/index.md)

## 目录结构

```
RULES4AGENTS/
├── HTTPAPI.md              ← Layer 1: 本文件 (入口 + 写作规范)
├── HttpAPIs/
│   └── index.md            ← Layer 2: API 文档主索引 (含完整编码表)
└── HttpAPIs/detail/
    ├── A111-APIS-0001.md   ← Layer 3: A1111 WebUI HTTP API
    ├── STBL-APIS-0001.md   ← Layer 3: Stability AI HTTP API
    ├── DREM-APIS-0001.md   ← Layer 3: DreamAndroid HTTP API
    └── CMPR-APIS-0001.md   ← Layer 3: API 对比分析
```

## 设计原则

- **分散存储**: 每个 API 独立一个文件，互不干扰
- **固定长度编码**: `XXXX-XXXX-NNNN` 12字符固定长度 ID，便于 grep
- **Agent 友好**: 入口 → 索引 → 详情，三级层次清晰
- **索引驱动**: index.md 维护全部 API 文档的主表，不维护统计 (git 管理版本)

## Agent 使用指南

### 查找 API 文档
1. 打开 [HttpAPIs/index.md](HttpAPIs/index.md) 查看 API 文档总表
2. 点击对应详情链接进入 detail/ 下的具体文件

### 新增 API 文档
1. 在 `HttpAPIs/detail/` 下创建新文件，按编码规则命名
2. 更新 [HttpAPIs/index.md](HttpAPIs/index.md) 主表添加条目
3. 详情文件必须包含 `## 变更历史` 节

### 更新已有文档
1. 修改 `detail/` 下的对应文件
2. 在文件末尾 `## 变更历史` 追加变更记录
3. 如必要同步更新 index.md 的摘要信息

## 写作规范

### 文件编码规则

编码格式: `XXXX-XXXX-NNNN` (12字符固定长度)

| 段 | 含义 | 示例 |
|----|------|------|
| XXXX (前4) | API 来源代号 | `A111`, `STBL`, `DREM`, `CMPR` |
| XXXX (中4) | 文档分类 | `APIS` = API 参考, `ANLS` = 分析对比 |
| NNNN (后4) | 四位序号 (独立编号) | `0001` |

### 编码领域表

| 前缀 | 含义 |
|------|------|
| `A111-APIS` | A1111 WebUI API 参考 |
| `STBL-APIS` | Stability AI API 参考 |
| `DREM-APIS` | DreamAndroid API 参考 |
| `CMPR-APIS` | API 对比分析 |

### 详情文件格式要求

每个 detail 文件必须包含:
1. 标题: `# XXXX-XXXX-NNNN: <一句话描述>`
2. 元数据表格 (`| 属性 | 值 |`)
3. 正文内容 (按需组织章节)
4. `## 变更历史` — 每次修改必须追加一行 `| YYYY-MM-DD | 描述 |`

index.md 不维护统计数字和变更历史 (git 管理版本)。
