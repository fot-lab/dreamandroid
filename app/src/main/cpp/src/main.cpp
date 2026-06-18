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

RequestContext g_req;

// ════════════════════════════════════════════════════════════════════════════
// §  Internal Helper Functions  (file-scope, not exposed in headers)
// ════════════════════════════════════════════════════════════════════════════

namespace {

// ── Magic Numbers ──────────────────────────────────────────────────────
constexpr int kAspectPadPx   = 8;     // pixel padding for aspect-ratio inpaint
constexpr int kUpscaleMinEdge = 192;   // minimum edge before upscale pre-resize

// ══════════════════════════════════════════════════════════════════════
// §1  JSON / Error helpers  —  eliminate 10+ repeated error-json blocks
// ══════════════════════════════════════════════════════════════════════

/**
 * Generate a unique timestamp-based ID for error-response `id` fields.
 * Uses steady_clock for monotonicity; cast to ms for compactness.
 */
inline uint64_t nowId() {
    return static_cast<uint64_t>(
        std::chrono::steady_clock::now().time_since_epoch().count());
}

/**
 * Build a Stability-AI-compatible error JSON object.
 *
 * @param category  Short prefix for the id field (e.g. "busy", "parse").
 * @param message   Human-readable error string.
 * @param idSuffix  Optional suffix appended to the timestamp id; defaults
 *                  to the timestamp itself.
 */
inline nlohmann::json errorJson(const std::string &category,
                                const std::string &message,
                                const std::string &idSuffix = {}) {
    std::string id = category + "-";
    if (idSuffix.empty())
        id += std::to_string(nowId());
    else
        id += idSuffix;
    return {{"id", id}, {"name", category}, {"errors", {message}}};
}

/**
 * Build a 503 "busy" error JSON — used by /generate and /upscale.
 */
inline nlohmann::json busyErrorJson() {
    return errorJson("busy", "Server is currently processing another request");
}

// ══════════════════════════════════════════════════════════════════════
// §2  HTTP / SSE response helpers  —  eliminate repeated sink.write() +
//     header-setting boilerplate
// ══════════════════════════════════════════════════════════════════════

/**
 * Set a standard HTTP error response (JSON body + status code).
 */
inline void setHttpError(httplib::Response &res, int status,
                         const std::string &category,
                         const std::string &message) {
    res.status = status;
    res.set_content(errorJson(category, message).dump(), "application/json");
}

/**
 * Set a 503 Service Unavailable response with Retry-After.
 */
inline void set503Busy(httplib::Response &res) {
    res.status = 503;
    res.set_header("Retry-After", "3");
    res.set_content(busyErrorJson().dump(), "application/json");
}

/**
 * Write a single SSE event (name + JSON body) to a chunked sink.
 * Returns false if the underlying write fails (sink disconnected).
 */
inline bool sseWrite(httplib::DataSink &sink,
                     const std::string &event,
                     const std::string &jsonPayload) {
    std::string frame = "event: " + event + "\ndata: " + jsonPayload + "\n\n";
    return sink.write(frame.c_str(), frame.size());
}

/** Overload that accepts nlohmann::json. */
inline bool sseWrite(httplib::DataSink &sink,
                     const std::string &event,
                     const nlohmann::json &data) {
    return sseWrite(sink, event, data.dump());
}

/**
 * Write an SSE error event, mark the sink done, and return false
 * (the canonical "generation failed" exit from a chunked provider).
 */
inline bool sseErrorDone(httplib::DataSink &sink,
                         const std::string &message) {
    sseWrite(sink, "error", errorJson("generation_error", message));
    sink.done();
    return false;
}

// ══════════════════════════════════════════════════════════════════════
// §3  Duration logging helper  —  eliminate repeated duration_cast blocks
// ══════════════════════════════════════════════════════════════════════

inline void logDuration(const char *label,
                        std::chrono::high_resolution_clock::time_point start,
                        std::chrono::high_resolution_clock::time_point end) {
    std::cout << label << ": "
              << std::chrono::duration_cast<std::chrono::milliseconds>(
                     end - start)
                     .count()
              << "ms\n";
}

// ══════════════════════════════════════════════════════════════════════
// §4  Busy-lock RAII guard  —  ensures appCtx.serverState.release() is
//     called on every exit path (including exceptions).
// ══════════════════════════════════════════════════════════════════════

/**
 * RAII guard that *optionally* calls ServerState::release() on destruction.
 * Set `detach()` to prevent the release (used by the /generate chunked-callback
 * which must release itself after the async generation completes).
 */
class BusyGuard {
public:
    explicit BusyGuard(ServerState &st) : state_(&st) {}
    ~BusyGuard() { if (state_) state_->release(); }

    BusyGuard(const BusyGuard &) = delete;
    BusyGuard &operator=(const BusyGuard &) = delete;

    /** Detach: the callback will release manually. */
    void detach() { state_ = nullptr; }

private:
    ServerState *state_;
};

// ══════════════════════════════════════════════════════════════════════
// §5  Request parsing helpers  —  extract repeated sub-logic from the
//     /generate handler
// ══════════════════════════════════════════════════════════════════════

/**
 * Parse the base64-encoded image from JSON into req.img_data (float [C,H,W]).
 * Handles decode → resize (aspect-ratio preserving, center-crop) →
 * normalize [-1, +1].
 *
 * Throws std::runtime_error / std::invalid_argument on failure.
 */
static void decodeAndSetImage(const std::string &imgB64,
                              RequestContext &req) {
    std::string decStr = base64_decode(imgB64);
    std::vector<uint8_t> decBuf(decStr.begin(), decStr.end());
    std::vector<uint8_t> decPix;
    decode_image(decBuf, decPix, req.output_width, req.output_height);

    if (decPix.size() != 3u * req.output_width * req.output_height)
        throw std::runtime_error("Img size mismatch");

    std::vector<int> imgShape = {1, req.output_height, req.output_width, 3};
    xt::xarray<uint8_t> xu8 = xt::adapt(decPix, imgShape);
    xt::xarray<float> xf = xt::cast<float>(xu8);
    xf = xt::eval(xf / 127.5f - 1.0f);
    xf = xt::transpose(xf, {0, 3, 1, 2});
    req.img_data.assign(xf.begin(), xf.end());
}

/**
 * Parse the base64-encoded mask from JSON into req.{mask_data, mask_data_full}.
 * Produces both latent-resolution (4ch) and full-resolution (3ch) masks.
 *
 * Throws std::runtime_error on failure.
 */
static void decodeAndSetMask(const std::string &maskB64,
                             RequestContext &req) {
    std::string decStr = base64_decode(maskB64);
    std::vector<uint8_t> decBuf(decStr.begin(), decStr.end());

    std::vector<uint8_t> maskLatRgb, maskFullRgb;
    decode_image(decBuf, maskLatRgb, req.sample_width, req.sample_height);
    decode_image(decBuf, maskFullRgb, req.output_width, req.output_height);

    if (maskLatRgb.empty() || maskFullRgb.empty())
        throw std::runtime_error("Mask decode empty");

    // Latent-resolution mask: 4-channel broadcast
    {
        std::vector<int> shape = {req.sample_height, req.sample_width, 3};
        xt::xarray<uint8_t> xu8 = xt::adapt(maskLatRgb, shape);
        xt::xarray<float> xf = xt::mean(xt::cast<float>(xu8), {2});
        xf = xt::eval(xf / 255.0f);
        xf = xt::reshape_view(xf, {1, 1, req.sample_height, req.sample_width});
        xt::xarray<float> xf4 = xt::concatenate(xt::xtuple(xf, xf, xf, xf), 1);
        req.mask_data.assign(xf4.begin(), xf4.end());
    }

    // Full-resolution mask: 3-channel broadcast
    {
        std::vector<int> shape = {req.output_height, req.output_width, 3};
        xt::xarray<uint8_t> xu8 = xt::adapt(maskFullRgb, shape);
        xt::xarray<float> xf = xt::mean(xt::cast<float>(xu8), {2});
        xf = xt::eval(xf / 255.0f);
        xf = xt::reshape_view(xf, {1, 1, req.output_height, req.output_width});
        xt::xarray<float> xf3 = xt::concatenate(xt::xtuple(xf, xf, xf), 1);
        req.mask_data_full.assign(xf3.begin(), xf3.end());
    }
}

/**
 * Parse the /generate JSON body into g_req and compute derived paint-rect
 * values (`paint_*`).  Handles:
 *   1. Core fields (prompt, steps, cfg, seed, scheduler, size …)
 *   2. SDXL aspect_ratio → sets req.{target_crop_w/h, aspect_pad_inpaint}
 *   3. Image decoding (user-supplied or synthetic base for aspect-pad inpaint)
 *   4. Mask decoding (user-supplied or full-opacity paint rect)
 *   5. Aspect-padding mask intersection
 *
 * Throws on any parse/decode error; the caller catches and returns 4xx.
 */
static void parseGenerateRequest(
    const nlohmann::json &json,
    AppContext &appCtx)
{
    // ── Core fields ──────────────────────────────────────────────────
    if (!json.contains("prompt"))
        throw std::invalid_argument("Missing 'prompt'");

    g_req.prompt               = json["prompt"].get<std::string>();
    g_req.negative_prompt      = json.value("negative_prompt", "");
    g_req.steps                = json.value("steps", 20);
    g_req.cfg                  = json.value("cfg", 7.5f);
    g_req.scheduler_type       = json.value("scheduler", "dpm");
    g_req.use_opencl           = json.value("use_opencl", false);
    g_req.show_diffusion_process = json.value("show_diffusion_process", false);
    g_req.show_diffusion_stride  = json.value("show_diffusion_stride", 1);
    g_req.seed                 = json.value(
        "seed",
        (unsigned)hashSeed(
            std::chrono::system_clock::now().time_since_epoch().count()));
    g_req.denoise_strength     = json.value("denoise_strength", 0.6f);

    int reqW = json.value("width", 512);
    int reqH = json.value("height", 512);
    if (json.contains("size")) {
        int sz = json.value("size", 512);
        reqW = sz; reqH = sz;
    }
    if (appCtx.conf.sdxl_mode) { reqW = 1024; reqH = 1024; }

    // Zero-init img2img / mask fields
    g_req.request_img2img       = false;
    g_req.request_has_mask     = false;
    g_req.aspect_pad_inpaint   = false;
    g_req.aspect_pad_synthetic_base = false;
    g_req.user_supplied_mask   = false;
    g_req.target_crop_width    = 0;
    g_req.target_crop_height   = 0;

    // Release previous request vector capacity
    g_req.img_data.clear();       g_req.img_data.shrink_to_fit();
    g_req.mask_data.clear();      g_req.mask_data.shrink_to_fit();
    g_req.mask_data_full.clear(); g_req.mask_data_full.shrink_to_fit();

    g_req.output_width  = reqW;
    g_req.output_height = reqH;
    g_req.sample_width  = reqW / 8;
    g_req.sample_height = reqH / 8;

    // ── SDXL aspect ratio ────────────────────────────────────────────
    if (appCtx.conf.sdxl_mode && json.contains("aspect_ratio") &&
        !appCtx.conf.vaeEncoderPath.empty()) {
        std::string ar = json["aspect_ratio"].get<std::string>();
        auto colon = ar.find(':');
        if (colon != std::string::npos) {
            try {
                int rw = std::stoi(ar.substr(0, colon));
                int rh = std::stoi(ar.substr(colon + 1));
                if (rw > 0 && rh > 0 && rw != rh) {
                    int tw, th;
                    if (rw >= rh) {
                        tw = 1024;
                        th = static_cast<int>((1024.0 * rh) / rw);
                        th = (th / 8) * 8;
                        if (th < 8) th = 8;
                    } else {
                        th = 1024;
                        tw = static_cast<int>((1024.0 * rw) / rh);
                        tw = (tw / 8) * 8;
                        if (tw < 8) tw = 8;
                    }
                    g_req.target_crop_width  = tw;
                    g_req.target_crop_height = th;
                    g_req.aspect_pad_inpaint = true;
                }
            } catch (...) {
                // Bad aspect_ratio string — proceed with 1:1.
            }
        }
    }

    // ── Compute paint rectangle ──────────────────────────────────────
    int paint_w  = g_req.target_crop_width;
    int paint_h  = g_req.target_crop_height;
    int paint_x0 = 0;
    int paint_y0 = 0;
    if (g_req.aspect_pad_inpaint) {
        if (g_req.target_crop_width < g_req.output_width)
            paint_w = std::min(g_req.output_width,
                               g_req.target_crop_width + 2 * kAspectPadPx);
        if (g_req.target_crop_height < g_req.output_height)
            paint_h = std::min(g_req.output_height,
                               g_req.target_crop_height + 2 * kAspectPadPx);
        paint_x0 = (g_req.output_width  - paint_w) / 2;
        paint_y0 = (g_req.output_height - paint_h) / 2;
    }

    // ── Image (user-supplied or synthetic base) ──────────────────────
    if (json.contains("image")) {
        g_req.request_img2img = true;
        decodeAndSetImage(json["image"].get<std::string>(), g_req);
    } else if (g_req.aspect_pad_inpaint) {
        // Synthetic white-on-black canvas: black border (-1) with white
        // paint region (+1) extended kAspectPadPx past the crop along the
        // short axis so the mask boundary never coincides with the latent's
        // black→white transition.
        g_req.aspect_pad_synthetic_base = true;
        size_t imgTotal = 3ull * g_req.output_width * g_req.output_height;
        g_req.img_data.assign(imgTotal, -1.0f);
        for (int c = 0; c < 3; ++c) {
            for (int y = paint_y0; y < paint_y0 + paint_h; ++y) {
                float *row = g_req.img_data.data() +
                             (static_cast<size_t>(c) * g_req.output_height + y) *
                                 g_req.output_width;
                for (int x = paint_x0; x < paint_x0 + paint_w; ++x)
                    row[x] = 1.0f;
            }
        }
        g_req.request_img2img   = true;
        g_req.denoise_strength  = 1.0f;  // fully renoise
    }

    // ── Mask ─────────────────────────────────────────────────────────
    if (json.contains("mask")) {
        if (!g_req.request_img2img)
            throw std::runtime_error("mask requires image");
        g_req.request_has_mask  = true;
        g_req.user_supplied_mask = true;
        decodeAndSetMask(json["mask"].get<std::string>(), g_req);
    }

    // ── Aspect padding mask (intersect or install) ───────────────────
    if (g_req.aspect_pad_inpaint) {
        int lx0 = paint_x0 / 8;
        int ly0 = paint_y0 / 8;
        int lx1 = std::min(g_req.sample_width,
                           (paint_x0 + paint_w + 7) / 8);
        int ly1 = std::min(g_req.sample_height,
                           (paint_y0 + paint_h + 7) / 8);

        if (g_req.request_has_mask) {
            // Intersect: zero out everything outside the paint rectangle
            for (int c = 0; c < 4; ++c) {
                for (int y = 0; y < g_req.sample_height; ++y) {
                    float *row = g_req.mask_data.data() +
                                 (static_cast<size_t>(c) * g_req.sample_height + y) *
                                     g_req.sample_width;
                    if (y < ly0 || y >= ly1) {
                        std::fill(row, row + g_req.sample_width, 0.0f);
                    } else {
                        std::fill(row, row + lx0, 0.0f);
                        std::fill(row + lx1, row + g_req.sample_width, 0.0f);
                    }
                }
            }
            for (int c = 0; c < 3; ++c) {
                for (int y = 0; y < g_req.output_height; ++y) {
                    float *row = g_req.mask_data_full.data() +
                                 (static_cast<size_t>(c) * g_req.output_height + y) *
                                     g_req.output_width;
                    if (y < paint_y0 || y >= paint_y0 + paint_h) {
                        std::fill(row, row + g_req.output_width, 0.0f);
                    } else {
                        std::fill(row, row + paint_x0, 0.0f);
                        std::fill(row + paint_x0 + paint_w,
                                  row + g_req.output_width, 0.0f);
                    }
                }
            }
        } else {
            // Install full-opacity paint-rect mask
            g_req.mask_data.assign(
                4ull * g_req.sample_width * g_req.sample_height, 0.0f);
            for (int c = 0; c < 4; ++c) {
                for (int y = ly0; y < ly1; ++y) {
                    float *row = g_req.mask_data.data() +
                                 (static_cast<size_t>(c) * g_req.sample_height + y) *
                                     g_req.sample_width;
                    for (int x = lx0; x < lx1; ++x) row[x] = 1.0f;
                }
            }
            g_req.mask_data_full.assign(
                3ull * g_req.output_width * g_req.output_height, 0.0f);
            for (int c = 0; c < 3; ++c) {
                for (int y = paint_y0; y < paint_y0 + paint_h; ++y) {
                    float *row = g_req.mask_data_full.data() +
                                 (static_cast<size_t>(c) * g_req.output_height + y) *
                                     g_req.output_width;
                    for (int x = paint_x0; x < paint_x0 + paint_w; ++x)
                        row[x] = 1.0f;
                }
            }
            g_req.request_has_mask = true;
        }
    }

    // ── Log parsed request ───────────────────────────────────────────
    std::cout << "Req Rcvd: P:" << g_req.prompt
              << " NP:" << g_req.negative_prompt
              << " S:" << g_req.steps
              << " CFG:" << g_req.cfg
              << " Seed:" << g_req.seed
              << " Size:" << g_req.output_width << "x" << g_req.output_height
              << " Img2Img:" << g_req.request_img2img
              << " Mask:" << g_req.request_has_mask
              << " Denoise:" << g_req.denoise_strength
              << " ShowProcess:" << g_req.show_diffusion_process
              << " Stride:" << g_req.show_diffusion_stride << std::endl;
}

// ══════════════════════════════════════════════════════════════════════
// §6  Generation SSE callback —  build and write progress events
// ══════════════════════════════════════════════════════════════════════

static void onGenerateProgress(httplib::DataSink &sink,
                               ServerState &serverState,
                               int step, int totalSteps,
                               const std::string &imgBase64) {
    serverState.setProgress(step, totalSteps);
    nlohmann::json p = {
        {"type", "progress"},
        {"step", step},
        {"total_steps", totalSteps}
    };
    if (!imgBase64.empty()) p["image"] = imgBase64;
    sseWrite(sink, "progress", p);
}

} // anonymous namespace


// ════════════════════════════════════════════════════════════════════════════
// Main
// ════════════════════════════════════════════════════════════════════════════

int main(int argc, char **argv) {

    AppContext appCtx;

    using namespace qnn::tools;
    if (!qnn::log::initializeLogging()) {
        std::cerr << "ERROR: Init logging failed!\n";
        return EXIT_FAILURE;
    }
    processCommandLine(argc, argv, appCtx);

    int initStatus = initializeModels(appCtx);
    if (initStatus != EXIT_SUCCESS) return initStatus;

    // ─── HTTP Server ────────────────────────────────────────────────────
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

    // ─── GET /health ────────────────────────────────────────────────────
    svr.Get("/health", [](const httplib::Request &, httplib::Response &res) {
        res.status = 200;
    });

    // ─── GET /progress ──────────────────────────────────────────────────
    svr.Get("/progress", [&](const httplib::Request &, httplib::Response &res) {
        nlohmann::json r;
        r["busy"]         = appCtx.serverState.isBusy();
        r["current_step"] = appCtx.serverState.currentStep();
        r["total_steps"]  = appCtx.serverState.totalSteps();
        res.status = 200;
        res.set_content(r.dump(), "application/json");
    });

    // ─── POST /generate ─────────────────────────────────────────────────
    svr.Post("/generate", [&](const httplib::Request &req,
                              httplib::Response &res) {
        std::chrono::steady_clock::time_point acquireTime;
        if (!appCtx.serverState.acquireBusy(acquireTime)) {
            set503Busy(res);
            return;
        }
        BusyGuard busyGuard(appCtx.serverState);

        // ── Phase 1: parse request (sync) ────────────────────────────
        try {
            parseGenerateRequest(
                nlohmann::json::parse(req.body), appCtx);
        } catch (const nlohmann::json::parse_error &e) {
            setHttpError(res, 400, "invalid_json", e.what());
            return;
        } catch (const std::invalid_argument &e) {
            setHttpError(res, 400, "invalid_argument", e.what());
            return;
        } catch (const std::exception &e) {
            setHttpError(res, 500, "server_error", e.what());
            return;
        }

        // ── Phase 2: SSE chunked generation (async) ──────────────────
        busyGuard.detach();  // chunked callback releases itself

        res.set_header("Content-Type", "text/event-stream");
        res.set_header("Cache-Control", "no-cache");
        res.set_header("Connection", "keep-alive");
        res.set_chunked_content_provider(
            "text/event-stream",
            [&appCtx, acquireTime](intptr_t, httplib::DataSink &sink) -> bool {
                // Watchdog: check for hung generation
                if (appCtx.serverState.checkAndReleaseTimeout(acquireTime))
                    return sseErrorDone(sink,
                        "Generation timed out after " +
                        std::to_string(appCtx.serverState.generation_timeout_secs) +
                        "s");

                try {
                    auto result = generateImage(
                        g_req, appCtx,
                        [&sink, &appCtx](int s, int t,
                                         const std::string &img) {
                            onGenerateProgress(sink, appCtx.serverState, s, t,
                                               img);
                        });

                    auto encStart = std::chrono::high_resolution_clock::now();
                    std::string imageStr(result.image_data.begin(),
                                         result.image_data.end());
                    std::string encImg = base64_encode(imageStr);
                    logDuration("Enc time", encStart,
                                std::chrono::high_resolution_clock::now());

                    nlohmann::json complete = {
                        {"type", "complete"},
                        {"image", encImg},
                        {"seed", g_req.seed},
                        {"width", result.width},
                        {"height", result.height},
                        {"channels", result.channels},
                        {"generation_time_ms", result.generation_time_ms},
                        {"first_step_time_ms", result.first_step_time_ms}
                    };

                    auto sendStart = std::chrono::high_resolution_clock::now();
                    sseWrite(sink, "complete", complete);
                    auto sendEnd = std::chrono::high_resolution_clock::now();
                    std::cout << "Image send time: "
                              << std::chrono::duration_cast<
                                     std::chrono::milliseconds>(
                                     sendEnd - sendStart)
                                     .count()
                              << "ms\n";

                    sink.done();
                    appCtx.serverState.release();
                    return true;

                } catch (const std::exception &e) {
                    appCtx.serverState.release();
                    return sseErrorDone(sink, e.what());
                }
            });
    });

    // ─── POST /upscale ───────────────────────────────────────────────────
    svr.Post("/upscale", [&](const httplib::Request &req,
                             httplib::Response &res) {
        std::unique_ptr<QnnModel> tempUpscalerApp;

        std::chrono::steady_clock::time_point acquireTime;
        if (!appCtx.serverState.acquireBusy(acquireTime)) {
            set503Busy(res);
            return;
        }
        // Guard releases the busy flag AND resets the upscaler model.
        BusyGuard busyGuard(appCtx.serverState);
        auto cleanupUpscaler = [&]() {
            if (tempUpscalerApp) tempUpscalerApp.reset();
        };

        try {
            // Validate headers
            auto requireHeader = [&](const char *name) {
                if (!req.has_header(name))
                    throw std::invalid_argument(
                        std::string("Missing '") + name + "' header");
            };
            requireHeader("X-Image-Width");
            requireHeader("X-Image-Height");
            requireHeader("X-Upscaler-Path");

            int origW   = std::stoi(req.get_header_value("X-Image-Width"));
            int origH   = std::stoi(req.get_header_value("X-Image-Height"));
            std::string upPath = req.get_header_value("X-Upscaler-Path");

            bool useOpenCL = false;
            if (req.has_header("X-Use-OpenCL")) {
                std::string v = req.get_header_value("X-Use-OpenCL");
                useOpenCL = (v == "true" || v == "1");
            }

            // Determine model type from file extension
            std::string ext;
            if (upPath.size() >= 4)
                ext = upPath.substr(upPath.size() - 4);
            bool isMnn = (ext == ".mnn");

            QNN_INFO("Upscale: %dx%d → %s %s%s",
                     origW, origH, upPath.c_str(),
                     isMnn ? "MNN" : "QNN",
                     (isMnn && useOpenCL) ? "OpenCL" : "");

            std::vector<uint8_t> imgData(req.body.begin(), req.body.end());
            if (imgData.size() != static_cast<size_t>(origW) * origH * 3) {
                throw std::invalid_argument(
                    "Image data size mismatch. Expected " +
                    std::to_string(origW * origH * 3) +
                    " bytes, got " + std::to_string(imgData.size()));
            }

            // Pre-process: upsample tiny images to at least kUpscaleMinEdge
            int procW = origW, procH = origH;
            std::vector<uint8_t> procImg = imgData;
            if (std::min(origW, origH) < kUpscaleMinEdge) {
                QNN_INFO("Image too small (%dx%d), resizing to min edge %d",
                         origW, origH, kUpscaleMinEdge);
                procImg = resizeImageToMinSize(imgData, origW, origH,
                                               kUpscaleMinEdge, procW, procH);
                QNN_INFO("Resized to %dx%d", procW, procH);
            }

            auto t0 = std::chrono::high_resolution_clock::now();
            xt::xarray<uint8_t> upscaled;

            if (isMnn) {
                upscaled = upscaleImageWithMNN(procImg, procW, procH,
                                               upPath, useOpenCL);
            } else {
                tempUpscalerApp = createQnnModel(upPath, "upscaler", appCtx);
                if (!tempUpscalerApp)
                    throw std::runtime_error("Failed to create upscaler: " + upPath);
                if (sample_app::initializeQnnApp("Upscaler", tempUpscalerApp) !=
                    EXIT_SUCCESS)
                    throw std::runtime_error("Failed to init upscaler");

                upscaled = upscaleImageWithModel(procImg, procW, procH,
                                                 tempUpscalerApp);
            }
            logDuration("Upscaling", t0,
                        std::chrono::high_resolution_clock::now());

            int upW = procW * 4, upH = procH * 4;
            int finalW = origW * 4, finalH = origH * 4;
            std::vector<uint8_t> finalRgb(upscaled.begin(), upscaled.end());

            if (upW != finalW || upH != finalH) {
                QNN_INFO("Resizing output %dx%d → %dx%d",
                         upW, upH, finalW, finalH);
                finalRgb = resizeImageToTarget(finalRgb, upW, upH,
                                               finalW, finalH);
            }

            auto encStart = std::chrono::high_resolution_clock::now();
            auto outJpeg = encodeJPEG(finalRgb, finalW, finalH, 95);
            logDuration("JPEG encode", encStart,
                        std::chrono::high_resolution_clock::now());

            res.status = 200;
            res.set_content(
                std::string(outJpeg.begin(), outJpeg.end()), "image/jpeg");
            res.set_header("X-Output-Width",  std::to_string(finalW));
            res.set_header("X-Output-Height", std::to_string(finalH));
            res.set_header("X-Duration-Ms",
                std::to_string(std::chrono::duration_cast<
                    std::chrono::milliseconds>(
                    std::chrono::high_resolution_clock::now() - t0).count()));
            res.set_header("Access-Control-Expose-Headers",
                           "X-Output-Width,X-Output-Height,X-Duration-Ms");

            cleanupUpscaler();

        } catch (const std::invalid_argument &e) {
            cleanupUpscaler();
            setHttpError(res, 400, "invalid_argument", e.what());
        } catch (const std::exception &e) {
            cleanupUpscaler();
            setHttpError(res, 500, "server_error", e.what());
        }
    });

    // ─── POST /tokenize ──────────────────────────────────────────────────
    svr.Post("/tokenize", [&](const httplib::Request &req,
                              httplib::Response &res) {
        handleTokenize(req, res,
                       appCtx.conf.sdxl_mode,
                       text_embedding_size_2,
                       appCtx.models.promptProcessor,
                       appCtx.models.tokenizer.get());
    });

    // ─── POST /shutdown ──────────────────────────────────────────────────
    svr.Post("/shutdown", [&](const httplib::Request &, httplib::Response &res) {
        appCtx.serverState.initiateShutdown();
        res.status = 200;
        res.set_content(R"({"status":"shutting_down"})", "application/json");
        std::cout << "[Server] Shutdown requested" << std::endl;
        std::thread([&svr]() {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
            svr.stop();
        }).detach();
    });

    // ─── Listen ──────────────────────────────────────────────────────────
    std::cout << "Server listening on " << appCtx.conf.listen_address
              << ":" << appCtx.conf.port << std::endl;
    svr.listen(appCtx.conf.listen_address.c_str(), appCtx.conf.port);

    // ─── Cleanup ─────────────────────────────────────────────────────────
    auto &m = appCtx.models;
    if (m.clipSession)
        m.clipInterpreter->releaseSession(m.clipSession);
    if (m.clip2Session)
        m.clip2Interpreter->releaseSession(m.clip2Session);
    if (m.safetyCheckerSession)
        m.safetyCheckerInterpreter->releaseSession(m.safetyCheckerSession);
    m.clipSession = m.clip2Session = m.safetyCheckerSession = nullptr;
    delete m.clipInterpreter;
    delete m.clip2Interpreter;
    delete m.safetyCheckerInterpreter;
    m.unetApp.reset();
    m.vaeDecoderApp.reset();
    m.vaeEncoderApp.reset();

    return EXIT_SUCCESS;
}
