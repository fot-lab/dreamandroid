// StbImpl.cpp — single translation unit for stb_image family implementations.
//
// stb is a single-header library: the header contains both declarations (when
// included normally) and implementations (when STB_IMAGE_IMPLEMENTATION etc.
// are #defined before inclusion).  Defining the implementation macros in a
// header causes every .cpp that includes that header to compile a full copy,
// leading to duplicate-symbol linker errors.
//
// This file is the *sole* unit that defines the implemenation macros, so the
// stb symbols are compiled exactly once.  All other translation units include
// the stb headers via SDUtils.hpp without the macros and get only declarations.

#define STB_IMAGE_IMPLEMENTATION
#define STB_IMAGE_WRITE_IMPLEMENTATION
#define STB_IMAGE_RESIZE_IMPLEMENTATION

#include "stb_image.h"
#include "stb_image_resize2.h"
#include "stb_image_write.h"
