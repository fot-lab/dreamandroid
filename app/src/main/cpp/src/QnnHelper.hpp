#pragma once
// QnnHelper.hpp — QNN inference framework helpers
//
// Extracted from main.cpp per BKND-PROC-0008 P2 file-split plan.
// Contains QNN model creation, zstd patching, QNN model lifecycle
// (load/release), and QNN-based upscaling.  Template functions are
// header-only; non-template implementations live in QnnHelper.cpp.

#include <QnnModel.hpp>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "xtensor/xarray.hpp"

// ── Utility types (moved from main.cpp) ───────────────────────────

/// RAII read-only whole-file memory map.  Keeps large transient inputs
/// as file-backed pages instead of anonymous heap buffers.
struct MmapFile {
  const uint8_t *data = nullptr;
  size_t size = 0;

  explicit MmapFile(const std::string &path);
  ~MmapFile();
  MmapFile(const MmapFile &) = delete;
  MmapFile &operator=(const MmapFile &) = delete;
  bool valid() const { return data != nullptr; }

 private:
  void *base_ = nullptr;
};

struct PatchedModelBuffer {
  std::shared_ptr<uint8_t> buffer;
  uint64_t size;

  PatchedModelBuffer() : buffer(nullptr), size(0) {}

  PatchedModelBuffer(uint8_t *buf, uint64_t sz)
      : buffer(buf, std::default_delete<uint8_t[]>()), size(sz) {}

  void reset() {
    buffer.reset();
    size = 0;
  }
};

// Forward declarations
struct AppContext;

// ── QNN Helpers ───────────────────────────────────────────────────
// BKND-PROC-0008 P3: Pure functions — no extern globals.
// Lowram load/release accept AppContext&.

/// Create a QnnModel from a .bin file, loading the QNN backend and
/// function pointers dynamically.
std::unique_ptr<QnnModel> createQnnModel(const std::string &modelPath,
                                         const std::string &modelName,
                                         const AppContext &ctx);

/// SDXL lowram: lazy-load QNN UNET.
void loadSdxlQnnUnetIfNeeded(AppContext &ctx);

/// SDXL lowram: release QNN UNET.
void releaseSdxlQnnUnet(AppContext &ctx);

/// SDXL lowram: lazy-load QNN VAE Decoder.
void loadSdxlQnnVaeDecoderIfNeeded(AppContext &ctx);

/// SDXL lowram: release QNN VAE Decoder.
void releaseSdxlQnnVaeDecoder(AppContext &ctx);

/// SDXL lowram: lazy-load QNN VAE Encoder.
void loadSdxlQnnVaeEncoderIfNeeded(AppContext &ctx);

/// SDXL lowram: release QNN VAE Encoder.
void releaseSdxlQnnVaeEncoder(AppContext &ctx);

/// Upscale image using QNN model with tiling and blending.
xt::xarray<uint8_t> upscaleImageWithModel(
    const std::vector<uint8_t> &input_image, int width, int height,
    std::unique_ptr<QnnModel> &upscaler);

// ── qnn::tools::sample_app namespace ───────────────────────────────
namespace qnn {
namespace tools {
namespace sample_app {

/// Read a binary file into a byte vector.
std::vector<char> readFileForPatch(const std::string &filePath);

/// Apply a zstd patch to a model file buffer, using the old file as
/// the compression dictionary.
std::unique_ptr<PatchedModelBuffer> applyZstdPatchToBuffer(
    const std::string &oldFilePath, const std::string &patchFilePath);

/// QnnModel initialization (template — header-only).
/// Called as sample_app::initializeQnnApp(...) from main().
template <typename AppType>
int initializeQnnApp(const std::string &modelName,
                     std::unique_ptr<AppType> &app,
                     const uint8_t *buffer = nullptr,
                     uint64_t bufferSize = 0) {
  if (!app) return EXIT_FAILURE;

  if (buffer && bufferSize > 0) {
    QNN_INFO("Initializing QNN App from Buffer: %s (size: %llu bytes)",
             modelName.c_str(), bufferSize);
  } else {
    QNN_INFO("Initializing QNN App from Cache: %s", modelName.c_str());
  }

  if (StatusCode::SUCCESS != app->initialize())
    return app->reportError(modelName + " Init failure");
  if (StatusCode::SUCCESS != app->initializeBackend())
    return app->reportError(modelName + " Backend Init failure");
  auto devPropStat = app->isDevicePropertySupported();
  if (StatusCode::FAILURE != devPropStat) {
    if (StatusCode::SUCCESS != app->createDevice())
      return app->reportError(modelName + " Device Creation failure");
  }
  if (StatusCode::SUCCESS != app->initializeProfiling())
    return app->reportError(modelName + " Profiling Init failure");
  if (StatusCode::SUCCESS != app->registerOpPackages())
    return app->reportError(modelName + " Register Op Packages failure");

  if (buffer && bufferSize > 0) {
    if (StatusCode::SUCCESS != app->createFromBuffer(buffer, bufferSize))
      return app->reportError(modelName + " Create From Buffer failure");
  } else {
    if (StatusCode::SUCCESS != app->createFromBinary())
      return app->reportError(modelName + " Create From Binary failure");
  }

  if (StatusCode::SUCCESS != app->enablePerformaceMode())
    return app->reportError(modelName + " Enable Performance Mode failure");

  if (buffer && bufferSize > 0) {
    QNN_INFO("QNN App Initialized from Buffer: %s", modelName.c_str());
  } else {
    QNN_INFO("QNN App Initialized from Cache: %s", modelName.c_str());
  }
  return EXIT_SUCCESS;
}

}  // namespace sample_app
}  // namespace tools
}  // namespace qnn
