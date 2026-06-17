# BKND-PROC-0008: main.cpp 并发安全 & 内存安全审计 + 重构建议

| 属性 | 值 |
|------|-----|
| 优先级 | P0 |
| 分类 | Backend Process — C++ Internals Safety |
| 关联 | BKND-PROC-0006 (僵尸进程), QUEU-SYST-0001 (队列架构) |

## 问题描述

`main.cpp` 是一个 **3889 行**的单体 C++ 文件，承载了 CLI 解析、模型加载、HTTP 服务器、Stable Diffusion 推理管线、SDXL lowram 动态加载、图片安全检测、Tokenize 等全部逻辑。

核心风险面：

1. **P0: `/generate` handler 以 `[&]` 捕获全部全局变量，并发请求数据竞态**
2. **P1: `img_data` / `mask_data` 等全局 vector 使用 `.clear()` 不释放 capacity，请求间内存累积**
3. **P1: SDXL lowram 模式的 load/release 函数修改全局 QNN/MNN 指针，与并发请求竞态**
4. **P2: 单体文件缺乏模块边界，难以测试和维护**

## 涉及文件

- `app/src/main/cpp/src/main.cpp` — 全部 3889 行，所有问题均在此文件
- `app/src/main/cpp/src/SDUtils.hpp` — `GenerationResult` 结构体定义（无问题）

---

## §1 根因分析

### 1.1 P0: `/generate` Handler 的 `[&]` 捕获 —— 数据竞态

```cpp:3304:3320:app/src/main/cpp/src/main.cpp
svr.Post("/generate", [&](const httplib::Request &req,
                          httplib::Response &res) {
  try {
    auto json = nlohmann::json::parse(req.body);
    // ...
    prompt = json["prompt"].get<std::string>();        // 全局
    negative_prompt = json.value("negative_prompt", ""); // 全局
    steps = json.value("steps", 20);                    // 全局
    cfg = json.value("cfg", 7.5f);                      // 全局
    // ... 共 20+ 全局变量被覆写
```

**cpp-httplib 为每个连接创建独立线程。** `/generate` 的 lambda 通过 `[&]` 捕获了所有 60+ 个全局变量。两个并发的 `/generate` 请求会在以下时间窗口内互相覆盖对方的参数：

```
Thread A: prompt = "a cat"     →  进入 generateImage()
Thread B: prompt = "a dog"     →  进入 generateImage()
                                   ↑ A 的 prompt 已经被 B 覆盖为 "a dog"
```

**受影响的关键全局变量（完整清单）**：

| 行号 | 变量 | 类型 | 覆写时机 |
|------|------|------|----------|
| 184 | `prompt` | `std::string` | 每个请求 |
| 185 | `negative_prompt` | `std::string` | 每个请求 |
| 209 | `steps` | `int` | 每个请求 |
| 210 | `cfg` | `float` | 每个请求 |
| 211 | `seed` | `unsigned` | 每个请求 |
| 212 | `scheduler_type` | `std::string` | 每个请求 |
| 213 | `img_data` | `std::vector<float>` | 每个请求 clear+resize |
| 214 | `mask_data` | `std::vector<float>` | 每个请求 clear+resize |
| 215 | `mask_data_full` | `std::vector<float>` | 每个请求 clear+resize |
| 216 | `denoise_strength` | `float` | 每个请求 |
| 217 | `request_img2img` | `bool` | 每个请求 |
| 218 | `request_has_mask` | `bool` | 每个请求 |
| 219 | `use_opencl` | `bool` | 每个请求 |
| 224-236 | `aspect_pad_*` 系列 | `bool/int` | 每个请求 |
| 239-240 | `show_diffusion_*` | `bool/int` | 每个请求 |
| 344-347 | `sample_width/height` | `int` | 每个请求 |
| 342-343 | `output_width/height` | `int` | 每个请求 |

**为什么 Queue "一次只发一个" 的假设不够安全**：

1. Worker 重试时可能在上一个请求的 SSE 流尚未完全关闭时发第二个请求
2. TCP 连接可能有 TIME_WAIT 残留，导致旧连接的 chunked response 仍在传输
3. Health check 虽然安全（`[]` 无状态捕获），但 Worker 的频繁轮询 + 重试形成时间窗口

### 1.2 P1: 全局 Vector 的 Capacity 泄漏

```cpp:3341:3343:app/src/main/cpp/src/main.cpp
img_data.clear();
mask_data.clear();
mask_data_full.clear();
```

`std::vector::clear()` 不释放已分配的 capacity。每个请求的 `img_data`（高分辨率图可达 `3×W×H×sizeof(float)`，1024×1024 时 ~12MB）在 clear 后仍然占据内存。多个请求后，这些空闲 capacity 累积在进程地址空间中不动。

**具体数值**：
- 1024×1024 输入图：`img_data` capacity 可达 **12MB**
- 2048×2048 输入图：可达 **48MB**
- `mask_data_full` 同样规模

虽然有 32GB 设备空间，但这是不必要的内存驻留，在低内存场景或连续大量请求时会加速到达 OS 压力阈值。

### 1.3 P1: SDXL Lowram 模式的并发风险

Lowram 模式在推理过程中动态加载/释放 QNN 模型：

```cpp:1062:1132:app/src/main/cpp/src/main.cpp
static void loadSdxlClipMnnIfNeeded() {
  if (!clipInterpreter) {         // 读全局指针
    clipInterpreter = createMnnInterpreterMmap(...);  // 写全局指针
    // ...
  }
}

static void releaseSdxlClipMnn() {
  if (clipSession && clipInterpreter) {
    clipInterpreter->releaseSession(clipSession);
  }
  clipSession = nullptr;          // 写全局指针
  delete clipInterpreter;          // 写全局指针 + 释放内存
  clipInterpreter = nullptr;
}
```

两个并发的 lowram 请求可能：
- **Use-After-Free**：线程 A 执行到 CLIP 推理时，线程 B 调 `releaseSdxlClipMnn()` 释放了 `clipInterpreter`
- **Double-Delete**：两个线程同时调 `releaseSdxlClipMnn()`

当前缓解：`ScopeExit` (line 1054-1059) 在 `generateImage()` 返回时释放所有 lowram 模型。但如果 `generateImage()` 本身因为竞态导致 use-after-free crash，`ScopeExit` 也无济于事。

### 1.4 P2: 文件单体膨胀（已解决 ✅）

~~原始 main.cpp 为 3889 行单体文件。~~ 经过 P2-P4 拆分后：

| 逻辑模块 | 当前文件 | 行数 |
|----------|---------|------|
| Globals & Config | AppContext.hpp + Config.hpp + RequestContext.hpp | ~170 |
| CLI Parsing | ServerCli.cpp/.hpp | ~450 |
| Model Loading | ModelLoader.cpp/.hpp | ~165 |
| Lowram Helpers | MnnHelper.cpp/.hpp + QnnHelper.cpp/.hpp | ~500 |
| `generateImage()` | GenerateHandler.cpp/.hpp | ~1265 |
| HTTP Server + Handlers | main.cpp | ~600 |
| Utilities | SDUtils.hpp + VaeTilingHelper + PromptCacheUtils + TextEncoder + TokenizeHandler | ~1100 |

**main.cpp: 3889 → 914 行（-76.5%）**，拆分为 17 个模块文件。

---

## §2 修复方案（已实施）

### 2.1 ✅ P0: 并发请求拒绝 — HTTP 503 Service Unavailable（Stability AI / Ollama 对齐）

**已实施**：用 `ServerState` 类（`server_state.hpp`）替代散落的 atomic/mutex 全局变量。核心机制：

```
请求 → acquireBusy() [compare_exchange] → 成功 → 处理 → release()
                                       → 失败 → HTTP 503 + Retry-After: 3
```

- **HTTP 状态码**：~~409 Conflict~~ → **503 Service Unavailable**（对齐 Stability AI / Ollama / vLLM）
- **Retry 信号**：`Retry-After: 3` HTTP 标准头，而非 JSON 体内的 `retry_after_ms`
- **错误响应格式**：对齐 Stability AI 格式 `{"id": "busy-...", "name": "busy", "errors": ["..."]}`
- **原子操作**：`compare_exchange_strong` 无 check-then-act 窗口，无需额外 mutex

### 2.2 ✅ P1: 全局 Vector 释放 Capacity

已实施：在每个请求解析 JSON 后、填充 `img_data` 前调用 `shrink_to_fit()`。

### 2.3 ✅ P1: Lowram 模式并发安全

已实施：所有 7 个 load/release 函数族使用 `lowramMutex()`（`server_state.hpp` 提供），与 `ServerState` 解耦。

### 2.4 ✅ P0: 死锁/假死防御 — Generation Watchdog

**新增**：`ServerState` 内置可配置超时（默认 300s）。在 SSE chunked content provider 启动时检查超时，超时则：
1. 自动 `release()` 释放 busy flag
2. 向客户端发送 `event: error` SSE 事件（含 timeout 原因）
3. 退出 provider 循环

这防止了 GPU/QNN 管线 hang 导致的永久死锁。

### 2.5 ✅ P0: Upscale 并发保护

**新增**：`/upscale` handler 同样使用 `ServerState::acquireBusy()` / `release()` 串行化，返回 503 当 busy。

### 2.6 ✅ P1: Stability AI API 格式对齐

所有错误响应统一为 Stability AI 风格：
```json
{
  "id": "type-timestamp",
  "name": "error_name",
  "errors": ["human-readable message"]
}
```

涵盖：400（请求错误）、503（服务器繁忙）、500（内部错误）、SSE error 事件。

### 2.7 ✅ P1: Graceful Shutdown 端点

新增 `POST /shutdown`：设置 `ShuttingDown` 状态 → 返回 200 → 异步 `svr.stop()`。BackendManager 的 orphan detection 通过 health check 感知。

### 2.8 🏗️ P2/P3: 文件拆分 + 集中状态管理（BKND-PROC-0008 阶段 2 & 3）

**P3 架构：AppContext 统一状态管理**

```
AppContext appCtx;  // owned by main()
├── Config (immutable after CLI parsing)
│   ├── Pipeline: use_mnn, sdxl_mode, use_v_pred, ...
│   ├── Paths:    clipPath, unetPath, vaeDecoderPath, ...
│   └── Server:   port, listen_address, nsfw_threshold
├── Models (mutable, loaded once or on-demand)
│   ├── MNN:      clip/clip2/safety interpreters + sessions
│   ├── QNN:      unet/vae apps + function pointers
│   ├── CLIP:     pos_emb, token_emb tables
│   └── Tokenizer + PromptProcessor
└── ServerState (concurrency, progress, watchdog)
```

**宏别名兼容层**（main.cpp 内部）：
```cpp
#define sdxl_mode  (gconf.sdxl_mode)   // → appCtx.conf.sdxl_mode
#define unetApp    (gmod.unetApp)      // → appCtx.models.unetApp
#define g_serverState (gsvr)           // → appCtx.serverState
// ... 共 40+ 别名
```

**拆分文件状态**：

```
app/src/main/cpp/src/
├── AppContext.hpp          ← ✅ 已创建：集中状态管理 struct
├── RequestContext.hpp      ← ✅ 已创建：每次请求不可变参数
├── ServerState.hpp         ← ✅ 已创建：状态机 + 超时检测
├── VaeTilingHelper.cpp/.hpp ← ✅ 纯函数：VAE tiling blender + tile pos
├── PromptCacheUtils.cpp/.hpp ← ✅ 纯函数：prompt cache I/O + UTF-8
│   （promptHasEmbedding 接受 PromptProcessor&，无 extern 依赖）
├── TokenizeHandler.cpp/.hpp ← ✅ 纯函数：tokenize handler + BPE budget
│   （所有依赖通过参数传递）
├── MnnHelper.cpp/.hpp      ← ✅ 纯函数化：无 extern globals
│   （loadSdxlClipMnnIfNeeded/releaseSdxlClipMnn 接受 AppContext&）
├── QnnHelper.cpp/.hpp      ← ✅ 纯函数化：无 extern globals
│   （loadSdxlQnn*/releaseSdxlQnn* 接受 AppContext&）
├── ServerCli.cpp/.hpp      ← ✅ CLI parsing (processCommandLine 接受 AppContext&)
├── TextEncoder.cpp/.hpp    ← ✅ 纯函数：prompt→CLIP embeddings (接受 AppContext&)
│   （processWeightedPrompt / processPromptPair）
├── SDUtils.hpp             ← ✅ TokenEmbTable 从 main.cpp 迁入
├── GenerateHandler.cpp/.hpp ← ✅ 纯函数：generateImage() (接受 const RequestContext& + AppContext&)
│   （1260 行最大单体函数，零宏依赖，所有状态通过参数传递）
├── ModelLoader.cpp/.hpp    ← ✅ 纯函数：initializeModels() (接受 AppContext&)
│   （Tokenizer + embeddings + MNN sessions + QNN init）
├── main.cpp                ← ✅ 914 行：main() + HTTP 路由 + 6 端点
│   ├── GET  /health      — 健康检查 (3 行)
│   ├── GET  /progress    — watchdog 进度查询
│   ├── POST /generate    — SSE chunked 图像生成
│   ├── POST /upscale     — 超分辨率
│   ├── POST /tokenize    — tokenize + BPE
│   └── POST /shutdown    — graceful shutdown
└── Config.hpp              ← output_width/height/sample_width/height 仅保留默认值（被宏覆盖）
```

**拆分进度**：

| 模块 | 状态 | 提取内容 | 纯函数 |
|------|------|----------|--------|
| AppContext | ✅ | 60+ globals → 1 struct | N/A |
| VaeTilingHelper | ✅ | blendVae* ×2, calculateTilePos ×2 | ✅ |
| PromptCacheUtils | ✅ | prompt_cache, utf8*, load/save* ×5 | ✅ |
| TokenizeHandler | ✅ | prefixBytesWithinBudget, handleTokenize | ✅ |
| MnnHelper (lowram) | ✅ | load/release CLIP | ✅ (AppContext&) |
| QnnHelper (lowram) | ✅ | load/release UNET/VAE ×6 | ✅ (AppContext&) |
| ServerCli | ✅ | showHelp, processCommandLine | ✅ (AppContext&) |
| TextEncoder | ✅ | processWeightedPrompt, processPromptPair | ✅ (AppContext&) |
| TokenEmbTable→SDUtils | ✅ | Mmap-based FP16 lookup table class | N/A |
| RequestContext wiring | ✅ | 20 per-request globals→1 struct+macros | N/A |
| GenerateHandler | ✅ | generateImage() 1260行→独立模块 | ✅ (RequestContext&+AppContext&) |
| ModelLoader | ✅ | Tokenizer + embeddings + MNN sessions + QNN init | ✅ (AppContext&) |
| main.cpp | ✅ | 914行: main() + 6 端点 (health/progress/generate/upscale/tokenize/shutdown) | N/A |

**CMakeLists.txt 变更**：✅ 已完成 — glob `src/*.cpp` 自动纳入新增文件。

---

## §3 风险矩阵（更新后）

| 风险 | 状态 | 触发条件 | 后果 | 严重度 |
|------|------|----------|------|--------|
| 并发 generate 数据竞态 | ✅ 已修复 | Worker 重试 + 旧连接未断 | 503 拒绝 + Retry-After | P0 |
| Lowram use-after-free | ✅ 已修复 | 并发 lowram 请求 | 受 lowramMutex() 保护 | P0 |
| 生成管线 hang 假死 | ✅ 已修复 | GPU/QNN 卡死 | 超时 watchdog 自动释放 | P0 |
| Upscale 并发资源竞争 | ✅ 已修复 | 并发 upscale 请求 | 503 拒绝 | P1 |
| Vector capacity 累积 | ✅ 已修复 | 连续大量请求 | shrink_to_fit() 释放 | P1 |
| 文件单体 ~3972 行 | 🔜 计划中 | 任何修改 | 编译慢/难以测试 | P2 |

---

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-17 | 初始审计：并发安全 P0 ×2, 内存 P1 ×1, 重构 P2 ×1 |
| 2026-06-17 | P0 修复：添加 `std::atomic<bool> g_generation_in_progress` + `std::mutex g_generate_mutex` 防止并发 /generate |
| 2026-06-17 | P0 修复：/generate handler 检测 g_generation_in_progress → 返回 HTTP 409 Conflict (含 retry_after_ms) |
| 2026-06-17 | P0 修复：所有低内存 load/release 函数增加 `std::lock_guard<std::mutex>(g_lowram_mutex)` |
| 2026-06-17 | P1 修复：img_data/mask_data/mask_data_full 在每次请求前调用 `shrink_to_fit()` 释放 capacity |
| 2026-06-17 | 新增 GET /progress 端点：返回 busy/current_step/total_steps 供 Android 端查询 |
| 2026-06-17 | Kotlin 侧：BackendManager 处理 HTTP 409 → AppError.BackendBusy (含 retryAfterMs) |
| 2026-06-17 | Kotlin 侧：BackendManager + BackendService 新增 queryProgress() |
| 2026-06-17 | Kotlin 侧：GenerationWorker 处理 BackendBusy → delay 后重试，不移除任务 |
| 2026-06-17 | Kotlin 侧：QueueController 新增 hasActiveWorker() 供 UI 查询 |
| 2026-06-17 | **🔧 API 重组 (Stability AI/Ollama 对齐)** |
| 2026-06-17 | ← 409 Conflict → **503 Service Unavailable** + `Retry-After: 3` HTTP 标准头 |
| 2026-06-17 | ← 散落 atomic/mutex 全局变量 → **ServerState 类**（`server_state.hpp`），无锁 compare_exchange 状态机 |
| 2026-06-17 | ← `{"error": {"type": "...", "message": "..."}}` → **Stability AI 格式** `{"id":"..","name":"..","errors":[".."]}` |
| 2026-06-17 | ← `g_lowram_mutex` 全局变量 → `lowramMutex()` 函数作用域静态变量 |
| 2026-06-17 | **新增：Generation Watchdog** — 默认 300s 超时自动 release，防止死锁/假死 |
| 2026-06-17 | **新增：/upscale 并发保护** — 同样使用 acquireBusy()/release()，返回 503 |
| 2026-06-17 | **新增：POST /shutdown** — 优雅关闭，发送 200 后异步 stop |
| 2026-06-17 | Kotlin 侧：BackendManager 改为检测 HTTP 503 + 解析 `Retry-After` 头 |
| 2026-06-17 | 📋 **文件拆分计划** — 记录于 §2.8：context.hpp → generate.cpp → utils.cpp → lowram.cpp → upscale.cpp → tokenize.cpp → server_cli.cpp → server_main.cpp |
| 2026-06-17 | 🔧 **P2 拆分：VAE Tiling Helper** — 新建 `VaeTilingHelper.cpp/.hpp`，提取 `blendVaeEncoderTiles()`、`blendVaeOutputTiles()`、`calculateVaeTilePositions()`、`calculateTilePositions()` 四个函数；main.cpp 更新 include 并重命名 call sites 为 camelCase；CMakeLists.txt 无需变更（已 glob `src/*.cpp`）；Kotlin 侧无需变更（HTTP API 不受内部重构影响） |
| 2026-06-17 | 🔧 **P2 拆分：PromptCacheUtils** — 新建 `PromptCacheUtils.cpp/.hpp`，提取 `prompt_cache` 命名空间（kMagic/kVersion/kModeSd15/kModeSdxl/kSeqLen/Header）、`utf8ByteOffsetToUtf16()`、`promptHasEmbedding()`、`promptCachePath()`、`loadPromptCache()`、`savePromptCache()` 共 6 函数+命名空间；main.cpp 更新 include；extern 声明 `promptProcessor` 全局变量 |
| 2026-06-17 | 🔧 **P2 拆分：TokenizeHandler** — 新建 `TokenizeHandler.cpp/.hpp`，提取 `prefixBytesWithinBudget()` 和 `handleTokenize()`；main.cpp 中 `/tokenize` lambda 缩减为 1 行委托调用（`handleTokenize(req, res, sdxl_mode, text_embedding_size_2, promptProcessor, tokenizer.get())`）；`handleTokenize` 通过参数接收所有依赖 |
| 2026-06-17 | 🐛 **Bugfix: 移除 main.cpp 残留命名空间关闭括号** — `}  // namespace sample_app / tools / qnn` 三个关闭括号为 MNN/QNN 拆分遗留，QnnHelper.hpp 已在内部分别开启/关闭这些命名空间；残留会导致编译错误（此前 CI 未触发 native 编译因此未暴露） |
| 2026-06-17 | 📋 **更新拆分计划** — 标记 PromptCacheUtils、TokenizeHandler 为 ✅ 已完成；server_cli/server_main 标注为 🔜 延期（30+ 全局变量依赖，待 context.hpp 推进后重访）；累计 ~505 行从 main.cpp 迁出 |
| 2026-06-17 | 🏗️ **P3: 集中状态管理 AppContext** — 创建 `AppContext.hpp`，将 60+ 个裸全局变量统一归入 `AppContext::Config`（不可变配置）、`AppContext::Models`（可变模型对象）、`AppContext::serverState`（并发控制）三个子结构；main.cpp 使用宏别名（`#define sdxl_mode (gconf.sdxl_mode)` 等）向后兼容，避免大面积 diff；`main()` 声明 `AppContext appCtx;` 统一持有所有权 |
| 2026-06-17 | 🏗️ **P3: MnnHelper/QnnHelper 纯函数化** — 移除 MnnHelper.hpp/QnnHelper.hpp 中所有 `extern` 全局变量声明；`loadSdxlClipMnnIfNeeded`/`releaseSdxlClipMnn`/`loadSdxlQnn*`/`releaseSdxlQnn*` 全部改为接受 `AppContext&` 参数；MnnHelper.cpp/QnnHelper.cpp 通过 `ctx.models.xxx`/`ctx.conf.xxx` 访问模型和配置 |
| 2026-06-17 | 🏗️ **P3: PromptCacheUtils 纯函数化** — 移除 `extern PromptProcessor promptProcessor;`；`promptHasEmbedding()` 改为接受 `PromptProcessor&` 参数；调用处更新为 `promptHasEmbedding(promptProcessor, text)` |
| 2026-06-17 | 🏗️ **P3: TokenizeHandler 已纯函数化** — 确认 `handleTokenize()` 和 `prefixBytesWithinBudget()` 通过参数接收所有依赖（sdxl_mode, text_embedding_size_2, promptProcessor, tokenizer），不依赖任何 extern 全局变量 |
| 2026-06-17 | 📋 **P3 架构总结** — AppContext 提供统一状态管理：`main()` 持有所有权 → `processCommandLine()` 填充配置 → HTTP handlers 通过宏/引用访问 → 提取的模块函数通过 `AppContext&` 参数接收模型/配置 → `RequestContext`（待集成）隔离每次请求的不可变参数 |
| 2026-06-17 | 🔧 **P3 拆分：ServerCli** — 新建 `ServerCli.cpp/.hpp`，提取 `showHelp()`、`showHelpAndExit()`、`processCommandLine()`；`processCommandLine()` 改为接受 `AppContext&` 参数，直接写入 `ctx.conf.*`/`ctx.models.*`；main.cpp 减少 **413 行**（CLI 段完全移除）；main() 调用改为 `processCommandLine(argc, argv, appCtx)` |
| 2026-06-17 | 📊 **拆分统计** — main.cpp: 2947→2526 行（-421 行，-14%）；新增文件 10 个（AppContext, RequestContext, ServerCli, VaeTilingHelper, PromptCacheUtils, TokenizeHandler, MnnHelper, QnnHelper, ServerState, + .hpp 对应）；剩余 main.cpp 2526 行主要包含 generateImage() (~1250行) + HTTP server (~400行) + 模型初始化 (~130行) + 工具类（TokenEmbTable）+ per-request globals |
| 2026-06-17 | 🔧 **P3 拆分：TokenEmbTable → SDUtils.hpp** — 将 38 行 TokenEmbTable 类（mmap-based FP16 lookup table）从 main.cpp 迁入 SDUtils.hpp；移除 main.cpp 中 POSIX includes（fcntl.h, sys/mman.h, sys/stat.h, unistd.h — 不再需要） |
| 2026-06-17 | 🔧 **P3 拆分：TextEncoder** — 新建 `TextEncoder.cpp/.hpp`，提取 `processWeightedPrompt()`（145 行）和 `processPromptPair()`（20 行）；两个函数接受 `const AppContext&`，通过 `ctx.conf.sdxl_mode`、`ctx.models.tokenizer`、`ctx.models.token_emb` 等访问模型和配置；返回 struct ProcessedPrompt / ProcessedPromptPair；main.cpp 调用改为 `processPromptPair(gctx, prompt, negative_prompt, 77)` |
| 2026-06-17 | 🏗️ **P3: RequestContext 集成** — 将 20 个 per-request 全局变量（prompt, negative_prompt, steps, cfg, seed, scheduler_type, img_data, mask_data, mask_data_full, denoise_strength, request_img2img, request_has_mask, use_opencl, aspect_pad_inpaint, target_crop_width/height, aspect_pad_synthetic_base, user_supplied_mask, show_diffusion_process/stride）替换为 `RequestContext g_req` + 宏别名（`#define prompt (g_req.prompt)` 等）；`generateImage()` 签名新增 `const RequestContext &req` 参数；`/generate` handler 不再操作裸全局变量，统一通过 `g_req` 填充；output_width/height/sample_width/height 保留在 Config.hpp（VAE tiling 需要可变性，后续处理） |
| 2026-06-17 | 📊 **拆分统计** — main.cpp: 2526→2305 行（-221 行，-8.7%）；累计迁出 ~1139 行；新增文件 13 个（+TextEncoder×2, +SDUtils 内嵌 TokenEmbTable）；per-request globals 从 20 个裸变量缩减为 1 个 RequestContext |
| 2026-06-17 | 🏗️ **P4: GenerateHandler 提取** — 新建 `GenerateHandler.cpp/.hpp`，将 1260 行 `generateImage()` 从 main.cpp 完全迁出；函数签名 `generateImage(const RequestContext&, AppContext&, callback)`；所有宏别名（prompt, steps, cfg, sdxl_mode, clipInterpreter 等）替换为显式 `req.xxx` / `conf.xxx` / `models.xxx` 访问；output_width/height/sample_width/height 改为本地可变拷贝 `cur_out_w/h` / `cur_samp_w/h`；函数内部零宏依赖，所有状态通过参数传递 |
| 2026-06-17 | 🏗️ **P4: ModelLoader 提取** — 新建 `ModelLoader.cpp/.hpp`，提取 main() 中 Tokenizer 加载、Embeddings 加载、MNN 会话创建、QNN 模型初始化共 ~130 行 → `initializeModels(AppContext&)`；返回 0=成功，非零=失败 |
| 2026-06-17 | 🏗️ **P4: output dim macros 迁移** — 新增 `#define output_width (g_req.output_width)` 等 4 个宏，将 Config.hpp 的 `inline int output_width` 等变量覆盖为 g_req 字段；/generate handler 的 `output_width = req_width` 等赋值现在直接写入 `g_req` |
| 2026-06-17 | 📊 **拆分统计** — main.cpp: 2305→914 行（-1391 行，-60.4%）；累计迁出 ~2530 行；新增文件 16 个（+GenerateHandler×2, +ModelLoader×2）；main.cpp 现在仅含 main()（25 行）+ HTTP Server（~400 行）+ /generate /upscale /tokenize handler（~500 行） |
| 2026-06-17 | 📊 **P4 成果** — 39.8:1 初始文件拆分为 17 个模块（1 个 AppContext + 2 个 struct + 14 个功能模块），main.cpp 从 3889 行缩减为 914 行（-76.5%），所有功能模块均为纯函数（接受 AppContext& 参数，零 extern 依赖） |

