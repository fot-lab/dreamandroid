#include <atomic>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iostream>
#include <memory>
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
#include "ErrorJson.hpp"
#include "HttpUtils.hpp"
#include "RequestParser.hpp"

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

namespace {
constexpr int kUpscaleMinEdge = 192;   // minimum edge before upscale pre-resize
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

    // ─── GET /v1/health ───────────────────────────────────────────────
    svr.Get("/v1/health", [&](const httplib::Request &, httplib::Response &res) {
        bool busy = appCtx.serverState.isBusy();
        nlohmann::json r;
        r["status"] = busy ? "busy" : "idle";
        res.status = 200;
        res.set_content(r.dump(), "application/json");
    });

    // ─── GET /v1/progress ─────────────────────────────────────────────
    svr.Get("/v1/progress", [&](const httplib::Request &, httplib::Response &res) {
        int cur = appCtx.serverState.currentStep();
        int tot = appCtx.serverState.totalSteps();
        float pct = (tot > 0) ? static_cast<float>(cur) / static_cast<float>(tot) : 0.0f;
        nlohmann::json r;
        r["status"]       = appCtx.serverState.isBusy() ? "busy" : "idle";
        r["current_step"] = cur;
        r["total_steps"]  = tot;
        r["progress"]     = pct;
        res.status = 200;
        res.set_content(r.dump(), "application/json");
    });

    // ─── POST /v1/generate ────────────────────────────────────────────
    svr.Post("/v1/generate", [&](const httplib::Request &req,
                              httplib::Response &res) {
        std::chrono::steady_clock::time_point acquireTime;
        if (!appCtx.serverState.acquireBusy(acquireTime)) {
            http_detail::set503Busy(res);
            return;
        }
        http_detail::BusyGuard busyGuard(appCtx.serverState);

        // ── Phase 1: parse request (sync) ────────────────────────────
        RequestContext reqCtx;
        try {
            request_detail::parseGenerateRequest(
                nlohmann::json::parse(req.body), appCtx, reqCtx);
        } catch (const nlohmann::json::parse_error &e) {
            http_detail::setHttpError(res, 400, "invalid_json", e.what());
            return;
        } catch (const std::invalid_argument &e) {
            http_detail::setHttpError(res, 400, "invalid_argument", e.what());
            return;
        } catch (const std::exception &e) {
            http_detail::setHttpError(res, 500, "server_error", e.what());
            return;
        }

        // ── Phase 2: SSE chunked generation (async) ──────────────────
        busyGuard.detach();  // chunked callback releases itself

        res.set_header("Content-Type", "text/event-stream");
        res.set_header("Cache-Control", "no-cache");
        res.set_header("Connection", "keep-alive");
        res.set_chunked_content_provider(
            "text/event-stream",
            [&appCtx, acquireTime, reqCtx](intptr_t, httplib::DataSink &sink) mutable -> bool {
                // Watchdog: check for hung generation
                if (appCtx.serverState.checkAndReleaseTimeout(acquireTime))
                    return http_detail::sseErrorDone(sink,
                        "Generation timed out after " +
                        std::to_string(appCtx.serverState.generation_timeout_secs) +
                        "s");

                try {
                    auto result = generateImage(
                        reqCtx, appCtx,
                        [&sink, &appCtx](int s, int t,
                                         const std::string &img) {
                            http_detail::onGenerateProgress(sink, appCtx.serverState, s, t,
                                               img);
                        });

                    auto encStart = std::chrono::high_resolution_clock::now();
                    std::string imageStr(result.image_data.begin(),
                                         result.image_data.end());
                    std::string encImg = base64_encode(imageStr);
                    http_detail::logDuration("Enc time", encStart,
                                std::chrono::high_resolution_clock::now());

                    nlohmann::json complete = {
                        {"type", "complete"},
                        {"image", encImg},
                        {"seed", reqCtx.seed},
                        {"width", result.width},
                        {"height", result.height},
                        {"channels", result.channels},
                        {"generation_time_ms", result.generation_time_ms},
                        {"first_step_time_ms", result.first_step_time_ms},
                        {"finish_reason", "SUCCESS"}
                    };

                    auto sendStart = std::chrono::high_resolution_clock::now();
                    if (!http_detail::sseWrite(sink, "complete", complete)) {
                        std::cerr << "ERROR: failed to send complete SSE event (sink disconnected)\n";
                        appCtx.serverState.release();
                        return false;
                    }
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
                    return http_detail::sseErrorDone(sink, e.what());
                }
            });
    });

    // ─── POST /v1/upscale ───────────────────────────────────────────────
    svr.Post("/v1/upscale", [&](const httplib::Request &req,
                             httplib::Response &res) {
        std::unique_ptr<QnnModel> tempUpscalerApp;

        std::chrono::steady_clock::time_point acquireTime;
        if (!appCtx.serverState.acquireBusy(acquireTime)) {
            http_detail::set503Busy(res);
            return;
        }
        // Guard releases the busy flag AND resets the upscaler model.
        http_detail::BusyGuard busyGuard(appCtx.serverState);
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
            http_detail::logDuration("Upscaling", t0,
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
            http_detail::logDuration("JPEG encode", encStart,
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
            http_detail::setHttpError(res, 400, "invalid_argument", e.what());
        } catch (const std::exception &e) {
            cleanupUpscaler();
            http_detail::setHttpError(res, 500, "server_error", e.what());
        }
    });

    // ─── POST /v1/tokenize ────────────────────────────────────────────
    svr.Post("/v1/tokenize", [&](const httplib::Request &req,
                              httplib::Response &res) {
        handleTokenize(req, res,
                       appCtx.conf.sdxl_mode,
                       text_embedding_size_2,
                       appCtx.models.promptProcessor,
                       appCtx.models.tokenizer.get());
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
