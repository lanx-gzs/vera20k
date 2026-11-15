//! VERA20k — client library root.
//!
//! Re-exports all modules so the game and the tool binaries in `src/bin/`
//! can access the engine's functionality. The binary entry point (main.rs)
//! delegates to this library for all logic.
//!
//! The GPU-free engine (assets, rules, map, sim, net, util and the headless
//! scenario) is the `vera20k-core` crate (`src/core_lib.rs`). Its modules are
//! re-exported here, so `crate::sim::...` and `vera20k::sim::...` keep naming
//! them.
#[cfg(all(target_arch = "wasm32", target_os = "unknown", not(feature = "wasm")))]
compile_error!("browser builds require --features wasm");

pub use vera20k_core::{
    assets, headless_scenario, map, net, rules, sim, skirmish_launch, skirmish_modes, util,
};

// Cargo unifies dependency features across a workspace. Refuse a client built
// without its Ares consumers when another package enables the core capability.
const _: () = assert!(
    cfg!(feature = "ares") == rules::ARES_ENABLED,
    "enable Ares through the vera20k package's ares feature"
);
// The same boundary for Phobos: the capability is independent of Ares and is
// likewise enabled through the client package.
const _: () = assert!(
    cfg!(feature = "phobos") == rules::PHOBOS_ENABLED,
    "enable Phobos through the vera20k package's phobos feature"
);
// The same boundary for the NPatch placeholder.
const _: () = assert!(
    cfg!(feature = "npatch") == rules::NPATCH_ENABLED,
    "enable NPatch through the vera20k package's npatch feature"
);
// The same boundary for VERA20K's own VeraX extension capability.
const _: () = assert!(
    cfg!(feature = "verax") == rules::VERAX_ENABLED,
    "enable VeraX through the vera20k package's verax feature"
);
// The same boundary for the NPBUG bug-fix capability.
const _: () = assert!(
    cfg!(feature = "npbug") == rules::NPBUG_ENABLED,
    "enable NPBUG through the vera20k package's npbug feature"
);

// Opaque process-RNG cursor receipt shared by map generation and simulation.
use vera20k_core::rng_continuation;

// Headless asset browsing for automated callers — the `asset` binary.
// Sits above assets/ and rules/; never depends on sim/, ui/, audio/, or net/.
pub mod asset_tools;

// GPU rendering — wgpu-based sprite, terrain, voxel rendering.
// Reads from sim/ state but never mutates it.
pub mod render;

// Menus, dialogs and the in-game sidebar: layout and state, drawn by app/ and render/.
pub mod ui;

// VERA's own localized text, layered over the retail CSF (`[VERA] Language=`).
pub mod i18n;

// Sound/music via rodio.
pub mod audio;

// The app orchestrator. Public for testing but not intended for direct use.
pub mod app;

// Process start-up shared by the desktop binary and the Android cdylib, which
// has no `main` of its own. The browser starts in `web` instead, and its build
// has neither the system dialogs nor the spawn-launch result this module uses.
#[cfg(not(all(target_arch = "wasm32", target_os = "unknown")))]
pub mod entry;

// Source-level dependency guards for the domain-boundaries ledger.
#[cfg(test)]
mod architecture_guards;

// Fixture files that library tests read from the checkout.
#[cfg(test)]
use vera20k_core::test_fixture;

#[cfg(all(feature = "wasm", target_arch = "wasm32", target_os = "unknown"))]
pub mod web;

// Browser protocol tests run on the host without linking JavaScript APIs.
#[cfg(all(feature = "wasm", any(test, all(target_arch = "wasm32", target_os = "unknown"))))]
#[path = "web/lifecycle.rs"]
mod browser_lifecycle;

#[cfg(all(feature = "wasm", any(test, all(target_arch = "wasm32", target_os = "unknown"))))]
#[path = "web/ime_protocol.rs"]
mod browser_ime;

/// Text from the Android keyboard overlay, as if the system input method had
/// committed it.
///
/// Only Android needs this: `android-native-activity` gives winit no input
/// connection, so `Window::set_ime_allowed` does nothing and no `Ime` event
/// arrives. The activity hosts an `EditText` and calls this with the finished
/// run; the event loop drains it once per frame, so the text lands in the same
/// chat edit an OS commit would and nowhere else.
#[cfg(target_os = "android")]
pub fn inject_ime_commit(text: &str) {
    crate::app::input::ime::inject_commit(text);
}

/// Backspace from the Android keyboard.
#[cfg(target_os = "android")]
pub fn inject_ime_backspace() {
    crate::app::input::ime::inject_backspace();
}

/// The Android keyboard's done key.
#[cfg(target_os = "android")]
pub fn inject_ime_enter() {
    crate::app::input::ime::inject_enter();
}

/// Whether a chat edit takes text on the last frame, for the same overlay.
#[cfg(target_os = "android")]
pub fn ime_chat_open() -> bool {
    crate::app::input::ime::chat_open()
}

/// Set or clear one on-screen modifier chip (Ctrl, Shift, Alt).
///
/// The flags are OR-ed into every modifier read the input layer does, so a chip
/// behaves like a held key until it is tapped again. Returns the new state of
/// that chip; `None` when the name is unknown.
#[cfg(target_os = "android")]
pub fn set_sticky_modifier(name: &str, down: bool) -> Option<bool> {
    crate::app::input::sticky_modifiers::set(name, down)
}

/// Which chips are lit, so the activity can redraw them after re-attaching.
#[cfg(target_os = "android")]
pub fn sticky_modifier_mask() -> u8 {
    crate::app::input::sticky_modifiers::mask()
}
