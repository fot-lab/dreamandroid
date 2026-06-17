// MnnHelper.cpp — MNN inference framework helpers
// Extracted from main.cpp per BKND-PROC-0008 P2 file-split plan.

#include "MnnHelper.hpp"

#include <MNN/Interpreter.hpp>
#include <MNN/MNNDefine.h>
#include <MNN/Tensor.hpp>

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <filesystem>
#include <iostream>
#include <mutex>

#include "AppContext.hpp"
#include "SDUtils.hpp"
#include "ServerState.hpp"

#include "xtensor/xadapt.hpp"
#include "xtensor/xarray.hpp"
#include "xtensor/xbuilder.hpp"
#include "xtensor/xindex_view.hpp"
#include "xtensor/xio.hpp"
#include "xtensor/xmath.hpp"
#include "xtensor/xview.hpp"

// NOTE: MmapFile lives in QnnHelper.hpp — its only consumer is
// applyZstdPatchToBuffer in QnnHelper.cpp.

// ── ensureCacheDir ─────────────────────────────────────────────────
std::string ensureCacheDir(const std::string &model_dir) {
  if (model_dir.empty()) return "";
  std::filesystem::path p = std::filesystem::path(model_dir) / "cache";
  std::error_code ec;
  std::filesystem::create_directories(p, ec);
  if (ec) return "";
  return p.string();
}

// ── createMnnInterpreterMmap ──────────────────────────────────────
MNN::Interpreter *createMnnInterpreterMmap(const char *path) {
  int fd = open(path, O_RDONLY);
  if (fd < 0) {
    return MNN::Interpreter::createFromFile(path);
  }
  struct stat st{};
  if (0 != fstat(fd, &st) || st.st_size <= 0) {
    close(fd);
    return MNN::Interpreter::createFromFile(path);
  }
  size_t size = static_cast<size_t>(st.st_size);
  void *mapped = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
  // The mapping holds its own file reference, so the fd can be closed now.
  close(fd);
  if (MAP_FAILED == mapped) {
    return MNN::Interpreter::createFromFile(path);
  }
  // MNN copies the whole buffer once, sequentially; hint readahead to match.
  madvise(mapped, size, MADV_SEQUENTIAL);
  MNN::Interpreter *interpreter =
      MNN::Interpreter::createFromBuffer(mapped, size);
  munmap(mapped, size);
  if (interpreter) {
    // createFromFile sets a default external weight path; createFromBuffer does
    // not. Mirror it so models that store weights in a companion ".weight" file
    // still resolve them at session creation. Harmless when no such file
    // exists.
    interpreter->setExternalFile((std::string(path) + ".weight").c_str());
  }
  return interpreter;
}

// ── SDXL Lowram CLIP (MNN) ────────────────────────────────────────
void loadSdxlClipMnnIfNeeded(AppContext &ctx) {
  std::lock_guard<std::mutex> lock(lowramMutex());
  auto &m = ctx.models;
  auto &c = ctx.conf;
  if (!m.clipInterpreter) {
    m.clipInterpreter = createMnnInterpreterMmap(c.clipPath.c_str());
    if (!m.clipInterpreter)
      throw std::runtime_error("[lowram] Failed load SDXL CLIP1 MNN");
  }
  if (!m.clip2Interpreter) {
    m.clip2Interpreter = createMnnInterpreterMmap(c.clip2Path.c_str());
    if (!m.clip2Interpreter)
      throw std::runtime_error("[lowram] Failed load SDXL CLIP2 MNN");
  }
  MNN::ScheduleConfig cfg;
  cfg.type = MNN_FORWARD_CPU;
  cfg.numThread = 4;
  MNN::BackendConfig bk;
  bk.memory = MNN::BackendConfig::Memory_Low;
  bk.power = MNN::BackendConfig::Power_High;
  cfg.backendConfig = &bk;
  if (!m.clipSession) {
    m.clipSession = m.clipInterpreter->createSession(cfg);
    if (!m.clipSession)
      throw std::runtime_error("[lowram] Failed create SDXL CLIP1 session");
    auto in1 = m.clipInterpreter->getSessionInput(m.clipSession, "input_embedding");
    m.clipInterpreter->resizeTensor(in1, {1, 77, text_embedding_size});
    m.clipInterpreter->resizeSession(m.clipSession);
    m.clipInterpreter->releaseModel();
  }
  if (!m.clip2Session) {
    m.clip2Session = m.clip2Interpreter->createSession(cfg);
    if (!m.clip2Session)
      throw std::runtime_error("[lowram] Failed create SDXL CLIP2 session");
    auto in2 =
        m.clip2Interpreter->getSessionInput(m.clip2Session, "input_embedding");
    m.clip2Interpreter->resizeTensor(in2, {1, 77, text_embedding_size_2});
    m.clip2Interpreter->resizeSession(m.clip2Session);
    m.clip2Interpreter->releaseModel();
  }
  QNN_INFO("[lowram] SDXL CLIP MNN loaded");
}

void releaseSdxlClipMnn(AppContext &ctx) {
  std::lock_guard<std::mutex> lock(lowramMutex());
  auto &m = ctx.models;
  if (m.clipSession && m.clipInterpreter) {
    m.clipInterpreter->releaseSession(m.clipSession);
  }
  m.clipSession = nullptr;
  if (m.clip2Session && m.clip2Interpreter) {
    m.clip2Interpreter->releaseSession(m.clip2Session);
  }
  m.clip2Session = nullptr;
  if (m.clipInterpreter) {
    delete m.clipInterpreter;
    m.clipInterpreter = nullptr;
  }
  if (m.clip2Interpreter) {
    delete m.clip2Interpreter;
    m.clip2Interpreter = nullptr;
  }
  QNN_INFO("[lowram] SDXL CLIP MNN released");
}

// ── calculateTilePositions helper ──────────────────────────────────
namespace {
std::vector<int> calculateTilePositions(int dimension, int tile_size,
                                        int min_overlap) {
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
    if (i == num_tiles - 1) {
      positions.push_back(dimension - tile_size);
    } else {
      positions.push_back(i * base_stride);
    }
  }

  return positions;
}
}  // anonymous namespace

// ── upscaleImageWithMNN ───────────────────────────────────────────
xt::xarray<uint8_t> upscaleImageWithMNN(const std::vector<uint8_t> &input_image,
                                        int width, int height,
                                        const std::string &model_path,
                                        bool use_opencl) {
  const int tile_size = 192;
  const int output_tile_size = 768;
  const int min_overlap = 12;
  const float scale_factor = 4.0f;

  auto interpreter = std::shared_ptr<MNN::Interpreter>(
      createMnnInterpreterMmap(model_path.c_str()));
  if (!interpreter) {
    throw std::runtime_error("Failed to create MNN interpreter from: " +
                             model_path);
  }

  MNN::ScheduleConfig config;
  MNN::BackendConfig backendConfig;
  if (use_opencl) {
    auto cache_dir = ensureCacheDir(
        std::filesystem::path(model_path).parent_path().string());
    auto cache_file =
        (cache_dir.empty()
             ? model_path
             : cache_dir + "/" +
                   std::filesystem::path(model_path).filename().string()) +
        ".mnnc";
    interpreter->setCacheFile(cache_file.c_str());
    config.type = MNN_FORWARD_OPENCL;
    config.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
    backendConfig.precision = MNN::BackendConfig::Precision_Low;
  } else {
    config.type = MNN_FORWARD_CPU;
    config.numThread = 4;
    backendConfig.memory = MNN::BackendConfig::Memory_Low;
  }
  backendConfig.power = MNN::BackendConfig::Power_High;
  config.backendConfig = &backendConfig;

  auto session = interpreter->createSession(config);
  if (!session) {
    throw std::runtime_error("Failed to create MNN session");
  }

  auto x_coords = calculateTilePositions(width, tile_size, min_overlap);
  auto y_coords = calculateTilePositions(height, tile_size, min_overlap);
  int num_tiles_w = x_coords.size();
  int num_tiles_h = y_coords.size();

  int output_width = width * scale_factor;
  int output_height = height * scale_factor;

  QNN_INFO("Upscaling %dx%d to %dx%d using MNN (%s), %dx%d tiles", width,
           height, output_width, output_height, use_opencl ? "OpenCL" : "CPU",
           num_tiles_w, num_tiles_h);

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

  // Get input and output tensors
  auto input_tensor = interpreter->getSessionInput(session, nullptr);
  auto output_tensor = interpreter->getSessionOutput(session, nullptr);

  int tile_count = 0;
  for (int y : y_coords) {
    for (int x : x_coords) {
      xt::xarray<float> input_tile =
          xt::view(input_chw, 0, xt::all(), xt::range(y, y + tile_size),
                   xt::range(x, x + tile_size));

      // Prepare input tensor
      std::vector<int> dims = {1, 3, tile_size, tile_size};
      interpreter->resizeTensor(input_tensor, dims);
      interpreter->resizeSession(session);

      auto host_tensor = MNN::Tensor::create<float>(
          dims, const_cast<float *>(input_tile.data()), MNN::Tensor::CAFFE);
      input_tensor->copyFromHostTensor(host_tensor);
      delete host_tensor;

      // Run inference
      if (interpreter->runSession(session) != 0) {
        throw std::runtime_error("MNN inference failed for tile");
      }

      // Get output
      auto output_host =
          MNN::Tensor::create<float>({1, 3, output_tile_size, output_tile_size},
                                     nullptr, MNN::Tensor::CAFFE);
      output_tensor->copyToHostTensor(output_host);

      std::vector<int> tile_output_shape = {1, 3, output_tile_size,
                                            output_tile_size};
      xt::xarray<float> output_tile = xt::adapt(
          output_host->host<float>(), output_tile_size * output_tile_size * 3,
          xt::no_ownership(), tile_output_shape);

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

      delete output_host;

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
