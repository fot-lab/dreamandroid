#pragma once
// ServerCli.hpp — CLI argument parsing for DreamHub C++ backend
//
// Extracted from main.cpp per BKND-PROC-0008 P3 file-split plan.
// Pure functions — accept AppContext& to populate config/models,
// no extern dependencies.
//
// BKND-PROC-0008 P3: processCommandLine accepts AppContext&.
//   showHelp / showHelpAndExit are self-contained I/O helpers.

#include <string>

struct AppContext;

/// Print usage information to stdout.
void showHelp();

/// Print error + usage to stderr, then exit(EXIT_FAILURE).
[[noreturn]] void showHelpAndExit(std::string &&error);

/// Parse command-line arguments and populate AppContext config & models.
/// Calls showHelpAndExit on fatal errors; may exit directly for --help,
/// --version, and --convert.
void processCommandLine(int argc, char **argv, AppContext &ctx);
