// ServerCli.cpp — CLI argument parsing for DreamHub C++ backend
// Extracted from main.cpp per BKND-PROC-0008 P3 file-split plan.
//
// Parameter model: --type + --model_dir with fixed per-type file layout.
// Each type implies the full file layout under --model_dir, the
// diffusion backend (MNN vs QNN), and the CLIP pipeline; nothing else
// is configurable per component.
//   sd15cpu: tokenizer.json clip_v2.mnn pos_emb.bin token_emb.bin
//            unet.mnn vae_encoder.mnn vae_decoder.mnn
//   sd15npu: tokenizer.json clip_v2.mnn pos_emb.bin token_emb.bin
//            unet.bin vae_encoder.bin vae_decoder.bin [+resolution patches]
//   sdxl:    tokenizer.json clip.mnn pos_emb.bin token_emb.bin clip_2.mnn
//            pos_emb_2.bin token_emb_2.bin unet.bin vae_encoder.bin
//            vae_decoder.bin
// CLIP always runs on MNN (CPU) with precomputed token/pos embeddings.

#include "ServerCli.hpp"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <filesystem>
#include <fstream>
#include <iostream>
#include <vector>

#include "AppContext.hpp"
#include "BuildId.hpp"
#include "DynamicLoadUtil.hpp"
#include "FloatConversion.hpp"
#include "Logger.hpp"
#include "MnnHelper.hpp"
#include "PAL/DynamicLoading.hpp"
#include "PAL/GetOpt.hpp"
#include "QnnHelper.hpp"
#include "QnnSampleAppUtils.hpp"
#include "SDUtils.hpp"
#include "SafeTensor2MNN.hpp"

void showHelp() {
  std::cout
      << "Usage:\n"
         "  stable_diffusion_core --type <sd15cpu|sd15npu|sdxl> "
         "--model_dir <dir> [--lib_dir <dir>] [options]\n"
         "  stable_diffusion_core --upscaler_mode [--lib_dir <dir>] "
         "[options]\n"
         "  stable_diffusion_core --convert <dir> [--clip_skip_2]\n"
         "\n"
         "Modes:\n"
         "  --type <type>          Model format: sd15cpu (MNN), sd15npu "
         "(QNN), sdxl (QNN)\n"
         "  --upscaler_mode        Upscale-only server, no diffusion model\n"
         "  --convert <dir>        Convert model.safetensors in <dir> to MNN "
         "and exit\n"
         "\n"
         "Paths:\n"
         "  --model_dir <dir>      Directory with the fixed per-type model "
         "files\n"
         "  --lib_dir <dir>        Directory with libQnnHtp.so / "
         "libQnnSystem.so (QNN types)\n"
         "  --patch <file>         zstd resolution patch for unet.bin "
         "(sd15npu)\n"
         "  --safety_checker <f>   NSFW checker MNN model\n"
         "\n"
         "Options:\n"
         "  --port <n>             HTTP port (default 8081)\n"
         "  --listen_all           Listen on 0.0.0.0 instead of 127.0.0.1\n"
         "  --no_img2img           Do not load the VAE encoder\n"
         "  --use_v_pred           v-prediction model\n"
         "  --lowram               (sdxl) load/release models per stage\n"
         "  --clip_skip_2          (convert) export CLIP with skip 2\n"
         "  --log_level <n>        QNN log level\n"
         "  --version              Print QNN SDK build id\n"
         "  --help                 Show this help\n";
}

void showHelpAndExit(std::string &&error) {
  std::cerr << "ERROR: " << error << "\n";
  showHelp();
  std::exit(EXIT_FAILURE);
}

void processCommandLine(int argc, char **argv, AppContext &ctx) {
  using namespace qnn::tools;
  auto &c = ctx.conf;
  auto &m = ctx.models;

  enum OPTIONS {
    OPT_HELP = 0,
    OPT_VERSION,
    OPT_TYPE,
    OPT_MODEL_DIR,
    OPT_LIB_DIR,
    OPT_PORT,
    OPT_LISTEN_ALL,
    OPT_NO_IMG2IMG,
    OPT_USE_V_PRED,
    OPT_SAFETY_CHECKER,
    OPT_CONVERT,
    OPT_CONVERT_CLIP_SKIP_2,
    OPT_PATCH,
    OPT_UPSCALER_MODE,
    OPT_LOWRAM,
    OPT_LOG_LEVEL
  };
  static struct pal::Option s_longOptions[] = {
      {"help", pal::no_argument, NULL, OPT_HELP},
      {"version", pal::no_argument, NULL, OPT_VERSION},
      {"type", pal::required_argument, NULL, OPT_TYPE},
      {"model_dir", pal::required_argument, NULL, OPT_MODEL_DIR},
      {"lib_dir", pal::required_argument, NULL, OPT_LIB_DIR},
      {"port", pal::required_argument, NULL, OPT_PORT},
      {"listen_all", pal::no_argument, NULL, OPT_LISTEN_ALL},
      {"no_img2img", pal::no_argument, NULL, OPT_NO_IMG2IMG},
      {"use_v_pred", pal::no_argument, NULL, OPT_USE_V_PRED},
      {"safety_checker", pal::required_argument, NULL, OPT_SAFETY_CHECKER},
      {"convert", pal::required_argument, NULL, OPT_CONVERT},
      {"clip_skip_2", pal::no_argument, NULL, OPT_CONVERT_CLIP_SKIP_2},
      {"patch", pal::required_argument, NULL, OPT_PATCH},
      {"upscaler_mode", pal::no_argument, NULL, OPT_UPSCALER_MODE},
      {"lowram", pal::no_argument, NULL, OPT_LOWRAM},
      {"log_level", pal::required_argument, NULL, OPT_LOG_LEVEL},
      {NULL, 0, NULL, 0}};

  std::string typeStr, libDir;
  QnnLog_Level_t logLevel = QNN_LOG_LEVEL_ERROR;
  int longIndex = 0, opt = 0;
  while ((opt = pal::getOptLongOnly(argc, argv, "", s_longOptions,
                                    &longIndex)) != -1) {
    switch (opt) {
      case OPT_HELP:
        showHelp();
        std::exit(EXIT_SUCCESS);
        break;
      case OPT_VERSION:
        std::cout << "QNN SDK " << qnn::tools::getBuildId() << "\n";
        std::exit(EXIT_SUCCESS);
        break;
      case OPT_TYPE:
        typeStr = pal::g_optArg;
        break;
      case OPT_MODEL_DIR:
        c.modelDir = pal::g_optArg;
        break;
      case OPT_LIB_DIR:
        libDir = pal::g_optArg;
        break;
      case OPT_PORT:
        c.port = std::stoi(pal::g_optArg);
        break;
      case OPT_LISTEN_ALL:
        c.listen_address = "0.0.0.0";
        break;
      case OPT_NO_IMG2IMG:
        c.no_img2img = true;
        break;
      case OPT_USE_V_PRED:
        c.use_v_pred = true;
        break;
      case OPT_SAFETY_CHECKER:
        c.use_safety_checker = true;
        c.safetyCheckerPath = pal::g_optArg;
        break;
      case OPT_CONVERT:
        c.cvt_model = true;
        c.modelDir = pal::g_optArg;
        break;
      case OPT_CONVERT_CLIP_SKIP_2:
        m.clip_skip_2 = true;
        break;
      case OPT_PATCH:
        c.patchPath = pal::g_optArg;
        break;
      case OPT_UPSCALER_MODE:
        c.upscaler_mode = true;
        break;
      case OPT_LOWRAM:
        c.lowram_mode = true;
        break;
      case OPT_LOG_LEVEL:
        logLevel = sample_app::parseLogLevel(pal::g_optArg);
        if (logLevel != QNN_LOG_LEVEL_MAX) {
          if (!qnn::log::setLogLevel(logLevel))
            showHelpAndExit("Unable to set log level.");
        }
        break;
      default:
        showHelpAndExit("Invalid argument passed.");
    }
  }

  // QNN backend stubs (HTP + system) are resolved inside --lib_dir.
  auto initQnn = [&libDir, &c, &m]() {
    if (libDir.empty()) showHelpAndExit("Missing --lib_dir for QNN");
    std::filesystem::path lib(libDir);
    c.backendPathCmd = (lib / "libQnnHtp.so").string();
    dynamicloadutil::StatusCode sysStatus =
        dynamicloadutil::getQnnSystemFunctionPointers(
            (lib / "libQnnSystem.so").string(), &m.qnnSystemFuncs);
    if (sysStatus != dynamicloadutil::StatusCode::SUCCESS)
      showHelpAndExit("Failed get QNN system func ptrs.");
  };

  // ── upscaler mode ──
  if (c.upscaler_mode) {
    // QNN upscalers need --lib_dir; MNN-only upscaling runs without it.
    if (!libDir.empty()) initQnn();
    return;
  }

  // ── convert mode ──
  if (c.cvt_model) {
    if (!std::filesystem::exists(c.modelDir)) {
      showHelpAndExit("Model directory does not exist: " + c.modelDir);
    }
    std::string model_name = "model.safetensors";
    auto model_path = std::filesystem::path(c.modelDir) / model_name;
    if (!std::filesystem::exists(model_path)) {
      showHelpAndExit("Model file does not exist");
    }

    std::vector<std::string> loras;
    std::vector<float> lora_weights;
    for (int i = 1;; ++i) {
      std::string lora_filename = "lora." + std::to_string(i) + ".safetensors";
      auto lora_path = std::filesystem::path(c.modelDir) / lora_filename;
      if (!std::filesystem::exists(lora_path)) {
        break;
      }
      loras.push_back(lora_filename);

      std::string weight_filename = "lora." + std::to_string(i) + ".weight";
      auto weight_path = std::filesystem::path(c.modelDir) / weight_filename;
      float weight = 1.0f;

      if (std::filesystem::exists(weight_path)) {
        std::ifstream weight_file(weight_path);
        if (weight_file.is_open()) {
          weight_file >> weight;
          weight_file.close();
        }
      }
      lora_weights.push_back(weight);
    }

    generateMNNModels(c.modelDir, model_name, m.clip_skip_2, loras,
                      lora_weights);
    exit(EXIT_SUCCESS);
  }

  // ── diffusion mode: validate type and model_dir ──
  if (typeStr == "sd15cpu")
    c.use_mnn = true;
  else if (typeStr == "sdxl")
    c.sdxl_mode = true;
  else if (typeStr != "sd15npu")
    showHelpAndExit(typeStr.empty() ? "Missing --type"
                                    : "Invalid --type: " + typeStr);
  if (c.modelDir.empty()) showHelpAndExit("Missing --model_dir");
  if (c.use_safety_checker && c.safetyCheckerPath.empty())
    showHelpAndExit("Missing safety checker path");

  if (c.sdxl_mode) {
    QNN_INFO("SDXL mode: text_embedding_size=%d, text_embedding_size_2=%d",
             text_embedding_size, text_embedding_size_2);
  }

  // Fixed per-type file layout under --model_dir.
  const std::filesystem::path dir(c.modelDir);
  const std::string ext = c.use_mnn ? ".mnn" : ".bin";
  c.tokenizerPath = (dir / "tokenizer.json").string();
  c.clipPath = (dir / (c.sdxl_mode ? "clip.mnn" : "clip_v2.mnn")).string();
  c.unetPath = (dir / ("unet" + ext)).string();
  c.vaeDecoderPath = (dir / ("vae_decoder" + ext)).string();
  if (!c.no_img2img) c.vaeEncoderPath = (dir / ("vae_encoder" + ext)).string();
  if (c.sdxl_mode) c.clip2Path = (dir / "clip_2.mnn").string();

  std::vector<std::string> required = {
      c.tokenizerPath,
      c.clipPath,
      c.unetPath,
      c.vaeDecoderPath,
      (dir / "pos_emb.bin").string(),
      (dir / "token_emb.bin").string(),
  };
  if (!c.vaeEncoderPath.empty()) required.push_back(c.vaeEncoderPath);
  if (c.sdxl_mode) {
    required.push_back(c.clip2Path);
    required.push_back((dir / "pos_emb_2.bin").string());
    required.push_back((dir / "token_emb_2.bin").string());
  }
  for (const auto &p : required) {
    if (!std::filesystem::exists(p)) showHelpAndExit("File not found: " + p);
  }

  // ── Load the precomputed CLIP token/position embedding tables ──
  auto loadTokenEmb = [](const std::filesystem::path &tokenEmbPath,
                         TokenEmbTable &dst, bool force_fp16) {
    std::ifstream tokenFile(tokenEmbPath, std::ios::binary);
    tokenFile.seekg(0, std::ios::end);
    size_t fileSize = tokenFile.tellg();
    tokenFile.seekg(0, std::ios::beg);

    const size_t SIZE_THRESHOLD = 100 * 1024 * 1024;  // 100MB
    if (!force_fp16 && fileSize > SIZE_THRESHOLD) {
      // FP32 on disk: narrow to FP16 in an owned buffer (cannot be mapped as
      // uint16 directly). This branch is the legacy SD1.5 large-table path.
      size_t tokenSize = fileSize / sizeof(float);
      std::vector<float> tempBuffer(tokenSize);
      tokenFile.read(reinterpret_cast<char *>(tempBuffer.data()), fileSize);
      std::vector<uint16_t> converted(tokenSize);
      for (size_t i = 0; i < tokenSize; i++) {
        converted[i] = fp32_to_fp16(tempBuffer[i]);
      }
      dst.setOwned(std::move(converted));
      QNN_INFO("Loaded %s: %zu floats (converted FP32->FP16)",
               tokenEmbPath.filename().string().c_str(), tokenSize);
      return;
    }

    // FP16 on disk: map read-only and look up lazily. Token lookups are
    // sparse, so MADV_RANDOM avoids pointless readahead of untouched rows.
    tokenFile.close();
    size_t tokenSize = fileSize / sizeof(uint16_t);
    int fd = open(tokenEmbPath.c_str(), O_RDONLY);
    void *mapped = MAP_FAILED;
    if (fd >= 0) {
      mapped = mmap(nullptr, fileSize, PROT_READ, MAP_PRIVATE, fd, 0);
      close(fd);
    }
    if (mapped != MAP_FAILED) {
      madvise(mapped, fileSize, MADV_RANDOM);
      dst.setMapped(mapped, fileSize);
      QNN_INFO("Mapped %s: %zu elements (FP16, mmap)",
               tokenEmbPath.filename().string().c_str(), tokenSize);
      return;
    }

    // Fallback: read into an owned buffer if the mapping failed.
    std::ifstream fallback(tokenEmbPath, std::ios::binary);
    std::vector<uint16_t> owned(tokenSize);
    fallback.read(reinterpret_cast<char *>(owned.data()), fileSize);
    dst.setOwned(std::move(owned));
    QNN_INFO("Loaded %s: %zu elements (FP16)",
             tokenEmbPath.filename().string().c_str(), tokenSize);
  };

  auto loadPosEmb = [](const std::filesystem::path &posEmbPath,
                       std::vector<float> &dst) {
    std::ifstream posFile(posEmbPath, std::ios::binary);
    posFile.seekg(0, std::ios::end);
    size_t posSize = posFile.tellg() / sizeof(float);
    posFile.seekg(0, std::ios::beg);
    dst.resize(posSize);
    posFile.read(reinterpret_cast<char *>(dst.data()), posSize * sizeof(float));
    posFile.close();
    QNN_INFO("Loaded %s: %zu floats", posEmbPath.filename().string().c_str(),
             posSize);
  };

  loadPosEmb(dir / "pos_emb.bin", m.pos_emb);
  // SD1.5 token_emb may still be legacy FP32 (detected by file size); SDXL
  // tables are always FP16.
  loadTokenEmb(dir / "token_emb.bin", m.token_emb, /*force_fp16=*/c.sdxl_mode);
  if (c.sdxl_mode) {
    loadPosEmb(dir / "pos_emb_2.bin", m.pos_emb_2);
    loadTokenEmb(dir / "token_emb_2.bin", m.token_emb_2, /*force_fp16=*/true);
  }

  if (c.use_safety_checker) {
    m.safetyCheckerInterpreter =
        createMnnInterpreterMmap(c.safetyCheckerPath.c_str());
    if (!m.safetyCheckerInterpreter)
      showHelpAndExit("Failed load Safety MNN: " + c.safetyCheckerPath);
  }

  // CLIP always runs on MNN. sd15npu keeps a persistent interpreter, sd15cpu
  // reloads it per request to keep idle memory low, and SDXL keeps both
  // encoders resident unless --lowram.
  if (!c.use_mnn && !c.sdxl_mode) {
    m.clipInterpreter = createMnnInterpreterMmap(c.clipPath.c_str());
    if (!m.clipInterpreter)
      showHelpAndExit("Failed load CLIP MNN: " + c.clipPath);
  }
  if (c.sdxl_mode && !c.lowram_mode) {
    m.clipInterpreter = createMnnInterpreterMmap(c.clipPath.c_str());
    if (!m.clipInterpreter)
      showHelpAndExit("Failed load SDXL CLIP1 MNN: " + c.clipPath);
    m.clip2Interpreter = createMnnInterpreterMmap(c.clip2Path.c_str());
    if (!m.clip2Interpreter)
      showHelpAndExit("Failed load SDXL CLIP2 MNN: " + c.clip2Path);
  }

  if (c.use_mnn) {
    return;
  }

  initQnn();

  if (!c.patchPath.empty()) {
    QNN_INFO("Applying patch to unet model in memory...");
    m.unetPatchedBuffer = applyZstdPatchToBuffer(c.unetPath, c.patchPath);
    if (!m.unetPatchedBuffer) {
      showHelpAndExit("Failed to apply patch to unet model buffer");
    }
    QNN_INFO("Patch applied successfully to buffer (size: %llu bytes)",
             m.unetPatchedBuffer->size);

    try {
      std::filesystem::path patchFile(c.patchPath);
      std::filesystem::path patchDir = patchFile.parent_path();

      size_t totalFreed = 0;
      int filesRemoved = 0;

      for (const auto &entry :
           std::filesystem::directory_iterator(patchDir)) {
        if (entry.is_regular_file()) {
          std::string filename = entry.path().filename().string();

          if (filename.rfind("unet.bin.", 0) == 0 && filename.length() > 9) {
            try {
              auto fileSize = entry.file_size();
              std::filesystem::remove(entry.path());
              totalFreed += fileSize;
              filesRemoved++;
              QNN_INFO("Cleaned up old patched file: %s (%.2f MB)",
                       entry.path().string().c_str(),
                       fileSize / (1024.0 * 1024.0));
            } catch (const std::exception &e) {
              QNN_WARN("Failed to remove file %s: %s",
                       entry.path().string().c_str(), e.what());
            }
          }
        }
      }

      if (filesRemoved > 0) {
        QNN_INFO("Total: cleaned up %d old patched file(s), freed %.2f MB",
                 filesRemoved, totalFreed / (1024.0 * 1024.0));
      } else {
        QNN_DEBUG("No old patched files to clean up");
      }
    } catch (const std::exception &e) {
      QNN_WARN("Failed to clean up old patched files: %s", e.what());
    }
  }

  bool sdxl_lowram = c.sdxl_mode && c.lowram_mode;

  if (!sdxl_lowram) {
    m.unetApp = createQnnModel(c.unetPath, "unet", ctx);
    if (!m.unetApp) showHelpAndExit("Failed create QNN UNET model.");

    m.vaeDecoderApp = createQnnModel(c.vaeDecoderPath, "vae_decoder", ctx);
    if (!m.vaeDecoderApp)
      showHelpAndExit("Failed create QNN VAE Decoder model.");

    if (!c.vaeEncoderPath.empty()) {
      m.vaeEncoderApp = createQnnModel(c.vaeEncoderPath, "vae_encoder", ctx);
      if (!m.vaeEncoderApp) QNN_WARN("Failed create QNN VAE Enc model.");
    } else
      QNN_INFO("img2img disabled: VAE encoder not loaded");
  } else {
    QNN_INFO(
        "[lowram] SDXL low-RAM mode: skipping pre-load of UNET/VAE QNN models");
  }
}
