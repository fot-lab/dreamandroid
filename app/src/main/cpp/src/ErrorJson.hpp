#pragma once
// ════════════════════════════════════════════════════════════════════════════
//  ErrorJson  —  JSON error-response builders
//
//  Extracted from main.cpp to eliminate 10+ repeated error-JSON blocks.
//  Produces Stability-AI-compatible error payloads.  Does NOT depend on
//  httplib — pure JSON construction, usable by any layer.
// ════════════════════════════════════════════════════════════════════════════

#include <chrono>
#include <string>

#include "json.hpp"

namespace http_detail {

/**
 * Generate a unique timestamp-based ID for error-response `id` fields.
 * Uses steady_clock for monotonicity; cast to raw count for compactness.
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
 *
 * Produces all fields matching upstream protocol:
 *   id, name, errors, error, detail, message, body, messages
 * where body=message, error=id, detail=name, messages=errors.
 */
inline nlohmann::json busyErrorJson() {
    auto base = errorJson("busy", "Server is currently processing another request");
    std::string msg = "Server is busy";
    base["message"]  = msg;
    base["body"]     = msg;
    base["error"]    = base["id"];
    base["detail"]   = base["name"];
    base["messages"] = base["errors"];
    return base;
}

} // namespace http_detail
