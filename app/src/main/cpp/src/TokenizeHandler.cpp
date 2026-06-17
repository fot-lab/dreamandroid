#include "TokenizeHandler.hpp"

#include <chrono>
#include <nlohmann/json.hpp>
#include <vector>

#include "PromptCacheUtils.hpp"
#include "PromptProcessor.hpp"
#include "tokenizers_cpp.h"

size_t prefixBytesWithinBudget(const std::string &text, int budget,
                               tokenizers::Tokenizer *tok) {
  if (budget <= 0 || text.empty()) return 0;
  std::vector<size_t> bounds;  // byte offset of each character start, plus end
  for (size_t i = 0; i < text.size();) {
    bounds.push_back(i);
    unsigned char c = static_cast<unsigned char>(text[i]);
    int len;
    if (c < 0x80)
      len = 1;
    else if ((c >> 5) == 0x6)
      len = 2;
    else if ((c >> 4) == 0xE)
      len = 3;
    else if ((c >> 3) == 0x1E)
      len = 4;
    else
      len = 1;
    i += len;
  }
  bounds.push_back(text.size());

  int lo = 0, hi = static_cast<int>(bounds.size()) - 1, best = 0;
  while (lo <= hi) {
    int mid = (lo + hi) / 2;
    int n = static_cast<int>(tok->Encode(text.substr(0, bounds[mid])).size());
    if (n <= budget) {
      best = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  return bounds[best];
}

void handleTokenize(const httplib::Request &req, httplib::Response &res,
                    bool sdxl_mode, int text_embedding_size_2,
                    PromptProcessor &promptProcessor,
                    tokenizers::Tokenizer *tokenizer) {
  try {
    auto json = nlohmann::json::parse(req.body);
    std::string text = json.value("prompt", std::string());
    const int max_len = 77;
    // Tokens that fit alongside the implicit BOS/EOS markers.
    const int budget = max_len - 2;

    int count = 2;  // BOS + EOS
    // UTF-16 index of the first character whose tokens exceed the limit, or
    // -1 when the prompt fits. For a text token the boundary is found at the
    // sub-word edge inside it; an embedding is an indivisible vector block,
    // so it is flagged from its first character.
    int overflow_offset = -1;
    if (!text.empty() && tokenizer) {
      auto tokens = promptProcessor.process(text);
      const int dim1 = 768;
      const int dim2 = text_embedding_size_2;
      int content = 0;
      for (const auto &token : tokens) {
        int tc = 0;
        if (token.is_embedding) {
          if (!token.embedding_data.empty())
            tc = token.embedding_data.size() / dim1;
          else if (sdxl_mode && !token.embedding_data_2.empty())
            tc = token.embedding_data_2.size() / dim2;
        } else {
          tc = (int)tokenizer->Encode(token.text).size();
        }
        if (overflow_offset < 0 && content + tc > budget) {
          size_t byte_off = token.source_start;
          if (!token.is_embedding) {
            // Boundary inside a text token: find the sub-word edge in the
            // cleaned text, then map it back to the user's prompt so the grey
            // region lines up with the actual characters they typed.
            size_t prefix = prefixBytesWithinBudget(
                token.text, budget - content, tokenizer);
            byte_off = (prefix < token.char_src.size())
                           ? token.char_src[prefix]
                           : token.source_start;
          }
          overflow_offset = utf8ByteOffsetToUtf16(text, byte_off);
        }
        content += tc;
      }
      count = content + 2;  // BOS + EOS
    }

    nlohmann::json resp = {{"count", count},
                           {"max_length", max_len},
                           {"overflow_offset", overflow_offset}};
    res.status = 200;
    res.set_content(resp.dump(), "application/json");
  } catch (const std::exception &e) {
    nlohmann::json err = {
        {"id",
         "tok-" +
             std::to_string(
                 std::chrono::system_clock::now()
                     .time_since_epoch()
                     .count())},
        {"name", "tokenize_error"},
        {"errors", {std::string(e.what())}},
    };
    res.status = 400;
    res.set_content(err.dump(), "application/json");
  }
}
