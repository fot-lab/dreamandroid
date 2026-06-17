#ifndef GENERATE_HANDLER_HPP
#define GENERATE_HANDLER_HPP

#include <functional>
#include <string>

#include "AppContext.hpp"
#include "RequestContext.hpp"
#include "SDUtils.hpp"

// ── BKND-PROC-0008 P4: generateImage() extracted to GenerateHandler
//
//  Accepts RequestContext (per-request immutable params) and AppContext
//  (server-wide model/config state).  No global variable dependencies.
//
//  The AppContext& is non-const because SDXL lowram mode may load/release
//  models on-demand during the pipeline.

GenerationResult generateImage(
    const RequestContext &req,
    AppContext &ctx,
    std::function<void(int step, int total_steps,
                       const std::string &image_data)>
        progress_callback);

#endif  // GENERATE_HANDLER_HPP
