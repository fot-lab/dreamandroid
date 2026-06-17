// ── BKND-PROC-0008 P4: Model Loader extracted from main() ──
//
//  Initializes tokenizer, embeddings, MNN sessions, and QNN models.
//  All state flows through AppContext& — no extern globals.

#include "ModelLoader.hpp"

#include <filesystem>
#include <iostream>
#include <stdexcept>
#include <string>

#include "Logger.hpp"
#include "QnnSampleAppUtils.hpp"
#include "SDUtils.hpp"

#include <MNN/MNNDefine.h>
#include <MNN/Interpreter.hpp>
#include "tokenizers_cpp.h"

using namespace qnn::tools;

int initializeModels(AppContext &ctx) {
  auto &conf   = ctx.conf;
  auto &models = ctx.models;

  if (!conf.upscaler_mode) {
    // ── Tokenizer ──
    try {
      auto blob = LoadBytesFromFile(conf.tokenizerPath);
      models.tokenizer = tokenizers::Tokenizer::FromBlobJSON(blob);
      if (!models.tokenizer)
        throw std::runtime_error("Tokenizer creation failed.");
    } catch (const std::exception &e) {
      std::cerr << "Failed load tokenizer: " << e.what() << std::endl;
      return EXIT_FAILURE;
    }

    // ── Embeddings ──
    if (!conf.modelDir.empty()) {
      std::filesystem::path modelPath(conf.modelDir);
      std::filesystem::path embeddingsPath =
          modelPath.parent_path().parent_path() / "embeddings";
      if (std::filesystem::exists(embeddingsPath)) {
        try {
          models.promptProcessor.loadEmbeddings(embeddingsPath.string(),
                                                conf.sdxl_mode);
          QNN_INFO("Loaded %zu embeddings (SDXL=%d) from %s",
                   models.promptProcessor.getEmbeddingCount(),
                   conf.sdxl_mode ? 1 : 0,
                   embeddingsPath.string().c_str());
        } catch (const std::exception &e) {
          QNN_WARN("Failed to load embeddings: %s", e.what());
        }
      } else {
        QNN_INFO("Embeddings directory not found: %s",
                 embeddingsPath.string().c_str());
      }
    }

    // ── MNN Sessions ──
    MNN::ScheduleConfig cfg_common;
    cfg_common.type = MNN_FORWARD_CPU;
    cfg_common.numThread = 1;
    MNN::BackendConfig bkCfg_common;
    bkCfg_common.memory = MNN::BackendConfig::Memory_Low;
    bkCfg_common.power = MNN::BackendConfig::Power_High;
    cfg_common.backendConfig = &bkCfg_common;
    MNN::ScheduleConfig cfg_mnn_clip = cfg_common;
    cfg_mnn_clip.numThread = 4;

    if (!conf.sdxl_mode && models.clipInterpreter) {
      models.clipSession =
          models.clipInterpreter->createSession(cfg_mnn_clip);
      if (!models.clipSession)
        QNN_ERROR("Failed create persistent MNN CLIP session!");
      else {
        QNN_INFO("Persistent MNN CLIP session created.");
        auto input = models.clipInterpreter->getSessionInput(
            models.clipSession, "input_embedding");
        models.clipInterpreter->resizeTensor(input, {1, 77, 768});
        models.clipInterpreter->resizeSession(models.clipSession);
        models.clipInterpreter->releaseModel();
      }
    }

    if (conf.sdxl_mode && !conf.lowram_mode &&
        models.clipInterpreter && models.clip2Interpreter) {
      models.clipSession =
          models.clipInterpreter->createSession(cfg_mnn_clip);
      models.clip2Session =
          models.clip2Interpreter->createSession(cfg_mnn_clip);
      if (!models.clipSession || !models.clip2Session) {
        QNN_ERROR("Failed create persistent SDXL MNN CLIP sessions!");
      } else {
        QNN_INFO("Persistent SDXL MNN CLIP1/CLIP2 sessions created.");
        auto input1 = models.clipInterpreter->getSessionInput(
            models.clipSession, "input_embedding");
        models.clipInterpreter->resizeTensor(
            input1, {1, 77, text_embedding_size});
        models.clipInterpreter->resizeSession(models.clipSession);
        models.clipInterpreter->releaseModel();

        auto input2 = models.clip2Interpreter->getSessionInput(
            models.clip2Session, "input_embedding");
        models.clip2Interpreter->resizeTensor(
            input2, {1, 77, text_embedding_size_2});
        models.clip2Interpreter->resizeSession(models.clip2Session);
        models.clip2Interpreter->releaseModel();
      }
    }

    if (models.safetyCheckerInterpreter) {
      models.safetyCheckerSession =
          models.safetyCheckerInterpreter->createSession(cfg_common);
      if (!models.safetyCheckerSession)
        QNN_ERROR("Failed create persistent MNN Safety session!");
      else {
        QNN_INFO("Persistent MNN Safety session created.");
        auto input = models.safetyCheckerInterpreter->getSessionInput(
            models.safetyCheckerSession, nullptr);
        models.safetyCheckerInterpreter->resizeTensor(
            input, {1, 224, 224, 3});
        models.safetyCheckerInterpreter->resizeSession(
            models.safetyCheckerSession);
        models.safetyCheckerInterpreter->releaseModel();
      }
    }

    // ── Initialize QNN Models ──
    if (!conf.use_mnn) {
      int status = EXIT_SUCCESS;
      if (models.unetApp) {
        if (models.unetPatchedBuffer && models.unetPatchedBuffer->buffer) {
          status = sample_app::initializeQnnApp(
              "UNET", models.unetApp,
              models.unetPatchedBuffer->buffer.get(),
              models.unetPatchedBuffer->size);
        } else {
          status = sample_app::initializeQnnApp("UNET", models.unetApp);
        }
        if (status != EXIT_SUCCESS) return status;

        if (models.unetPatchedBuffer) {
          QNN_INFO("Releasing unet patch buffer to free memory");
          models.unetPatchedBuffer.reset();
        }
      }
      if (models.vaeDecoderApp) {
        status = sample_app::initializeQnnApp("VAEDecoder",
                                               models.vaeDecoderApp);
        if (status != EXIT_SUCCESS) return status;
      }
      if (models.vaeEncoderApp) {
        status = sample_app::initializeQnnApp("VAEEncoder",
                                               models.vaeEncoderApp);
        if (status != EXIT_SUCCESS) return status;
      }
    }
  } else {
    QNN_INFO("Upscaler mode - skipping MNN and QNN model initialization");
  }

  return EXIT_SUCCESS;
}
