# Agent Rules

ALL project rules for AI agents reside in the **[RULES4AGENTS/](RULES4AGENTS/)** directory.

## Entry Points

| Entry | Purpose |
|-------|---------|
| [RULES4AGENTS/ACTION.md](RULES4AGENTS/ACTION.md) | Build & CI/CD behavior — no local toolchain; cloud CI only |
| [RULES4AGENTS/REVIEW.md](RULES4AGENTS/REVIEW.md) | Architecture review issues — discovery, tracking, resolution |
| [RULES4AGENTS/PrdReqDoc.md](RULES4AGENTS/PrdReqDoc.md) | Product requirement documents — feature specs & constraints |

## Top-Level Config Files

| File | Purpose |
|------|---------|
| [VERSION_NAME](VERSION_NAME) | Semantic version (`YYYY.MM.DD.HH.mm` format); drives CI release naming |
| [VERSION_CODE](VERSION_CODE) | Incrementing build number; drives Android versionCode |
| [build-log.txt](build-log.txt) | Raw CI build output (gitignored, downloaded from CI artifacts) |

## Design Hierarchy

The `RULES4AGENTS/` directory uses a three-layer structure for rule documentation:

```
RULES4AGENTS/
├── {ENTRY}.md             ← Layer 1: Entry file (REVIEW.md, PrdReqDoc.md, ACTION.md)
│   Redirect + write spec + quick reference tables + encoding rules
│
├── {Folder}/
│   └── index.md           ← Layer 2: Master index (ReviewDocs/index.md, PrdReqDocs/index.md)
│       Complete item table with ID links; no stats, no changelog (git tracks)
│
└── {Folder}/detail/
    └── XXXX-XXXX-NNNN.md  ← Layer 3: Detail files (one per item)
        Full analysis/spec + must include a human-readable changelog section
```

### Layer 1 — Entry File (`REVIEW.md`, `PrdReqDoc.md`, `ACTION.md`)

- `REVIEW.md` / `PrdReqDoc.md`: Lightweight redirect to the index folder; contains directory structure diagram, design principles, agent usage guide, write specifications, encoding rules, category quick-reference table, and general rules. Does NOT contain the master index of items.
- `ACTION.md`: Self-contained build behavior spec (no index/detail sub-structure). Declares no local build toolchain, CI/CD trigger rules, and agent build prohibitions.

### Layer 2 — Index (`index.md`)

- Master item table with ID, metadata, and links to detail files
- Does NOT maintain statistics/counts
- Does NOT maintain changelogs (git manages version history)
- Only the item table is maintained here

### Layer 3 — Detail Files (`detail/XXXX-XXXX-NNNN.md`)

- One file per encoded item
- Must include a "变更历史" (Change History) section at the end — each change must be directly human-readable
- Encoded using `XXXX-XXXX-NNNN` 12-character fixed-length IDs for grep-ability

## Rules

- **Read**: Before any task, scan `RULES4AGENTS/` for applicable rules.
- **Write**: When asked to persist a rule, create/update the appropriate file under `RULES4AGENTS/`.

This file itself contains no rules — it is only a redirect to `RULES4AGENTS/`.
