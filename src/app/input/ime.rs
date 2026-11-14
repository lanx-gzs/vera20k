//! OS input-method (IME) text entry for the chat edits.
//!
//! gamemd removes its main window's input context when it creates the window
//! (CreateMainWindow `0x00777D80`: ImmGetContext, then
//! `ImmAssociateContext(hWnd, 0)`), restores it when the in-game chat edit
//! opens (MessageListClass::AddEdit `0x005D4210` calls `0x00778180`) and
//! removes it again on every edit end (`0x007781A0`, from Input `0x005D4510`'s
//! end paths and Start_Scenario `0x00683E6B`). The shell's NewEdits are child
//! windows, which that call leaves alone. A finished character arrives as
//! WM_IME_CHAR: the main window queues it at `0x00B730EC`
//! (Game__MainWindowProc `0x007775C0`) and the chat pre-hook `0x0055E420`
//! feeds each queued unit to Input first; NewEdit `0x00614B30` widens it
//! (`0x007781B0`) and inserts it through its WM_CHAR path. The focused NewEdit
//! paints the open composition (`0x00777EA0`) at its caret (`0x00623880`).
//!
//! VERA20k takes the composition and the finished text from winit's `Ime`
//! events, and asks for an input method only while a chat edit takes keys:
//! the in-game line, the WOL lobby (`0x10F`), the WOL game room and LAN setup
//! (C2/C9, BC/BD) and the LAN lobby (BB). Finished text goes through each
//! screen's own text path, as WM_IME_CHAR does.
//!
//! Player-name edits stay Latin as native: NewEdit `0x00614B30` drops units
//! at or above 0x100 from them (`0x00615226..0x0061527A`, the byte-only filter),
//! and the LAN messages carry a name's low bytes.
//!
//! RESIDUAL: native's other NewEdits (login, page, buddy, saved-game
//! descriptions) also take IME input; here they keep plain key text. In
//! battle, native leaves the composition to the system's default IME window
//! (OwnerDraw__ServiceIMEMessage `0x00778030` passes the composition messages
//! to DefWindowProc). winit hides that window, so VERA20k draws the
//! composition after the typed text in the NewEdit composition colour.
//!
//! winit's web backend has no input-method support; in the browser `web::ime`
//! supplies these same events. RESIDUAL: there the candidate window opens at
//! the edit's left end plus the composition, not after the typed text.
//!
//! ## Dependency rules
//! - Part of the app layer — may depend on everything.

use crate::app::AppState;
use crate::ui::shell::geom::RectPx;
use winit::dpi::{PhysicalPosition, PhysicalSize};
use winit::event::Ime;

/// The composition colour `[0xAC4618]`, COLORREF `0x9F9F` that
/// ShellDialog__SubclassChildByClass `0x0060F9A0` sets.
pub(crate) const COMPOSITION_RGB: [u8; 3] = [0x9F, 0x9F, 0x00];

/// The chat edit that takes IME input.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ImeTarget {
    InGameChat,
    WolLobbyChat,
    SetupChat,
    LanLobbyChat,
}

/// Process-local input-method state. Presentation-only: never serialized or
/// read by the deterministic simulation.
#[derive(Debug, Default)]
pub(crate) struct ImeState {
    /// The value last handed to `Window::set_ime_allowed`.
    allowed: bool,
    target: Option<ImeTarget>,
    /// The composition string `0x00B73318`, in UTF-16 units.
    composition: Vec<u16>,
    /// The composition cursor `0x00B73568`, in UTF-16 units.
    cursor: usize,
    /// The area last handed to `Window::set_ime_cursor_area`.
    area: Option<RectPx>,
}

impl ImeState {
    /// The open composition and its cursor, when `target` owns it.
    pub(crate) fn composition(&self, target: ImeTarget) -> Option<(&[u16], usize)> {
        (self.target == Some(target) && !self.composition.is_empty())
            .then_some((self.composition.as_slice(), self.cursor))
    }

    /// While a composition is open, keys go to the input method (Windows
    /// delivers them as VK_PROCESSKEY), not to the edit.
    pub(crate) fn composing(&self) -> bool {
        !self.composition.is_empty()
    }

    /// OwnerDraw__CancelIMEComposition `0x00777E00`.
    fn cancel(&mut self) {
        self.composition.clear();
        self.cursor = 0;
    }
}

/// The chat edit that takes keys now, with its rectangle in window pixels.
/// The dialogs the key dispatch serves first leave none.
fn target(state: &AppState) -> Option<(ImeTarget, RectPx)> {
    if state.frontend.keyboard_dialog.is_some()
        || crate::app::input::coop_score::active(state)
        || crate::app::frontend::preview_zoom::is_open(state)
    {
        return None;
    }
    [
        (
            ImeTarget::InGameChat,
            crate::app::input::messages::ime_edit_rect(state),
        ),
        (
            ImeTarget::LanLobbyChat,
            crate::app::shell_lan::ime_chat_rect(state),
        ),
        (
            ImeTarget::SetupChat,
            crate::app::input::multiplayer_setup::ime_chat_rect(state),
        ),
        (
            ImeTarget::WolLobbyChat,
            crate::app::shell_wol::lobby::ime_chat_rect(state),
        ),
    ]
    .into_iter()
    .find_map(|(target, rect)| rect.map(|rect| (target, rect)))
}

/// Ask for an input method exactly while a chat edit takes keys
/// (`0x00778180` / `0x007781A0`), and keep the system's candidate window at
/// that edit.
pub(crate) fn sync(state: &mut AppState) {
    #[cfg(all(feature = "wasm", target_arch = "wasm32", target_os = "unknown"))]
    for ime in crate::web::ime::take_events() {
        event(state, ime);
    }
    let found = target(state);
    let target = found.map(|(target, _)| target);
    // Asks about every NewEdit, not just the chat lines: the overlay's button
    // must open for a save-name field too.
    let text_open = text_entry_open(state);
    let platform = &mut state.platform;
    let ime = &mut platform.ime;
    if ime.target != target {
        ime.cancel();
        ime.target = target;
    }
    if ime.allowed != target.is_some() {
        ime.allowed = target.is_some();
        ime.area = None;
        platform.window.set_ime_allowed(ime.allowed);
        #[cfg(all(feature = "wasm", target_arch = "wasm32", target_os = "unknown"))]
        crate::web::ime::set_allowed(ime.allowed);
    }
    if let Some((_, rect)) = found
        && ime.area != Some(rect)
    {
        ime.area = Some(rect);
        platform.window.set_ime_cursor_area(
            PhysicalPosition::new(rect.x, rect.y),
            PhysicalSize::new(rect.w.max(1) as u32, rect.h.max(1) as u32),
        );
        #[cfg(all(feature = "wasm", target_arch = "wasm32", target_os = "unknown"))]
        crate::web::ime::set_area(rect.x, rect.y, rect.w.max(1) as u32, rect.h.max(1) as u32);
    }
    #[cfg(all(feature = "wasm", target_arch = "wasm32", target_os = "unknown"))]
    if ime.allowed {
        crate::web::ime::keep_focus();
    }
    #[cfg(target_os = "android")]
    CHAT_OPEN.store(text_open, std::sync::atomic::Ordering::Relaxed);
}

/// One winit `Ime` event.
pub(crate) fn event(state: &mut AppState, event: Ime) {
    match event {
        Ime::Preedit(text, cursor) => {
            let ime = &mut state.platform.ime;
            ime.composition = text.encode_utf16().collect();
            // winit reports the cursor as byte offsets, or none when hidden.
            ime.cursor = cursor
                .and_then(|(start, _)| text.get(..start))
                .map_or(ime.composition.len(), |prefix| {
                    prefix.encode_utf16().count()
                });
        }
        Ime::Commit(text) => {
            state.platform.ime.cancel();
            commit(state, &text);
        }
        Ime::Disabled => state.platform.ime.cancel(),
        Ime::Enabled => {}
    }
    state.platform.window.request_redraw();
}

/// Whether a chat edit took IME text on the last frame.
///
/// The Android overlay asks before it opens a keyboard, so a player who forgot
/// to open the chat is told, rather than typing into a dialog whose text is
/// then dropped by [`commit`]'s `None` arm.
#[cfg(target_os = "android")]
static CHAT_OPEN: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// Text runs typed outside the event loop.
///
/// Android's `android-native-activity` backend has no input connection, so
/// winit never delivers an `Ime` event there and the system keyboard cannot be
/// asked for one. The activity shows its own `EditText` instead and posts the
/// finished run here; the loop drains it at the top of each frame (see
/// `AppHandler::about_to_wait`), so every commit still happens on the thread
/// that owns the state, in the same order an OS commit would.
#[cfg(target_os = "android")]
static INJECTED: std::sync::Mutex<Vec<String>> = std::sync::Mutex::new(Vec::new());

#[cfg(target_os = "android")]
pub(crate) fn inject_commit(text: &str) {
    if text.is_empty() {
        return;
    }
    let mut queue = INJECTED
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    // A burst of frames cannot hold more than a chat line's worth; dropping the
    // excess keeps a stuck overlay from growing the queue without bound.
    if queue.len() < 8 {
        queue.push(text.to_owned());
    }
}

#[cfg(target_os = "android")]
pub(crate) fn drain_injected(state: &mut AppState) {
    let pending = {
        let mut queue = INJECTED
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        std::mem::take(&mut *queue)
    };
    for text in pending {
        event(state, Ime::Commit(text));
    }
}

/// Whether a chat edit takes text now, for the activity's overlay button.
#[cfg(target_os = "android")]
pub(crate) fn chat_open() -> bool {
    CHAT_OPEN.load(std::sync::atomic::Ordering::Relaxed)
}

/// WM_IME_CHAR's consumers: the in-game queue and NewEdit's WM_CHAR insertion,
/// here each screen's own text path.
///
/// The chat lines keep their own paths; every other NewEdit (the save-game
/// description field, the saved-seed name field, the skirmish player name)
/// ignores text unless its own editor holds focus, so trying them in order is
/// safe and no screen can steal another's run.
fn commit(state: &mut AppState, text: &str) {
    if let Some(target) = target(state).map(|(target, _)| target) {
        match target {
            ImeTarget::InGameChat => crate::app::input::chat::commit_text(state, text),
            ImeTarget::LanLobbyChat => crate::app::shell_lan::key_press(state, None, Some(text)),
            ImeTarget::SetupChat => {
                crate::app::input::multiplayer_setup::key_press(state, None, Some(text))
            }
            ImeTarget::WolLobbyChat => {
                crate::app::shell_wol::lobby::key_press(state, None, Some(text))
            }
        }
        return;
    }
    // Save Game / Saved Seeds name fields (both are `SavedSeedBrowserState`).
    crate::app::App::saved_game_key(state, None, Some(text));
    crate::app::App::handle_saved_seed_browser_key(state, None, Some(text));
    if state.frontend.skirmish_shell_state.player_name_edit.focused {
        state
            .frontend
            .skirmish_shell_state
            .player_name_edit
            .insert_text(text);
    }
}

/// Whether any NewEdit takes committed text now — not only the chat lines.
///
/// The Android overlay asks this before it opens a keyboard: a save-name field
/// is as much a text field as a chat line, and refusing there was the bug that
/// made saving unusable.
#[cfg(target_os = "android")]
fn text_entry_open(state: &AppState) -> bool {
    target(state).is_some()
        || state
            .match_state
            .match_presentation
            .saved_game_browser
            .as_ref()
            .is_some_and(|browser| browser.description_edit.focused)
        || state
            .frontend
            .skirmish_shell_state
            .saved_seed_browser
            .as_ref()
            .is_some_and(|browser| browser.description_edit.focused)
        || state.frontend.skirmish_shell_state.player_name_edit.focused
}
