//! Ctrl / Shift / Alt that stay held until tapped again.
//!
//! The Android build has no physical modifier key: the on-screen chips send a
//! flag here, and every place that reads the keyboard modifiers also sees it.
//! A synthetic key press would not work — winit rebuilds `ModifiersState` from
//! the real keyboard state, so anything injected would be cleared by the next
//! event. These flags are only cleared by the chip that set them.
//!
//! Ctrl is what the game treats as "force attack", so a player picking a target
//! can leave the chip lit for the whole fight instead of holding a thumb on it.

use std::sync::atomic::{AtomicU8, Ordering};

use winit::keyboard::ModifiersState;

pub(crate) const CTRL: u8 = 1;
pub(crate) const SHIFT: u8 = 2;
pub(crate) const ALT: u8 = 4;

static LOCKED: AtomicU8 = AtomicU8::new(0);

/// Set or clear one sticky modifier. `None` when the name is not one of ours.
pub(crate) fn set(name: &str, down: bool) -> Option<bool> {
    let bit = match name.trim().to_ascii_lowercase().as_str() {
        "ctrl" | "control" => CTRL,
        "shift" => SHIFT,
        "alt" => ALT,
        _ => return None,
    };
    let previous = LOCKED.load(Ordering::Relaxed);
    let next = if down {
        previous | bit
    } else {
        previous & !bit
    };
    LOCKED.store(next, Ordering::Relaxed);
    Some(next & bit != 0)
}

/// The lit chips, as a bit mask (the activity re-reads it after a detach).
pub(crate) fn mask() -> u8 {
    LOCKED.load(Ordering::Relaxed)
}

/// OR the sticky flags into the modifiers the input layer is about to read.
pub(crate) fn apply(modifiers: &mut ModifiersState) {
    let locked = LOCKED.load(Ordering::Relaxed);
    if locked & CTRL != 0 {
        *modifiers |= ModifiersState::CONTROL;
    }
    if locked & SHIFT != 0 {
        *modifiers |= ModifiersState::SHIFT;
    }
    if locked & ALT != 0 {
        *modifiers |= ModifiersState::ALT;
    }
}
