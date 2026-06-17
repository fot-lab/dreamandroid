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

### 1.4 P2: 文件单体膨胀 (3889 行)

当前文件包含的逻辑模块：

| 逻辑模块 | 大致行数范围 | 代码行数 |
|----------|-------------|----------|
| Globals & Constants | 154-260 | ~100 |
| CLI Parsing | 630-1045 (+ namespace) | ~400 |
| Model Loading (main init) | 3161-3290 | ~130 |
| Lowram Helpers | 1062-1153 | ~90 |
| `generateImage()` | 1890-3158 | ~1250 |
| HTTP Server Setup | 3291-3890 | ~600 |
| `/health` handler | 3301-3303 | 3 |
| `/generate` handler | 3304-3643 | ~340 |
| `/upscale` handler | 3646-3800+ | ~150 |
| `/tokenize` handler | 3800-3867 | ~70 |
| Utilities (UTF-8, base64, SHA, etc.) | 270-620 | ~350 |

1250 行的 `generateImage()` 是最大单体函数，包含了 CLIP 编码、UNET 去噪、VAE 解码、安全检测等多阶段管线。

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

### 2.8 🔜 P2: 文件拆分计划（BKND-PROC-0008 阶段 2）

```
app/src/main/cpp/src/
├── server_main.cpp         ← 🔜 main() + HTTP 路由注册 (~400行)
│   【延期】依赖 30+ 全局变量，需待 context.hpp 就绪后推进
├── ServerState.hpp         ← ✅ 已创建：状态机 + 超时检测
├── VaeTilingHelper.cpp/.hpp ← ✅ 已创建：VAE encoder/decoder tiling blender + tile pos (~270行)
│   【已提取】blendVaeEncoderTiles / blendVaeOutputTiles / calculateVaeTilePositions / calculateTilePositions
├── PromptCacheUtils.cpp/.hpp ← ✅ 已创建：prompt_cache 命名空间 + 缓存 I/O + UTF-8 转换 (~170行)
│   【已提取】prompt_cache::Header/kMagic/kVersion/kModeSd15/kModeSdxl/kSeqLen,
│             utf8ByteOffsetToUtf16, promptHasEmbedding, promptCachePath, loadPromptCache, savePromptCache
├── TokenizeHandler.cpp/.hpp ← ✅ 已创建：tokenize HTTP handler + BPE budget search (~145行)
│   【已提取】prefixBytesWithinBudget, handleTokenize；main.cpp lambda ↓ 为 1 行委托
├── context.hpp             ← 🔜 【新建】RequestContext 结构体 (~50行)
├── generate.cpp/.hpp       ← 🔜 【新建】generateImage() (~1250行)
│   【核心】从 main.cpp 提取，改为接受 const RequestContext& 参数
├── lowram.cpp/.hpp         ← ✅ 已完成：load/release helpers 已迁入 QnnHelper.cpp / MnnHelper.cpp
├── upscale.cpp/.hpp        ← ✅ 已完成：upscaleImageWithModel / upscaleImageWithMNN 已迁入 QnnHelper.cpp / MnnHelper.cpp
├── server_cli.cpp/.hpp     ← 🔜 【延期】processCommandLine() (~400行) 依赖 30+ 全局变量
│   showHelp / showHelpAndExit 已可独立提取；processCommandLine 需 context.hpp 后重访
└── utils.cpp/.hpp          ← ✅ 已完成 → PromptCacheUtils + TokenizeHandler (见上)
```

**拆分进度**：

| 模块 | 状态 | 提取函数 | main.cpp 减少 |
|------|------|----------|---------------|
| VaeTilingHelper | ✅ | blendVae* ×2, calculateTilePos ×2 | ~270 行 |
| PromptCacheUtils | ✅ | prompt_cache, utf8*, promptHas*, load/save* ×4 | ~130 行 |
| TokenizeHandler | ✅ | prefixBytesWithinBudget, handleTokenize | ~105 行 |
| server_cli / server_main | 🔜 延期 | — | 30+ globals 依赖 |

**总计已减少**：~505 行从 main.cpp 迁出。

**CMakeLists.txt 变更**：✅ 已完成 — 已将 `file(GLOB SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/src/main.cpp")` 改为 glob 所有 `src/*.cpp`，新增 `.cpp` 文件自动纳入编译。

**🔧 Bugfix**：移除 main.cpp 中残留的 `namespace qnn::tools::sample_app` 关闭括号（QnnHelper.hpp 已在内部分别开启/关闭这些命名空间，残留会导致编译错误）。

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
