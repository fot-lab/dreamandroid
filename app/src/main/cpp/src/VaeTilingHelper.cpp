// ── VAE Tiling Helper Implementation ──────────────────────────────────
// BKND-PROC-0008 P2 split: extracted from main.cpp

#include "VaeTilingHelper.hpp"

#include <stdexcept>

#include <xtensor/xbuilder.hpp>
#include <xtensor/xeval.hpp>
#include <xtensor/xmath.hpp>
#include <xtensor/xrandom.hpp>
#include <xtensor/xview.hpp>

// ── Generic 1D tile position calculation ─────────────────────────────

std::vector<int> calculateTilePositions(int dimension, int tile_size,
                                        int min_overlap) {
  if (dimension <= tile_size) {
    return {0};
  }

  int num_tiles = 1;
  int effective_tile_size = tile_size - min_overlap;
  if (dimension > tile_size) {
    num_tiles +=
        (dimension - tile_size + effective_tile_size - 1) / effective_tile_size;
  }

  std::vector<int> positions;
  positions.reserve(num_tiles);
  positions.push_back(0);

  if (num_tiles == 1) {
    return positions;
  }

  int total_distance = dimension - tile_size;
  int num_strides = num_tiles - 1;

  int base_stride = total_distance / num_strides;
  int remainder = total_distance % num_strides;

  int current_pos = 0;
  for (int i = 0; i < num_strides; ++i) {
    int stride = base_stride + (i < remainder ? 1 : 0);
    current_pos += stride;
    positions.push_back(current_pos);
  }

  positions.back() = dimension - tile_size;

  return positions;
}

// ── VAE Tile Position Calculation ────────────────────────────────────

std::tuple<std::vector<std::pair<int, int>>, std::vector<std::pair<int, int>>,
           int, int, int, int>
calculateVaeTilePositions(int pixel_width, int pixel_height) {
  const int vae_tile_size = 512;        // Fixed VAE tile size in pixel space
  const int vae_latent_tile_size = 64;  // Fixed VAE tile size in latent space
  const int min_latent_overlap = 16;    // Minimum overlap in latent space
  const int scale_factor = 8;           // VAE scale: 512/64 = 8

  // Calculate positions for width and height separately
  auto pixel_x_coords = calculateTilePositions(
      pixel_width, vae_tile_size, min_latent_overlap * scale_factor);
  auto pixel_y_coords = calculateTilePositions(
      pixel_height, vae_tile_size, min_latent_overlap * scale_factor);

  // Calculate corresponding latent positions
  std::vector<int> latent_x_coords;
  std::vector<int> latent_y_coords;
  for (int px : pixel_x_coords) {
    latent_x_coords.push_back(px / scale_factor);
  }
  for (int py : pixel_y_coords) {
    latent_y_coords.push_back(py / scale_factor);
  }

  // Create position pairs
  std::vector<std::pair<int, int>> pixel_positions;
  std::vector<std::pair<int, int>> latent_positions;

  for (int py : pixel_y_coords) {
    for (int px : pixel_x_coords) {
      pixel_positions.push_back({px, py});
    }
  }

  for (int ly : latent_y_coords) {
    for (int lx : latent_x_coords) {
      latent_positions.push_back({lx, ly});
    }
  }

  // Calculate actual overlaps based on tile positions
  int pixel_overlap_x = 0;
  int latent_overlap_x = 0;
  int pixel_overlap_y = 0;
  int latent_overlap_y = 0;

  if (pixel_x_coords.size() > 1) {
    pixel_overlap_x = vae_tile_size - (pixel_x_coords[1] - pixel_x_coords[0]);
    latent_overlap_x =
        vae_latent_tile_size - (latent_x_coords[1] - latent_x_coords[0]);
  }

  if (pixel_y_coords.size() > 1) {
    pixel_overlap_y = vae_tile_size - (pixel_y_coords[1] - pixel_y_coords[0]);
    latent_overlap_y =
        vae_latent_tile_size - (latent_y_coords[1] - latent_y_coords[0]);
  }

  return {pixel_positions, latent_positions, pixel_overlap_x,
          pixel_overlap_y, latent_overlap_x, latent_overlap_y};
}

// ── VAE Encoder Tile Blending ────────────────────────────────────────

xt::xarray<float> blendVaeEncoderTiles(
    const std::vector<std::pair<xt::xarray<float>, xt::xarray<float>>>
        &tilesMeanStd,
    const std::vector<std::pair<int, int>> &positions, int latentH,
    int latentW, int tileSize, int overlapX, int overlapY) {
  if (tilesMeanStd.empty()) {
    throw std::runtime_error(
        "Tile list cannot be empty for VAE encoder blending.");
  }

  std::vector<int> accumulated_shape = {1, 4, latentH, latentW};
  xt::xarray<float> accumulated_mean = xt::zeros<float>(accumulated_shape);
  xt::xarray<float> accumulated_std = xt::zeros<float>(accumulated_shape);
  xt::xarray<float> weight_map = xt::zeros<float>({latentH, latentW});

  int fade_size_x = overlapX / 2;
  int fade_size_y = overlapY / 2;

  for (size_t idx = 0; idx < tilesMeanStd.size(); ++idx) {
    int x = positions[idx].first;
    int y = positions[idx].second;

    xt::xarray<float> tile_weight = xt::ones<float>({tileSize, tileSize});

    if (fade_size_y > 0) {
      if (y > 0) {
        for (int i = 0; i < fade_size_y; ++i) {
          float alpha = (float)(i + 1) / fade_size_y;
          xt::view(tile_weight, i, xt::all()) *= alpha;
        }
      }
      if (y + tileSize < latentH) {
        for (int i = 0; i < fade_size_y; ++i) {
          float alpha = (float)(i + 1) / fade_size_y;
          xt::view(tile_weight, tileSize - 1 - i, xt::all()) *= alpha;
        }
      }
    }

    if (fade_size_x > 0) {
      if (x > 0) {
        for (int i = 0; i < fade_size_x; ++i) {
          float alpha = (float)(i + 1) / fade_size_x;
          xt::view(tile_weight, xt::all(), i) *= alpha;
        }
      }
      if (x + tileSize < latentW) {
        for (int i = 0; i < fade_size_x; ++i) {
          float alpha = (float)(i + 1) / fade_size_x;
          xt::view(tile_weight, xt::all(), tileSize - 1 - i) *= alpha;
        }
      }
    }

    const auto &mean_tile = tilesMeanStd[idx].first;
    const auto &std_tile = tilesMeanStd[idx].second;

    for (int c = 0; c < 4; ++c) {
      auto acc_mean_slice =
          xt::view(accumulated_mean, 0, c, xt::range(y, y + tileSize),
                   xt::range(x, x + tileSize));
      auto mean_slice = xt::view(mean_tile, 0, c, xt::all(), xt::all());
      acc_mean_slice += mean_slice * tile_weight;

      auto acc_std_slice =
          xt::view(accumulated_std, 0, c, xt::range(y, y + tileSize),
                   xt::range(x, x + tileSize));
      auto std_slice = xt::view(std_tile, 0, c, xt::all(), xt::all());
      acc_std_slice += std_slice * tile_weight;
    }

    auto weight_slice = xt::view(weight_map, xt::range(y, y + tileSize),
                                 xt::range(x, x + tileSize));
    weight_slice += tile_weight;
  }

  weight_map = xt::maximum(weight_map, 1e-8f);
  xt::xarray<float> weight_expanded =
      xt::reshape_view(weight_map, {1, 1, latentH, latentW});

  xt::xarray<float> final_mean = accumulated_mean / weight_expanded;
  xt::xarray<float> final_std = accumulated_std / weight_expanded;

  xt::xarray<float> noise =
      xt::random::randn<float>({1, 4, latentH, latentW});
  xt::xarray<float> latent = xt::eval(final_mean + final_std * noise);

  return latent;
}

// ── VAE Decoder Tile Blending ────────────────────────────────────────

xt::xarray<float> blendVaeOutputTiles(
    const std::vector<xt::xarray<float>> &tiles,
    const std::vector<std::pair<int, int>> &positions, int outputH,
    int outputW, int tileSize, int overlapX, int overlapY) {
  if (tiles.empty()) {
    throw std::runtime_error(
        "Tile list cannot be empty for VAE output blending.");
  }

  std::vector<int> accumulated_shape = {1, 3, outputH, outputW};
  xt::xarray<float> accumulated = xt::zeros<float>(accumulated_shape);
  xt::xarray<float> weight_map = xt::zeros<float>({outputH, outputW});

  int fade_size_x = overlapX / 2;
  int fade_size_y = overlapY / 2;

  for (size_t idx = 0; idx < tiles.size(); ++idx) {
    int x = positions[idx].first;
    int y = positions[idx].second;

    xt::xarray<float> tile_weight = xt::ones<float>({tileSize, tileSize});

    if (fade_size_y > 0) {
      if (y > 0) {
        for (int i = 0; i < fade_size_y; ++i) {
          float alpha = (float)(i + 1) / fade_size_y;
          xt::view(tile_weight, i, xt::all()) *= alpha;
        }
      }
      if (y + tileSize < outputH) {
        for (int i = 0; i < fade_size_y; ++i) {
          float alpha = (float)(i + 1) / fade_size_y;
          xt::view(tile_weight, tileSize - 1 - i, xt::all()) *= alpha;
        }
      }
    }

    if (fade_size_x > 0) {
      if (x > 0) {
        for (int i = 0; i < fade_size_x; ++i) {
          float alpha = (float)(i + 1) / fade_size_x;
          xt::view(tile_weight, xt::all(), i) *= alpha;
        }
      }
      if (x + tileSize < outputW) {
        for (int i = 0; i < fade_size_x; ++i) {
          float alpha = (float)(i + 1) / fade_size_x;
          xt::view(tile_weight, xt::all(), tileSize - 1 - i) *= alpha;
        }
      }
    }

    for (int c = 0; c < 3; ++c) {
      auto acc_slice = xt::view(accumulated, 0, c,
                                xt::range(y, y + tileSize),
                                xt::range(x, x + tileSize));
      auto tile_slice = xt::view(tiles[idx], 0, c, xt::all(), xt::all());
      acc_slice += tile_slice * tile_weight;
    }

    auto weight_slice = xt::view(weight_map,
                                 xt::range(y, y + tileSize),
                                 xt::range(x, x + tileSize));
    weight_slice += tile_weight;
  }

  weight_map = xt::maximum(weight_map, 1e-8f);
  xt::xarray<float> weight_expanded =
      xt::reshape_view(weight_map, {1, 1, outputH, outputW});

  return accumulated / weight_expanded;
}
