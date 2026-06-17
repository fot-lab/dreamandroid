#pragma once
// MnnHelper.hpp — MNN inference framework helpers
//
// Extracted from main.cpp per BKND-PROC-0008 P2 file-split plan.
// Contains MNN model loading, CLIP session management, and MNN-based
// upscaling.
//
// BKND-PROC-0008 P3: Pure functions — no extern globals.
//   - loadSdxlClipMnnIfNeeded / releaseSdxlClipMnn accept AppContext&.
//   - createMnnInterpreterMmap is fully self-contained.
//   - upscaleImageWithMNN is fully self-contained.
//   - ensureCacheDir is fully self-contained.

#include <MNN/Interpreter.hpp>
#include <functional>
#include <stdexcept>
#include <string>
#include <vector>

#include "xtensor/xarray.hpp"

// Forward declarations
struct AppContext;

// ── MNN Helpers ───────────────────────────────────────────────────

/// Returns "{model_dir}/cache", creating it if needed.  Returns ""
/// when model_dir is empty or directory creation fails.
std::string ensureCacheDir(const std::string &model_dir);

/// Load an MNN model via mmap + createFromBuffer (falls back to
/// createFromFile on any mmap-path failure).
MNN::Interpreter *createMnnInterpreterMmap(const char *path);

/// SDXL lowram: lazy-load CLIP1/CLIP2 MNN interpreters + sessions.
void loadSdxlClipMnnIfNeeded(AppContext &ctx);

/// SDXL lowram: release CLIP1/CLIP2 MNN interpreters + sessions.
void releaseSdxlClipMnn(AppContext &ctx);

/// Upscale image using MNN model with tiling and blending.
xt::xarray<uint8_t> upscaleImageWithMNN(
    const std::vector<uint8_t> &input_image, int width, int height,
    const std::string &model_path, bool use_opencl);
