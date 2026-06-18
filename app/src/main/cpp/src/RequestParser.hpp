#pragma once
// ════════════════════════════════════════════════════════════════════════════
//  RequestParser  —  /generate JSON → RequestContext pipeline
//
//  Extracted from main.cpp.  Three helpers:
//    • decodeAndSetImage()   — base64 image → float [C,H,W]
//    • decodeAndSetMask()    — base64 mask  → latent(4ch) + full(3ch)
//    • parseGenerateRequest()— full JSON parse, writes g_req
//
//  Depends on the global RequestContext `g_req` (declared extern here,
//  defined in main.cpp).
// ════════════════════════════════════════════════════════════════════════════

#include <algorithm>
#include <cctype>
#include <stdexcept>
#include <string>
#include <vector>

#include "AppContext.hpp"
#include "RequestContext.hpp"
#include "SDUtils.hpp"
#include "json.hpp"

// ── Xtensor ──
#include <xtensor/xadapt.hpp>
#include <xtensor/xarray.hpp>
#include <xtensor/xeval.hpp>
#include <xtensor/xio.hpp>
#include <xtensor/xmanipulation.hpp>
#include <xtensor/xmath.hpp>
#include <xtensor/xoperation.hpp>
#include <xtensor/xview.hpp>

extern RequestContext g_req;

namespace request_detail {

constexpr int kAspectPadPx = 8;  // pixel padding for aspect-ratio inpaint

// ══════════════════════════════════════════════════════════════════════
// §1  Image / Mask decoders
// ══════════════════════════════════════════════════════════════════════

/**
 * Parse the base64-encoded image from JSON into req.img_data (float [C,H,W]).
 * Handles decode → resize (aspect-ratio preserving, center-crop) →
 * normalize [-1, +1].
 *
 * Throws std::runtime_error / std::invalid_argument on failure.
 */
inline void decodeAndSetImage(const std::string &imgB64,
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
inline void decodeAndSetMask(const std::string &maskB64,
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

// ══════════════════════════════════════════════════════════════════════
// §2  Full request parser
// ══════════════════════════════════════════════════════════════════════

/**
 * Parse the /generate JSON body into g_req and compute derived paint-rect
 * values.  Handles:
 *   1. Core fields (prompt, steps, cfg_scale, seed, scheduler, size …)
 *   2. SDXL aspect_ratio → sets req.{target_crop_w/h, aspect_pad_inpaint}
 *   3. Image decoding (user-supplied or synthetic base for aspect-pad inpaint)
 *   4. Mask decoding (user-supplied or full-opacity paint rect)
 *   5. Aspect-padding mask intersection
 *
 * Throws on any parse/decode error; the caller catches and returns 4xx.
 */
inline void parseGenerateRequest(
    const nlohmann::json &json,
    AppContext &appCtx)
{
    // ── Core fields ──────────────────────────────────────────────────
    if (!json.contains("prompt"))
        throw std::invalid_argument("Missing 'prompt'");

    g_req.prompt               = json["prompt"].get<std::string>();
    g_req.negative_prompt      = json.value("negative_prompt", "");
    g_req.steps                = json.value("steps", 20);
    g_req.samples              = json.value("samples", 1);
    g_req.cfg_scale            = json.value("cfg_scale", 7.5f);
    g_req.sampler_type         = json.value("sampler", "dpm");

    // -- Scheduler (denoise curve) with validation --
    if (json.contains("scheduler")) {
        std::string sched_val = json["scheduler"].get<std::string>();
        // Normalize to lowercase for case-insensitive matching
        std::transform(sched_val.begin(), sched_val.end(), sched_val.begin(),
                       [](unsigned char c) { return std::tolower(c); });

        static const std::vector<std::string> kSupportedSchedulers = {
            "scaled_linear", "linear", "karras"
        };
        if (std::find(kSupportedSchedulers.begin(), kSupportedSchedulers.end(),
                      sched_val) == kSupportedSchedulers.end()) {
            throw std::invalid_argument(
                "Unsupported scheduler '" + sched_val +
                "'. Supported: scaled_linear, linear, karras");
        }
        g_req.denoise_curve = sched_val;
    } else {
        // HuggingFace diffusers default: scaled_linear
        g_req.denoise_curve = "scaled_linear";
    }

    g_req.use_opencl           = json.value("use_opencl", false);
    g_req.show_diffusion_process = json.value("show_diffusion_process", false);
    g_req.show_diffusion_stride  = json.value("show_diffusion_stride", 1);
    g_req.seed                 = json.value("seed", 0u);  // 0 = random (Stability-AI)
    g_req.denoising_strength   = json.value("denoising_strength", 0.6f);  // A1111 name

    if (g_req.samples != 1)
        throw std::invalid_argument("samples must be 1 (batch>1 not supported)");

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
        g_req.request_img2img     = true;
        g_req.denoising_strength  = 1.0f;  // fully renoise
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
              << " CFG:" << g_req.cfg_scale
              << " Seed:" << g_req.seed
              << " Size:" << g_req.output_width << "x" << g_req.output_height
              << " Img2Img:" << g_req.request_img2img
              << " Mask:" << g_req.request_has_mask
              << " Denoise:" << g_req.denoising_strength
              << " Sampler:" << g_req.sampler_type
              << " DenoiseCurve:" << g_req.denoise_curve
              << " Samples:" << g_req.samples
              << " ShowProcess:" << g_req.show_diffusion_process
              << " Stride:" << g_req.show_diffusion_stride << std::endl;
}

} // namespace request_detail
