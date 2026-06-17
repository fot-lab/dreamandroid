#ifndef PROMPT_CACHE_UTILS_HPP
#define PROMPT_CACHE_UTILS_HPP

#include <cstdint>
#include <string>
#include <vector>

#include "Sha256.hpp"

class PromptProcessor;

// ── Persistent per-prompt CLIP cache lives on disk under
//     {modelDir}/cache/prompt_<sha32>.bin
// ── Positive and negative prompts are looked up independently: a single side
//     hit still skips half the CLIP work.  A prompt that uses a
//     textual-inversion embedding is excluded (its CLIP output depends on
//     embedding state we don't want baked into a stable file).
// ── BKND-PROC-0008: promptHasEmbedding() accepts PromptProcessor& — pure
//     function, no extern dependency.
namespace prompt_cache {

constexpr char kMagic[4] = {'P', 'C', 'L', 'P'};
constexpr uint32_t kVersion = 1;
constexpr uint32_t kModeSd15 = 0;
constexpr uint32_t kModeSdxl = 1;
constexpr uint32_t kSeqLen = 77;

struct Header {
  char magic[4];
  uint32_t version;
  uint32_t mode;
  uint32_t seq_len;
  uint32_t hidden_dim;
  uint32_t pooled_dim;
};

}  // namespace prompt_cache

// Count the UTF-16 code units in the first byteOffset bytes of a UTF-8 string.
// The prompt is a Kotlin String on the client, indexed in UTF-16 units, so a
// raw byte offset must be converted before it can address a character there.
int utf8ByteOffsetToUtf16(const std::string &s, size_t byteOffset);

// True if any token of `prompt_text` resolves to a textual-inversion
// embedding loaded by the given PromptProcessor. Used to opt that side
// out of the persistent prompt cache.  Pure function — no globals.
bool promptHasEmbedding(PromptProcessor &pp, const std::string &prompt_text);

std::string promptCachePath(const std::string &cache_dir,
                            const std::string &prompt_text);

// Reads {hidden_states[, pooled]} from disk for `prompt_text`. Returns true
// on a valid hit; the destination buffers must already be sized for the
// expected layout. The file is silently treated as miss when missing, wrong
// magic/version, or dimension mismatch.
//   - hidden_dst: seq_len * hidden_dim float32
//   - pooled_dst: pooled_dim float32 (nullptr for SD1.5)
bool loadPromptCache(const std::string &cache_dir,
                     const std::string &prompt_text, uint32_t mode,
                     uint32_t hidden_dim, uint32_t pooled_dim,
                     float *hidden_dst, float *pooled_dst);

void savePromptCache(const std::string &cache_dir,
                     const std::string &prompt_text, uint32_t mode,
                     uint32_t hidden_dim, uint32_t pooled_dim,
                     const float *hidden_src, const float *pooled_src);

#endif  // PROMPT_CACHE_UTILS_HPP
