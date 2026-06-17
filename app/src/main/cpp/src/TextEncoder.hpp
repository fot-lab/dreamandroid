#ifndef TEXT_ENCODER_HPP
#define TEXT_ENCODER_HPP
// ── BKND-PROC-0008 P3: Text Encoder — prompt → CLIP embeddings
//
//  Pure functions that process a prompt string into token IDs and
//  weighted embeddings.  All dependencies (tokenizer, prompt processor,
//  CLIP embedding tables) arrive through AppContext&.  No globals.
//
//  Extracted from main.cpp on 2026-06-17.

#include <string>
#include <vector>

#include "Config.hpp"   // text_embedding_size, text_embedding_size_2

struct AppContext;

struct ProcessedPrompt {
  std::vector<int> ids;                      // CLIP (pad 49407)
  std::vector<int> ids_2;                    // SDXL encoder 2 (pad 0)
  std::vector<float> weighted_embeddings;    // 77*768
  std::vector<float> weighted_embeddings_2;  // SDXL: 77*1280
};

struct ProcessedPromptPair {
  std::vector<int> ids;                      // old (2*77)
  std::vector<float> negative_embeddings;    // new embedding (77*768)
  std::vector<float> positive_embeddings;    // new embedding (77*768)
  std::vector<float> negative_embeddings_2;  // SDXL (77*1280)
  std::vector<float> positive_embeddings_2;  // SDXL (77*1280)
};

// Process a single prompt into token IDs and position-aware embeddings.
// Uses the CLIP tables from ctx.models (token_emb, pos_emb).
ProcessedPrompt processWeightedPrompt(AppContext &ctx,
                                      const std::string &prompt_text,
                                      int max_len = 77);

// Process a positive + negative prompt pair, returning combined token IDs
// (neg first, pos second) and separate embedding vectors for each side.
ProcessedPromptPair processPromptPair(AppContext &ctx,
                                      const std::string &positive,
                                      const std::string &negative,
                                      int max_len = 77);

#endif  // TEXT_ENCODER_HPP
