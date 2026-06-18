// Base class for diffusion samplers
#ifndef SAMPLER_HPP
#define SAMPLER_HPP

#include <optional>
#include <string>
#include <xtensor/xarray.hpp>

class Sampler {
 public:
  struct SamplerOutput {
    xt::xarray<float> prev_sample;
    xt::xarray<float> pred_original_sample;
  };

  virtual ~Sampler() = default;

  // Set the number of inference steps
  virtual void set_timesteps(int num_inference_steps) = 0;

  // Scale the model input (required for some samplers like Euler)
  virtual xt::xarray<float> scale_model_input(const xt::xarray<float> &sample,
                                              int timestep) = 0;

  // Perform one step of the diffusion process
  virtual SamplerOutput step(const xt::xarray<float> &model_output,
                               int timestep,
                               const xt::xarray<float> &sample) = 0;

  // Add noise to original samples
  virtual xt::xarray<float> add_noise(
      const xt::xarray<float> &original_samples, const xt::xarray<float> &noise,
      const xt::xarray<int> &timesteps) const = 0;

  // Set the begin index for img2img operations
  virtual void set_begin_index(int begin_index) = 0;

  // Set prediction type (epsilon, v_prediction, sample)
  virtual void set_prediction_type(const std::string &prediction_type) = 0;

  // Get the timesteps array
  virtual const xt::xarray<float> &get_timesteps() const = 0;

  // Get current step index
  virtual size_t get_step_index() const = 0;

  // Get current sigma value
  virtual float get_current_sigma() const = 0;

  // Get initial noise sigma (for scaling initial latents)
  virtual float get_init_noise_sigma() const = 0;
};
#endif  // SAMPLER_HPP