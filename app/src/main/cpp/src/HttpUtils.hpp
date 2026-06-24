#pragma once
// ════════════════════════════════════════════════════════════════════════════
//  HttpUtils  —  HTTP / SSE response helpers + concurrency guard
//
//  Extracted from main.cpp.  Includes:
//    • HTTP error response setters (setHttpError, set503Busy)
//    • SSE event writers (sseWrite, sseErrorDone)
//    • Duration logger (logDuration)
//    • Busy-lock RAII guard (BusyGuard)
//    • Generation progress SSE callback (onGenerateProgress)
// ════════════════════════════════════════════════════════════════════════════

#include <chrono>
#include <iostream>
#include <string>

#include "ErrorJson.hpp"
#include "ServerState.hpp"
#include "httplib.h"
#include "json.hpp"

namespace http_detail {

// ══════════════════════════════════════════════════════════════════════
// §1  HTTP error response setters
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

// ══════════════════════════════════════════════════════════════════════
// §2  SSE event writers
// ══════════════════════════════════════════════════════════════════════

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
 *
 * Produces a Stability-AI-compatible error envelope (id/name/errors) plus
 * a top-level "message" field for simpler client parsing.
 */
inline bool sseErrorDone(httplib::DataSink &sink,
                         const std::string &message) {
    nlohmann::json err = errorJson("generation_error", message);
    err["type"]    = "error";   // MUST: SseStreamParser discriminates on "type"
    err["message"] = message;   // dual-compat: Stability envelope + simple accessor
    sseWrite(sink, "error", err);
    sink.done();
    return false;
}

// ══════════════════════════════════════════════════════════════════════
// §3  Duration logging helper
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
// §4  Busy-lock RAII guard
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
// §5  Generation progress SSE callback
// ══════════════════════════════════════════════════════════════════════

inline void onGenerateProgress(httplib::DataSink &sink,
                               ServerState &serverState,
                               int step, int totalSteps,
                               const std::string &imgBase64) {
    serverState.setProgress(step, totalSteps);
    float progress = (totalSteps > 0)
        ? static_cast<float>(step) / static_cast<float>(totalSteps)
        : 0.0f;
    nlohmann::json p = {
        {"type", "progress"},
        {"step", step},
        {"total_steps", totalSteps},
        {"progress", progress}
    };
    if (!imgBase64.empty()) p["image"] = imgBase64;
    if (!sseWrite(sink, "progress", p)) {
        std::cerr << "WARNING: progress SSE write failed (sink disconnected)\n";
    }
}

} // namespace http_detail
