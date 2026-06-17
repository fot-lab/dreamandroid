#ifndef TOKENIZE_HANDLER_HPP
#define TOKENIZE_HANDLER_HPP

#include <string>
#include <httplib.h>

class PromptProcessor;

namespace tokenizers {
class Tokenizer;
}

// Byte length of the longest character-aligned prefix of `text` whose CLIP
// encoding fits within `budget` tokens. Lets the overflow boundary land on a
// sub-word edge inside a long token (e.g. a run of digits) instead of greying
// the whole token. BPE token counts are non-decreasing as the prefix grows, so
// a binary search over character boundaries is valid.
size_t prefixBytesWithinBudget(const std::string &text, int budget,
                               tokenizers::Tokenizer *tok);

// Handle POST /tokenize — returns CLIP token count for the given prompt.
// Formats and writes the JSON response directly to `res`.
void handleTokenize(const httplib::Request &req, httplib::Response &res,
                    bool sdxl_mode, int text_embedding_size_2,
                    PromptProcessor &promptProcessor,
                    tokenizers::Tokenizer *tokenizer);

#endif  // TOKENIZE_HANDLER_HPP
