#pragma once
// MnnHelper.hpp — MNN inference framework helpers
//
// Extracted from main.cpp per BKND-PROC-0008 P2 file-split plan.
// Contains MNN model loading, CLIP session management, and MNN-based
// upscaling.  All functions operate on shared globals declared extern
// here (defined in main.cpp).

#include <MNN/Interpreter.hpp>
#include <functional>
#include <stdexcept>
#include <string>
#include <vector>

#include "xtensor/xarray.hpp"

// ── Extern globals (defined in main.cpp) ──────────────────────────
extern MNN::Interpreter *clipInterpreter;
extern MNN::Interpreter *clip2Interpreter;
extern MNN::Session *clipSession;
extern MNN::Session *clip2Session;
extern std::string clipPath;
extern std::string clip2Path;
extern int text_embedding_size;
extern int text_embedding_size_2;
extern bool sdxl_mode;

// ── MNN Helpers ───────────────────────────────────────────────────

/// Returns "{model_dir}/cache", creating it if needed.  Returns ""
/// when model_dir is empty or directory creation fails.
std::string ensureCacheDir(const std::string &model_dir);

/// Load an MNN model via mmap + createFromBuffer (falls back to
/// createFromFile on any mmap-path failure).
MNN::Interpreter *createMnnInterpreterMmap(const char *path);

/// SDXL lowram: lazy-load CLIP1/CLIP2 MNN interpreters + sessions.
void loadSdxlClipMnnIfNeeded();

/// SDXL lowram: release CLIP1/CLIP2 MNN interpreters + sessions.
void releaseSdxlClipMnn();

/// Upscale image using MNN model with tiling and blending.
xt::xarray<uint8_t> upscaleImageWithMNN(
    const std::vector<uint8_t> &input_image, int width, int height,
    const std::string &model_path, bool use_opencl);
