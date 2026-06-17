#pragma once
// ── VAE Tiling Helper ────────────────────────────────────────────────
// BKND-PROC-0008 P2 split: extract VAE encoder/decoder tiling logic
// from main.cpp into a dedicated module.
//
// Provides:
//   calculateTilePositions()      — generic 1D tile position calculator
//   calculateVaeTilePositions()   — VAE-specific tile position calculator
//   blendVaeEncoderTiles()        — weighted blend of encoder tile latents
//   blendVaeOutputTiles()         — weighted blend of decoder output tiles

#include <tuple>
#include <utility>
#include <vector>

#include <xtensor/xarray.hpp>

// ── Generic 1D tile position calculation ─────────────────────────────
// Used by upscaler tiling (via upscaleImageWithModel / upscaleImageWithMNN
// already moved to QnnHelper.cpp / MnnHelper.cpp) and by
// calculateVaeTilePositions below.
std::vector<int> calculateTilePositions(int dimension, int tile_size,
                                        int min_overlap);

// ── VAE Tile Position Calculation ────────────────────────────────────
// Returns: {pixel_positions, latent_positions, pixel_overlap_x,
//           pixel_overlap_y, latent_overlap_x, latent_overlap_y}
std::tuple<std::vector<std::pair<int, int>>, std::vector<std::pair<int, int>>,
           int, int, int, int>
calculateVaeTilePositions(int pixel_width, int pixel_height);

// ── VAE Encoder Tile Blending ────────────────────────────────────────
// Blend VAE encoder tiles (mean/std pairs) with linear feathering into
// a single latent tensor.  Returns latent [1, 4, H, W] with noise applied.
xt::xarray<float> blendVaeEncoderTiles(
    const std::vector<std::pair<xt::xarray<float>, xt::xarray<float>>>
        &tilesMeanStd,
    const std::vector<std::pair<int, int>> &positions, int latentH,
    int latentW, int tileSize, int overlapX, int overlapY);

// ── VAE Decoder Tile Blending ────────────────────────────────────────
// Blend VAE decoder output tiles (3-channel RGB) with linear feathering
// into a single image.  Returns pixel data [1, 3, H, W].
xt::xarray<float> blendVaeOutputTiles(
    const std::vector<xt::xarray<float>> &tiles,
    const std::vector<std::pair<int, int>> &positions, int outputH,
    int outputW, int tileSize, int overlapX, int overlapY);
