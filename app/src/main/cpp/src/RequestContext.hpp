#ifndef REQUEST_CONTEXT_HPP
#define REQUEST_CONTEXT_HPP

#include <functional>
#include <string>
#include <vector>

// ── BKND-PROC-0008 P0: Per-request immutable context
//
//  Replaces the 20+ global variables that the /generate handler was
//  writing before each call to generateImage().  By bundling them into
//  a const ref parameter we:
//   1. eliminate the data-race window (even under serialisation the old
//      [&] lambda kept globals live across requests);
//   2. make generateImage() testable in isolation;
//   3. free the global namespace from request-specific state.
//
//  Model objects (CLIP/UNET/VAE interpreters & paths) remain global —
//  they are legitimate shared resources, loaded once at startup.

struct RequestContext {
  // ── Core prompt ──
  std::string prompt;
  std::string negative_prompt;

  // ── Generation parameters ──
  int steps = 20;
  int samples = 1;
  float cfg_scale = 7.5f;
  unsigned seed = 0;                // 0 = random (Stability-AI convention)
  std::string sampler_type = "dpm";
  std::string denoise_curve = "scaled_linear";
  float denoising_strength = 0.75f;  // A1111-compatible name

  // ── Image input (img2img / inpaint) ──
  std::vector<float> img_data;        // 3×W×H, empty = txt2img
  std::vector<float> mask_data;       // 1×W×H, empty = no mask
  std::vector<float> mask_data_full;  // pre-converted resized mask
  bool request_img2img = false;
  bool request_has_mask = false;

  // ── Output size (read-only; copied to locals during tiling) ──
  int output_width = 512;
  int output_height = 512;
  int sample_width = 64;
  int sample_height = 64;

  // ── Output media flags ──
  bool use_opencl = false;
  bool show_diffusion_process = false;
  int show_diffusion_stride = 1;

  // ── SDXL aspect-padded inpaint ──
  bool aspect_pad_inpaint = false;
  int target_crop_width = 0;
  int target_crop_height = 0;
  bool aspect_pad_synthetic_base = false;
  bool user_supplied_mask = false;
};

// ── Type alias for the SSE progress callback ──
using ProgressCallback = std::function<void(
    int step, int total_steps, const std::string &image_data)>;

#endif  // REQUEST_CONTEXT_HPP
