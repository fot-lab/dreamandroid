// QnnHelper.cpp — QNN inference framework helpers
// Extracted from main.cpp per BKND-PROC-0008 P2 file-split plan.

#include "QnnHelper.hpp"

#include "AppContext.hpp"

#include <dlfcn.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <fstream>
#include <iostream>
#include <mutex>

#include "DynamicLoadUtil.hpp"
#include "Logger.hpp"
#include "ServerState.hpp"
#include "zstd.h"

#include "xtensor/xadapt.hpp"
#include "xtensor/xarray.hpp"
#include "xtensor/xbuilder.hpp"
#include "xtensor/xindex_view.hpp"
#include "xtensor/xio.hpp"
#include "xtensor/xmath.hpp"
#include "xtensor/xview.hpp"

// ── MmapFile RAII wrapper ─────────────────────────────────────────
MmapFile::MmapFile(const std::string &path) {
  int fd = open(path.c_str(), O_RDONLY);
  if (fd < 0) return;
  struct stat st{};
  if (0 == fstat(fd, &st) && st.st_size > 0) {
    void *m = mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ,
                   MAP_PRIVATE, fd, 0);
    if (m != MAP_FAILED) {
      base_ = m;
      size = static_cast<size_t>(st.st_size);
      data = static_cast<const uint8_t *>(m);
    }
  }
  close(fd);
}

MmapFile::~MmapFile() {
  if (base_ != nullptr) munmap(base_, size);
}

// ── createQnnModel ─────────────────────────────────────────────────
std::unique_ptr<QnnModel> createQnnModel(const std::string &modelPath,
                                         const std::string &modelName,
                                         const AppContext &ctx) {
  using namespace qnn::tools;
  const auto &gconf = ctx.conf;
  const auto &gmod = ctx.models;
  QnnFunctionPointers funcs = gmod.qnnSystemFuncs;
  void *backendHandle = nullptr;
  void *modelHandle = nullptr;
  dynamicloadutil::StatusCode drvStatus =
      dynamicloadutil::getQnnFunctionPointers(gconf.backendPathCmd, modelPath,
                                              &funcs, &backendHandle, false,
                                              &modelHandle);
  if (drvStatus != dynamicloadutil::StatusCode::SUCCESS) {
    QNN_ERROR("Failed get QNN func ptrs for %s.", modelName.c_str());
    if (modelHandle) dlclose(modelHandle);
    return nullptr;
  }
  std::string inputListPaths, opPackagePaths, outputPath, saveBinaryName;
  bool debug = false;
  bool dumpOutputs = false;
  iotensor::OutputDataType outputDataType =
      iotensor::OutputDataType::FLOAT_ONLY;
  iotensor::InputDataType inputDataType = iotensor::InputDataType::FLOAT;
  sample_app::ProfilingLevel profilingLevel = ProfilingLevel::OFF;
  auto app = std::make_unique<QnnModel>(
      funcs, inputListPaths, opPackagePaths, backendHandle, outputPath, debug,
      outputDataType, inputDataType, profilingLevel, dumpOutputs, modelPath,
      saveBinaryName);
  // Hand off the model library handle so the QnnModel destructor can dlclose
  // it. Otherwise lowram mode leaks one .so handle per load cycle.
  if (app) app->m_modelHandle = modelHandle;
  return app;
}

// ── qnn::tools::sample_app utilities ───────────────────────────────
namespace qnn {
namespace tools {
namespace sample_app {

std::vector<char> readFileForPatch(const std::string &filePath) {
  std::ifstream file(filePath, std::ios::binary | std::ios::ate);
  if (!file.is_open()) {
    throw std::runtime_error("Failed to open file: " + filePath);
  }
  std::streamsize size = file.tellg();
  file.seekg(0, std::ios::beg);
  std::vector<char> buffer(size);
  if (size > 0) {
    if (!file.read(buffer.data(), size)) {
      throw std::runtime_error("Failed to read file: " + filePath);
    }
  }
  return buffer;
}

std::unique_ptr<PatchedModelBuffer> applyZstdPatchToBuffer(
    const std::string &oldFilePath, const std::string &patchFilePath) {
  try {
    // The old model is only read (as the zstd dictionary), so map it read-only
    // instead of pulling the whole multi-GB file into an anonymous buffer.
    MmapFile oldFile(oldFilePath);
    if (!oldFile.valid()) {
      throw std::runtime_error("Failed to map old file: " + oldFilePath);
    }
    QNN_INFO("Mapped old file (%s): %zu bytes.", oldFilePath.c_str(),
             oldFile.size);

    std::vector<char> patchFileBuffer = readFileForPatch(patchFilePath);
    QNN_INFO("Read patch file (%s): %zu bytes.", patchFilePath.c_str(),
             patchFileBuffer.size());

    if (patchFileBuffer.empty()) {
      throw std::runtime_error("Patch file (" + patchFilePath +
                               ") is empty or could not be read.");
    }

    unsigned long long const decompressedSize = ZSTD_getFrameContentSize(
        patchFileBuffer.data(), patchFileBuffer.size());

    if (decompressedSize == ZSTD_CONTENTSIZE_ERROR) {
      throw std::runtime_error("Patch file (" + patchFilePath +
                               ") is not a valid zstd frame.");
    }
    if (decompressedSize == ZSTD_CONTENTSIZE_UNKNOWN) {
      throw std::runtime_error(
          "Decompressed size is unknown. Cannot proceed with this simple "
          "implementation.");
    }

    if (decompressedSize == 0) {
      QNN_ERROR("Patch resulted in empty buffer.");
      return nullptr;
    }

    uint8_t *newBuffer = new uint8_t[decompressedSize];

    ZSTD_DCtx *const dctx = ZSTD_createDCtx();
    if (dctx == nullptr) {
      delete[] newBuffer;
      throw std::runtime_error("ZSTD_createDCtx() failed!");
    }

    size_t const actualDecompressedSize = ZSTD_decompress_usingDict(
        dctx, newBuffer, decompressedSize, patchFileBuffer.data(),
        patchFileBuffer.size(), oldFile.data, oldFile.size);

    ZSTD_freeDCtx(dctx);

    if (ZSTD_isError(actualDecompressedSize)) {
      delete[] newBuffer;
      throw std::runtime_error(
          "ZSTD_decompress_usingDict() failed: " +
          std::string(ZSTD_getErrorName(actualDecompressedSize)));
    }

    QNN_INFO("Successfully applied patch to buffer. Decompressed %zu bytes.",
             actualDecompressedSize);

    return std::make_unique<PatchedModelBuffer>(newBuffer,
                                                actualDecompressedSize);

  } catch (const std::exception &e) {
    QNN_ERROR("Error applying patch to buffer: %s", e.what());
    return nullptr;
  }
}

}  // namespace sample_app
}  // namespace tools
}  // namespace qnn

// ── SDXL Lowram QNN helpers ────────────────────────────────────────
void loadSdxlQnnUnetIfNeeded(AppContext &ctx) {
  std::lock_guard<std::mutex> lock(lowramMutex());
  auto &m = ctx.models;
  auto &c = ctx.conf;
  if (m.unetApp) return;
  m.unetApp = createQnnModel(c.unetPath, "unet", ctx);
  if (!m.unetApp) throw std::runtime_error("[lowram] Failed create SDXL UNET");
  if (qnn::tools::sample_app::initializeQnnApp("UNET", m.unetApp) !=
      EXIT_SUCCESS) {
    m.unetApp.reset();
    throw std::runtime_error("[lowram] Failed init SDXL UNET");
  }
  QNN_INFO("[lowram] SDXL UNET loaded");
}

void releaseSdxlQnnUnet(AppContext &ctx) {
  std::lock_guard<std::mutex> lock(lowramMutex());
  if (!ctx.models.unetApp) return;
  ctx.models.unetApp.reset();
  QNN_INFO("[lowram] SDXL UNET released");
}

void loadSdxlQnnVaeDecoderIfNeeded(AppContext &ctx) {
  std::lock_guard<std::mutex> lock(lowramMutex());
  auto &m = ctx.models;
  auto &c = ctx.conf;
  if (m.vaeDecoderApp) return;
  m.vaeDecoderApp = createQnnModel(c.vaeDecoderPath, "vae_decoder", ctx);
  if (!m.vaeDecoderApp)
    throw std::runtime_error("[lowram] Failed create SDXL VAE Decoder");
  if (qnn::tools::sample_app::initializeQnnApp("VAEDecoder", m.vaeDecoderApp) !=
      EXIT_SUCCESS) {
    m.vaeDecoderApp.reset();
    throw std::runtime_error("[lowram] Failed init SDXL VAE Decoder");
  }
  QNN_INFO("[lowram] SDXL VAE Decoder loaded");
}

void releaseSdxlQnnVaeDecoder(AppContext &ctx) {
  std::lock_guard<std::mutex> lock(lowramMutex());
  if (!ctx.models.vaeDecoderApp) return;
  ctx.models.vaeDecoderApp.reset();
  QNN_INFO("[lowram] SDXL VAE Decoder released");
}

void loadSdxlQnnVaeEncoderIfNeeded(AppContext &ctx) {
  std::lock_guard<std::mutex> lock(lowramMutex());
  auto &m = ctx.models;
  auto &c = ctx.conf;
  if (m.vaeEncoderApp) return;
  if (c.vaeEncoderPath.empty())
    throw std::runtime_error("[lowram] SDXL VAE Encoder path missing");
  m.vaeEncoderApp = createQnnModel(c.vaeEncoderPath, "vae_encoder", ctx);
  if (!m.vaeEncoderApp)
    throw std::runtime_error("[lowram] Failed create SDXL VAE Encoder");
  if (qnn::tools::sample_app::initializeQnnApp("VAEEncoder", m.vaeEncoderApp) !=
      EXIT_SUCCESS) {
    m.vaeEncoderApp.reset();
    throw std::runtime_error("[lowram] Failed init SDXL VAE Encoder");
  }
  QNN_INFO("[lowram] SDXL VAE Encoder loaded");
}

void releaseSdxlQnnVaeEncoder(AppContext &ctx) {
  std::lock_guard<std::mutex> lock(lowramMutex());
  if (!ctx.models.vaeEncoderApp) return;
  ctx.models.vaeEncoderApp.reset();
  QNN_INFO("[lowram] SDXL VAE Encoder released");
}

// ── Tile position calculation helper ───────────────────────────────
namespace {
std::vector<int> calcTilePos(int dimension, int tile_size, int min_overlap) {
  if (dimension <= tile_size) return {0};

  int num_tiles = 1;
  int effective_tile_size = tile_size - min_overlap;
  if (dimension > tile_size) {
    num_tiles +=
        (dimension - tile_size + effective_tile_size - 1) / effective_tile_size;
  }

  std::vector<int> positions;
  positions.reserve(num_tiles);
  positions.push_back(0);

  if (num_tiles == 1) return positions;

  int total_distance = dimension - tile_size;
  int num_strides = num_tiles - 1;
  int base_stride = total_distance / num_strides;
  for (int i = 1; i < num_tiles; i++) {
    positions.push_back(i * base_stride);
  }
  positions.back() = dimension - tile_size;

  return positions;
}
}  // anonymous namespace

// ── upscaleImageWithModel (QNN) ────────────────────────────────────
xt::xarray<uint8_t> upscaleImageWithModel(
    const std::vector<uint8_t> &input_image, int width, int height,
    std::unique_ptr<QnnModel> &upscaler) {
  if (!upscaler) {
    throw std::runtime_error("Upscaler model not provided");
  }

  const int tile_size = 192;
  const int output_tile_size = 768;
  const int min_overlap = 12;
  const float scale_factor = 4.0f;

  auto x_coords = calcTilePos(width, tile_size, min_overlap);
  auto y_coords = calcTilePos(height, tile_size, min_overlap);
  int num_tiles_w = x_coords.size();
  int num_tiles_h = y_coords.size();

  int output_width = width * scale_factor;
  int output_height = height * scale_factor;

  QNN_INFO("Upscaling %dx%d to %dx%d using %dx%d tiles (variable overlap)",
           width, height, output_width, output_height, num_tiles_w,
           num_tiles_h);

  std::vector<int> input_shape = {1, height, width, 3};
  xt::xarray<uint8_t> input_hwc_u8 = xt::adapt(input_image, input_shape);
  xt::xarray<float> input_hwc_f32 = xt::cast<float>(input_hwc_u8) / 255.0f;
  xt::xarray<float> input_chw =
      xt::transpose(input_hwc_f32, {0, 3, 1, 2});  // (1, 3, H, W)

  std::vector<int> output_shape = {1, 3, output_height, output_width};
  xt::xarray<float> accumulated_output = xt::zeros<float>(output_shape);
  xt::xarray<float> weight_map =
      xt::zeros<float>({output_height, output_width});

  int output_overlap = min_overlap * scale_factor;
  int fade_size = output_overlap / 2;
  xt::xarray<float> tile_weight =
      xt::ones<float>({output_tile_size, output_tile_size});

  if (fade_size > 0) {
    for (int i = 0; i < fade_size; ++i) {
      float alpha = static_cast<float>(i + 1) / fade_size;
      xt::view(tile_weight, i, xt::all()) *= alpha;
      xt::view(tile_weight, output_tile_size - 1 - i, xt::all()) *= alpha;
      xt::view(tile_weight, xt::all(), i) *= alpha;
      xt::view(tile_weight, xt::all(), output_tile_size - 1 - i) *= alpha;
    }
  }

  int tile_count = 0;
  for (int y : y_coords) {
    for (int x : x_coords) {
      xt::xarray<float> input_tile =
          xt::view(input_chw, 0, xt::all(), xt::range(y, y + tile_size),
                   xt::range(x, x + tile_size));

      std::vector<float> tile_input_vec(input_tile.begin(), input_tile.end());
      std::vector<float> tile_output_vec(1 * 3 * output_tile_size *
                                         output_tile_size);

      if (StatusCode::SUCCESS !=
          upscaler->executeUpscalerGraphs(tile_input_vec.data(),
                                          tile_output_vec.data())) {
        throw std::runtime_error("Upscaler execution failed for tile");
      }

      std::vector<int> tile_output_shape = {1, 3, output_tile_size,
                                            output_tile_size};
      xt::xarray<float> output_tile =
          xt::adapt(tile_output_vec, tile_output_shape);

      int out_x = x * scale_factor;
      int out_y = y * scale_factor;

      for (int c = 0; c < 3; ++c) {
        auto acc_slice = xt::view(accumulated_output, 0, c,
                                  xt::range(out_y, out_y + output_tile_size),
                                  xt::range(out_x, out_x + output_tile_size));
        auto tile_slice = xt::view(output_tile, 0, c, xt::all(), xt::all());
        acc_slice += tile_slice * tile_weight;
      }

      auto weight_slice =
          xt::view(weight_map, xt::range(out_y, out_y + output_tile_size),
                   xt::range(out_x, out_x + output_tile_size));
      weight_slice += tile_weight;

      tile_count++;
      std::cout << "Processed tile " << tile_count << "/"
                << (num_tiles_w * num_tiles_h) << std::endl;
    }
  }

  weight_map = xt::maximum(weight_map, 1e-8f);
  xt::xarray<float> weight_expanded =
      xt::reshape_view(weight_map, {1, 1, output_height, output_width});

  xt::xarray<float> normalized_output = accumulated_output / weight_expanded;

  auto output_hwc = xt::transpose(normalized_output, {0, 2, 3, 1});
  auto output_clamped = xt::clip(output_hwc, 0.0f, 1.0f);
  auto output_normalized = output_clamped * 255.0f;
  xt::xarray<uint8_t> output_uint8 = xt::cast<uint8_t>(output_normalized);

  return output_uint8;
}
