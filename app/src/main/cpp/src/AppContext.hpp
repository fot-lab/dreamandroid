#ifndef APP_CONTEXT_HPP
#define APP_CONTEXT_HPP
// ── BKND-PROC-0008 P2: Centralised Application State
//
//  Replaces the 60+ bare global variables in main.cpp with a single
//  well-typed struct.  Ownership lives in main(); all other code receives
//  a const reference (for immutable config) or a mutable reference (for
//  model lifecycle).  Per-request mutable state is isolated in RequestContext.
//
//  Design principles:
//   1. No extern globals — all state flows through AppContext& parameters.
//   2. Immutable-after-init config is const AppContext& to callees.
//   3. Model pointers are mutable (load/release) but protected by lowramMutex().
//   4. RequestContext carries per-request data; AppContext carries server-wide state.
//
//  Structure:
//   AppContext
//   ├── Config (immutable after CLI parsing)
//   │   ├── type/model paths
//   │   ├── flags (use_mnn, sdxl_mode, ...)
//   │   └── server settings (port, listen_address)
//   ├── Models (mutable, loaded once or on-demand)
//   │   ├── MNN: clip/clip2/safety interpreters + sessions
//   │   ├── QNN: unet/vae apps + function pointers
//   │   ├── CLIP caches: pos_emb, token_emb tables
//   │   └── Patched model buffer (QNN)
//   ├── Tokenizer & PromptProcessor
//   └── ServerState (concurrency control)
//

#include "Config.hpp"
#include "MnnHelper.hpp"   // MmapFile, PatchedModelBuffer
#include "QnnModel.hpp"
#include "PromptProcessor.hpp"
#include "QnnHelper.hpp"   // QnnFunctionPointers forward
#include "ServerState.hpp"
#include "SDUtils.hpp"     // TokenEmbTable

#include <memory>
#include <string>
#include <vector>

// Forward declarations
namespace tokenizers { class Tokenizer; }

struct AppContext {

  // ══════════════════════════════════════════════════════════════════
  // §1  Server Configuration (immutable after CLI parsing)
  // ══════════════════════════════════════════════════════════════════

  struct Config {
    // ── Pipeline type ──
    bool use_mnn    = false;   // --type sd15cpu: whole pipeline on MNN
    bool sdxl_mode  = false;   // --type sdxl
    bool use_mnn_clip = false; // CLIP on MNN (always true in practice, default)
    bool use_v_pred = false;   // --use_v_pred
    bool use_safety_checker = false;
    bool upscaler_mode = false;
    bool lowram_mode = false;
    bool no_img2img  = false;  // skip the VAE encoder entirely
    bool cvt_model   = false;  // --convert (safetensors→MNN)

    // ── Model paths ──
    std::string clipPath, clip2Path, unetPath,
                vaeDecoderPath, vaeEncoderPath,
                safetyCheckerPath, tokenizerPath,
                patchPath, modelDir;

    // ── Server ──
    int port = 8081;
    std::string listen_address = "127.0.0.1";
    float nsfw_threshold = 0.5f;

    // ── QNN backend ──
    std::string backendPathCmd;  // path to libQnnHtp.so
  } conf;

  // ══════════════════════════════════════════════════════════════════
  // §2  Model Objects (mutable — loaded once or on-demand)
  // ══════════════════════════════════════════════════════════════════

  struct Models {
    // ── MNN interpreters (CLIP, CLIP-2, Safety) ──
    MNN::Interpreter *clipInterpreter  = nullptr;
    MNN::Interpreter *clip2Interpreter = nullptr;
    MNN::Interpreter *safetyCheckerInterpreter = nullptr;

    // ── MNN sessions ──
    MNN::Session *clipSession  = nullptr;
    MNN::Session *clip2Session = nullptr;
    MNN::Session *safetyCheckerSession = nullptr;

    // ── QNN model apps ──
    std::unique_ptr<QnnModel> unetApp;
    std::unique_ptr<QnnModel> vaeDecoderApp;
    std::unique_ptr<QnnModel> vaeEncoderApp;

    // ── QNN runtime ──
    QnnFunctionPointers qnnSystemFuncs{};
    std::unique_ptr<PatchedModelBuffer> unetPatchedBuffer;

    // ── CLIP embedding tables ──
    std::vector<float> pos_emb;
    TokenEmbTable        token_emb;
    std::vector<float> pos_emb_2;
    TokenEmbTable        token_emb_2;

    // ── Tokenizer ──
    std::shared_ptr<tokenizers::Tokenizer> tokenizer;
    PromptProcessor promptProcessor;

    bool clip_skip_2 = false;  // --clip_skip_2 (convert mode only)
  } models;

  // ══════════════════════════════════════════════════════════════════
  // §3  Server State (concurrency, progress, watchdog)
  // ══════════════════════════════════════════════════════════════════

  ServerState serverState;
};

#endif  // APP_CONTEXT_HPP
