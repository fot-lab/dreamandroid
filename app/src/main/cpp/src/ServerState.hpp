#ifndef SERVER_STATE_HPP
#define SERVER_STATE_HPP

#include <atomic>
#include <chrono>
#include <iostream>
#include <mutex>
#include <string>

// ────────────────────────────────────────────────────────────────────────────
// ServerState — Centralised concurrency & state management for the DreamHub
// C++ backend.  Replaces the scattered atomic/mutex globals with a single
// well-defined state machine.  All public methods are thread-safe.
//
// State machine:
//   Idle  ── acquireBusy() ──►  Busy
//   Busy  ── release()     ──►  Idle
//
// Clients receive:
//   - Normal conflict:  HTTP 503 Service Unavailable
//   - Deadlock:         HTTP 503 with "hang detected" message
// ────────────────────────────────────────────────────────────────────────────

class ServerState {
 public:
  enum class State : int { kIdle = 0, kBusy = 1 };

  // ── Configuration ──────────────────────────────────────────────────────

  /// Maximum generation wall-clock time (seconds).  Exceeding this triggers
  /// an automatic busy-flag release to prevent permanent deadlock from a
  /// hung GPU/QNN pipeline.  Set to 0 to disable.
  int generation_timeout_secs = 300;  // 5 minutes

  // ── State Queries ──────────────────────────────────────────────────────

  State currentState() const {
    return static_cast<State>(state_.load(std::memory_order_acquire));
  }

  bool isBusy() const { return currentState() == State::kBusy; }

  // ── Progress ───────────────────────────────────────────────────────────

  int currentStep() const {
    return current_step_.load(std::memory_order_acquire);
  }
  int totalSteps() const {
    return total_steps_.load(std::memory_order_acquire);
  }
  void setProgress(int current, int total) {
    current_step_.store(current, std::memory_order_release);
    total_steps_.store(total, std::memory_order_release);
  }

  // ── Busy Acquisition / Release ─────────────────────────────────────────

  /// Attempt to mark the server as Busy before processing a generation
  /// request.  Returns true on success; false if the server is already busy.
  /// On success the caller MUST call release().
  ///
  /// The returned token is the start time for the watchdog; pass it to
  /// release().
  bool acquireBusy(std::chrono::steady_clock::time_point &acquireTime) {
    State expected = State::kIdle;
    if (!state_.compare_exchange_strong(expected, State::kBusy,
                                        std::memory_order_acquire,
                                        std::memory_order_relaxed)) {
      return false;  // Already busy
    }
    acquireTime = std::chrono::steady_clock::now();
    // Reset progress for the new request
    current_step_.store(0, std::memory_order_release);
    total_steps_.store(0, std::memory_order_release);
    return true;
  }

  /// Release the Busy flag.  Must be called exactly once for each
  /// successful acquireBusy().
  void release() { state_.store(State::kIdle, std::memory_order_release); }

  // ── Timeout / Deadlock Detection ───────────────────────────────────────

  /// Check whether the current generation has exceeded the timeout.
  /// Called by the watchdog or by the chunked-content callback to
  /// preemptively detect a hung pipeline.
  bool isTimedOut(
      std::chrono::steady_clock::time_point acquireTime) const {
    if (generation_timeout_secs <= 0) return false;
    auto elapsed = std::chrono::steady_clock::now() - acquireTime;
    return elapsed > std::chrono::seconds(generation_timeout_secs);
  }

  /// Force-release the busy flag if the generation has timed out.
  /// Returns true if the flag was released (hang detected).
  bool checkAndReleaseTimeout(
      std::chrono::steady_clock::time_point acquireTime) {
    if (!isTimedOut(acquireTime)) return false;
    std::cerr << "[ServerState] Generation timeout ("
              << generation_timeout_secs << "s) — forcing release"
              << std::endl;
    release();
    return true;
  }

 private:
  std::atomic<State> state_{State::kIdle};
  std::atomic<int> current_step_{0};
  std::atomic<int> total_steps_{0};
};

// ── Low-RAM Mutex (separate from generation lock) ───────────────────────
// Serialises SDXL lowram load/release to prevent use-after-free and
// double-delete.  Held independently from ServerState to avoid blocking
// health checks and progress queries.

inline std::mutex &lowramMutex() {
  static std::mutex m;
  return m;
}

#endif  // SERVER_STATE_HPP
