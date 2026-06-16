# LCLE-MEMO-0002: 惰性初始化依赖未就绪时的空降处理完整审计

| 属性 | 值 |
|------|-----|
| 优先级 | P1 |
| 分类 | Lifecycle & Memory Safety |
| 关联 | DATA-STOR-0003, BKND-PROC-0007, CORO-EXEC-0003 |
| 审计日期 | 2026-06-16 |

## 1. 审计范围

对 App 启动链路中所有「首次访问时自动创建」的惰性初始化依赖进行审计，检查如果被依赖的对象/文件尚未创建时的处理策略：

| 初始化模式 | 示例 | 使用位置 |
|------------|------|----------|
| `by lazy` | `backendManager`, `backendService` | `DreamAndroidApplication` |
| Double-checked locking singleton | `AppDatabase.get()`, `QueueRepository.getInstance()` | `data/db`, `service` |
| Constructor-init with filesystem scan | `ModelRepository.initializeModels()` | `data/Model.kt` |
| `@Synchronized` + `@Volatile` flag | `RuntimeDirPreparer.prepare()` | `service/backend` |
| Constructor-init Room DAO | `RecordRepository.dao` | `data/RecordRepository.kt` |

## 2. 逐项审计

### 2.1 `by lazy { BackendManager(this) }` — DreamAndroidApplication.backendManager

| 检查项 | 结果 |
|--------|------|
| 首次访问前未初始化时 | `by lazy` 自动创建，线程安全 (`LazyThreadSafetyMode.SYNCHRONIZED`) |
| 构造失败时 | 异常穿透到首次调用方（ViewModel 构造函数），无 try-catch → ❌ |
| 被多个 ViewModel 同时访问 | `synchronized` 保证只初始化一次，后续调用方等待 → ✅ |
| 内部依赖不存在 (RuntimeDirPreparer) | `appScope.launch` 异步预准备，有 try-catch → ✅ |
| 策略评价 | ⚠️ 基本正确，但构造失败无保护 |

### 2.2 `AppDatabase.get(context)` — Room 单例

| 检查项 | 结果 |
|--------|------|
| 首次访问前未初始化时 | Double-checked locking + `Room.databaseBuilder().build()` 创建 → ✅ |
| 数据库文件不存在时 | Room 自动创建空数据库 + 执行 schema 迁移 → ✅ |
| 迁移失败时 | `fallbackToDestructiveMigration(dropAllTables = true)` 兜底 → ⚠️ SQL 执行失败仍会抛异常 |
| 被多个调用方并发访问 | `synchronized` 保护 → ✅ |
| 上下文为 Activity 时 | 内部使用 `context.applicationContext` 防止泄漏 → ✅ |
| 策略评价 | ✅ 基本正确，无泄漏 |

### 2.3 `QueueRepository.getInstance(context)` — 队列单例

| 检查项 | 结果 |
|--------|------|
| 首次访问前未初始化时 | Double-checked locking + 创建实例 + `restoreFromDb()` → ✅ |
| DB 恢复失败时 | `restoreFromDb()` 在协程内，异常只影响日志 → ✅ |
| 被多个调用方并发访问 | `@Volatile` + `synchronized` → ✅ |
| 策略评价 | ✅ 正确 |

### 2.4 `ModelRepository.initializeModels()` — 文件系统扫描

| 检查项 | 结果 |
|--------|------|
| modelsDir 目录不存在时 | `if (modelsDir.exists() && modelsDir.isDirectory)` 判空，返回空列表 → ✅ |
| 特定模型目录不存在时 | `forEach` 跳过 → ✅ |
| SharedPreferences 不存在时 | `baseUrl` 懒加载中使用默认值 → ✅ |
| 构造时阻塞主线程 | `runBlocking(Dispatchers.IO)` 在 `by lazy` 中阻塞调用线程 → ❌ (见 CORO-EXEC-0003) |
| 策略评价 | ⚠️ 文件系统处理正确，但 runBlocking 有问题 |

### 2.5 `RuntimeDirPreparer.prepare()` — QNN 库解压

| 检查项 | 结果 |
|--------|------|
| 首次调用前未初始化时 | `@Synchronized` + `if (!prepared)` → 创建目录 + 复制 assets → ✅ |
| 目录不存在时 | `if (!exists()) mkdirs()` → ✅ |
| 文件不存在/大小不匹配时 | `needsCopy` = `!targetLib.exists() \|\| sizeMismatch` → ✅ |
| 复制失败时 | `throw RuntimeException("Failed to prepare QNN libraries")` → ❌ 会穿透到调用方 |
| cleanup 后重新 prepare | `cleanup()` 重置 `prepared = false` → ✅ |
| 策略评价 | ✅ 基本正确，复制失败处理粗暴 |

### 2.6 `RecordRepository(context)` — Records 持久化

| 检查项 | 结果 |
|--------|------|
| 数据库未初始化时 | `AppDatabase.get(context)` 自动创建 → ✅ |
| Legacy JSON 不存在时 | `if (!legacyFile.exists()) return` → ✅ |
| Legacy JSON 损坏时 | try-catch → 备份为 `.corrupted` 文件 → ✅ |
| 构造在主线程 | 由 `QueueViewModel` 构造函数触发 → ❌ (见 DATA-STOR-0003) |
| 策略评价 | ⚠️ 文件处理正确，但构造位置不合理 |

## 3. 发现总结

| # | 优先级 | 问题 | 详情 |
|---|--------|------|------|
| 1 | P0 | `by lazy` 构造失败无 try-catch | `BackendManager` 构造异常直接穿透至 ViewModel → 崩溃 |
| 2 | P0 | ViewModel 构造时同步触发 DB | `RecordRepository` 在 `QueueViewModel` 构造时打开 Room (DATA-STOR-0003) |
| 3 | P1 | `runBlocking` 在 `by lazy` 中阻塞主线程 | `ModelRepository._baseUrl` (CORO-EXEC-0003) |
| 4 | P1 | ViewModel 构造触发重量级初始化链 | `backendService` → `BackendManager` (BKND-PROC-0007) |
| 5 | P2 | QNN 库复制失败抛 RuntimeException | `RuntimeDirPreparer` 无优雅降级 |

## 4. 推荐模式

对于所有 App 启动时「不存在则创建」的依赖，应采用以下分层策略：

| 层级 | 模式 | 适用场景 |
|------|------|----------|
| L0 — 无状态变量 | 直接初始化 (val) | 常量、配置 |
| L1 — 轻量对象 | `by lazy` (无 IO) | OkHttpClient, CoroutineScope 等 |
| L2 — 需要 IO 的对象 | `by lazy` + try-catch 或 Application.onCreate 中预初始化 | BackendManager, AppDatabase |
| L3 — 文件/目录 | 存在性检查 + 创建 + try-catch | RuntimeDirPreparer, modelsDir |

**原则**: 
1. ViewModel 构造函数中只做无风险赋值，不做 IO/DB/C++ 进程等可能失败的操作
2. 所有 `by lazy` 块应包裹 try-catch 或在外部有兜底
3. 文件系统操作必须 `exists()` 判断 + 错误处理

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-16 | 初始审计，覆盖 6 种初始化模式，发现 5 个问题 |
