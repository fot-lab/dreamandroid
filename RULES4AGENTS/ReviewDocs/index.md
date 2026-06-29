# Architecture Review — Issue Index

> 版本: 20.0
> 更新日期: 2026-06-29
> Phase A~F+startup+ViewModel 审计 (67 总数, 57 Fixed/Won'tFix, 10 剩余)

> **编码规则与写入规范**: 详见入口文件 [REVIEW.md](../REVIEW.md)。

> **总体规划**: 参见 [PLAN-ROAD-0001](detail/PLAN-ROAD-0001.md)

## 1. 问题总表

| ID | P | 问题摘要 | 状态 | 详情 |
|----|---|---------|------|------|
| BKND-PROC-0001 | P0 | 进程所有权混乱：BackendService/UpscaleBackendManager 已删除 | Fully Fixed | [detail/BKND-PROC-0001.md](detail/BKND-PROC-0001.md) |
| BKND-PROC-0002 | P1 | 状态机不统一（两个 StateFlow） | Fully Fixed | [detail/BKND-PROC-0002.md](detail/BKND-PROC-0002.md) |
| BKND-PROC-0003 | P1 | 切换逻辑泄漏到 UI | Fully Fixed | [detail/BKND-PROC-0003.md](detail/BKND-PROC-0003.md) |
| BKND-PROC-0004 | P1 | prepareRuntimeDir 代码重复 | Fully Fixed | [detail/BKND-PROC-0004.md](detail/BKND-PROC-0004.md) |
| BKND-PROC-0005 | P1 | Upscale 无前台通知保护 | Fully Fixed | [detail/BKND-PROC-0005.md](detail/BKND-PROC-0005.md) |
| BKND-PROC-0006 | P0 | 僵尸进程风险 | Fully Fixed | [detail/BKND-PROC-0006.md](detail/BKND-PROC-0006.md) |
| BKND-PROC-0007 | P0 | ViewModel 构造函数触发 BackendManager 重量级初始化链 | Newly Discovered | [detail/BKND-PROC-0007.md](detail/BKND-PROC-0007.md) |
| BKLC-BPAS-0001 | P0 | ModelRunScreen startForegroundService 绕过 | Fully Fixed | [detail/BKLC-BPAS-0001.md](detail/BKLC-BPAS-0001.md) |
| BKLC-BPAS-0002 | P0 | ModelRunScreen ACTION_RESTART 绕过 | Fully Fixed | [detail/BKLC-BPAS-0002.md](detail/BKLC-BPAS-0002.md) |
| BKLC-BPAS-0003 | P1 | ModelRunScreen cleanup() stopService 绕过 | Fully Fixed | [detail/BKLC-BPAS-0003.md](detail/BKLC-BPAS-0003.md) |
| BKLC-BPAS-0004 | P1 | ModelRunScreen BackgroundGenerationService 绕过 | Fully Fixed | [detail/BKLC-BPAS-0004.md](detail/BKLC-BPAS-0004.md) |
| BKLC-BPAS-0005 | P1 | UpscaleBackendManager 重复启动逻辑 | Fully Fixed | [detail/BKLC-BPAS-0005.md](detail/BKLC-BPAS-0005.md) |
| BKLC-BPAS-0006 | P1 | UpscaleBackendManager 重复 stop 逻辑 | Fully Fixed | [detail/BKLC-BPAS-0006.md](detail/BKLC-BPAS-0006.md) |
| BKLC-BPAS-0007 | P1 | UpscaleBackendManager 独立状态流 | Fully Fixed | [detail/BKLC-BPAS-0007.md](detail/BKLC-BPAS-0007.md) |
| BKLC-BPAS-0008 | P1 | MainActivity 直接使用 UpscaleBackendManager | Fully Fixed | [detail/BKLC-BPAS-0008.md](detail/BKLC-BPAS-0008.md) |
| BKLC-BPAS-0009 | P1 | BackendService.prepareRuntimeDir 重复 | Fully Fixed | [detail/BKLC-BPAS-0009.md](detail/BKLC-BPAS-0009.md) |
| BKLC-BPAS-0010 | P2 | BackendService flavor 检查不一致 | Fully Fixed | [detail/BKLC-BPAS-0010.md](detail/BKLC-BPAS-0010.md) |
| QUEU-SYST-0001 | P0 | per-task Service 开销大 | Fully Fixed | [detail/QUEU-SYST-0001.md](detail/QUEU-SYST-0001.md) |
| QUEU-SYST-0002 | P0 | 队列循环依赖 UI 生命周期 | Fully Fixed | [detail/QUEU-SYST-0002.md](detail/QUEU-SYST-0002.md) |
| QUEU-SYST-0003 | P1 | 静态 companion 共享状态 | Fully Fixed | [detail/QUEU-SYST-0003.md](detail/QUEU-SYST-0003.md) |
| QUEU-SYST-0004 | P2 | busy-wait 在 UI 层 | Fully Fixed | [detail/QUEU-SYST-0004.md](detail/QUEU-SYST-0004.md) |
| QUEU-SYST-0005 | P1 | 队列无持久化（进程杀丢 PENDING） | Fully Fixed | [detail/QUEU-SYST-0005.md](detail/QUEU-SYST-0005.md) |
| QUEU-SYST-0006 | P2 | SSE 解析不可复用 | Fully Fixed | [detail/QUEU-SYST-0006.md](detail/QUEU-SYST-0006.md) |
| QUEU-SYST-0007 | P2 | 大 Bitmap 未主动回收 | Fully Fixed | [detail/QUEU-SYST-0007.md](detail/QUEU-SYST-0007.md) |
| QUEU-SYST-0008 | P2 | QueueProcessingService/GenerationWorker 代码重复 | Resolution Planned | [detail/QUEU-SYST-0008.md](detail/QUEU-SYST-0008.md) |
| UILA-COMP-0001 | P0 | God Object：AppContent() ~1800 行 | Fully Fixed | [detail/UILA-COMP-0001.md](detail/UILA-COMP-0001.md) |
| UILA-COMP-0002 | P1 | 无法单独测试 | Fully Fixed | [detail/UILA-COMP-0002.md](detail/UILA-COMP-0002.md) |
| UILA-COMP-0003 | P2 | 错误处理不一致 | Fully Fixed | [detail/UILA-COMP-0003.md](detail/UILA-COMP-0003.md) |
| UILA-COMP-0004 | P2 | 无 DI 框架 | Won't Fix | [detail/UILA-COMP-0004.md](detail/UILA-COMP-0004.md) |
| UILA-COMP-0005 | P2 | UI 层直接 HTTP | Fully Fixed | [detail/UILA-COMP-0005.md](detail/UILA-COMP-0005.md) |
| UILA-COMP-0006 | P1 | 超大 Kotlin 文件拆分 (Phase 1-4 完成, ModelRunScreen -86%; Phase 5 deferred) | Fully Fixed | [detail/UILA-COMP-0006.md](detail/UILA-COMP-0006.md) |
| UILA-COMP-0007 | P1 | ModelRunScreen 遗留代码分析与拆分 (Phase 4: 4471→610, -86%) | Fully Fixed | [detail/UILA-COMP-0007.md](detail/UILA-COMP-0007.md) |
| UILA-COMP-0008 | P3 | MainActivity.onCreate() 未使用 val app 变量 (重构遗留) | Newly Discovered | [detail/UILA-COMP-0008.md](detail/UILA-COMP-0008.md) |
| UILA-COMP-0009 | P3 | MainViewModel 误用 AndroidViewModel (纯状态持有者无需 Application) | Newly Discovered | [detail/UILA-COMP-0009.md](detail/UILA-COMP-0009.md) |
| HTTP-CLNT-0001 | P1 | 4 个 OkHttpClient 无复用 | Fully Fixed | [detail/HTTP-CLNT-0001.md](detail/HTTP-CLNT-0001.md) |
| HTTP-CLNT-0002 | P1 | Health check 每次新建 client | Fully Fixed | [detail/HTTP-CLNT-0002.md](detail/HTTP-CLNT-0002.md) |
| HTTP-CLNT-0003 | P2 | 超时配置不一致 | Fully Fixed | [detail/HTTP-CLNT-0003.md](detail/HTTP-CLNT-0003.md) |
| HTTP-CLNT-0004 | P3 | UI 层处理 HTTP 错误 | Fully Fixed | [detail/HTTP-CLNT-0004.md](detail/HTTP-CLNT-0004.md) |
| DATA-STOR-0001 | P1 | 模型数据双源无 SSOT | Newly Discovered | [detail/DATA-STOR-0001.md](detail/DATA-STOR-0001.md) |
| DATA-STOR-0002 | P2 | SharedPreferences 碎片化 | Fully Fixed | [detail/DATA-STOR-0002.md](detail/DATA-STOR-0002.md) |
| DATA-STOR-0003 | P0 | RecordRepository 在 ViewModel 构造中同步触发 Room DB | Newly Discovered | [detail/DATA-STOR-0003.md](detail/DATA-STOR-0003.md) |
| CORO-EXEC-0001 | P0 | runBlocking 主线程阻塞 | Fully Fixed | [detail/CORO-EXEC-0001.md](detail/CORO-EXEC-0001.md) |
| CORO-EXEC-0002 | P1 | 协程 Scope 泄漏（3 处） | Fully Fixed | [detail/CORO-EXEC-0002.md](detail/CORO-EXEC-0002.md) |
| CORO-EXEC-0003 | P1 | ModelRepository.runBlocking 在 by lazy 中阻塞主线程 | Newly Discovered | [detail/CORO-EXEC-0003.md](detail/CORO-EXEC-0003.md) |
| MODU-SPLT-0001 | P3 | 单模块无编译隔离 | Newly Discovered | [detail/MODU-SPLT-0001.md](detail/MODU-SPLT-0001.md) |
| DFLW-INTG-0001 | P0 | saveGeneratedImage 返回值被忽略 | Fully Fixed | [detail/DFLW-INTG-0001.md](detail/DFLW-INTG-0001.md) |
| DFLW-INTG-0002 | P2 | HistoryManager 文件-DB 写入不一致 | Fully Fixed | [detail/DFLW-INTG-0002.md](detail/DFLW-INTG-0002.md) |
| DFLW-INTG-0003 | P1 | SseStreamParser: flow{} 内 withContext 反模式 | Fully Fixed | [detail/DFLW-INTG-0003.md](detail/DFLW-INTG-0003.md) |
| DFLW-INTG-0004 | P1 | SseStreamParser: readLine() 阻塞不响应取消 | Fully Fixed | [detail/DFLW-INTG-0004.md](detail/DFLW-INTG-0004.md) |
| DFLW-INTG-0005 | P1 | Health check 双路径策略不一致 | Fully Fixed | [detail/DFLW-INTG-0005.md](detail/DFLW-INTG-0005.md) |
| DFLW-INTG-0006 | P1 | 生成异常双路径策略不一致 | Fully Fixed | [detail/DFLW-INTG-0006.md](detail/DFLW-INTG-0006.md) |
| DFLW-INTG-0007 | P2 | resultBitmap 内存累积 | Fully Fixed | [detail/DFLW-INTG-0007.md](detail/DFLW-INTG-0007.md) |
| DFLW-INTG-0008 | P2 | RecordRepository JSON 损坏全丢 | Fully Fixed | [detail/DFLW-INTG-0008.md](detail/DFLW-INTG-0008.md) |
| DFLW-INTG-0009 | P3 | RecordRepository 并发写入不安全 | Fully Fixed | [detail/DFLW-INTG-0009.md](detail/DFLW-INTG-0009.md) |
| DFLW-INTG-0010 | P1 | processingActive 双重数据源 | Fully Fixed | [detail/DFLW-INTG-0010.md](detail/DFLW-INTG-0010.md) |
| DFLW-INTG-0011 | P2 | QueueController.stop() 竞态 | Fully Fixed | [detail/DFLW-INTG-0011.md](detail/DFLW-INTG-0011.md) |
| DFLW-INTG-0012 | P0 | 进程被杀 PENDING 全丢 | Fully Fixed | [detail/DFLW-INTG-0012.md](detail/DFLW-INTG-0012.md) |
| DFLW-INTG-0013 | P2 | 生成参数双重加载 | Fully Fixed | [detail/DFLW-INTG-0013.md](detail/DFLW-INTG-0013.md) |
| DFLW-INTG-0014 | P3 | Add to Queue 静默失败 | Fully Fixed | [detail/DFLW-INTG-0014.md](detail/DFLW-INTG-0014.md) |
| DFLW-INTG-0015 | P3 | 无效 Seed 静默忽略 | Fully Fixed | [detail/DFLW-INTG-0015.md](detail/DFLW-INTG-0015.md) |
| DFLW-INTG-0016 | P1 | SSE 流前后端字段对齐与兼容性修正 | Fully Fixed | [detail/DFLW-INTG-0016.md](detail/DFLW-INTG-0016.md) |
| DFLW-INTG-0017 | P1 | cfg_scale / sampler / scheduler 参数全链路审计 + MNN/QNN 颜色编码对齐验证 | Verified | [detail/DFLW-INTG-0017.md](detail/DFLW-INTG-0017.md) |
| LCLE-MEMO-0001 | P0 | App 生命周期&内存安全完整审计 (13 findings) | Fully Fixed | [detail/LCLE-MEMO-0001.md](detail/LCLE-MEMO-0001.md) |
| LCLE-MEMO-0002 | P1 | 惰性初始化依赖未就绪时的空降处理完整审计 (6 种模式, 5 个问题) | Newly Discovered | [detail/LCLE-MEMO-0002.md](detail/LCLE-MEMO-0002.md) |


