#include "PromptCacheUtils.hpp"

#include <algorithm>
#include <cstring>
#include <fstream>
#include <stdexcept>

#include "PromptProcessor.hpp"

int utf8ByteOffsetToUtf16(const std::string &s, size_t byteOffset) {
  int units = 0;
  size_t i = 0;
  size_t limit = std::min(byteOffset, s.size());
  while (i < limit) {
    unsigned char c = static_cast<unsigned char>(s[i]);
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
      len = 1;  // invalid lead byte; advance one to stay in sync
    units += (len == 4) ? 2 : 1;  // astral planes need a surrogate pair
    i += len;
  }
  return units;
}

bool promptHasEmbedding(PromptProcessor &pp, const std::string &prompt_text) {
  auto tokens = pp.process(prompt_text);
  for (const auto &t : tokens) {
    if (t.is_embedding) return true;
  }
  return false;
}

std::string promptCachePath(const std::string &cache_dir,
                            const std::string &prompt_text) {
  if (cache_dir.empty()) return "";
  return cache_dir + "/prompt_" + Sha256::hashHex(prompt_text, 32) + ".bin";
}

bool loadPromptCache(const std::string &cache_dir,
                     const std::string &prompt_text, uint32_t mode,
                     uint32_t hidden_dim, uint32_t pooled_dim,
                     float *hidden_dst, float *pooled_dst) {
  std::string path = promptCachePath(cache_dir, prompt_text);
  if (path.empty()) return false;
  std::ifstream ifs(path, std::ios::binary);
  if (!ifs) return false;

  prompt_cache::Header h{};
  ifs.read(reinterpret_cast<char *>(&h), sizeof(h));
  if (!ifs) return false;
  if (std::memcmp(h.magic, prompt_cache::kMagic, 4) != 0) return false;
  if (h.version != prompt_cache::kVersion) return false;
  if (h.mode != mode) return false;
  if (h.seq_len != prompt_cache::kSeqLen) return false;
  if (h.hidden_dim != hidden_dim) return false;
  if (h.pooled_dim != pooled_dim) return false;

  size_t hidden_bytes = size_t(h.seq_len) * h.hidden_dim * sizeof(float);
  ifs.read(reinterpret_cast<char *>(hidden_dst), hidden_bytes);
  if (!ifs) return false;
  if (pooled_dim > 0) {
    if (!pooled_dst) return false;
    size_t pooled_bytes = size_t(pooled_dim) * sizeof(float);
    ifs.read(reinterpret_cast<char *>(pooled_dst), pooled_bytes);
    if (!ifs) return false;
  }
  return true;
}

void savePromptCache(const std::string &cache_dir,
                     const std::string &prompt_text, uint32_t mode,
                     uint32_t hidden_dim, uint32_t pooled_dim,
                     const float *hidden_src, const float *pooled_src) {
  std::string path = promptCachePath(cache_dir, prompt_text);
  if (path.empty()) return;
  std::ofstream ofs(path, std::ios::binary | std::ios::trunc);
  if (!ofs) return;

  prompt_cache::Header h{};
  std::memcpy(h.magic, prompt_cache::kMagic, 4);
  h.version = prompt_cache::kVersion;
  h.mode = mode;
  h.seq_len = prompt_cache::kSeqLen;
  h.hidden_dim = hidden_dim;
  h.pooled_dim = pooled_dim;
  ofs.write(reinterpret_cast<const char *>(&h), sizeof(h));
  ofs.write(reinterpret_cast<const char *>(hidden_src),
            size_t(h.seq_len) * h.hidden_dim * sizeof(float));
  if (pooled_dim > 0 && pooled_src) {
    ofs.write(reinterpret_cast<const char *>(pooled_src),
              size_t(pooled_dim) * sizeof(float));
  }
}
