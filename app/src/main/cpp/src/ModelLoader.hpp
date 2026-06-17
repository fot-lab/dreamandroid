#ifndef MODEL_LOADER_HPP
#define MODEL_LOADER_HPP

#include "AppContext.hpp"

// ── BKND-PROC-0008 P4: Model Loader extracted from main() ──
//
//  Initializes all MNN sessions and QNN models.  Returns 0 on success,
//  non-zero on failure (caller should exit with EXIT_FAILURE).
//
//  Accepts AppContext& (mutable) to populate sessions and QNN apps.
//  No global variable dependencies.

int initializeModels(AppContext &ctx);

#endif  // MODEL_LOADER_HPP
