// ── BKND-PROC-0008 P3: Text Encoder implementation ──

#include "TextEncoder.hpp"

#include "AppContext.hpp"
#include "FloatConversion.hpp"
#include "tokenizers_cpp.h"

#include <iostream>

ProcessedPrompt processWeightedPrompt(AppContext &ctx,
                                      const std::string &prompt_text,
                                      int max_len) {
  const auto &gconf = ctx.conf;
  auto &gmod = ctx.models;

  ProcessedPrompt result;

  auto tokens = gmod.promptProcessor.process(prompt_text);

  const int dim1 = 768;
  const int dim2 = text_embedding_size_2;

  std::vector<float> embeddings(max_len * dim1, 0.0f);
  std::vector<float> embeddings_2;
  if (gconf.sdxl_mode) embeddings_2.assign(max_len * dim2, 0.0f);
  std::vector<int> ids;
  std::vector<float> weights;

  int current_pos = 1;
  ids.push_back(49406);  // BOS token

  for (const auto &token : tokens) {
    if (current_pos >= max_len - 1) break;

    if (token.is_embedding) {
      int emb_tokens = 0;
      if (!token.embedding_data.empty())
        emb_tokens = token.embedding_data.size() / dim1;
      else if (gconf.sdxl_mode && !token.embedding_data_2.empty())
        emb_tokens = token.embedding_data_2.size() / dim2;

      for (int i = 0; i < emb_tokens && current_pos < max_len - 1; i++) {
        ids.push_back(49407);
        if (!token.embedding_data.empty()) {
          for (int j = 0; j < dim1; j++) {
            embeddings[current_pos * dim1 + j] =
                token.embedding_data[i * dim1 + j] * token.weight;
          }
        }
        if (gconf.sdxl_mode && !token.embedding_data_2.empty()) {
          for (int j = 0; j < dim2; j++) {
            embeddings_2[current_pos * dim2 + j] =
                token.embedding_data_2[i * dim2 + j] * token.weight;
          }
        }
        weights.push_back(token.weight);
        current_pos++;
      }
    } else {
      // tokenize
      std::vector<int> token_ids = gmod.tokenizer->Encode(token.text);

      for (int tid : token_ids) {
        if (current_pos >= max_len - 1) break;
        ids.push_back(tid);

        if (current_pos < max_len) {
          weights.push_back(token.weight);
        }
        current_pos++;
      }
    }
  }

  while (ids.size() < max_len) {
    ids.push_back(49407);  // PAD/EOS token
    weights.push_back(1.0f);
  }

  if (ids.size() > max_len) {
    ids.resize(max_len);
  }

  result.ids = ids;

  // SDXL encoder 2 uses pad id 0 instead of 49407 after the first EOS.
  if (gconf.sdxl_mode) {
    std::vector<int> ids2 = ids;
    int eos_pos = -1;
    for (int i = 1; i < max_len; i++) {
      if (ids2[i] == 49407) {
        eos_pos = i;
        break;
      }
    }
    if (eos_pos >= 0) {
      for (int i = eos_pos + 1; i < max_len; i++) ids2[i] = 0;
    }
    result.ids_2 = ids2;
  }

  if (!gmod.token_emb.empty() && !gmod.pos_emb.empty()) {
    for (int i = 0; i < max_len; i++) {
      int token_id = ids[i];
      float weight = (i < (int)weights.size()) ? weights[i] : 1.0f;

      bool has_emb = false;
      for (int j = 0; j < dim1; j++) {
        if (embeddings[i * dim1 + j] != 0.0f) {
          has_emb = true;
          break;
        }
      }

      if (!has_emb) {
        for (int j = 0; j < dim1; j++) {
          float token_val = fp16_to_fp32(gmod.token_emb[token_id * dim1 + j]);
          embeddings[i * dim1 + j] = token_val * weight + gmod.pos_emb[i * dim1 + j];
        }
      } else {
        for (int j = 0; j < dim1; j++) {
          embeddings[i * dim1 + j] += gmod.pos_emb[i * dim1 + j];
        }
      }
    }
  }

  if (gconf.sdxl_mode && !gmod.token_emb_2.empty() && !gmod.pos_emb_2.empty()) {
    const std::vector<int> &ids2 = result.ids_2;
    for (int i = 0; i < max_len; i++) {
      int token_id = ids2[i];
      float weight = (i < (int)weights.size()) ? weights[i] : 1.0f;

      bool has_emb = false;
      for (int j = 0; j < dim2; j++) {
        if (embeddings_2[i * dim2 + j] != 0.0f) {
          has_emb = true;
          break;
        }
      }

      if (!has_emb) {
        for (int j = 0; j < dim2; j++) {
          float token_val = fp16_to_fp32(gmod.token_emb_2[token_id * dim2 + j]);
          embeddings_2[i * dim2 + j] =
              token_val * weight + gmod.pos_emb_2[i * dim2 + j];
        }
      } else {
        for (int j = 0; j < dim2; j++) {
          embeddings_2[i * dim2 + j] += gmod.pos_emb_2[i * dim2 + j];
        }
      }
    }
  }

  result.weighted_embeddings = embeddings;
  result.weighted_embeddings_2 = embeddings_2;
  return result;
}

ProcessedPromptPair processPromptPair(AppContext &ctx,
                                      const std::string &positive,
                                      const std::string &negative,
                                      int max_len) {
  ProcessedPromptPair result;

  auto pos_result = processWeightedPrompt(ctx, positive, max_len);
  auto neg_result = processWeightedPrompt(ctx, negative, max_len);

  result.ids.reserve(2 * max_len);
  result.ids.insert(result.ids.end(), neg_result.ids.begin(),
                    neg_result.ids.end());
  result.ids.insert(result.ids.end(), pos_result.ids.begin(),
                    pos_result.ids.end());

  result.negative_embeddings = neg_result.weighted_embeddings;
  result.positive_embeddings = pos_result.weighted_embeddings;
  result.negative_embeddings_2 = neg_result.weighted_embeddings_2;
  result.positive_embeddings_2 = pos_result.weighted_embeddings_2;

  return result;
}
