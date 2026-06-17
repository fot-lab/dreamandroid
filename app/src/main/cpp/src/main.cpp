#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iostream>
#include <memory>
#include <mutex>
#include <numeric>
#include <random>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include "AppContext.hpp"
#include "Config.hpp"
#include "DPMSolverMultistepScheduler.hpp"
#include "ServerCli.hpp"
#include "EulerAncestralDiscreteScheduler.hpp"
#include "EulerDiscreteScheduler.hpp"
#include "FloatConversion.hpp"
#include "LCMScheduler.hpp"
#include "LaplacianBlend.hpp"
#include "MnnHelper.hpp"
#include "PromptProcessor.hpp"
#include "QnnHelper.hpp"
#include "QnnModel.hpp"
#include "SDUtils.hpp"
#include "SafeTensor2MNN.hpp"
#include "Scheduler.hpp"
#include "ServerState.hpp"
#include "VaeTilingHelper.hpp"
#include "PromptCacheUtils.hpp"
#include "TokenizeHandler.hpp"
#include "Sha256.hpp"

// QNN Headers
#include "BuildId.hpp"
#include "DynamicLoadUtil.hpp"
#include "Logger.hpp"
#include "PAL/DynamicLoading.hpp"
#include "PAL/GetOpt.hpp"
#include "QnnSampleAppUtils.hpp"

// External Libraries
#include "httplib.h"
#include "json.hpp"
#include "tokenizers_cpp.h"

// MNN
#include <MNN/MNNDefine.h>

#include <MNN/Interpreter.hpp>

// Xtensor
#include <xtensor/xadapt.hpp>
#include <xtensor/xarray.hpp>
#include <xtensor/xbuilder.hpp>
#include <xtensor/xeval.hpp>
#include <xtensor/xindex_view.hpp>
#include <xtensor/xio.hpp>
#include <xtensor/xmanipulation.hpp>
#include <xtensor/xmath.hpp>
#include <xtensor/xoperation.hpp>
#include <xtensor/xrandom.hpp>
#include <xtensor/xview.hpp>

// FP16 token-embedding lookup table. Either owns a converted vector (when the
// on-disk data was FP32 and had to be narrowed) or maps the on-disk FP16 file
// read-only. Lookups are sparse (only the prompt's token rows), so the mmap
// path keeps the large table out of resident anonymous memory: untouched rows
// never fault in, and the pages that do are clean and reclaimable.
class TokenEmbTable {
 public:
  TokenEmbTable() = default;
  ~TokenEmbTable() { reset(); }
  TokenEmbTable(const TokenEmbTable &) = delete;
  TokenEmbTable &operator=(const TokenEmbTable &) = delete;

  bool empty() const { return data_ == nullptr; }
  uint16_t operator[](size_t i) const { return data_[i]; }

  void setOwned(std::vector<uint16_t> &&v) {
    reset();
    owned_ = std::move(v);
    data_ = owned_.data();
  }
  void setMapped(void *base, size_t bytes) {
    reset();
    map_ = base;
    mapBytes_ = bytes;
    data_ = static_cast<const uint16_t *>(base);
  }

 private:
  void reset() {
    if (map_ != nullptr) {
      munmap(map_, mapBytes_);
      map_ = nullptr;
      mapBytes_ = 0;
    }
    owned_ = std::vector<uint16_t>();
    data_ = nullptr;
  }
  const uint16_t *data_ = nullptr;
  std::vector<uint16_t> owned_;
  void *map_ = nullptr;
  size_t mapBytes_ = 0;
};

// MmapFile moved to QnnHelper.hpp — its only consumer (applyZstdPatchToBuffer)
// now lives in QnnHelper.cpp.

// ── BKND-PROC-0008 P2: Centralised Application State
//
// All mutable application state lives in AppContext, owned by main().
// Per-request mutable state is isolated in RequestContext (passed as const&
// to generateImage).  Model objects (MNN/QNN/CLIP) are legitimate shared
// resources loaded once at startup.
//
// Macro aliases provide backward-compatible access during migration;
// the long-term goal is explicit AppContext& parameters on all functions.

#define gctx  (appCtx)
#define gconf (appCtx.conf)
#define gmod  (appCtx.models)
#define gsvr  (appCtx.serverState)

// Shorthand macros for frequently-accessed fields.
// All expand to appCtx.conf.xxx or appCtx.models.xxx.
#define sdxl_mode           (gconf.sdxl_mode)
#define use_mnn             (gconf.use_mnn)
#define upscaler_mode       (gconf.upscaler_mode)
#define lowram_mode         (gconf.lowram_mode)
#define no_img2img          (gconf.no_img2img)
#define use_v_pred          (gconf.use_v_pred)
#define use_safety_checker  (gconf.use_safety_checker)
#define cvt_model           (gconf.cvt_model)
#define nsfw_threshold      (gconf.nsfw_threshold)
#define port                (gconf.port)
#define listen_address      (gconf.listen_address)
#define clip_skip_2         (gmod.clip_skip_2)
#define clipPath            (gconf.clipPath)
#define clip2Path           (gconf.clip2Path)
#define unetPath            (gconf.unetPath)
#define vaeDecoderPath      (gconf.vaeDecoderPath)
#define vaeEncoderPath      (gconf.vaeEncoderPath)
#define safetyCheckerPath   (gconf.safetyCheckerPath)
#define tokenizerPath       (gconf.tokenizerPath)
#define patchPath           (gconf.patchPath)
#define modelDir            (gconf.modelDir)
#define g_backendPathCmd    (gconf.backendPathCmd)
#define g_qnnSystemFuncs    (gmod.qnnSystemFuncs)
#define g_unetPatchedBuffer (gmod.unetPatchedBuffer)
#define unetApp             (gmod.unetApp)
#define vaeDecoderApp       (gmod.vaeDecoderApp)
#define vaeEncoderApp       (gmod.vaeEncoderApp)
#define clipInterpreter     (gmod.clipInterpreter)
#define clip2Interpreter    (gmod.clip2Interpreter)
#define safetyCheckerInterpreter (gmod.safetyCheckerInterpreter)
#define clipSession         (gmod.clipSession)
#define clip2Session        (gmod.clip2Session)
#define safetyCheckerSession (gmod.safetyCheckerSession)
#define pos_emb             (gmod.pos_emb)
#define token_emb           (gmod.token_emb)
#define pos_emb_2           (gmod.pos_emb_2)
#define token_emb_2         (gmod.token_emb_2)
#define tokenizer           (gmod.tokenizer)
#define promptProcessor     (gmod.promptProcessor)
#define g_serverState       (gsvr)

// ── Per-request globals (to be migrated to RequestContext in next step)
//     These are still global for now to minimize diff; the /generate handler
//     fills them, and generateImage() reads them.
std::string prompt;
std::string negative_prompt;
int steps;
float cfg;
unsigned seed;
std::string scheduler_type;
std::vector<float> img_data;
std::vector<float> mask_data;
std::vector<float> mask_data_full;
float denoise_strength;
bool request_img2img;
bool request_has_mask;
bool use_opencl;
bool aspect_pad_inpaint = false;
int target_crop_width = 0;
int target_crop_height = 0;
bool aspect_pad_synthetic_base = false;
bool user_supplied_mask = false;
bool show_diffusion_process = false;
int show_diffusion_stride = 1;

// utf8ByteOffsetToUtf16 → PromptCacheUtils.cpp (BKND-PROC-0008 P2 split)

// prefixBytesWithinBudget → TokenizeHandler.cpp (BKND-PROC-0008 P2 split)

// ensureCacheDir         → MnnHelper.cpp  (BKND-PROC-0008 P2 split)
// createQnnModel          → QnnHelper.cpp  (BKND-PROC-0008 P2 split)
// createMnnInterpreterMmap → MnnHelper.cpp (BKND-PROC-0008 P2 split)
// readFileForPatch / applyZstdPatchToBuffer → QnnHelper.cpp
// initializeQnnApp        → QnnHelper.hpp  (BKND-PROC-0008 P2 split)

// promptHasEmbedding / promptCachePath / loadPromptCache / savePromptCache
//   → PromptCacheUtils.cpp  (BKND-PROC-0008 P2 split)

// createQnnModel → QnnHelper.cpp, createMnnInterpreterMmap → MnnHelper.cpp
// qnn::tools::sample_app::readFileForPatch / applyZstdPatchToBuffer → QnnHelper.cpp
// initializeQnnApp template → QnnHelper.hpp  (BKND-PROC-0008 P2 split)

// showHelp / showHelpAndExit / processCommandLine → ServerCli.cpp
// (BKND-PROC-0008 P3 split)

// ── BKND-PROC-0008 P2: qnn::tools::sample_app namespace closes
//     were removed — QnnHelper.hpp already opens/closes them.
// Generic RAII guard: invokes the stored callable on scope exit unless
// disarmed. Used in generateImage to ensure lowram-loaded SDXL models are
// released even if the pipeline throws partway.
struct ScopeExit {
  std::function<void()> fn;
  ~ScopeExit() {
    if (fn) fn();
  }
};

// --- SDXL low-RAM lazy load/release helpers ---
// BKND-PROC-0008 P1: All load/release functions are serialised via
// lowramMutex() to prevent use-after-free / double-delete races when
// concurrent requests toggle lowram models.
//
// SDXL lowram load/release helpers moved:
//   - MNN CLIP:  loadSdxlClipMnnIfNeeded / releaseSdxlClipMnn → MnnHelper.cpp
//   - QNN UNET:  loadSdxlQnnUnetIfNeeded / releaseSdxlQnnUnet → QnnHelper.cpp
//   - QNN VAE:   loadSdxlQnnVaeDecoderIfNeeded etc.              → QnnHelper.cpp
//   (BKND-PROC-0008 P2 split)

// --- Text Processing ---
struct ProcessedPrompt {
  std::vector<int> ids;                      // CLIP (pad 49407)
  std::vector<int> ids_2;                    // SDXL encoder 2 (pad 0)
  std::vector<float> weighted_embeddings;    // 77*768
  std::vector<float> weighted_embeddings_2;  // SDXL: 77*1280
};

ProcessedPrompt processWeightedPrompt(const std::string &prompt_text,
                                      int max_len = 77) {
  ProcessedPrompt result;

  auto tokens = promptProcessor.process(prompt_text);

  const int dim1 = 768;
  const int dim2 = text_embedding_size_2;

  std::vector<float> embeddings(max_len * dim1, 0.0f);
  std::vector<float> embeddings_2;
  if (sdxl_mode) embeddings_2.assign(max_len * dim2, 0.0f);
  std::vector<int> ids;
  std::vector<float> weights;

  int current_pos = 1;
  ids.push_back(49406);  // BOS token

  for (const auto &token : tokens) {
    if (current_pos >= max_len - 1) break;

    if (token.is_embedding) {
      int emb_tokens = 0;
      if (!token.embedding_data.empty())
        emb_tokens = token.embedding_data.size() / dim1;
      else if (sdxl_mode && !token.embedding_data_2.empty())
        emb_tokens = token.embedding_data_2.size() / dim2;

      for (int i = 0; i < emb_tokens && current_pos < max_len - 1; i++) {
        ids.push_back(49407);
        if (!token.embedding_data.empty()) {
          for (int j = 0; j < dim1; j++) {
            embeddings[current_pos * dim1 + j] =
                token.embedding_data[i * dim1 + j] * token.weight;
          }
        }
        if (sdxl_mode && !token.embedding_data_2.empty()) {
          for (int j = 0; j < dim2; j++) {
            embeddings_2[current_pos * dim2 + j] =
                token.embedding_data_2[i * dim2 + j] * token.weight;
          }
        }
        weights.push_back(token.weight);
        current_pos++;
      }
    } else {
      // tokenize
      std::vector<int> token_ids = tokenizer->Encode(token.text);

      for (int tid : token_ids) {
        if (current_pos >= max_len - 1) break;
        ids.push_back(tid);

        if (current_pos < max_len) {
          weights.push_back(token.weight);
        }
        current_pos++;
      }
    }
  }

  while (ids.size() < max_len) {
    ids.push_back(49407);  // PAD/EOS token
    weights.push_back(1.0f);
  }

  if (ids.size() > max_len) {
    ids.resize(max_len);
  }

  result.ids = ids;

  // SDXL encoder 2 uses pad id 0 instead of 49407 after the first EOS.
  if (sdxl_mode) {
    std::vector<int> ids2 = ids;
    int eos_pos = -1;
    for (int i = 1; i < max_len; i++) {
      if (ids2[i] == 49407) {
        eos_pos = i;
        break;
      }
    }
    if (eos_pos >= 0) {
      for (int i = eos_pos + 1; i < max_len; i++) ids2[i] = 0;
    }
    result.ids_2 = ids2;
  }

  if (!token_emb.empty() && !pos_emb.empty()) {
    for (int i = 0; i < max_len; i++) {
      int token_id = ids[i];
      float weight = (i < (int)weights.size()) ? weights[i] : 1.0f;

      bool has_emb = false;
      for (int j = 0; j < dim1; j++) {
        if (embeddings[i * dim1 + j] != 0.0f) {
          has_emb = true;
          break;
        }
      }

      if (!has_emb) {
        for (int j = 0; j < dim1; j++) {
          float token_val = fp16_to_fp32(token_emb[token_id * dim1 + j]);
          embeddings[i * dim1 + j] = token_val * weight + pos_emb[i * dim1 + j];
        }
      } else {
        for (int j = 0; j < dim1; j++) {
          embeddings[i * dim1 + j] += pos_emb[i * dim1 + j];
        }
      }
    }
  }

  if (sdxl_mode && !token_emb_2.empty() && !pos_emb_2.empty()) {
    const std::vector<int> &ids2 = result.ids_2;
    for (int i = 0; i < max_len; i++) {
      int token_id = ids2[i];
      float weight = (i < (int)weights.size()) ? weights[i] : 1.0f;

      bool has_emb = false;
      for (int j = 0; j < dim2; j++) {
        if (embeddings_2[i * dim2 + j] != 0.0f) {
          has_emb = true;
          break;
        }
      }

      if (!has_emb) {
        for (int j = 0; j < dim2; j++) {
          float token_val = fp16_to_fp32(token_emb_2[token_id * dim2 + j]);
          embeddings_2[i * dim2 + j] =
              token_val * weight + pos_emb_2[i * dim2 + j];
        }
      } else {
        for (int j = 0; j < dim2; j++) {
          embeddings_2[i * dim2 + j] += pos_emb_2[i * dim2 + j];
        }
      }
    }
  }

  result.weighted_embeddings = embeddings;
  result.weighted_embeddings_2 = embeddings_2;
  return result;
}

struct ProcessedPromptPair {
  std::vector<int> ids;                      // old (2*77)
  std::vector<float> negative_embeddings;    // new embedding (77*768)
  std::vector<float> positive_embeddings;    // new embedding (77*768)
  std::vector<float> negative_embeddings_2;  // SDXL (77*1280)
  std::vector<float> positive_embeddings_2;  // SDXL (77*1280)
};

ProcessedPromptPair processPromptPair(const std::string &positive,
                                      const std::string &negative,
                                      int max_len = 77) {
  ProcessedPromptPair result;

  auto pos_result = processWeightedPrompt(positive, max_len);
  auto neg_result = processWeightedPrompt(negative, max_len);

  result.ids.reserve(2 * max_len);
  result.ids.insert(result.ids.end(), neg_result.ids.begin(),
                    neg_result.ids.end());
  result.ids.insert(result.ids.end(), pos_result.ids.begin(),
                    pos_result.ids.end());

  result.negative_embeddings = neg_result.weighted_embeddings;
  result.positive_embeddings = pos_result.weighted_embeddings;
  result.negative_embeddings_2 = neg_result.weighted_embeddings_2;
  result.positive_embeddings_2 = pos_result.weighted_embeddings_2;

  return result;
}

// ── VAE tiling functions → VaeTilingHelper.cpp (BKND-PROC-0008 P2 split)

// upscaleImageWithMNN → MnnHelper.cpp (BKND-PROC-0008 P2 split)

// --- Image Generation ---
GenerationResult generateImage(
    std::function<void(int step, int total_steps,
                       const std::string &image_data)>
        progress_callback) {
  using namespace qnn::tools::sample_app;
  if (prompt.empty()) throw std::invalid_argument("Global prompt empty");
  if (use_safety_checker && !safetyCheckerInterpreter)
    throw std::runtime_error("SafetyChecker missing");
  bool sdxl_lowram = sdxl_mode && lowram_mode;
  if (!use_mnn) {
    if (!sdxl_mode) {
      if (!clipInterpreter) throw std::runtime_error("MNN CLIP missing");
    } else if (!sdxl_lowram) {
      if (!clipInterpreter || !clip2Interpreter)
        throw std::runtime_error("SDXL MNN CLIP interpreters missing");
    }
    if (!sdxl_lowram) {
      if (!unetApp) throw std::runtime_error("QNN UNET missing");
      if (!vaeDecoderApp) throw std::runtime_error("QNN VAE Dec missing");
      if (request_img2img && !vaeEncoderApp)
        throw std::runtime_error("QNN VAE Enc missing");
    }
  }
  if (request_img2img && img_data.size() != 3 * output_width * output_height)
    throw std::invalid_argument("Invalid global img_data");
  if (request_has_mask &&
      (mask_data.size() != 4 * sample_width * sample_height ||
       mask_data_full.size() != 3 * output_width * output_height))
    throw std::invalid_argument("Invalid global mask_data*");

  // Catch-all guard: in lowram mode, release any model still loaded when this
  // function exits (normal return or exception). The explicit release calls
  // below stay in place to free memory between pipeline stages.
  ScopeExit lowramReleaseGuard;
  if (sdxl_lowram) {
    lowramReleaseGuard.fn = []() {
      if (clipInterpreter || clip2Interpreter) releaseSdxlClipMnn(gctx);
      if (unetApp) releaseSdxlQnnUnet(gctx);
      if (vaeDecoderApp) releaseSdxlQnnVaeDecoder(gctx);
      if (vaeEncoderApp) releaseSdxlQnnVaeEncoder(gctx);
    };
  }

  try {
    auto start_time = std::chrono::high_resolution_clock::now();
    int first_step_time_ms = 0;
    int total_run_steps = steps + (request_img2img ? 1 : 0) + 2;
    int current_step = 0;
    const int batch_size = 2;

    // --- CLIP ---
    // Regular embedding buffer (SD1.5) reused in SDXL as encoder-1 output.
    std::vector<float> text_embedding_float(batch_size * 77 *
                                            text_embedding_size);

    // SDXL-specific buffers.
    const int sdxl_concat_dim =
        text_embedding_size + text_embedding_size_2;  // 2048
    std::vector<float> sdxl_encoder_hidden_states;    // [batch, 77, 2048]
    std::vector<float> sdxl_text_embeds;              // [batch, 1280]
    std::vector<float> sdxl_time_ids;                 // [batch, 6]
    if (sdxl_mode) {
      sdxl_encoder_hidden_states.assign(batch_size * 77 * sdxl_concat_dim,
                                        0.0f);
      sdxl_text_embeds.assign(batch_size * text_embedding_size_2, 0.0f);
      sdxl_time_ids.assign(batch_size * 6, 0.0f);
      for (int b = 0; b < batch_size; b++) {
        sdxl_time_ids[b * 6 + 0] = (float)output_height;  // original_size h
        sdxl_time_ids[b * 6 + 1] = (float)output_width;   // original_size w
        sdxl_time_ids[b * 6 + 2] = 0.0f;                  // crop_top
        sdxl_time_ids[b * 6 + 3] = 0.0f;                  // crop_left
        sdxl_time_ids[b * 6 + 4] = (float)output_height;  // target_size h
        sdxl_time_ids[b * 6 + 5] = (float)output_width;   // target_size w
      }
    }

    auto clip_start = std::chrono::high_resolution_clock::now();

    // Persistent per-prompt CLIP cache. Positive and negative are looked up
    // independently — a one-sided hit still saves half the CLIP work. A side
    // whose prompt resolves any TI embedding token is excluded from disk
    // caching: the CLIP output then depends on currently-loaded embedding
    // data we don't want frozen into a stable file.
    std::string prompt_cache_dir = ensureCacheDir(modelDir);
    bool neg_has_emb = promptHasEmbedding(promptProcessor, negative_prompt);
    bool pos_has_emb = promptHasEmbedding(promptProcessor, prompt);
    bool neg_cache_eligible = !prompt_cache_dir.empty() && !neg_has_emb;
    bool pos_cache_eligible = !prompt_cache_dir.empty() && !pos_has_emb;

    const uint32_t cache_mode =
        sdxl_mode ? prompt_cache::kModeSdxl : prompt_cache::kModeSd15;
    const uint32_t cache_hidden_dim =
        sdxl_mode ? (uint32_t)sdxl_concat_dim : (uint32_t)text_embedding_size;
    const uint32_t cache_pooled_dim =
        sdxl_mode ? (uint32_t)text_embedding_size_2 : 0u;

    float *neg_hidden_dst = sdxl_mode ? sdxl_encoder_hidden_states.data()
                                      : text_embedding_float.data();
    float *pos_hidden_dst =
        sdxl_mode ? sdxl_encoder_hidden_states.data() + 77 * sdxl_concat_dim
                  : text_embedding_float.data() + 77 * text_embedding_size;
    float *neg_pooled_dst = sdxl_mode ? sdxl_text_embeds.data() : nullptr;
    float *pos_pooled_dst =
        sdxl_mode ? sdxl_text_embeds.data() + text_embedding_size_2 : nullptr;

    bool neg_hit =
        neg_cache_eligible &&
        loadPromptCache(prompt_cache_dir, negative_prompt, cache_mode,
                        cache_hidden_dim, cache_pooled_dim, neg_hidden_dst,
                        neg_pooled_dst);
    bool pos_hit =
        pos_cache_eligible &&
        loadPromptCache(prompt_cache_dir, prompt, cache_mode, cache_hidden_dim,
                        cache_pooled_dim, pos_hidden_dst, pos_pooled_dst);

    if (neg_hit) QNN_INFO("Prompt cache hit (negative)");
    if (pos_hit) QNN_INFO("Prompt cache hit (positive)");

    if (neg_hit && pos_hit) {
      QNN_INFO("CLIP cache hit (both sides), skipping CLIP inference");
    } else {
      ProcessedPromptPair processed =
          processPromptPair(prompt, negative_prompt, 77);

      std::vector<int> clip_input_ids = processed.ids;  // old (2*77)
      auto parsed_input_text = tokenizer->Decode(clip_input_ids);
      QNN_INFO("Parsed Input Text: %s", parsed_input_text.c_str());

      float *embed_ptr = text_embedding_float.data();

      if (sdxl_mode) {
        if (sdxl_lowram) loadSdxlClipMnnIfNeeded(gctx);
        if (!clipInterpreter || !clip2Interpreter)
          throw std::runtime_error("SDXL CLIP interpreters not initialized!");

        auto run_sdxl_clip = [&](const std::vector<float> &emb1,
                                 const std::vector<float> &emb2,
                                 const int *ids77,
                                 float *out_hidden_concat /*77*2048*/,
                                 float *out_pooled /*1280*/) {
          // Encoder 1 (CLIP-L): 77x768 -> last_hidden_state 77x768
          auto in1 =
              clipInterpreter->getSessionInput(clipSession, "input_embedding");
          memcpy(in1->host<float>(), emb1.data(),
                 77 * text_embedding_size * sizeof(float));
          clipInterpreter->runSession(clipSession);
          auto out1 = clipInterpreter->getSessionOutput(clipSession,
                                                        "last_hidden_state");
          const float *out1_data = out1->host<float>();

          // Encoder 2 (CLIP-G): 77x1280 -> last_hidden_state 77x1280 +
          // pooled_output 77x1280 (exported without pooling; we select
          // the EOS row here as the true pooled embedding).
          auto in2 = clip2Interpreter->getSessionInput(clip2Session,
                                                       "input_embedding");
          memcpy(in2->host<float>(), emb2.data(),
                 77 * text_embedding_size_2 * sizeof(float));
          clip2Interpreter->runSession(clip2Session);
          auto out2_hidden = clip2Interpreter->getSessionOutput(
              clip2Session, "last_hidden_state");
          auto out2_pool =
              clip2Interpreter->getSessionOutput(clip2Session, "pooled_output");
          const float *out2_hidden_data = out2_hidden->host<float>();
          const float *out2_pool_data = out2_pool->host<float>();

          // Concat along feature dim: [77, 768] + [77, 1280] = [77, 2048]
          for (int t = 0; t < 77; t++) {
            memcpy(out_hidden_concat + t * sdxl_concat_dim,
                   out1_data + t * text_embedding_size,
                   text_embedding_size * sizeof(float));
            memcpy(
                out_hidden_concat + t * sdxl_concat_dim + text_embedding_size,
                out2_hidden_data + t * text_embedding_size_2,
                text_embedding_size_2 * sizeof(float));
          }
          // Pool by picking the EOS (49407) row; fall back to last row (76).
          int eos_pos = 76;
          for (int i = 0; i < 77; i++) {
            if (ids77[i] == 49407) {
              eos_pos = i;
              break;
            }
          }
          memcpy(out_pooled, out2_pool_data + eos_pos * text_embedding_size_2,
                 text_embedding_size_2 * sizeof(float));
        };

        if (!neg_hit) {
          run_sdxl_clip(processed.negative_embeddings,
                        processed.negative_embeddings_2, processed.ids.data(),
                        sdxl_encoder_hidden_states.data(),
                        sdxl_text_embeds.data());
        }
        if (!pos_hit) {
          run_sdxl_clip(
              processed.positive_embeddings, processed.positive_embeddings_2,
              processed.ids.data() + 77,
              sdxl_encoder_hidden_states.data() + 77 * sdxl_concat_dim,
              sdxl_text_embeds.data() + text_embedding_size_2);
        }
        if (sdxl_lowram) releaseSdxlClipMnn(gctx);
      } else {
        // SD1.5: persistent CLIP session on NPU builds, per-request load on
        // CPU builds to keep idle memory low.
        MNN::Interpreter *currentClipInterpreter = clipInterpreter;
        MNN::Session *currentClipSession = clipSession;
        bool dynamicCreated = false;

        if (use_mnn) {
          currentClipInterpreter = createMnnInterpreterMmap(clipPath.c_str());
          if (!currentClipInterpreter)
            throw std::runtime_error(
                "Failed to create temporary MNN CLIP interpreter!");
          currentClipSession = nullptr;
          dynamicCreated = true;
        } else if (!currentClipInterpreter) {
          throw std::runtime_error("Global clipInterpreter not initialized!");
        }

        bool sessionCreated = false;
        if (!currentClipSession) {
          MNN::ScheduleConfig cfg_clip;
          cfg_clip.type = MNN_FORWARD_CPU;
          cfg_clip.numThread = 4;
          MNN::BackendConfig bkCfg_clip;
          bkCfg_clip.memory = MNN::BackendConfig::Memory_Low;
          bkCfg_clip.power = MNN::BackendConfig::Power_High;
          cfg_clip.backendConfig = &bkCfg_clip;
          currentClipSession = currentClipInterpreter->createSession(cfg_clip);
          if (!currentClipSession)
            throw std::runtime_error(
                "Failed to create temporary MNN CLIP session!");
          sessionCreated = true;
        }

        auto input = currentClipInterpreter->getSessionInput(currentClipSession,
                                                             "input_embedding");
        currentClipInterpreter->resizeTensor(input, {1, 77, 768});
        currentClipInterpreter->resizeSession(currentClipSession);

        if (dynamicCreated) currentClipInterpreter->releaseModel();

        if (!neg_hit) {
          memcpy(input->host<float>(), processed.negative_embeddings.data(),
                 77 * 768 * sizeof(float));
          currentClipInterpreter->runSession(currentClipSession);
          auto out = currentClipInterpreter->getSessionOutput(
              currentClipSession, "last_hidden_state");
          memcpy(embed_ptr, out->host<float>(),
                 77 * text_embedding_size * sizeof(float));
        }

        if (!pos_hit) {
          memcpy(input->host<float>(), processed.positive_embeddings.data(),
                 77 * 768 * sizeof(float));
          currentClipInterpreter->runSession(currentClipSession);
          auto out = currentClipInterpreter->getSessionOutput(
              currentClipSession, "last_hidden_state");
          memcpy(embed_ptr + 77 * text_embedding_size, out->host<float>(),
                 77 * text_embedding_size * sizeof(float));
        }

        if (sessionCreated)
          currentClipInterpreter->releaseSession(currentClipSession);
        if (dynamicCreated) delete currentClipInterpreter;
      }

      // Persist freshly-computed CLIP outputs (per side). Sides that used a
      // TI embedding stay out of disk cache.
      if (!neg_hit && neg_cache_eligible) {
        savePromptCache(prompt_cache_dir, negative_prompt, cache_mode,
                        cache_hidden_dim, cache_pooled_dim, neg_hidden_dst,
                        neg_pooled_dst);
      }
      if (!pos_hit && pos_cache_eligible) {
        savePromptCache(prompt_cache_dir, prompt, cache_mode, cache_hidden_dim,
                        cache_pooled_dim, pos_hidden_dst, pos_pooled_dst);
      }
    }

    auto clip_end = std::chrono::high_resolution_clock::now();
    std::cout << "CLIP dur: "
              << std::chrono::duration_cast<std::chrono::milliseconds>(
                     clip_end - clip_start)
                     .count()
              << "ms\n";
    current_step++;
    progress_callback(current_step, total_run_steps, "");

    // --- Scheduler & Latents ---
    std::unique_ptr<Scheduler> scheduler;
    const char *timestep_spacing = sdxl_mode ? "trailing" : "leading";
    if (scheduler_type == "euler_a" || scheduler_type == "eulera" ||
        scheduler_type == "euler_a_karras") {
      bool use_karras = (scheduler_type == "euler_a_karras");
      scheduler = std::make_unique<EulerAncestralDiscreteScheduler>(
          1000, 0.00085f, 0.012f, "scaled_linear", "epsilon", timestep_spacing,
          0, false, use_karras);
    } else if (scheduler_type == "euler" || scheduler_type == "euler_karras") {
      bool use_karras = (scheduler_type == "euler_karras");
      scheduler = std::make_unique<EulerDiscreteScheduler>(
          1000, 0.00085f, 0.012f, "scaled_linear", "epsilon", timestep_spacing,
          0, false, use_karras);
    } else if (scheduler_type == "lcm") {
      scheduler = std::make_unique<LCMScheduler>(1000, 0.00085f, 0.012f,
                                                 "scaled_linear", "epsilon", 50,
                                                 10.0f, true, false);
    } else if (scheduler_type == "dpm_sde" ||
               scheduler_type == "dpm_sde_karras") {
      bool use_karras = (scheduler_type == "dpm_sde_karras");
      scheduler = std::make_unique<DPMSolverMultistepScheduler>(
          1000, 0.00085f, 0.012f, "scaled_linear", 2, "epsilon",
          timestep_spacing, use_karras, "sde-dpmsolver++");
    } else {
      // Default to DPM solver; "dpm_karras" enables Karras sigma schedule.
      bool use_karras = (scheduler_type == "dpm_karras");
      scheduler = std::make_unique<DPMSolverMultistepScheduler>(
          1000, 0.00085f, 0.012f, "scaled_linear", 2, "epsilon",
          timestep_spacing, use_karras);
    }
    if (use_v_pred) scheduler->set_prediction_type("v_prediction");
    scheduler->set_timesteps(steps);
    xt::xarray<float> timesteps = scheduler->get_timesteps();
    const float vae_scale = sdxl_mode ? 0.13025f : 0.18215f;
    std::vector<int> shape = {1, 4, sample_height, sample_width};
    std::vector<int> shape_batch2 = {batch_size, 4, sample_height,
                                     sample_width};
    xt::random::seed(seed);
    xt::xarray<float> latents = xt::random::randn<float>(shape);
    xt::xarray<float> latents_noise = xt::random::randn<float>(shape);

    // Scale initial latents by init_noise_sigma (required for Euler schedulers)
    float init_noise_sigma = scheduler->get_init_noise_sigma();
    latents = latents * init_noise_sigma;

    xt::xarray<float> original_latents, original_image, mask, mask_full;
    int start_step = 0;

    // --- Img2Img / VAE Encode ---
    if (request_img2img) {
      auto vae_enc_start = std::chrono::high_resolution_clock::now();
      std::vector<int> img_shape = {1, 3, output_height, output_width};
      original_image = xt::adapt(img_data, img_shape);

      bool need_vae_enc_tiling = ((output_width > 512 || output_height > 512) &&
                                  !use_mnn && vaeEncoderApp && !sdxl_mode);

      xt::xarray<float> img_lat_scaled;

      if (!need_vae_enc_tiling) {
        std::vector<float> vae_enc_mean(1 * 4 * sample_width * sample_height);
        std::vector<float> vae_enc_std(1 * 4 * sample_width * sample_height);

        // For SDXL aspect-ratio padded inpaint with a synthetic base
        // (txt2img path) the VAE encoder input is a deterministic
        // white-on-black canvas keyed by target_crop size, so the (mean,
        // std) latent stats are reproducible. Cache them to disk so we pay
        // the encoder cost only once per (model, target size).
        // User-supplied images (img2img / inpaint) are content-dependent
        // and skip the cache.
        std::string black_latent_cache_path;
        bool loaded_from_cache = false;
        if (aspect_pad_inpaint && aspect_pad_synthetic_base &&
            !modelDir.empty()) {
          auto cache_dir = ensureCacheDir(modelDir);
          if (!cache_dir.empty()) {
            black_latent_cache_path = cache_dir + "/aspect_latent_" +
                                      std::to_string(target_crop_width) + "x" +
                                      std::to_string(target_crop_height) +
                                      ".bin";
          }
          std::ifstream ifs(black_latent_cache_path, std::ios::binary);
          if (ifs) {
            ifs.seekg(0, std::ios::end);
            std::streamsize sz = ifs.tellg();
            size_t expected =
                (vae_enc_mean.size() + vae_enc_std.size()) * sizeof(float);
            if (sz == (std::streamsize)expected) {
              ifs.seekg(0);
              ifs.read(reinterpret_cast<char *>(vae_enc_mean.data()),
                       vae_enc_mean.size() * sizeof(float));
              ifs.read(reinterpret_cast<char *>(vae_enc_std.data()),
                       vae_enc_std.size() * sizeof(float));
              loaded_from_cache = ifs.good();
              if (loaded_from_cache) {
                std::cout << "Loaded aspect-canvas VAE latent from cache: "
                          << black_latent_cache_path << std::endl;
              }
            }
          }
        }

        if (!loaded_from_cache) {
          if (use_mnn) {
            MNN::Interpreter *currentVaeEncoderInterpreter =
                createMnnInterpreterMmap(vaeEncoderPath.c_str());
            if (!currentVaeEncoderInterpreter)
              throw std::runtime_error("Failed MNN VAE Enc create");

            MNN::ScheduleConfig cfg_vae_enc;
            MNN::BackendConfig bkCfg_vae_enc;
            if (use_opencl) {
              auto cache_dir = ensureCacheDir(modelDir);
              auto cache_file = (cache_dir.empty() ? modelDir : cache_dir) +
                                "/vae_enc_cache.mnnc." +
                                std::to_string(output_width);
              currentVaeEncoderInterpreter->setCacheFile(cache_file.c_str());
              cfg_vae_enc.type = MNN_FORWARD_OPENCL;
              cfg_vae_enc.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
              bkCfg_vae_enc.precision = MNN::BackendConfig::Precision_Low;
            } else {
              cfg_vae_enc.type = MNN_FORWARD_CPU;
              cfg_vae_enc.numThread = 4;
              bkCfg_vae_enc.memory = MNN::BackendConfig::Memory_Low;
            }
            bkCfg_vae_enc.power = MNN::BackendConfig::Power_High;
            cfg_vae_enc.backendConfig = &bkCfg_vae_enc;

            MNN::Session *currentVaeEncSession =
                currentVaeEncoderInterpreter->createSession(cfg_vae_enc);
            if (!currentVaeEncSession)
              throw std::runtime_error(
                  "Failed create temp MNN VAE Enc session!");

            auto input = currentVaeEncoderInterpreter->getSessionInput(
                currentVaeEncSession, "input");
            currentVaeEncoderInterpreter->resizeTensor(
                input, {1, 3, output_height, output_width});
            currentVaeEncoderInterpreter->resizeSession(currentVaeEncSession);
            if (use_opencl) {
              currentVaeEncoderInterpreter->updateCacheFile(
                  currentVaeEncSession);
            }
            currentVaeEncoderInterpreter->releaseModel();

            auto input_nchw_tensor = new MNN::Tensor(input, MNN::Tensor::CAFFE);
            auto mean_t = currentVaeEncoderInterpreter->getSessionOutput(
                currentVaeEncSession, "mean");
            auto std_t = currentVaeEncoderInterpreter->getSessionOutput(
                currentVaeEncSession, "std");
            auto mean_nchw_tensor = new MNN::Tensor(mean_t, MNN::Tensor::CAFFE);
            auto std_nchw_tensor = new MNN::Tensor(std_t, MNN::Tensor::CAFFE);

            memcpy(input_nchw_tensor->host<float>(), img_data.data(),
                   img_data.size() * sizeof(float));
            input->copyFromHostTensor(input_nchw_tensor);
            currentVaeEncoderInterpreter->runSession(currentVaeEncSession);

            mean_t->copyToHostTensor(mean_nchw_tensor);
            std_t->copyToHostTensor(std_nchw_tensor);
            memcpy(vae_enc_mean.data(), mean_nchw_tensor->host<float>(),
                   vae_enc_mean.size() * sizeof(float));
            memcpy(vae_enc_std.data(), std_nchw_tensor->host<float>(),
                   vae_enc_std.size() * sizeof(float));

            delete input_nchw_tensor;
            delete mean_nchw_tensor;
            delete std_nchw_tensor;

            currentVaeEncoderInterpreter->releaseSession(currentVaeEncSession);
            delete currentVaeEncoderInterpreter;
          } else {
            if (sdxl_lowram) loadSdxlQnnVaeEncoderIfNeeded(gctx);
            if (!vaeEncoderApp)
              throw std::runtime_error("Global vaeEncoderApp not init!");
            if (sdxl_mode) {
              if (StatusCode::SUCCESS !=
                  vaeEncoderApp->executeVaeEncoderGraphsSDXL(
                      img_data.data(), vae_enc_mean.data(), vae_enc_std.data()))
                throw std::runtime_error("QNN VAE enc SDXL exec failed");
            } else {
              if (StatusCode::SUCCESS !=
                  vaeEncoderApp->executeVaeEncoderGraphs(
                      img_data.data(), vae_enc_mean.data(), vae_enc_std.data()))
                throw std::runtime_error("QNN VAE enc exec failed");
            }
            if (sdxl_lowram) releaseSdxlQnnVaeEncoder(gctx);
          }

          // Persist the freshly-computed aspect-canvas latent stats for reuse
          // on subsequent runs at the same target size.
          if (aspect_pad_inpaint && !black_latent_cache_path.empty()) {
            std::ofstream ofs(black_latent_cache_path, std::ios::binary);
            if (ofs) {
              ofs.write(reinterpret_cast<const char *>(vae_enc_mean.data()),
                        vae_enc_mean.size() * sizeof(float));
              ofs.write(reinterpret_cast<const char *>(vae_enc_std.data()),
                        vae_enc_std.size() * sizeof(float));
              if (ofs.good()) {
                std::cout << "Saved aspect-canvas VAE latent to cache: "
                          << black_latent_cache_path << std::endl;
              }
            }
          }
        }  // !loaded_from_cache

        auto mean = xt::adapt(vae_enc_mean, shape);
        auto std_dev = xt::adapt(vae_enc_std, shape);
        xt::xarray<float> noise_0 = xt::random::randn<float>(shape);
        xt::xarray<float> img_lat = xt::eval(mean + std_dev * noise_0);
        img_lat_scaled = xt::eval(vae_scale * img_lat);

      } else {
        std::cout << "Using VAE encoder tiling for " << output_width << "x"
                  << output_height << " input..." << std::endl;

        const int vae_enc_tile_size = 512;
        const int vae_enc_latent_tile_size = 64;

        // Use generic tile position calculator
        auto [img_positions, latent_positions, img_overlap_x, img_overlap_y,
              latent_overlap_x, latent_overlap_y] =
            calculateVaeTilePositions(output_width, output_height);

        int num_tiles = img_positions.size();
        std::cout << "VAE encoder will use " << num_tiles
                  << " tiles with overlap " << img_overlap_x << "x"
                  << img_overlap_y << "px (latent: " << latent_overlap_x << "x"
                  << latent_overlap_y << ")" << std::endl;

        int original_output_width = output_width;
        int original_output_height = output_height;
        int original_sample_width = sample_width;
        int original_sample_height = sample_height;

        output_width = vae_enc_tile_size;
        output_height = vae_enc_tile_size;
        sample_width = vae_enc_latent_tile_size;
        sample_height = vae_enc_latent_tile_size;

        std::vector<std::pair<xt::xarray<float>, xt::xarray<float>>>
            encoded_tiles_mean_std;
        encoded_tiles_mean_std.reserve(img_positions.size());

        for (size_t i = 0; i < img_positions.size(); ++i) {
          auto img_pos = img_positions[i];
          xt::xarray<float> img_tile = xt::view(
              original_image, 0, xt::all(),
              xt::range(img_pos.second, img_pos.second + vae_enc_tile_size),
              xt::range(img_pos.first, img_pos.first + vae_enc_tile_size));

          std::vector<float> tile_img_vec(img_tile.begin(), img_tile.end());
          std::vector<float> tile_mean_vec(1 * 4 * vae_enc_latent_tile_size *
                                           vae_enc_latent_tile_size);
          std::vector<float> tile_std_vec(1 * 4 * vae_enc_latent_tile_size *
                                          vae_enc_latent_tile_size);

          if (!vaeEncoderApp)
            throw std::runtime_error("Global vaeEncoderApp not init!");

          if (StatusCode::SUCCESS !=
              vaeEncoderApp->executeVaeEncoderGraphs(tile_img_vec.data(),
                                                     tile_mean_vec.data(),
                                                     tile_std_vec.data()))
            throw std::runtime_error("QNN VAE enc exec failed for tile");

          std::vector<int> tile_shape = {1, 4, vae_enc_latent_tile_size,
                                         vae_enc_latent_tile_size};
          encoded_tiles_mean_std.push_back(
              {xt::adapt(tile_mean_vec, tile_shape),
               xt::adapt(tile_std_vec, tile_shape)});
          std::cout << "Processed VAE encoder tile " << i + 1 << "/"
                    << img_positions.size() << std::endl;
        }

        output_width = original_output_width;
        output_height = original_output_height;
        sample_width = original_sample_width;
        sample_height = original_sample_height;

        xt::xarray<float> img_lat = blendVaeEncoderTiles(
            encoded_tiles_mean_std, latent_positions, sample_height,
            sample_width, vae_enc_latent_tile_size, latent_overlap_x,
            latent_overlap_y);

        img_lat_scaled = xt::eval(vae_scale * img_lat);

        std::cout << "VAE encoder tiling completed: "
                  << encoded_tiles_mean_std.size()
                  << " tiles processed and blended" << std::endl;
      }

      auto vae_enc_end = std::chrono::high_resolution_clock::now();
      std::cout << "VAE Enc dur: "
                << std::chrono::duration_cast<std::chrono::milliseconds>(
                       vae_enc_end - vae_enc_start)
                       .count()
                << "ms\n";

      original_latents = img_lat_scaled;
      start_step = steps * (1.0f - denoise_strength);
      // Clamp so timesteps(start_step) below is never out-of-bounds. With
      // denoise_strength = 0 (often used to inspect the base image) the
      // unclamped value would equal `steps` and the OOB read produced
      // garbage noise that decoded to a random pattern.
      if (start_step >= steps) start_step = steps - 1;
      if (start_step < 0) start_step = 0;
      total_run_steps -= start_step;
      scheduler->set_begin_index(start_step);
      xt::xarray<int> t = {(int)(timesteps(start_step))};

      // For SYNTHETIC-base aspect padding (txt2img path) we replace the
      // mask region with a txt2img-style pure-noise prior so the generated
      // region doesn't inherit the black-canvas bias from VAE encoding.
      // Do NOT do this for user-image base (img2img / inpaint): there the
      // mask region should start from the user's actual image (noised), not
      // pure noise — otherwise img2img degenerates into txt2img.
      xt::xarray<float> pure_noise_latents;
      if (aspect_pad_synthetic_base) {
        pure_noise_latents = xt::eval(latents);
      }

      latents = scheduler->add_noise(original_latents, latents_noise, t);

      if (request_has_mask) {
        mask = xt::adapt(mask_data, {1, 4, sample_height, sample_width});
        mask_full =
            xt::adapt(mask_data_full, {1, 3, output_height, output_width});

        if (aspect_pad_synthetic_base) {
          // Inside the mask: txt2img-style pure noise (no black-latent bias).
          // Outside: noised black latent, kept stable each step by the mask
          // blend further down in the denoising loop.
          latents =
              xt::eval(pure_noise_latents * mask + latents * (1.0f - mask));
        }
      }

      current_step++;
      progress_callback(current_step, total_run_steps, "");
    }  // --- UNET Denoising Loop ---
    int single_latent_size = 1 * 4 * sample_width * sample_height;

    MNN::Interpreter *currentUnetInterpreter = nullptr;
    MNN::Session *currentUnetSession = nullptr;

    if (use_mnn) {
      currentUnetInterpreter = createMnnInterpreterMmap(unetPath.c_str());
      if (!currentUnetInterpreter)
        throw std::runtime_error(
            "Failed to create temporary MNN UNET interpreter!");

      MNN::ScheduleConfig cfg_unet;
      MNN::BackendConfig bkCfg_unet;
      if (use_opencl) {
        auto cache_dir = ensureCacheDir(modelDir);
        auto cache_file = (cache_dir.empty() ? modelDir : cache_dir) +
                          "/unet_cache.mnnc." + std::to_string(output_width);
        currentUnetInterpreter->setCacheFile(cache_file.c_str());
        cfg_unet.type = MNN_FORWARD_OPENCL;
        cfg_unet.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
        bkCfg_unet.precision = MNN::BackendConfig::Precision_Low;
      } else {
        cfg_unet.type = MNN_FORWARD_CPU;
        cfg_unet.numThread = 4;
        bkCfg_unet.memory = MNN::BackendConfig::Memory_Low;
      }
      bkCfg_unet.power = MNN::BackendConfig::Power_High;
      cfg_unet.backendConfig = &bkCfg_unet;

      currentUnetSession = currentUnetInterpreter->createSession(cfg_unet);
      if (!currentUnetSession)
        throw std::runtime_error(
            "Failed to create temporary MNN UNET session!");

      auto samp =
          currentUnetInterpreter->getSessionInput(currentUnetSession, "sample");
      auto ts = currentUnetInterpreter->getSessionInput(currentUnetSession,
                                                        "timestep");
      auto enc = currentUnetInterpreter->getSessionInput(
          currentUnetSession, "encoder_hidden_states");

      currentUnetInterpreter->resizeTensor(
          samp, {batch_size, 4, sample_height, sample_width});
      currentUnetInterpreter->resizeTensor(ts, {1});
      currentUnetInterpreter->resizeTensor(
          enc, {batch_size, 77, text_embedding_size});
      currentUnetInterpreter->resizeSession(currentUnetSession);
      if (use_opencl) {
        currentUnetInterpreter->updateCacheFile(currentUnetSession);
      }

      currentUnetInterpreter->releaseModel();
    }

    if (sdxl_lowram) loadSdxlQnnUnetIfNeeded(gctx);

    for (int i = start_step; i < timesteps.size(); ++i) {
      if (show_diffusion_process && !use_mnn && !sdxl_lowram &&
          (i - start_step) % show_diffusion_stride == 0) {
        try {
          // Decode current latents for preview
          xt::xarray<float> preview_latents =
              xt::eval((1.0 / vae_scale) * latents);

          xt::xarray<float> pixels;
          bool preview_success = false;

          if ((output_width > 512 || output_height > 512) && !sdxl_mode) {
            // Use tiling for QNN large resolution preview
            auto [output_positions, latent_positions, overlap_x, overlap_y,
                  latent_overlap_x, latent_overlap_y] =
                calculateVaeTilePositions(output_width, output_height);

            const int vae_tile_size = 512;
            const int vae_latent_tile_size = 64;

            int original_output_width = output_width;
            int original_output_height = output_height;
            int original_sample_width = sample_width;
            int original_sample_height = sample_height;

            output_width = vae_tile_size;
            output_height = vae_tile_size;
            sample_width = vae_latent_tile_size;
            sample_height = vae_latent_tile_size;

            std::vector<xt::xarray<float>> decoded_tiles;
            decoded_tiles.reserve(latent_positions.size());

            bool tile_success = true;
            for (size_t tile_idx = 0; tile_idx < latent_positions.size();
                 ++tile_idx) {
              auto lat_pos = latent_positions[tile_idx];
              // Extract latent tile
              xt::xarray<float> latent_tile =
                  xt::view(preview_latents, 0, xt::all(),
                           xt::range(lat_pos.second,
                                     lat_pos.second + vae_latent_tile_size),
                           xt::range(lat_pos.first,
                                     lat_pos.first + vae_latent_tile_size));

              std::vector<float> tile_latent_vec(latent_tile.begin(),
                                                 latent_tile.end());
              xt::xarray<float> tile_output =
                  xt::zeros<float>({1, 3, vae_tile_size, vae_tile_size});

              if (StatusCode::SUCCESS !=
                  vaeDecoderApp->executeVaeDecoderGraphs(tile_latent_vec.data(),
                                                         tile_output.data())) {
                tile_success = false;
                break;
              }

              decoded_tiles.push_back(std::move(tile_output));
            }

            output_width = original_output_width;
            output_height = original_output_height;
            sample_width = original_sample_width;
            sample_height = original_sample_height;

            if (tile_success) {
              pixels = blendVaeOutputTiles(
                  decoded_tiles, output_positions, output_height, output_width,
                  vae_tile_size, overlap_x, overlap_y);
              preview_success = true;
            }
          } else {
            // Single inference for QNN <= 512 (or SDXL @ 1024)
            std::vector<float> vae_dec_in_vec(preview_latents.begin(),
                                              preview_latents.end());
            std::vector<float> vae_dec_out_pixels(1 * 3 * output_width *
                                                  output_height);
            StatusCode vae_dec_status =
                sdxl_mode
                    ? vaeDecoderApp->executeVaeDecoderGraphsSDXL(
                          vae_dec_in_vec.data(), vae_dec_out_pixels.data())
                    : vaeDecoderApp->executeVaeDecoderGraphs(
                          vae_dec_in_vec.data(), vae_dec_out_pixels.data());
            if (StatusCode::SUCCESS == vae_dec_status) {
              std::vector<int> pixel_shape = {1, 3, output_height,
                                              output_width};
              pixels = xt::adapt(vae_dec_out_pixels, pixel_shape);
              preview_success = true;
            }
          }

          if (preview_success) {
            auto img = xt::view(pixels, 0);
            auto transp = xt::transpose(img, {1, 2, 0});
            auto norm = xt::clip(((transp + 1.0) / 2.0) * 255.0, 0.0, 255.0);
            xt::xarray<uint8_t> u8_img = xt::cast<uint8_t>(norm);
            std::vector<uint8_t> out_data(u8_img.begin(), u8_img.end());

            // Aspect padding: also crop the preview to the target rectangle
            // so the UI sees the same dimensions / framing as the final
            // result (otherwise progress shows the 1024x1024 padded canvas
            // and complete shows the cropped image).
            if (aspect_pad_inpaint && target_crop_width > 0 &&
                target_crop_height > 0 &&
                (target_crop_width != output_width ||
                 target_crop_height != output_height)) {
              int px0 = (output_width - target_crop_width) / 2;
              int py0 = (output_height - target_crop_height) / 2;
              std::vector<uint8_t> cropped((size_t)3 * target_crop_width *
                                           target_crop_height);
              for (int y = 0; y < target_crop_height; ++y) {
                const uint8_t *src_row =
                    out_data.data() +
                    ((size_t)(py0 + y) * output_width + px0) * 3;
                uint8_t *dst_row =
                    cropped.data() + (size_t)y * target_crop_width * 3;
                std::memcpy(dst_row, src_row, (size_t)target_crop_width * 3);
              }
              out_data = std::move(cropped);
            }

            std::string image_str_result(out_data.begin(), out_data.end());
            std::string enc_img = base64_encode(image_str_result);
            progress_callback(current_step, total_run_steps, enc_img);
          } else {
            progress_callback(current_step, total_run_steps, "");
          }
        } catch (const std::exception &e) {
          QNN_WARN("Preview generation failed: %s", e.what());
          progress_callback(current_step, total_run_steps, "");
        }
      } else {
        progress_callback(current_step, total_run_steps, "");
      }

      auto step_start_time = std::chrono::high_resolution_clock::now();

      // Scale model input (required for Euler schedulers)
      float current_ts = timesteps(i);
      xt::xarray<float> latents_scaled =
          scheduler->scale_model_input(latents, current_ts);

      std::vector<float> latents_in_vec;
      latents_in_vec.reserve(batch_size * single_latent_size);
      latents_in_vec.insert(latents_in_vec.end(), latents_scaled.begin(),
                            latents_scaled.end());
      latents_in_vec.insert(latents_in_vec.end(), latents_scaled.begin(),
                            latents_scaled.end());
      std::vector<float> unet_out_latents(batch_size * single_latent_size);

      if (use_mnn) {
        auto samp = currentUnetInterpreter->getSessionInput(currentUnetSession,
                                                            "sample");
        auto ts = currentUnetInterpreter->getSessionInput(currentUnetSession,
                                                          "timestep");
        auto enc = currentUnetInterpreter->getSessionInput(
            currentUnetSession, "encoder_hidden_states");

        int current_ts_int = (int)(current_ts);

        auto samp_nchw_tensor = new MNN::Tensor(samp, MNN::Tensor::CAFFE);
        auto ts_nchw_tensor = new MNN::Tensor(ts, MNN::Tensor::CAFFE);
        auto enc_nchw_tensor = new MNN::Tensor(enc, MNN::Tensor::CAFFE);

        // Copy both batches (negative and positive) at once
        memcpy(samp_nchw_tensor->host<float>(), latents_in_vec.data(),
               latents_in_vec.size() * sizeof(float));
        memcpy(ts_nchw_tensor->host<int>(), &current_ts_int, sizeof(int));
        memcpy(enc_nchw_tensor->host<float>(), text_embedding_float.data(),
               text_embedding_float.size() * sizeof(float));

        samp->copyFromHostTensor(samp_nchw_tensor);
        ts->copyFromHostTensor(ts_nchw_tensor);
        enc->copyFromHostTensor(enc_nchw_tensor);

        // Single batch inference for both negative and positive conditions
        currentUnetInterpreter->runSession(currentUnetSession);

        auto output = currentUnetInterpreter->getSessionOutput(
            currentUnetSession, "out_sample");
        output->copyToHostTensor(samp_nchw_tensor);
        memcpy(unet_out_latents.data(), samp_nchw_tensor->host<float>(),
               unet_out_latents.size() * sizeof(float));

        delete samp_nchw_tensor;
        delete ts_nchw_tensor;
        delete enc_nchw_tensor;
      } else {
        if (!unetApp)
          throw std::runtime_error("Global unetApp not initialized!");

        float *latents_in_ptr = latents_in_vec.data();
        float *latents_out_ptr = unet_out_latents.data();

        // With cfg = 1.0, noise_pred = uncond + 1*(txt - uncond) = txt, so the
        // unconditional pass is redundant. Skip it on QNN to halve UNet time.
        // MNN runs both batches in a single graph call so the optimization
        // does not apply there.
        const bool skip_uncond = (cfg == 1.0f);

        if (sdxl_mode) {
          float *hidden_ptr = sdxl_encoder_hidden_states.data();
          float *pooled_ptr = sdxl_text_embeds.data();
          float *time_ids_ptr = sdxl_time_ids.data();
          const int hidden_stride = 77 * sdxl_concat_dim;
          const int pooled_stride = text_embedding_size_2;
          const int time_ids_stride = 6;

          if (!skip_uncond &&
              StatusCode::SUCCESS !=
                  unetApp->executeUnetGraphsSDXL(
                      latents_in_ptr, static_cast<int>(current_ts), hidden_ptr,
                      pooled_ptr, time_ids_ptr, latents_out_ptr))
            throw std::runtime_error("QNN UNET SDXL exec failed (uncond)");

          if (StatusCode::SUCCESS !=
              unetApp->executeUnetGraphsSDXL(
                  latents_in_ptr + single_latent_size,
                  static_cast<int>(current_ts), hidden_ptr + hidden_stride,
                  pooled_ptr + pooled_stride, time_ids_ptr + time_ids_stride,
                  latents_out_ptr + single_latent_size))
            throw std::runtime_error("QNN UNET SDXL exec failed (cond)");
        } else {
          float *embed_ptr = text_embedding_float.data();

          if (!skip_uncond &&
              StatusCode::SUCCESS !=
                  unetApp->executeUnetGraphs(latents_in_ptr,
                                             static_cast<int>(current_ts),
                                             embed_ptr, latents_out_ptr))
            throw std::runtime_error("QNN UNET exec failed (uncond)");

          if (StatusCode::SUCCESS !=
              unetApp->executeUnetGraphs(latents_in_ptr + single_latent_size,
                                         static_cast<int>(current_ts),
                                         embed_ptr + 77 * text_embedding_size,
                                         latents_out_ptr + single_latent_size))
            throw std::runtime_error("QNN UNET exec failed (cond)");
        }
      }

      auto step_end_time = std::chrono::high_resolution_clock::now();
      auto step_dur = std::chrono::duration_cast<std::chrono::milliseconds>(
          step_end_time - step_start_time);

      if (i == start_step) first_step_time_ms = step_dur.count();
      std::cout << "UNET step " << i << " dur: " << step_dur.count() << "ms\n";

      xt::xarray<float> noise_pred;
      if (!use_mnn && cfg == 1.0f) {
        // cfg = 1 path: only the cond half of unet_out_latents was filled.
        std::vector<float> cond_only(
            unet_out_latents.begin() + single_latent_size,
            unet_out_latents.end());
        noise_pred = xt::adapt(cond_only, shape);
      } else {
        xt::xarray<float> noise_pred_batch =
            xt::adapt(unet_out_latents, shape_batch2);
        xt::xarray<float> uncond = xt::view(noise_pred_batch, 0);
        xt::xarray<float> txt = xt::view(noise_pred_batch, 1);
        noise_pred = xt::eval(uncond + cfg * (txt - uncond));
      }
      latents = scheduler->step(noise_pred, timesteps(i), latents).prev_sample;

      if (request_has_mask) {
        xt::xarray<int> t_xt = {(int)(timesteps(i))};
        xt::xarray<float> orig_noised =
            scheduler->add_noise(original_latents, latents_noise, t_xt);
        latents = xt::eval(orig_noised * (1.0f - mask) + latents * mask);
      }

      current_step++;
    }

    if (use_mnn) {
      if (currentUnetSession)
        currentUnetInterpreter->releaseSession(currentUnetSession);
      if (currentUnetInterpreter) delete currentUnetInterpreter;
    }

    if (sdxl_lowram) releaseSdxlQnnUnet(gctx);

    // --- VAE Decode ---
    auto vae_dec_start = std::chrono::high_resolution_clock::now();

    bool need_vae_tiling =
        ((output_width > 512 || output_height > 512) && !use_mnn && !sdxl_mode);
    if (need_vae_tiling) {
      std::cout << "Using VAE decoder tiling for " << output_width << "x"
                << output_height << " output..." << std::endl;
    }

    latents = xt::eval((1.0 / vae_scale) * latents);

    xt::xarray<float> pixels;

    if (!need_vae_tiling) {
      std::vector<float> vae_dec_in_vec(latents.begin(), latents.end());
      std::vector<float> vae_dec_out_pixels(1 * 3 * output_width *
                                            output_height);

      if (use_mnn) {
        MNN::Interpreter *currentVaeDecoderInterpreter =
            createMnnInterpreterMmap(vaeDecoderPath.c_str());

        if (!currentVaeDecoderInterpreter)
          throw std::runtime_error(
              "Failed to create temporary MNN VAE Decoder interpreter!");

        MNN::ScheduleConfig cfg_vae;
        MNN::BackendConfig bkCfg_vae;
        if (use_opencl) {
          auto cache_dir = ensureCacheDir(modelDir);
          auto cache_file = (cache_dir.empty() ? modelDir : cache_dir) +
                            "/vae_dec_cache.mnnc." +
                            std::to_string(output_width);
          currentVaeDecoderInterpreter->setCacheFile(cache_file.c_str());
          cfg_vae.type = MNN_FORWARD_OPENCL;
          cfg_vae.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
          bkCfg_vae.precision = MNN::BackendConfig::Precision_Low;
        } else {
          cfg_vae.type = MNN_FORWARD_CPU;
          cfg_vae.numThread = 4;
          bkCfg_vae.memory = MNN::BackendConfig::Memory_Low;
        }
        bkCfg_vae.power = MNN::BackendConfig::Power_High;
        cfg_vae.backendConfig = &bkCfg_vae;

        MNN::Session *currentVaeDecSession =
            currentVaeDecoderInterpreter->createSession(cfg_vae);

        if (!currentVaeDecSession)
          throw std::runtime_error("Failed create temp MNN VAE Dec session!");

        auto input = currentVaeDecoderInterpreter->getSessionInput(
            currentVaeDecSession, "latent_sample");

        currentVaeDecoderInterpreter->resizeTensor(
            input, {1, 4, sample_height, sample_width});
        currentVaeDecoderInterpreter->resizeSession(currentVaeDecSession);
        if (use_opencl) {
          currentVaeDecoderInterpreter->updateCacheFile(currentVaeDecSession);
        }

        currentVaeDecoderInterpreter->releaseModel();

        auto input_nchw_tensor = new MNN::Tensor(input, MNN::Tensor::CAFFE);
        auto output = currentVaeDecoderInterpreter->getSessionOutput(
            currentVaeDecSession, "sample");
        auto output_nchw_tensor = new MNN::Tensor(output, MNN::Tensor::CAFFE);

        memcpy(input_nchw_tensor->host<float>(), vae_dec_in_vec.data(),
               vae_dec_in_vec.size() * sizeof(float));
        input->copyFromHostTensor(input_nchw_tensor);

        currentVaeDecoderInterpreter->runSession(currentVaeDecSession);

        output->copyToHostTensor(output_nchw_tensor);
        memcpy(vae_dec_out_pixels.data(), output_nchw_tensor->host<float>(),
               vae_dec_out_pixels.size() * sizeof(float));

        delete input_nchw_tensor;
        delete output_nchw_tensor;

        currentVaeDecoderInterpreter->releaseSession(currentVaeDecSession);
        delete currentVaeDecoderInterpreter;
      } else {
        if (sdxl_lowram) loadSdxlQnnVaeDecoderIfNeeded(gctx);
        if (!vaeDecoderApp)
          throw std::runtime_error("Global vaeDecoderApp not init!");

        if (sdxl_mode) {
          if (StatusCode::SUCCESS !=
              vaeDecoderApp->executeVaeDecoderGraphsSDXL(
                  vae_dec_in_vec.data(), vae_dec_out_pixels.data()))
            throw std::runtime_error("QNN VAE dec SDXL exec failed");
        } else {
          if (StatusCode::SUCCESS !=
              vaeDecoderApp->executeVaeDecoderGraphs(vae_dec_in_vec.data(),
                                                     vae_dec_out_pixels.data()))
            throw std::runtime_error("QNN VAE dec exec failed");
        }
        if (sdxl_lowram) releaseSdxlQnnVaeDecoder(gctx);
      }

      std::vector<int> pixel_shape = {1, 3, output_height, output_width};
      pixels = xt::adapt(vae_dec_out_pixels, pixel_shape);

    } else {
      const int vae_tile_size = 512;
      const int vae_latent_tile_size = 64;

      // Use generic tile position calculator
      auto [output_positions, latent_positions, overlap_x, overlap_y,
            latent_overlap_x, latent_overlap_y] =
          calculateVaeTilePositions(output_width, output_height);

      int num_tiles = output_positions.size();
      std::cout << "VAE decoder will use " << num_tiles
                << " tiles with overlap " << overlap_x << "x" << overlap_y
                << "px (latent: " << latent_overlap_x << "x" << latent_overlap_y
                << ")" << std::endl;

      int original_output_width = output_width;
      int original_output_height = output_height;
      int original_sample_width = sample_width;
      int original_sample_height = sample_height;

      output_width = vae_tile_size;
      output_height = vae_tile_size;
      sample_width = vae_latent_tile_size;
      sample_height = vae_latent_tile_size;

      std::vector<xt::xarray<float>> decoded_tiles;
      decoded_tiles.reserve(latent_positions.size());

      for (size_t i = 0; i < latent_positions.size(); ++i) {
        auto lat_pos = latent_positions[i];
        xt::xarray<float> latent_tile = xt::view(
            latents, 0, xt::all(),
            xt::range(lat_pos.second, lat_pos.second + vae_latent_tile_size),
            xt::range(lat_pos.first, lat_pos.first + vae_latent_tile_size));

        std::vector<float> tile_latent_vec(latent_tile.begin(),
                                           latent_tile.end());
        xt::xarray<float> tile_output =
            xt::zeros<float>({1, 3, vae_tile_size, vae_tile_size});

        if (!vaeDecoderApp)
          throw std::runtime_error("Global vaeDecoderApp not init!");

        if (StatusCode::SUCCESS !=
            vaeDecoderApp->executeVaeDecoderGraphs(tile_latent_vec.data(),
                                                   tile_output.data()))
          throw std::runtime_error("QNN VAE dec exec failed for tile");

        decoded_tiles.push_back(std::move(tile_output));

        std::cout << "Processed VAE tile " << i + 1 << "/"
                  << latent_positions.size() << std::endl;
      }

      output_width = original_output_width;
      output_height = original_output_height;
      sample_width = original_sample_width;
      sample_height = original_sample_height;

      pixels = blendVaeOutputTiles(decoded_tiles, output_positions,
                                      output_height, output_width,
                                      vae_tile_size, overlap_x, overlap_y);

      std::cout << "VAE tiling completed: " << decoded_tiles.size()
                << " tiles processed and blended" << std::endl;
    }

    auto vae_dec_end = std::chrono::high_resolution_clock::now();
    std::cout << "VAE Dec dur: "
              << std::chrono::duration_cast<std::chrono::milliseconds>(
                     vae_dec_end - vae_dec_start)
                     .count()
              << "ms\n";

    // --- Post-process Image ---
    // Laplacian-blend the decoded image against the original only when the
    // user actually painted a mask (real inpaint). For auto-installed aspect
    // masks the "original" is just the synthetic canvas / padded user image
    // and the mask region is the entire visible crop, so blending adds no
    // value and risks contaminating with the surrounding canvas.
    if (request_has_mask && user_supplied_mask) {
      if (aspect_pad_inpaint) {
        // Blend only inside the centered target rectangle so the discarded
        // black border / pad don't pull dark content into the crop edge.
        int px0 = (output_width - target_crop_width) / 2;
        int py0 = (output_height - target_crop_height) / 2;
        xt::xarray<float> orig_crop =
            xt::eval(xt::view(original_image, 0, xt::all(),
                              xt::range(py0, py0 + target_crop_height),
                              xt::range(px0, px0 + target_crop_width)));
        xt::xarray<float> gen_crop = xt::eval(xt::view(
            pixels, 0, xt::all(), xt::range(py0, py0 + target_crop_height),
            xt::range(px0, px0 + target_crop_width)));
        xt::xarray<float> mask_crop = xt::eval(xt::view(
            mask_full, 0, xt::all(), xt::range(py0, py0 + target_crop_height),
            xt::range(px0, px0 + target_crop_width)));
        auto blended = laplacianPyramidBlend(orig_crop, gen_crop, mask_crop);
        // Write back into the same target rectangle of `pixels`.
        auto target_view = xt::view(pixels, 0, xt::all(),
                                    xt::range(py0, py0 + target_crop_height),
                                    xt::range(px0, px0 + target_crop_width));
        target_view = xt::reshape_view(
            blended, {3, target_crop_height, target_crop_width});
      } else {
        auto orig_img_view = xt::view(original_image, 0);  // (3, H, W)
        auto gen_img_view = xt::view(pixels, 0);           // (3, H, W)
        auto mask_view = xt::view(mask_full, 0);           // (1, H, W)

        auto blended =
            laplacianPyramidBlend(orig_img_view, gen_img_view, mask_view);
        pixels = xt::reshape_view(blended, {1, 3, output_height, output_width});
      }
    }
    auto img = xt::view(pixels, 0);
    auto transp = xt::transpose(img, {1, 2, 0});
    auto norm = xt::clip(((transp + 1.0) / 2.0) * 255.0, 0.0, 255.0);
    xt::xarray<uint8_t> u8_img = xt::cast<uint8_t>(norm);
    std::vector<uint8_t> out_data(u8_img.begin(), u8_img.end());

    int final_width = output_width;
    int final_height = output_height;

    // --- Safety Checker ---
    if (use_safety_checker) {
      auto safety_start = std::chrono::high_resolution_clock::now();
      float score = 0.0f;

      if (safety_check(out_data, output_width, output_height, score,
                       safetyCheckerInterpreter, safetyCheckerSession)) {
        std::cout << "NSFW Score: " << score << std::endl;
        if (score > nsfw_threshold) {
          QNN_WARN("NSFW detected (%.2f>%.2f).", score, nsfw_threshold);
          std::fill(out_data.begin(), out_data.end(), 255);
        }
      } else {
        QNN_WARN("Safety check failed.");
      }

      auto safety_end = std::chrono::high_resolution_clock::now();
      std::cout << "Safety check dur: "
                << std::chrono::duration_cast<std::chrono::milliseconds>(
                       safety_end - safety_start)
                       .count()
                << "ms\n";
    }

    current_step++;
    progress_callback(current_step, total_run_steps, "");
    auto end_time = std::chrono::high_resolution_clock::now();
    auto total_time = std::chrono::duration_cast<std::chrono::milliseconds>(
                          end_time - start_time)
                          .count();

    // SDXL aspect-ratio padded inpaint: crop the centered target region out
    // of the 1024x1024 canvas before returning.
    if (aspect_pad_inpaint && target_crop_width > 0 && target_crop_height > 0 &&
        (target_crop_width != output_width ||
         target_crop_height != output_height)) {
      int px0 = (output_width - target_crop_width) / 2;
      int py0 = (output_height - target_crop_height) / 2;
      std::vector<uint8_t> cropped((size_t)3 * target_crop_width *
                                   target_crop_height);
      for (int y = 0; y < target_crop_height; ++y) {
        const uint8_t *src_row =
            out_data.data() + ((size_t)(py0 + y) * output_width + px0) * 3;
        uint8_t *dst_row = cropped.data() + (size_t)y * target_crop_width * 3;
        std::memcpy(dst_row, src_row, (size_t)target_crop_width * 3);
      }
      out_data = std::move(cropped);
      final_width = target_crop_width;
      final_height = target_crop_height;
    }

    return GenerationResult{out_data,
                            final_width,
                            final_height,
                            3,
                            static_cast<int>(total_time),
                            first_step_time_ms};
  } catch (const std::exception &e) {
    QNN_ERROR("Image generation error: %s", e.what());
    throw;
  }
}

// --- Main Function ---
int main(int argc, char **argv) {
  // ── BKND-PROC-0008 P2: Centralised application state ──
  // All model objects, config, and server state are owned here.
  // Macro aliases (defined above) expand to appCtx.conf.xxx / appCtx.models.xxx.
  AppContext appCtx;

  using namespace qnn::tools;
  if (!qnn::log::initializeLogging()) {
    std::cerr << "ERROR: Init logging failed!\n";
    return EXIT_FAILURE;
  }
  processCommandLine(argc, argv, appCtx);

  if (!upscaler_mode) {
    try {
      auto blob = LoadBytesFromFile(tokenizerPath);
      tokenizer = tokenizers::Tokenizer::FromBlobJSON(blob);
      if (!tokenizer) throw std::runtime_error("Tokenizer creation failed.");
    } catch (const std::exception &e) {
      std::cerr << "Failed load tokenizer: " << e.what() << std::endl;
      return EXIT_FAILURE;
    }

    // Load embeddings
    if (!modelDir.empty()) {
      std::filesystem::path modelPath(modelDir);
      std::filesystem::path embeddingsPath =
          modelPath.parent_path().parent_path() / "embeddings";
      if (std::filesystem::exists(embeddingsPath)) {
        try {
          promptProcessor.loadEmbeddings(embeddingsPath.string(), sdxl_mode);
          QNN_INFO("Loaded %zu embeddings (SDXL=%d) from %s",
                   promptProcessor.getEmbeddingCount(), sdxl_mode ? 1 : 0,
                   embeddingsPath.string().c_str());
        } catch (const std::exception &e) {
          QNN_WARN("Failed to load embeddings: %s", e.what());
        }
      } else {
        QNN_INFO("Embeddings directory not found: %s",
                 embeddingsPath.string().c_str());
      }
    }

    MNN::ScheduleConfig cfg_common;
    cfg_common.type = MNN_FORWARD_CPU;
    cfg_common.numThread = 1;
    MNN::BackendConfig bkCfg_common;
    bkCfg_common.memory = MNN::BackendConfig::Memory_Low;
    bkCfg_common.power = MNN::BackendConfig::Power_High;
    cfg_common.backendConfig = &bkCfg_common;
    MNN::ScheduleConfig cfg_mnn_clip = cfg_common;
    cfg_mnn_clip.numThread = 4;

    if (!sdxl_mode && clipInterpreter) {
      clipSession = clipInterpreter->createSession(cfg_mnn_clip);
      if (!clipSession)
        QNN_ERROR("Failed create persistent MNN CLIP session!");
      else {
        QNN_INFO("Persistent MNN CLIP session created.");
        auto input =
            clipInterpreter->getSessionInput(clipSession, "input_embedding");
        clipInterpreter->resizeTensor(input, {1, 77, 768});
        clipInterpreter->resizeSession(clipSession);
        clipInterpreter->releaseModel();
      }
    }

    if (sdxl_mode && !lowram_mode && clipInterpreter && clip2Interpreter) {
      clipSession = clipInterpreter->createSession(cfg_mnn_clip);
      clip2Session = clip2Interpreter->createSession(cfg_mnn_clip);
      if (!clipSession || !clip2Session) {
        QNN_ERROR("Failed create persistent SDXL MNN CLIP sessions!");
      } else {
        QNN_INFO("Persistent SDXL MNN CLIP1/CLIP2 sessions created.");
        auto input1 =
            clipInterpreter->getSessionInput(clipSession, "input_embedding");
        clipInterpreter->resizeTensor(input1, {1, 77, text_embedding_size});
        clipInterpreter->resizeSession(clipSession);
        clipInterpreter->releaseModel();

        auto input2 =
            clip2Interpreter->getSessionInput(clip2Session, "input_embedding");
        clip2Interpreter->resizeTensor(input2, {1, 77, text_embedding_size_2});
        clip2Interpreter->resizeSession(clip2Session);
        clip2Interpreter->releaseModel();
      }
    }

    if (safetyCheckerInterpreter) {
      safetyCheckerSession =
          safetyCheckerInterpreter->createSession(cfg_common);
      if (!safetyCheckerSession)
        QNN_ERROR("Failed create persistent MNN Safety session!");
      else {
        QNN_INFO("Persistent MNN Safety session created.");
        auto input = safetyCheckerInterpreter->getSessionInput(
            safetyCheckerSession, nullptr);
        safetyCheckerInterpreter->resizeTensor(input, {1, 224, 224, 3});
        safetyCheckerInterpreter->resizeSession(safetyCheckerSession);
        safetyCheckerInterpreter->releaseModel();
      }
    }

    // --- Initialize QNN Models ---
    if (!use_mnn) {
      int status = EXIT_SUCCESS;
      if (unetApp) {
        if (g_unetPatchedBuffer && g_unetPatchedBuffer->buffer) {
          status = sample_app::initializeQnnApp(
              "UNET", unetApp, g_unetPatchedBuffer->buffer.get(),
              g_unetPatchedBuffer->size);
        } else {
          status = sample_app::initializeQnnApp("UNET", unetApp);
        }
        if (status != EXIT_SUCCESS) return status;

        if (g_unetPatchedBuffer) {
          QNN_INFO("Releasing unet patch buffer to free memory");
          g_unetPatchedBuffer.reset();
        }
      }
      if (vaeDecoderApp) {
        status = sample_app::initializeQnnApp("VAEDecoder", vaeDecoderApp);
        if (status != EXIT_SUCCESS) return status;
      }
      if (vaeEncoderApp) {
        status = sample_app::initializeQnnApp("VAEEncoder", vaeEncoderApp);
        if (status != EXIT_SUCCESS) return status;
      }
    }
  } else {
    QNN_INFO("Upscaler mode - skipping MNN and QNN model initialization");
  }

  // --- HTTP Server ---
  httplib::Server svr;
  svr.set_default_headers({
      {"Access-Control-Allow-Origin", "*"},
      {"Access-Control-Allow-Methods", "GET, POST, OPTIONS"},
      {"Access-Control-Allow-Headers", "Content-Type, Authorization"},
      {"Access-Control-Max-Age", "86400"},
  });
  svr.Options(R"(.*)", [](const httplib::Request &, httplib::Response &res) {
    res.status = 204;
  });
  svr.Get("/health", [](const httplib::Request &, httplib::Response &res) {
    res.status = 200;
  });

  // ── BKND-PROC-0008: Progress query endpoint ──
  // Allows the Android client to poll generation progress without holding
  // an SSE connection.  Returns JSON with current step, total steps, and
  // whether a generation is currently in progress.
  svr.Get("/progress", [&](const httplib::Request &, httplib::Response &res) {
    nlohmann::json r;
    r["busy"] = g_serverState.isBusy();
    r["current_step"] = g_serverState.currentStep();
    r["total_steps"] = g_serverState.totalSteps();
    res.status = 200;
    res.set_content(r.dump(), "application/json");
  });
  svr.Post("/generate", [&](const httplib::Request &req,
                            httplib::Response &res) {
    // ── BKND-PROC-0008: Reject concurrent requests ──
    // Returns 503 Service Unavailable (aligned with Stability AI/Ollama
    // conventions) with a standard Retry-After header.  The error body
    // follows Stability AI's format: { "id", "name", "errors" }.
    std::chrono::steady_clock::time_point acquireTime;
    if (!g_serverState.acquireBusy(acquireTime)) {
      nlohmann::json busy = {
          {"id",
           "busy-" +
               std::to_string(
                   std::chrono::system_clock::now()
                       .time_since_epoch()
                       .count())},
          {"name", "busy"},
          {"errors",
           {"Server is currently processing another request"}},
      };
      res.status = 503;
      res.set_header("Retry-After", "3");
      res.set_content(busy.dump(), "application/json");
      return;
    }

    // RAII: release the busy flag on every exit path so the server
    // can accept new requests again.
    auto clearBusy = [&]() { g_serverState.release(); };
    try {
      auto json = nlohmann::json::parse(req.body);
      if (!json.contains("prompt"))
        throw std::invalid_argument("Missing 'prompt'");
      prompt = json["prompt"].get<std::string>();
      negative_prompt = json.value("negative_prompt", "");
      steps = json.value("steps", 20);
      cfg = json.value("cfg", 7.5f);
      scheduler_type = json.value("scheduler", "dpm");
      use_opencl = json.value("use_opencl", false);
      show_diffusion_process = json.value("show_diffusion_process", false);
      show_diffusion_stride = json.value("show_diffusion_stride", 1);
      seed = json.value(
          "seed",
          (unsigned)hashSeed(
              std::chrono::system_clock::now().time_since_epoch().count()));
      int req_width = json.value("width", 512);
      int req_height = json.value("height", 512);
      if (json.contains("size")) {
        int size = json.value("size", 512);
        req_width = size;
        req_height = size;
      }
      if (sdxl_mode) {
        req_width = 1024;
        req_height = 1024;
      }
      denoise_strength = json.value("denoise_strength", 0.6f);
      request_img2img = false;
      request_has_mask = false;
      aspect_pad_inpaint = false;
      aspect_pad_synthetic_base = false;
      user_supplied_mask = false;
      target_crop_width = 0;
      target_crop_height = 0;
      // BKND-PROC-0008 P1: Release vector capacity between requests
      img_data.clear();
      img_data.shrink_to_fit();
      mask_data.clear();
      mask_data.shrink_to_fit();
      mask_data_full.clear();
      mask_data_full.shrink_to_fit();
      output_width = req_width;
      output_height = req_height;
      sample_width = req_width / 8;
      sample_height = req_height / 8;

      // --- SDXL aspect ratio: parse target dims first ----------------------
      // Resolve target_crop_w/h from aspect_ratio. We compute it independently
      // of img/mask presence so all three modes (txt2img / img2img / inpaint)
      // share the same downstream crop-after-decode behavior. Requires a VAE
      // encoder so the synthetic black canvas can be encoded as the inpaint
      // base latent; if the SDXL build was started without one, fall through
      // to plain 1024x1024 generation.
      if (sdxl_mode && json.contains("aspect_ratio") &&
          !vaeEncoderPath.empty()) {
        std::string ar = json["aspect_ratio"].get<std::string>();
        auto colon = ar.find(':');
        if (colon != std::string::npos) {
          try {
            int rw = std::stoi(ar.substr(0, colon));
            int rh = std::stoi(ar.substr(colon + 1));
            if (rw > 0 && rh > 0 && !(rw == rh)) {
              int tw, th;
              if (rw >= rh) {
                tw = 1024;
                th = (int)((1024.0 * rh) / rw);
                th = (th / 8) * 8;
                if (th < 8) th = 8;
              } else {
                th = 1024;
                tw = (int)((1024.0 * rw) / rh);
                tw = (tw / 8) * 8;
                if (tw < 8) tw = 8;
              }
              target_crop_width = tw;
              target_crop_height = th;
              aspect_pad_inpaint = true;
            }
          } catch (...) {
            // Bad aspect_ratio string, ignore and proceed with 1:1.
          }
        }
      }

      // Paint rectangle = target + short-axis pad. Shared by the synthetic
      // white-on-black base image and the aspect padding mask so both stay
      // strictly aligned. Only computed when aspect padding is in effect.
      const int kAspectPadPx = 8;
      int paint_w = target_crop_width;
      int paint_h = target_crop_height;
      int paint_x0 = 0, paint_y0 = 0;
      if (aspect_pad_inpaint) {
        if (target_crop_width < output_width)
          paint_w =
              std::min(output_width, target_crop_width + 2 * kAspectPadPx);
        if (target_crop_height < output_height)
          paint_h =
              std::min(output_height, target_crop_height + 2 * kAspectPadPx);
        paint_x0 = (output_width - paint_w) / 2;
        paint_y0 = (output_height - paint_h) / 2;
      }

      // --- Base image: user-supplied or synthetic --------------------------
      if (json.contains("image")) {
        request_img2img = true;
        std::string img_b64 = json["image"].get<std::string>();
        try {
          std::string dec_str = base64_decode(img_b64);
          std::vector<uint8_t> dec_buf(dec_str.begin(), dec_str.end());
          std::vector<uint8_t> dec_pix;
          decode_image(dec_buf, dec_pix, output_width, output_height);
          if (dec_pix.size() != 3 * output_width * output_height)
            throw std::runtime_error("Img size mismatch");
          std::vector<int> img_shape = {1, output_height, output_width, 3};
          xt::xarray<uint8_t> xt_u8 = xt::adapt(dec_pix, img_shape);
          xt::xarray<float> xt_f = xt::cast<float>(xt_u8);
          xt_f = xt::eval(xt_f / 127.5f - 1.0f);
          xt_f = xt::transpose(xt_f, {0, 3, 1, 2});
          img_data.assign(xt_f.begin(), xt_f.end());
        } catch (const std::exception &e) {
          throw std::invalid_argument("Err proc img: " + std::string(e.what()));
        }
      } else if (aspect_pad_inpaint) {
        // No user image but aspect padding requested: synthesise the
        // white-on-black canvas as the inpaint base. Outer ring = black
        // (value -1) to signal "edge"; center paint region = white (value
        // +1) to hint "content". The white region extends `kAspectPadPx`
        // pixels past the crop along the short axis so the mask boundary
        // never coincides with the latent's black->white transition; the
        // pad area gets generated but is cropped away on output.
        // This is also the only path eligible for the per-target
        // VAE-encoder cache.
        aspect_pad_synthetic_base = true;
        size_t img_total = 3 * (size_t)output_width * output_height;
        img_data.assign(img_total, -1.0f);
        for (int c = 0; c < 3; ++c) {
          for (int y = paint_y0; y < paint_y0 + paint_h; ++y) {
            float *row = img_data.data() +
                         ((size_t)c * output_height + y) * output_width;
            for (int x = paint_x0; x < paint_x0 + paint_w; ++x) row[x] = 1.0f;
          }
        }
        request_img2img = true;
        // Pure txt2img through the inpaint pipeline: fully renoise.
        denoise_strength = 1.0f;
      }

      // --- Mask: user-supplied, possibly intersected with aspect mask -----
      if (json.contains("mask")) {
        try {
          if (!request_img2img) throw std::runtime_error("mask requires image");
          request_has_mask = true;
          user_supplied_mask = true;
          std::string mask_b64 = json["mask"].get<std::string>();
          std::string dec_mask_str = base64_decode(mask_b64);
          std::vector<uint8_t> dec_mask_buf(dec_mask_str.begin(),
                                            dec_mask_str.end());
          std::vector<uint8_t> mask_pix_lat_rgb, mask_pix_full_rgb;
          decode_image(dec_mask_buf, mask_pix_lat_rgb, sample_width,
                       sample_height);
          decode_image(dec_mask_buf, mask_pix_full_rgb, output_width,
                       output_height);
          if (mask_pix_lat_rgb.empty() || mask_pix_full_rgb.empty())
            throw std::runtime_error("Mask decode empty");
          std::vector<int> mlat_shape = {sample_height, sample_width, 3};
          xt::xarray<uint8_t> xmlat_u8 =
              xt::adapt(mask_pix_lat_rgb, mlat_shape);
          xt::xarray<float> xmlat_f = xt::mean(xt::cast<float>(xmlat_u8), {2});
          xmlat_f = xt::eval(xmlat_f / 255.0f);
          xmlat_f =
              xt::reshape_view(xmlat_f, {1, 1, sample_height, sample_width});
          xt::xarray<float> xmlat_f_4 = xt::concatenate(
              xt::xtuple(xmlat_f, xmlat_f, xmlat_f, xmlat_f), 1);
          mask_data.assign(xmlat_f_4.begin(), xmlat_f_4.end());

          std::vector<int> mfull_shape = {output_height, output_width, 3};
          xt::xarray<uint8_t> xmfull_u8 =
              xt::adapt(mask_pix_full_rgb, mfull_shape);
          xt::xarray<float> xmfull_f =
              xt::mean(xt::cast<float>(xmfull_u8), {2});
          xmfull_f = xt::eval(xmfull_f / 255.0f);
          xmfull_f =
              xt::reshape_view(xmfull_f, {1, 1, output_height, output_width});
          xt::xarray<float> xmfull_f_3 =
              xt::concatenate(xt::xtuple(xmfull_f, xmfull_f, xmfull_f), 1);
          mask_data_full.assign(xmfull_f_3.begin(), xmfull_f_3.end());
        } catch (const std::exception &e) {
          throw std::invalid_argument("Err proc mask: " +
                                      std::string(e.what()));
        }
      }

      // --- Aspect padding mask --------------------------------------------
      // Install or intersect with the centered paint rectangle (computed
      // above). If a user mask was supplied we zero out everything outside
      // it so the user can never paint outside the visible crop area;
      // otherwise we install the paint rect directly so the outer black
      // border is preserved through every diffusion step. Latent (1/8)
      // bounds use floor(origin) and ceil(end) to fully cover the
      // pixel-space paint rect.
      if (aspect_pad_inpaint) {
        int lx0 = paint_x0 / 8;
        int ly0 = paint_y0 / 8;
        int lx1 = std::min(sample_width, (paint_x0 + paint_w + 7) / 8);
        int ly1 = std::min(sample_height, (paint_y0 + paint_h + 7) / 8);

        if (request_has_mask) {
          // Zero out everything outside the paint rectangle.
          for (int c = 0; c < 4; ++c) {
            for (int y = 0; y < sample_height; ++y) {
              float *row = mask_data.data() +
                           ((size_t)c * sample_height + y) * sample_width;
              if (y < ly0 || y >= ly1) {
                std::fill(row, row + sample_width, 0.0f);
              } else {
                std::fill(row, row + lx0, 0.0f);
                std::fill(row + lx1, row + sample_width, 0.0f);
              }
            }
          }
          for (int c = 0; c < 3; ++c) {
            for (int y = 0; y < output_height; ++y) {
              float *row = mask_data_full.data() +
                           ((size_t)c * output_height + y) * output_width;
              if (y < paint_y0 || y >= paint_y0 + paint_h) {
                std::fill(row, row + output_width, 0.0f);
              } else {
                std::fill(row, row + paint_x0, 0.0f);
                std::fill(row + paint_x0 + paint_w, row + output_width, 0.0f);
              }
            }
          }
        } else {
          // No user mask: aspect mask alone, full opacity in the paint rect.
          mask_data.assign((size_t)4 * sample_width * sample_height, 0.0f);
          for (int c = 0; c < 4; ++c) {
            for (int y = ly0; y < ly1; ++y) {
              float *row = mask_data.data() +
                           ((size_t)c * sample_height + y) * sample_width;
              for (int x = lx0; x < lx1; ++x) row[x] = 1.0f;
            }
          }
          mask_data_full.assign((size_t)3 * output_width * output_height, 0.0f);
          for (int c = 0; c < 3; ++c) {
            for (int y = paint_y0; y < paint_y0 + paint_h; ++y) {
              float *row = mask_data_full.data() +
                           ((size_t)c * output_height + y) * output_width;
              for (int x = paint_x0; x < paint_x0 + paint_w; ++x) row[x] = 1.0f;
            }
          }
          request_has_mask = true;
        }
      }
      std::cout << "Req Rcvd (globals): P:" << prompt
                << " NP:" << negative_prompt << " S:" << steps << " CFG:" << cfg
                << " Seed:" << seed << " Size:" << output_width << "x"
                << output_height << " Img2Img:" << request_img2img
                << " Mask:" << request_has_mask
                << " Denoise:" << denoise_strength
                << " ShowProcess:" << show_diffusion_process
                << " Stride:" << show_diffusion_stride << std::endl;
      res.set_header("Content-Type", "text/event-stream");
      res.set_header("Cache-Control", "no-cache");
      res.set_header("Connection", "keep-alive");
      res.set_chunked_content_provider(
          "text/event-stream", [&](intptr_t, httplib::DataSink &sink) -> bool {
            try {
              // BKND-PROC-0008: Watchdog — check for hung generation before
              // starting.  A previously stuck pipeline may have left the flag
              // set; force-release if the timeout is exceeded.
              if (g_serverState.checkAndReleaseTimeout(acquireTime)) {
                nlohmann::json err = {
                    {"id",
                     "timeout-" +
                         std::to_string(
                             std::chrono::system_clock::now()
                                 .time_since_epoch()
                                 .count())},
                    {"name", "timeout"},
                    {"errors",
                     {"Generation timed out after " +
                      std::to_string(g_serverState.generation_timeout_secs) +
                      "s"}},
                };
                std::string ev = "event: error\ndata: " + err.dump() + "\n\n";
                sink.write(ev.c_str(), ev.size());
                sink.done();
                return false;
              }
              auto result =
                  generateImage([&sink](int s, int t, const std::string &img) {
                    // BKND-PROC-0008: Track progress via ServerState
                    g_serverState.setProgress(s, t);
                    nlohmann::json p = {
                        {"type", "progress"}, {"step", s}, {"total_steps", t}};
                    if (!img.empty()) {
                      p["image"] = img;
                    }
                    std::string ev =
                        "event: progress\ndata: " + p.dump() + "\n\n";
                    sink.write(ev.c_str(), ev.size());
                  });
              auto enc_start = std::chrono::high_resolution_clock::now();
              std::string image_str_result(result.image_data.begin(),
                                           result.image_data.end());
              std::string enc_img = base64_encode(image_str_result);
              auto enc_end = std::chrono::high_resolution_clock::now();
              std::cout
                  << "Enc time: "
                  << std::chrono::duration_cast<std::chrono::milliseconds>(
                         enc_end - enc_start)
                         .count()
                  << "ms\n";
              nlohmann::json c = {
                  {"type", "complete"},
                  {"image", enc_img},
                  {"seed", seed},
                  {"width", result.width},
                  {"height", result.height},
                  {"channels", result.channels},
                  {"generation_time_ms", result.generation_time_ms},
                  {"first_step_time_ms", result.first_step_time_ms}};
              std::string ev = "event: complete\ndata: " + c.dump() + "\n\n";
              auto send_start = std::chrono::high_resolution_clock::now();
              sink.write(ev.c_str(), ev.size());
              auto send_end = std::chrono::high_resolution_clock::now();
              std::cout
                  << "Image send time: "
                  << std::chrono::duration_cast<std::chrono::milliseconds>(
                         send_end - send_start)
                         .count()
                  << "ms, size: " << ev.size() << " bytes\n";
              sink.done();
              clearBusy();
              return true;
            } catch (const std::exception &e) {
              clearBusy();
              nlohmann::json err = {
                  {"id",
                   "gen-err-" +
                       std::to_string(
                           std::chrono::system_clock::now()
                               .time_since_epoch()
                               .count())},
                  {"name", "generation_error"},
                  {"errors", {std::string(e.what())}},
              };
              std::string ev = "event: error\ndata: " + err.dump() + "\n\n";
              sink.write(ev.c_str(), ev.size());
              sink.done();
              return false;
            }
          });
    } catch (const nlohmann::json::parse_error &e) {
      clearBusy();
      nlohmann::json err = {
          {"id",
           "parse-" +
               std::to_string(
                   std::chrono::system_clock::now()
                       .time_since_epoch()
                       .count())},
          {"name", "invalid_json"},
          {"errors", {std::string(e.what())}},
      };
      res.status = 400;
      res.set_content(err.dump(), "application/json");
    } catch (const std::invalid_argument &e) {
      clearBusy();
      nlohmann::json err = {
          {"id",
           "arg-" +
               std::to_string(
                   std::chrono::system_clock::now()
                       .time_since_epoch()
                       .count())},
          {"name", "invalid_argument"},
          {"errors", {std::string(e.what())}},
      };
      res.status = 400;
      res.set_content(err.dump(), "application/json");
    } catch (const std::exception &e) {
      clearBusy();
      nlohmann::json err = {
          {"id",
           "srv-" +
               std::to_string(
                   std::chrono::system_clock::now()
                       .time_since_epoch()
                       .count())},
          {"name", "server_error"},
          {"errors", {std::string(e.what())}},
      };
      res.status = 500;
      res.set_content(err.dump(), "application/json");
    }
  });

  // Binary protocol upscale endpoint - optimized for performance
  svr.Post("/upscale", [&](const httplib::Request &req,
                           httplib::Response &res) {
    std::unique_ptr<QnnModel> tempUpscalerApp = nullptr;

    // BKND-PROC-0008: Upscaler uses the GPU/QNN — serialize with generation
    // to prevent resource contention.  Returns 503 if busy.
    std::chrono::steady_clock::time_point upscaleAcquireTime;
    if (!g_serverState.acquireBusy(upscaleAcquireTime)) {
      nlohmann::json busy = {
          {"id",
           "busy-" +
               std::to_string(
                   std::chrono::system_clock::now()
                       .time_since_epoch()
                       .count())},
          {"name", "busy"},
          {"errors",
           {"Server is currently processing another request"}},
      };
      res.status = 503;
      res.set_header("Retry-After", "3");
      res.set_content(busy.dump(), "application/json");
      return;
    }
    auto clearUpscaleBusy = [&]() { g_serverState.release(); };

    try {
      // Read parameters from headers
      if (!req.has_header("X-Image-Width")) {
        throw std::invalid_argument("Missing 'X-Image-Width' header");
      }
      if (!req.has_header("X-Image-Height")) {
        throw std::invalid_argument("Missing 'X-Image-Height' header");
      }
      if (!req.has_header("X-Upscaler-Path")) {
        throw std::invalid_argument("Missing 'X-Upscaler-Path' header");
      }

      int original_width = std::stoi(req.get_header_value("X-Image-Width"));
      int original_height = std::stoi(req.get_header_value("X-Image-Height"));
      std::string upscaler_path = req.get_header_value("X-Upscaler-Path");

      // Check if use_opencl header is present (for MNN models)
      bool use_opencl = false;
      if (req.has_header("X-Use-OpenCL")) {
        std::string opencl_str = req.get_header_value("X-Use-OpenCL");
        use_opencl = (opencl_str == "true" || opencl_str == "1");
      }

      // Determine model type based on file extension
      bool is_mnn_model = false;
      if (upscaler_path.size() >= 4) {
        std::string ext = upscaler_path.substr(upscaler_path.size() - 4);
        std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);
        is_mnn_model = (ext == ".mnn");
      }

      QNN_INFO("Binary upscale request: %dx%d, upscaler: %s, type: %s%s",
               original_width, original_height, upscaler_path.c_str(),
               is_mnn_model ? "MNN" : "QNN",
               is_mnn_model && use_opencl ? " (OpenCL)" : "");

      std::vector<uint8_t> image_data(req.body.begin(), req.body.end());

      if (image_data.size() != original_width * original_height * 3) {
        throw std::invalid_argument(
            "Image data size mismatch. Expected " +
            std::to_string(original_width * original_height * 3) +
            " bytes, got " + std::to_string(image_data.size()) + " bytes");
      }

      // Pre-process: resize if shortest edge < 192
      const int min_size = 192;
      int process_width = original_width;
      int process_height = original_height;
      std::vector<uint8_t> process_image = image_data;

      if (std::min(original_width, original_height) < min_size) {
        QNN_INFO("Image too small (%dx%d), resizing to min edge %d",
                 original_width, original_height, min_size);
        process_image =
            resizeImageToMinSize(image_data, original_width, original_height,
                                 min_size, process_width, process_height);
        QNN_INFO("Resized to %dx%d for processing", process_width,
                 process_height);
      }

      auto start_time = std::chrono::high_resolution_clock::now();

      xt::xarray<uint8_t> upscaled;

      if (is_mnn_model) {
        // Use MNN model
        upscaled =
            upscaleImageWithMNN(process_image, process_width, process_height,
                                upscaler_path, use_opencl);
      } else {
        // Use QNN model
        tempUpscalerApp = createQnnModel(upscaler_path, "upscaler");
        if (!tempUpscalerApp) {
          throw std::runtime_error("Failed to create upscaler model from: " +
                                   upscaler_path);
        }

        auto status = sample_app::initializeQnnApp("Upscaler", tempUpscalerApp);
        if (status != EXIT_SUCCESS) {
          throw std::runtime_error("Failed to initialize upscaler model");
        }

        upscaled = upscaleImageWithModel(process_image, process_width,
                                         process_height, tempUpscalerApp);
      }

      auto end_time = std::chrono::high_resolution_clock::now();
      int duration = std::chrono::duration_cast<std::chrono::milliseconds>(
                         end_time - start_time)
                         .count();

      int upscaled_width = process_width * 4;
      int upscaled_height = process_height * 4;

      // Post-process: resize back to target dimensions if needed
      int final_width = original_width * 4;
      int final_height = original_height * 4;
      std::vector<uint8_t> final_rgb(upscaled.begin(), upscaled.end());

      if (upscaled_width != final_width || upscaled_height != final_height) {
        QNN_INFO("Resizing output from %dx%d to %dx%d", upscaled_width,
                 upscaled_height, final_width, final_height);
        final_rgb =
            resizeImageToTarget(final_rgb, upscaled_width, upscaled_height,
                                final_width, final_height);
      }

      auto encode_start = std::chrono::high_resolution_clock::now();
      std::vector<uint8_t> output_jpeg =
          encodeJPEG(final_rgb, final_width, final_height, 95);
      auto encode_end = std::chrono::high_resolution_clock::now();
      int encode_duration =
          std::chrono::duration_cast<std::chrono::milliseconds>(encode_end -
                                                                encode_start)
              .count();

      QNN_INFO("Upscaling completed in %d ms: %dx%d -> %dx%d", duration,
               original_width, original_height, final_width, final_height);
      QNN_INFO("JPEG encoding time: %d ms, size: %zu KB", encode_duration,
               output_jpeg.size() / 1024);

      res.status = 200;
      res.set_content(std::string(output_jpeg.begin(), output_jpeg.end()),
                      "image/jpeg");
      res.set_header("X-Output-Width", std::to_string(final_width));
      res.set_header("X-Output-Height", std::to_string(final_height));
      res.set_header("X-Duration-Ms", std::to_string(duration));
      res.set_header("Access-Control-Expose-Headers",
                     "X-Output-Width,X-Output-Height,X-Duration-Ms");

      // Release the temporary upscaler model
      if (tempUpscalerApp) {
        tempUpscalerApp.reset();
        QNN_INFO("Upscaler model released");
      }
      clearUpscaleBusy();

    } catch (const std::invalid_argument &e) {
      tempUpscalerApp.reset();
      clearUpscaleBusy();
      nlohmann::json err = {
          {"id",
           "upscale-arg-" +
               std::to_string(
                   std::chrono::system_clock::now()
                       .time_since_epoch()
                       .count())},
          {"name", "invalid_argument"},
          {"errors", {std::string(e.what())}},
      };
      res.status = 400;
      res.set_content(err.dump(), "application/json");
    } catch (const std::exception &e) {
      tempUpscalerApp.reset();
      clearUpscaleBusy();
      nlohmann::json err = {
          {"id",
           "upscale-srv-" +
               std::to_string(
                   std::chrono::system_clock::now()
                       .time_since_epoch()
                       .count())},
          {"name", "server_error"},
          {"errors", {std::string(e.what())}},
      };
      res.status = 500;
      res.set_content(err.dump(), "application/json");
    }
  });

  svr.Post("/tokenize", [&](const httplib::Request &req,
                            httplib::Response &res) {
    handleTokenize(req, res, sdxl_mode, text_embedding_size_2,
                   promptProcessor, tokenizer.get());
  });

  // ── Graceful Shutdown ─────────────────────────────────────────────────
  // POST /shutdown  sets the server state to ShuttingDown so queued health
  // checks (Orphan Detection in BackendManager) see the backend as gone.
  // After responding 200, the server exits svr.listen() via svr.stop().
  svr.Post("/shutdown", [&](const httplib::Request &,
                             httplib::Response &res) {
    g_serverState.initiateShutdown();
    nlohmann::json resp = {{"status", "shutting_down"}};
    res.status = 200;
    res.set_content(resp.dump(), "application/json");
    std::cout << "[Server] Shutdown requested via /shutdown" << std::endl;
    // Schedule async stop so the 200 response is sent first.
    std::thread([&svr]() {
      std::this_thread::sleep_for(std::chrono::milliseconds(100));
      svr.stop();
    }).detach();
  });

  std::cout << "Server listening on " << listen_address << ":" << port
            << std::endl;
  svr.listen(listen_address.c_str(), port);

  // --- Cleanup ---
  if (clipSession) clipInterpreter->releaseSession(clipSession);
  clipSession = nullptr;
  if (clip2Session) clip2Interpreter->releaseSession(clip2Session);
  clip2Session = nullptr;
  if (safetyCheckerSession)
    safetyCheckerInterpreter->releaseSession(safetyCheckerSession);
  safetyCheckerSession = nullptr;
  delete clipInterpreter;
  delete clip2Interpreter;
  delete safetyCheckerInterpreter;
  unetApp.reset();
  vaeDecoderApp.reset();
  vaeEncoderApp.reset();

  return EXIT_SUCCESS;
}