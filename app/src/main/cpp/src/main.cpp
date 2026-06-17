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
#include "GenerateHandler.hpp"
#include "LCMScheduler.hpp"
#include "LaplacianBlend.hpp"
#include "MnnHelper.hpp"
#include "PromptProcessor.hpp"
#include "QnnHelper.hpp"
#include "QnnModel.hpp"
#include "RequestContext.hpp"
#include "SDUtils.hpp"
#include "SafeTensor2MNN.hpp"
#include "Scheduler.hpp"
#include "ServerState.hpp"
#include "VaeTilingHelper.hpp"
#include "PromptCacheUtils.hpp"
#include "TextEncoder.hpp"
#include "TokenizeHandler.hpp"
#include "Sha256.hpp"

// QNN Headers
#include "BuildId.hpp"
#include "DynamicLoadUtil.hpp"
#include "Logger.hpp"
#include "ModelLoader.hpp"
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


// TokenEmbTable → SDUtils.hpp  (BKND-PROC-0008 P3 split)
// MmapFile → QnnHelper.hpp    (BKND-PROC-0008 P2 split)

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

// ── BKND-PROC-0008 P3: Per-request state migrated to RequestContext
//     Filled by the /generate handler, passed as const& to generateImage().
//     Macro aliases provide backward-compatible access during migration.
RequestContext g_req;

#define prompt                  (g_req.prompt)
#define negative_prompt         (g_req.negative_prompt)
#define steps                   (g_req.steps)
#define cfg                     (g_req.cfg)
#define seed                    (g_req.seed)
#define scheduler_type          (g_req.scheduler_type)
#define img_data                (g_req.img_data)
#define mask_data               (g_req.mask_data)
#define mask_data_full          (g_req.mask_data_full)
#define denoise_strength        (g_req.denoise_strength)
#define request_img2img         (g_req.request_img2img)
#define request_has_mask        (g_req.request_has_mask)
#define use_opencl              (g_req.use_opencl)
#define aspect_pad_inpaint      (g_req.aspect_pad_inpaint)
#define target_crop_width       (g_req.target_crop_width)
#define target_crop_height      (g_req.target_crop_height)
#define aspect_pad_synthetic_base (g_req.aspect_pad_synthetic_base)
#define user_supplied_mask      (g_req.user_supplied_mask)
#define show_diffusion_process  (g_req.show_diffusion_process)
#define show_diffusion_stride   (g_req.show_diffusion_stride)
#define output_width            (g_req.output_width)
#define output_height           (g_req.output_height)
#define sample_width            (g_req.sample_width)
#define sample_height           (g_req.sample_height)

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
// SDXL lowram load/release helpers moved:
//   - MNN CLIP:  loadSdxlClipMnnIfNeeded / releaseSdxlClipMnn → MnnHelper.cpp
//   - QNN UNET:  loadSdxlQnnUnetIfNeeded / releaseSdxlQnnUnet → QnnHelper.cpp
//   - QNN VAE:   loadSdxlQnnVaeDecoderIfNeeded etc.              → QnnHelper.cpp
//   (BKND-PROC-0008 P2 split)

// processWeightedPrompt / processPromptPair → TextEncoder.cpp (BKND-PROC-0008 P3 split)

// ── VAE tiling functions → VaeTilingHelper.cpp (BKND-PROC-0008 P2 split)

// upscaleImageWithMNN → MnnHelper.cpp (BKND-PROC-0008 P2 split)

// generateImage() → GenerateHandler.cpp (BKND-PROC-0008 P4 split)

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

  // ── BKND-PROC-0008 P4: Model initialization extracted to ModelLoader ──
  int initStatus = initializeModels(appCtx);
  if (initStatus != EXIT_SUCCESS) return initStatus;
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
                  generateImage(g_req, appCtx, [&sink](int s, int t, const std::string &img) {
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
