// ── BKND-PROC-0008 P4: generateImage() extracted from main.cpp ──
//
//  All macro aliases replaced with explicit parameter access:
//    - req.xxx   for per-request fields (RequestContext)
//    - ctx.conf.xxx / ctx.models.xxx for server-wide state (AppContext)
//    - cur_out_w / cur_out_h / cur_samp_w / cur_samp_h for mutable dims
//
//  Dependencies passed explicitly — no extern globals.

#include "GenerateHandler.hpp"

#include <chrono>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>

#include "tokenizers_cpp.h"

#include "DPMSolverMultistepScheduler.hpp"
#include "EulerAncestralDiscreteScheduler.hpp"
#include "EulerDiscreteScheduler.hpp"
#include "FloatConversion.hpp"
#include "LCMScheduler.hpp"
#include "LaplacianBlend.hpp"
#include "Logger.hpp"
#include "MnnHelper.hpp"
#include "PromptCacheUtils.hpp"
#include "QnnHelper.hpp"
#include "Scheduler.hpp"
#include "SDUtils.hpp"
#include "TextEncoder.hpp"
#include "VaeTilingHelper.hpp"

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

// ── BKND-PROC-0008 P4: Local ScopeExit RAII guard ──
namespace {
struct ScopeExit {
  std::function<void()> fn;
  ~ScopeExit() {
    if (fn) fn();
  }
};
}  // namespace

GenerationResult generateImage(
    const RequestContext &req,
    AppContext &ctx,
    std::function<void(int step, int total_steps,
                       const std::string &image_data)>
        progress_callback) {

  using namespace qnn::tools::sample_app;

  // ── Local aliases for compact access ──
  const auto &conf   = ctx.conf;
  auto       &models = ctx.models;

  // ── Per-request read-only params (via req.xxx) ──
  // ── Mutable output dimension copies (VAE tiling mutates these) ──
  int cur_out_w  = req.output_width;
  int cur_out_h  = req.output_height;
  int cur_samp_w = req.sample_width;
  int cur_samp_h = req.sample_height;

  // ── BKND-PROC-0008 P0: Sync Config.hpp inline globals with per-request
  //     dimensions so that QnnModel methods (which read sample_width/
  //     sample_height / output_width / output_height directly from
  //     Config.hpp) use the correct sizes for this request.
  sample_width  = cur_samp_w;
  sample_height = cur_samp_h;
  output_width  = cur_out_w;
  output_height = cur_out_h;

  // ── Precondition checks ──
  if (req.prompt.empty()) throw std::invalid_argument("Prompt empty");
  if (conf.use_safety_checker && !models.safetyCheckerInterpreter)
    throw std::runtime_error("SafetyChecker missing");
  bool sdxl_lowram = conf.sdxl_mode && conf.lowram_mode;
  if (!conf.use_mnn) {
    if (!conf.sdxl_mode) {
      if (!models.clipInterpreter) throw std::runtime_error("MNN CLIP missing");
    } else if (!sdxl_lowram) {
      if (!models.clipInterpreter || !models.clip2Interpreter)
        throw std::runtime_error("SDXL MNN CLIP interpreters missing");
    }
    if (!sdxl_lowram) {
      if (!models.unetApp) throw std::runtime_error("QNN UNET missing");
      if (!models.vaeDecoderApp) throw std::runtime_error("QNN VAE Dec missing");
      if (req.request_img2img && !models.vaeEncoderApp)
        throw std::runtime_error("QNN VAE Enc missing");
    }
  }
  if (req.request_img2img && req.img_data.size() != 3 * cur_out_w * cur_out_h)
    throw std::invalid_argument("Invalid img_data");
  if (req.request_has_mask &&
      (req.mask_data.size() != 4 * cur_samp_w * cur_samp_h ||
       req.mask_data_full.size() != 3 * cur_out_w * cur_out_h))
    throw std::invalid_argument("Invalid mask_data*");

  // Catch-all guard: in lowram mode, release any model still loaded when this
  // function exits (normal return or exception).
  ScopeExit lowramReleaseGuard;
  if (sdxl_lowram) {
    lowramReleaseGuard.fn = [&]() {
      if (models.clipInterpreter || models.clip2Interpreter)
        releaseSdxlClipMnn(ctx);
      if (models.unetApp) releaseSdxlQnnUnet(ctx);
      if (models.vaeDecoderApp) releaseSdxlQnnVaeDecoder(ctx);
      if (models.vaeEncoderApp) releaseSdxlQnnVaeEncoder(ctx);
    };
  }

  try {
    auto start_time = std::chrono::high_resolution_clock::now();
    int first_step_time_ms = 0;
    int total_run_steps = req.steps + (req.request_img2img ? 1 : 0) + 2;
    int current_step = 0;
    const int batch_size = 2;

    // --- CLIP ---
    std::vector<float> text_embedding_float(batch_size * 77 *
                                            text_embedding_size);

    const int sdxl_concat_dim =
        text_embedding_size + text_embedding_size_2;
    std::vector<float> sdxl_encoder_hidden_states;
    std::vector<float> sdxl_text_embeds;
    std::vector<float> sdxl_time_ids;
    if (conf.sdxl_mode) {
      sdxl_encoder_hidden_states.assign(batch_size * 77 * sdxl_concat_dim,
                                        0.0f);
      sdxl_text_embeds.assign(batch_size * text_embedding_size_2, 0.0f);
      sdxl_time_ids.assign(batch_size * 6, 0.0f);
      for (int b = 0; b < batch_size; b++) {
        sdxl_time_ids[b * 6 + 0] = (float)cur_out_h;  // original_size h
        sdxl_time_ids[b * 6 + 1] = (float)cur_out_w;  // original_size w
        sdxl_time_ids[b * 6 + 2] = 0.0f;              // crop_top
        sdxl_time_ids[b * 6 + 3] = 0.0f;              // crop_left
        sdxl_time_ids[b * 6 + 4] = (float)cur_out_h;  // target_size h
        sdxl_time_ids[b * 6 + 5] = (float)cur_out_w;  // target_size w
      }
    }

    auto clip_start = std::chrono::high_resolution_clock::now();

    std::string prompt_cache_dir = ensureCacheDir(conf.modelDir);
    bool neg_has_emb =
        promptHasEmbedding(models.promptProcessor, req.negative_prompt);
    bool pos_has_emb =
        promptHasEmbedding(models.promptProcessor, req.prompt);
    bool neg_cache_eligible = !prompt_cache_dir.empty() && !neg_has_emb;
    bool pos_cache_eligible = !prompt_cache_dir.empty() && !pos_has_emb;

    const uint32_t cache_mode =
        conf.sdxl_mode ? prompt_cache::kModeSdxl : prompt_cache::kModeSd15;
    const uint32_t cache_hidden_dim =
        conf.sdxl_mode ? (uint32_t)sdxl_concat_dim : (uint32_t)text_embedding_size;
    const uint32_t cache_pooled_dim =
        conf.sdxl_mode ? (uint32_t)text_embedding_size_2 : 0u;

    float *neg_hidden_dst = conf.sdxl_mode ? sdxl_encoder_hidden_states.data()
                                           : text_embedding_float.data();
    float *pos_hidden_dst =
        conf.sdxl_mode ? sdxl_encoder_hidden_states.data() + 77 * sdxl_concat_dim
                     : text_embedding_float.data() + 77 * text_embedding_size;
    float *neg_pooled_dst = conf.sdxl_mode ? sdxl_text_embeds.data() : nullptr;
    float *pos_pooled_dst =
        conf.sdxl_mode ? sdxl_text_embeds.data() + text_embedding_size_2 : nullptr;

    bool neg_hit =
        neg_cache_eligible &&
        loadPromptCache(prompt_cache_dir, req.negative_prompt, cache_mode,
                        cache_hidden_dim, cache_pooled_dim, neg_hidden_dst,
                        neg_pooled_dst);
    bool pos_hit =
        pos_cache_eligible &&
        loadPromptCache(prompt_cache_dir, req.prompt, cache_mode, cache_hidden_dim,
                        cache_pooled_dim, pos_hidden_dst, pos_pooled_dst);

    if (neg_hit) QNN_INFO("Prompt cache hit (negative)");
    if (pos_hit) QNN_INFO("Prompt cache hit (positive)");

    if (neg_hit && pos_hit) {
      QNN_INFO("CLIP cache hit (both sides), skipping CLIP inference");
    } else {
      ProcessedPromptPair processed =
          processPromptPair(ctx, req.prompt, req.negative_prompt, 77);

      std::vector<int> clip_input_ids = processed.ids;
      auto parsed_input_text = models.tokenizer->Decode(clip_input_ids);
      QNN_INFO("Parsed Input Text: %s", parsed_input_text.c_str());

      float *embed_ptr = text_embedding_float.data();

      if (conf.sdxl_mode) {
        if (sdxl_lowram) loadSdxlClipMnnIfNeeded(ctx);
        if (!models.clipInterpreter || !models.clip2Interpreter)
          throw std::runtime_error("SDXL CLIP interpreters not initialized!");

        auto run_sdxl_clip = [&](const std::vector<float> &emb1,
                                 const std::vector<float> &emb2,
                                 const int *ids77,
                                 float *out_hidden_concat /*77*2048*/,
                                 float *out_pooled /*1280*/) {
          auto in1 =
              models.clipInterpreter->getSessionInput(models.clipSession, "input_embedding");
          memcpy(in1->host<float>(), emb1.data(),
                 77 * text_embedding_size * sizeof(float));
          models.clipInterpreter->runSession(models.clipSession);
          auto out1 = models.clipInterpreter->getSessionOutput(models.clipSession,
                                                        "last_hidden_state");
          const float *out1_data = out1->host<float>();

          auto in2 = models.clip2Interpreter->getSessionInput(models.clip2Session,
                                                       "input_embedding");
          memcpy(in2->host<float>(), emb2.data(),
                 77 * text_embedding_size_2 * sizeof(float));
          models.clip2Interpreter->runSession(models.clip2Session);
          auto out2_hidden = models.clip2Interpreter->getSessionOutput(
              models.clip2Session, "last_hidden_state");
          auto out2_pool =
              models.clip2Interpreter->getSessionOutput(models.clip2Session, "pooled_output");
          const float *out2_hidden_data = out2_hidden->host<float>();
          const float *out2_pool_data = out2_pool->host<float>();

          for (int t = 0; t < 77; t++) {
            memcpy(out_hidden_concat + t * sdxl_concat_dim,
                   out1_data + t * text_embedding_size,
                   text_embedding_size * sizeof(float));
            memcpy(
                out_hidden_concat + t * sdxl_concat_dim + text_embedding_size,
                out2_hidden_data + t * text_embedding_size_2,
                text_embedding_size_2 * sizeof(float));
          }
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
        if (sdxl_lowram) releaseSdxlClipMnn(ctx);
      } else {
        MNN::Interpreter *currentClipInterpreter = models.clipInterpreter;
        MNN::Session *currentClipSession = models.clipSession;
        bool dynamicCreated = false;

        if (conf.use_mnn) {
          currentClipInterpreter = createMnnInterpreterMmap(conf.clipPath.c_str());
          if (!currentClipInterpreter)
            throw std::runtime_error(
                "Failed to create temporary MNN CLIP interpreter!");
          currentClipSession = nullptr;
          dynamicCreated = true;
        } else if (!currentClipInterpreter) {
          throw std::runtime_error("clipInterpreter not initialized!");
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

      if (!neg_hit && neg_cache_eligible) {
        savePromptCache(prompt_cache_dir, req.negative_prompt, cache_mode,
                        cache_hidden_dim, cache_pooled_dim, neg_hidden_dst,
                        neg_pooled_dst);
      }
      if (!pos_hit && pos_cache_eligible) {
        savePromptCache(prompt_cache_dir, req.prompt, cache_mode, cache_hidden_dim,
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
    const char *timestep_spacing = conf.sdxl_mode ? "trailing" : "leading";

    // Map denoise_curve (API "scheduler") to beta_schedule and use_karras
    std::string beta_schedule = "scaled_linear";  // HuggingFace diffusers default
    bool use_karras = false;
    if (req.denoise_curve == "karras") {
      use_karras = true;
    } else if (req.denoise_curve == "linear") {
      beta_schedule = "linear";
    }
    // else: "scaled_linear" → both defaults

    if (req.sampler_type == "euler_a" || req.sampler_type == "eulera") {
      scheduler = std::make_unique<EulerAncestralDiscreteScheduler>(
          1000, 0.00085f, 0.012f, beta_schedule, "epsilon", timestep_spacing,
          0, false, use_karras);
    } else if (req.sampler_type == "euler") {
      scheduler = std::make_unique<EulerDiscreteScheduler>(
          1000, 0.00085f, 0.012f, beta_schedule, "epsilon", timestep_spacing,
          0, false, use_karras);
    } else if (req.sampler_type == "lcm") {
      scheduler = std::make_unique<LCMScheduler>(1000, 0.00085f, 0.012f,
                                                 beta_schedule, "epsilon", 50,
                                                 10.0f, true, false);
    } else if (req.sampler_type == "dpm_sde") {
      // DPMSolver only supports "scaled_linear" beta_schedule
      scheduler = std::make_unique<DPMSolverMultistepScheduler>(
          1000, 0.00085f, 0.012f, "scaled_linear", 2, "epsilon",
          timestep_spacing, use_karras, "sde-dpmsolver++");
    } else {
      // default: "dpm"
      scheduler = std::make_unique<DPMSolverMultistepScheduler>(
          1000, 0.00085f, 0.012f, "scaled_linear", 2, "epsilon",
          timestep_spacing, use_karras);
    }
    if (conf.use_v_pred) scheduler->set_prediction_type("v_prediction");
    scheduler->set_timesteps(req.steps);
    xt::xarray<float> timesteps = scheduler->get_timesteps();
    const float vae_scale = conf.sdxl_mode ? 0.13025f : 0.18215f;
    std::vector<int> shape = {1, 4, cur_samp_h, cur_samp_w};
    std::vector<int> shape_batch2 = {batch_size, 4, cur_samp_h,
                                     cur_samp_w};
    xt::random::seed(req.seed);
    xt::xarray<float> latents = xt::random::randn<float>(shape);
    xt::xarray<float> latents_noise = xt::random::randn<float>(shape);

    float init_noise_sigma = scheduler->get_init_noise_sigma();
    latents = latents * init_noise_sigma;

    xt::xarray<float> original_latents, original_image, mask, mask_full;
    int start_step = 0;

    // --- Img2Img / VAE Encode ---
    if (req.request_img2img) {
      auto vae_enc_start = std::chrono::high_resolution_clock::now();
      std::vector<int> img_shape = {1, 3, cur_out_h, cur_out_w};
      original_image = xt::adapt(req.img_data, img_shape);

      bool need_vae_enc_tiling = ((cur_out_w > 512 || cur_out_h > 512) &&
                                  !conf.use_mnn && models.vaeEncoderApp && !conf.sdxl_mode);

      xt::xarray<float> img_lat_scaled;

      if (!need_vae_enc_tiling) {
        std::vector<float> vae_enc_mean(1 * 4 * cur_samp_w * cur_samp_h);
        std::vector<float> vae_enc_std(1 * 4 * cur_samp_w * cur_samp_h);

        std::string black_latent_cache_path;
        bool loaded_from_cache = false;
        if (req.aspect_pad_inpaint && req.aspect_pad_synthetic_base &&
            !conf.modelDir.empty()) {
          auto cache_dir = ensureCacheDir(conf.modelDir);
          if (!cache_dir.empty()) {
            black_latent_cache_path = cache_dir + "/aspect_latent_" +
                                      std::to_string(req.target_crop_width) + "x" +
                                      std::to_string(req.target_crop_height) +
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
          if (conf.use_mnn) {
            MNN::Interpreter *currentVaeEncoderInterpreter =
                createMnnInterpreterMmap(conf.vaeEncoderPath.c_str());
            if (!currentVaeEncoderInterpreter)
              throw std::runtime_error("Failed MNN VAE Enc create");

            MNN::ScheduleConfig cfg_vae_enc;
            MNN::BackendConfig bkCfg_vae_enc;
            if (req.use_opencl) {
              auto cache_dir = ensureCacheDir(conf.modelDir);
              auto cache_file = (cache_dir.empty() ? conf.modelDir : cache_dir) +
                                "/vae_enc_cache.mnnc." +
                                std::to_string(cur_out_w);
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
                input, {1, 3, cur_out_h, cur_out_w});
            currentVaeEncoderInterpreter->resizeSession(currentVaeEncSession);
            if (req.use_opencl) {
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

            memcpy(input_nchw_tensor->host<float>(), req.img_data.data(),
                   req.img_data.size() * sizeof(float));
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
            if (sdxl_lowram) loadSdxlQnnVaeEncoderIfNeeded(ctx);
            if (!models.vaeEncoderApp)
              throw std::runtime_error("vaeEncoderApp not init!");
            if (conf.sdxl_mode) {
              if (StatusCode::SUCCESS !=
                  models.vaeEncoderApp->executeVaeEncoderGraphsSDXL(
                      const_cast<float*>(req.img_data.data()), vae_enc_mean.data(), vae_enc_std.data()))
                throw std::runtime_error("QNN VAE enc SDXL exec failed");
            } else {
              if (StatusCode::SUCCESS !=
                  models.vaeEncoderApp->executeVaeEncoderGraphs(
                      const_cast<float*>(req.img_data.data()), vae_enc_mean.data(), vae_enc_std.data()))
                throw std::runtime_error("QNN VAE enc exec failed");
            }
            if (sdxl_lowram) releaseSdxlQnnVaeEncoder(ctx);
          }

          if (req.aspect_pad_inpaint && !black_latent_cache_path.empty()) {
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
        std::cout << "Using VAE encoder tiling for " << cur_out_w << "x"
                  << cur_out_h << " input..." << std::endl;

        const int vae_enc_tile_size = 512;
        const int vae_enc_latent_tile_size = 64;

        auto [img_positions, latent_positions, img_overlap_x, img_overlap_y,
              latent_overlap_x, latent_overlap_y] =
            calculateVaeTilePositions(cur_out_w, cur_out_h);

        int num_tiles = img_positions.size();
        std::cout << "VAE encoder will use " << num_tiles
                  << " tiles with overlap " << img_overlap_x << "x"
                  << img_overlap_y << "px (latent: " << latent_overlap_x << "x"
                  << latent_overlap_y << ")" << std::endl;

        int original_output_width = cur_out_w;
        int original_output_height = cur_out_h;
        int original_sample_width = cur_samp_w;
        int original_sample_height = cur_samp_h;

        cur_out_w = vae_enc_tile_size;
        cur_out_h = vae_enc_tile_size;
        cur_samp_w = vae_enc_latent_tile_size;
        cur_samp_h = vae_enc_latent_tile_size;
        // Sync Config.hpp globals so QnnModel methods see tile sizes
        output_width  = cur_out_w;
        output_height = cur_out_h;
        sample_width  = cur_samp_w;
        sample_height = cur_samp_h;

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

          if (!models.vaeEncoderApp)
            throw std::runtime_error("vaeEncoderApp not init!");

          if (StatusCode::SUCCESS !=
              models.vaeEncoderApp->executeVaeEncoderGraphs(tile_img_vec.data(),
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

        cur_out_w = original_output_width;
        cur_out_h = original_output_height;
        cur_samp_w = original_sample_width;
        cur_samp_h = original_sample_height;
        // Restore Config.hpp globals to original request dimensions
        output_width  = cur_out_w;
        output_height = cur_out_h;
        sample_width  = cur_samp_w;
        sample_height = cur_samp_h;

        xt::xarray<float> img_lat = blendVaeEncoderTiles(
            encoded_tiles_mean_std, latent_positions, cur_samp_h,
            cur_samp_w, vae_enc_latent_tile_size, latent_overlap_x,
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
      start_step = req.steps * (1.0f - req.denoising_strength);
      if (start_step >= req.steps) start_step = req.steps - 1;
      if (start_step < 0) start_step = 0;
      total_run_steps -= start_step;
      scheduler->set_begin_index(start_step);
      xt::xarray<int> t = {(int)(timesteps(start_step))};

      xt::xarray<float> pure_noise_latents;
      if (req.aspect_pad_synthetic_base) {
        pure_noise_latents = xt::eval(latents);
      }

      latents = scheduler->add_noise(original_latents, latents_noise, t);

      if (req.request_has_mask) {
        mask = xt::adapt(req.mask_data, {1, 4, cur_samp_h, cur_samp_w});
        mask_full =
            xt::adapt(req.mask_data_full, {1, 3, cur_out_h, cur_out_w});

        if (req.aspect_pad_synthetic_base) {
          latents =
              xt::eval(pure_noise_latents * mask + latents * (1.0f - mask));
        }
      }

      current_step++;
      progress_callback(current_step, total_run_steps, "");
    }  // --- UNET Denoising Loop ---
    int single_latent_size = 1 * 4 * cur_samp_w * cur_samp_h;

    MNN::Interpreter *currentUnetInterpreter = nullptr;
    MNN::Session *currentUnetSession = nullptr;

    if (conf.use_mnn) {
      currentUnetInterpreter = createMnnInterpreterMmap(conf.unetPath.c_str());
      if (!currentUnetInterpreter)
        throw std::runtime_error(
            "Failed to create temporary MNN UNET interpreter!");

      MNN::ScheduleConfig cfg_unet;
      MNN::BackendConfig bkCfg_unet;
      if (req.use_opencl) {
        auto cache_dir = ensureCacheDir(conf.modelDir);
        auto cache_file = (cache_dir.empty() ? conf.modelDir : cache_dir) +
                          "/unet_cache.mnnc." + std::to_string(cur_out_w);
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
          samp, {batch_size, 4, cur_samp_h, cur_samp_w});
      currentUnetInterpreter->resizeTensor(ts, {1});
      currentUnetInterpreter->resizeTensor(
          enc, {batch_size, 77, text_embedding_size});
      currentUnetInterpreter->resizeSession(currentUnetSession);
      if (req.use_opencl) {
        currentUnetInterpreter->updateCacheFile(currentUnetSession);
      }

      currentUnetInterpreter->releaseModel();
    }

    if (sdxl_lowram) loadSdxlQnnUnetIfNeeded(ctx);

    for (int i = start_step; i < timesteps.size(); ++i) {
      // BJKL-BUGFIX-0001: Preview VAE decode during UNET loop removed.
      // Running VAE decoder in the middle of UNET iteration corrupts the
      // NPU state (QNN models share runtime resources), causing garbled
      // final output. The Android client already ignores preview images
      // in SSE progress events, so this had zero functional benefit.
      progress_callback(current_step, total_run_steps, "");

      auto step_start_time = std::chrono::high_resolution_clock::now();

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

      if (conf.use_mnn) {
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

        memcpy(samp_nchw_tensor->host<float>(), latents_in_vec.data(),
               latents_in_vec.size() * sizeof(float));
        memcpy(ts_nchw_tensor->host<int>(), &current_ts_int, sizeof(int));
        memcpy(enc_nchw_tensor->host<float>(), text_embedding_float.data(),
               text_embedding_float.size() * sizeof(float));

        samp->copyFromHostTensor(samp_nchw_tensor);
        ts->copyFromHostTensor(ts_nchw_tensor);
        enc->copyFromHostTensor(enc_nchw_tensor);

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
        if (!models.unetApp)
          throw std::runtime_error("unetApp not initialized!");

        float *latents_in_ptr = latents_in_vec.data();
        float *latents_out_ptr = unet_out_latents.data();

        const bool skip_uncond = (req.cfg_scale == 1.0f);

        if (conf.sdxl_mode) {
          float *hidden_ptr = sdxl_encoder_hidden_states.data();
          float *pooled_ptr = sdxl_text_embeds.data();
          float *time_ids_ptr = sdxl_time_ids.data();
          const int hidden_stride = 77 * sdxl_concat_dim;
          const int pooled_stride = text_embedding_size_2;
          const int time_ids_stride = 6;

          if (!skip_uncond &&
              StatusCode::SUCCESS !=
                  models.unetApp->executeUnetGraphsSDXL(
                      latents_in_ptr, static_cast<int>(current_ts), hidden_ptr,
                      pooled_ptr, time_ids_ptr, latents_out_ptr))
            throw std::runtime_error("QNN UNET SDXL exec failed (uncond)");

          if (StatusCode::SUCCESS !=
              models.unetApp->executeUnetGraphsSDXL(
                  latents_in_ptr + single_latent_size,
                  static_cast<int>(current_ts), hidden_ptr + hidden_stride,
                  pooled_ptr + pooled_stride, time_ids_ptr + time_ids_stride,
                  latents_out_ptr + single_latent_size))
            throw std::runtime_error("QNN UNET SDXL exec failed (cond)");
        } else {
          float *embed_ptr = text_embedding_float.data();

          if (!skip_uncond &&
              StatusCode::SUCCESS !=
                  models.unetApp->executeUnetGraphs(latents_in_ptr,
                                             static_cast<int>(current_ts),
                                             embed_ptr, latents_out_ptr))
            throw std::runtime_error("QNN UNET exec failed (uncond)");

          if (StatusCode::SUCCESS !=
              models.unetApp->executeUnetGraphs(latents_in_ptr + single_latent_size,
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
      if (!conf.use_mnn && req.cfg_scale == 1.0f) {
        std::vector<float> cond_only(
            unet_out_latents.begin() + single_latent_size,
            unet_out_latents.end());
        noise_pred = xt::adapt(cond_only, shape);
      } else {
        xt::xarray<float> noise_pred_batch =
            xt::adapt(unet_out_latents, shape_batch2);
        xt::xarray<float> uncond = xt::view(noise_pred_batch, 0);
        xt::xarray<float> txt = xt::view(noise_pred_batch, 1);
        noise_pred = xt::eval(uncond + req.cfg_scale * (txt - uncond));
      }
      latents = scheduler->step(noise_pred, timesteps(i), latents).prev_sample;

      if (req.request_has_mask) {
        xt::xarray<int> t_xt = {(int)(timesteps(i))};
        xt::xarray<float> orig_noised =
            scheduler->add_noise(original_latents, latents_noise, t_xt);
        latents = xt::eval(orig_noised * (1.0f - mask) + latents * mask);
      }

      current_step++;
    }

    if (conf.use_mnn) {
      if (currentUnetSession)
        currentUnetInterpreter->releaseSession(currentUnetSession);
      if (currentUnetInterpreter) delete currentUnetInterpreter;
    }

    if (sdxl_lowram) releaseSdxlQnnUnet(ctx);

    // --- VAE Decode ---
    auto vae_dec_start = std::chrono::high_resolution_clock::now();

    bool need_vae_tiling =
        ((cur_out_w > 512 || cur_out_h > 512) && !conf.use_mnn && !conf.sdxl_mode);
    if (need_vae_tiling) {
      std::cout << "Using VAE decoder tiling for " << cur_out_w << "x"
                << cur_out_h << " output..." << std::endl;
    }

    latents = xt::eval((1.0 / vae_scale) * latents);

    xt::xarray<float> pixels;

    if (!need_vae_tiling) {
      std::vector<float> vae_dec_in_vec(latents.begin(), latents.end());
      std::vector<float> vae_dec_out_pixels(1 * 3 * cur_out_w *
                                            cur_out_h);

      if (conf.use_mnn) {
        MNN::Interpreter *currentVaeDecoderInterpreter =
            createMnnInterpreterMmap(conf.vaeDecoderPath.c_str());

        if (!currentVaeDecoderInterpreter)
          throw std::runtime_error(
              "Failed to create temporary MNN VAE Decoder interpreter!");

        MNN::ScheduleConfig cfg_vae;
        MNN::BackendConfig bkCfg_vae;
        if (req.use_opencl) {
          auto cache_dir = ensureCacheDir(conf.modelDir);
          auto cache_file = (cache_dir.empty() ? conf.modelDir : cache_dir) +
                            "/vae_dec_cache.mnnc." +
                            std::to_string(cur_out_w);
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
            input, {1, 4, cur_samp_h, cur_samp_w});
        currentVaeDecoderInterpreter->resizeSession(currentVaeDecSession);
        if (req.use_opencl) {
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
        if (sdxl_lowram) loadSdxlQnnVaeDecoderIfNeeded(ctx);
        if (!models.vaeDecoderApp)
          throw std::runtime_error("vaeDecoderApp not init!");

        if (conf.sdxl_mode) {
          if (StatusCode::SUCCESS !=
              models.vaeDecoderApp->executeVaeDecoderGraphsSDXL(
                  vae_dec_in_vec.data(), vae_dec_out_pixels.data()))
            throw std::runtime_error("QNN VAE dec SDXL exec failed");
        } else {
          if (StatusCode::SUCCESS !=
              models.vaeDecoderApp->executeVaeDecoderGraphs(vae_dec_in_vec.data(),
                                                     vae_dec_out_pixels.data()))
            throw std::runtime_error("QNN VAE dec exec failed");
        }
        if (sdxl_lowram) releaseSdxlQnnVaeDecoder(ctx);
      }

      // ── Diagnostic: dump raw VAE decoder output stats (gated by QNN_DEBUG) ──
      float vmin = vae_dec_out_pixels[0], vmax = vae_dec_out_pixels[0];
      for (auto v : vae_dec_out_pixels) {
        if (v < vmin) vmin = v;
        if (v > vmax) vmax = v;
      }
      size_t total = vae_dec_out_pixels.size();
      size_t expected = (size_t)1 * 3 * cur_out_w * cur_out_h;
      QNN_DEBUG("[DIAG] VAE dec raw output: %zu floats (expect %zu for %dx%d), range [%.4f, %.4f]",
                total, expected, cur_out_w, cur_out_h, vmin, vmax);
      // Sample first pixel (R,G,B interleaved if NCHW)
      float r0 = vae_dec_out_pixels[0];                          // ch0, pix0
      float g0 = vae_dec_out_pixels[cur_out_h * cur_out_w];       // ch1, pix0
      float b0 = vae_dec_out_pixels[2 * cur_out_h * cur_out_w];   // ch2, pix0
      QNN_DEBUG("[DIAG] VAE dec pixel(0,0) NCHW: R=%.4f G=%.4f B=%.4f", r0, g0, b0);
      // Also sample as if NHWC (offset 0,1,2)
      QNN_DEBUG("[DIAG] VAE dec pixel(0,0) NHWC: R=%.4f G=%.4f B=%.4f",
                vae_dec_out_pixels[0], vae_dec_out_pixels[1], vae_dec_out_pixels[2]);
      // Sample last pixel to verify dimensions
      int dx = cur_out_w - 1, dy = cur_out_h - 1;
      QNN_DEBUG("[DIAG] VAE dec pixel(%d,%d) NCHW: R=%.4f G=%.4f B=%.4f",
                dy, dx,
                vae_dec_out_pixels[dy * cur_out_w + dx],
                vae_dec_out_pixels[cur_out_h * cur_out_w + dy * cur_out_w + dx],
                vae_dec_out_pixels[2 * cur_out_h * cur_out_w + dy * cur_out_w + dx]);

      std::vector<int> pixel_shape = {1, 3, cur_out_h, cur_out_w};
      pixels = xt::adapt(vae_dec_out_pixels, pixel_shape);

    } else {
      const int vae_tile_size = 512;
      const int vae_latent_tile_size = 64;

      auto [output_positions, latent_positions, overlap_x, overlap_y,
            latent_overlap_x, latent_overlap_y] =
          calculateVaeTilePositions(cur_out_w, cur_out_h);

      int num_tiles = output_positions.size();
      std::cout << "VAE decoder will use " << num_tiles
                << " tiles with overlap " << overlap_x << "x" << overlap_y
                << "px (latent: " << latent_overlap_x << "x" << latent_overlap_y
                << ")" << std::endl;

      int original_output_width = cur_out_w;
      int original_output_height = cur_out_h;
      int original_sample_width = cur_samp_w;
      int original_sample_height = cur_samp_h;

      cur_out_w = vae_tile_size;
      cur_out_h = vae_tile_size;
      cur_samp_w = vae_latent_tile_size;
      cur_samp_h = vae_latent_tile_size;
      // Sync Config.hpp globals so QnnModel methods see tile sizes
      output_width  = cur_out_w;
      output_height = cur_out_h;
      sample_width  = cur_samp_w;
      sample_height = cur_samp_h;

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

        if (!models.vaeDecoderApp)
          throw std::runtime_error("vaeDecoderApp not init!");

        if (StatusCode::SUCCESS !=
            models.vaeDecoderApp->executeVaeDecoderGraphs(tile_latent_vec.data(),
                                                   tile_output.data()))
          throw std::runtime_error("QNN VAE dec exec failed for tile");

        decoded_tiles.push_back(std::move(tile_output));

        std::cout << "Processed VAE tile " << i + 1 << "/"
                  << latent_positions.size() << std::endl;
      }

      cur_out_w = original_output_width;
      cur_out_h = original_output_height;
      cur_samp_w = original_sample_width;
      cur_samp_h = original_sample_height;
      // Restore Config.hpp globals to original request dimensions
      output_width  = cur_out_w;
      output_height = cur_out_h;
      sample_width  = cur_samp_w;
      sample_height = cur_samp_h;

      pixels = blendVaeOutputTiles(decoded_tiles, output_positions,
                                      cur_out_h, cur_out_w,
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
    if (req.request_has_mask && req.user_supplied_mask) {
      if (req.aspect_pad_inpaint) {
        int px0 = (cur_out_w - req.target_crop_width) / 2;
        int py0 = (cur_out_h - req.target_crop_height) / 2;
        xt::xarray<float> orig_crop =
            xt::eval(xt::view(original_image, 0, xt::all(),
                              xt::range(py0, py0 + req.target_crop_height),
                              xt::range(px0, px0 + req.target_crop_width)));
        xt::xarray<float> gen_crop = xt::eval(xt::view(
            pixels, 0, xt::all(), xt::range(py0, py0 + req.target_crop_height),
            xt::range(px0, px0 + req.target_crop_width)));
        xt::xarray<float> mask_crop = xt::eval(xt::view(
            mask_full, 0, xt::all(), xt::range(py0, py0 + req.target_crop_height),
            xt::range(px0, px0 + req.target_crop_width)));
        auto blended = laplacianPyramidBlend(orig_crop, gen_crop, mask_crop);
        auto target_view = xt::view(pixels, 0, xt::all(),
                                    xt::range(py0, py0 + req.target_crop_height),
                                    xt::range(px0, px0 + req.target_crop_width));
        target_view = xt::reshape_view(
            blended, {3, req.target_crop_height, req.target_crop_width});
      } else {
        auto orig_img_view = xt::view(original_image, 0);
        auto gen_img_view = xt::view(pixels, 0);
        auto mask_view = xt::view(mask_full, 0);

        auto blended =
            laplacianPyramidBlend(orig_img_view, gen_img_view, mask_view);
        pixels = xt::reshape_view(blended, {1, 3, cur_out_h, cur_out_w});
      }
    }
    auto img = xt::view(pixels, 0);
    auto transp = xt::transpose(img, {1, 2, 0});
    auto norm = xt::clip(((transp + 1.0) / 2.0) * 255.0, 0.0, 255.0);
    xt::xarray<uint8_t> u8_img = xt::cast<uint8_t>(norm);
    std::vector<uint8_t> out_data(u8_img.begin(), u8_img.end());

    // ── Diagnostic: verify post-processed pixel bytes (gated by QNN_DEBUG) ──
    QNN_DEBUG("[DIAG] out_data size: %zu bytes (expect %d for %dx%dx3)",
              out_data.size(), cur_out_w * cur_out_h * 3, cur_out_w, cur_out_h);
    {
      size_t off_tl = 0;  // (0,0)
      QNN_DEBUG("[DIAG] out_data top-left (0,0): R=%d G=%d B=%d",
                (int)out_data[off_tl], (int)out_data[off_tl+1], (int)out_data[off_tl+2]);
      size_t off_cc = ((size_t)(cur_out_h/2) * cur_out_w + (cur_out_w/2)) * 3;
      QNN_DEBUG("[DIAG] out_data center (%d,%d): R=%d G=%d B=%d",
                cur_out_w/2, cur_out_h/2,
                (int)out_data[off_cc], (int)out_data[off_cc+1], (int)out_data[off_cc+2]);
      size_t off_br = ((size_t)(cur_out_h-1) * cur_out_w + (cur_out_w-1)) * 3;
      QNN_DEBUG("[DIAG] out_data bottom-right (%d,%d): R=%d G=%d B=%d",
                cur_out_w-1, cur_out_h-1,
                (int)out_data[off_br], (int)out_data[off_br+1], (int)out_data[off_br+2]);
    }

    int final_width = cur_out_w;
    int final_height = cur_out_h;

    // --- Safety Checker ---
    if (conf.use_safety_checker) {
      auto safety_start = std::chrono::high_resolution_clock::now();
      float score = 0.0f;

      if (safety_check(out_data, cur_out_w, cur_out_h, score,
                       models.safetyCheckerInterpreter, models.safetyCheckerSession)) {
        std::cout << "NSFW Score: " << score << std::endl;
        if (score > conf.nsfw_threshold) {
          QNN_WARN("NSFW detected (%.2f>%.2f).", score, conf.nsfw_threshold);
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

    if (req.aspect_pad_inpaint && req.target_crop_width > 0 && req.target_crop_height > 0 &&
        (req.target_crop_width != cur_out_w ||
         req.target_crop_height != cur_out_h)) {
      int px0 = (cur_out_w - req.target_crop_width) / 2;
      int py0 = (cur_out_h - req.target_crop_height) / 2;
      std::vector<uint8_t> cropped((size_t)3 * req.target_crop_width *
                                   req.target_crop_height);
      for (int y = 0; y < req.target_crop_height; ++y) {
        const uint8_t *src_row =
            out_data.data() + ((size_t)(py0 + y) * cur_out_w + px0) * 3;
        uint8_t *dst_row = cropped.data() + (size_t)y * req.target_crop_width * 3;
        std::memcpy(dst_row, src_row, (size_t)req.target_crop_width * 3);
      }
      out_data = std::move(cropped);
      final_width = req.target_crop_width;
      final_height = req.target_crop_height;
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
