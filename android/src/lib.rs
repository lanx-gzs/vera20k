//! Android entry point for the VERA20k client.
//!
//! Android never runs `main`. `android.app.NativeActivity` loads the cdylib and
//! resolves `android_main` by symbol (the manifest names the library through
//! `android.app.lib_name`), so the process starts here instead. Everything
//! platform-specific that has to happen before the first window — the working
//! directory, logging, panic reporting — runs in this order and then hands over
//! to `vera20k::entry`, the same start-up the desktop binary uses.
//!
//! One process gets one `android_main` call per activity creation; the activity
//! is destroyed and recreated on rotation, a locale change or a configuration
//! change the manifest does not absorb. `EventLoop` can be built only once per
//! process (winit keeps it in a static), so a second call must not reach
//! `EventLoop::builder()` — see the guard below. Killing the process on that
//! path is deliberate for the first milestone: a stale surface is a black
//! screen, while a restart is recoverable. P5 replaces it with real
//! suspend/resume handling.
#![cfg(target_os = "android")]

use android_activity::AndroidApp;
use jni::JavaVM;
use jni::objects::{JObject, JString};
use jni::sys::jstring;
use winit::platform::android::EventLoopBuilderExtAndroid;

/// Run `f` with the activity this process belongs to.
///
/// The picks go through `ndk_context`'s raw pointers rather than
/// `AndroidApp`'s own helpers: the engine calls this from inside winit's event
/// callback, where android-activity's activity lock is already held, and taking
/// it again would deadlock the loop.
fn with_activity<R>(f: impl FnOnce(&mut jni::JNIEnv, &JObject) -> R) -> Option<R> {
    let context = ndk_context::android_context();
    // SAFETY: android-activity published both handles at start-up and keeps the
    // Java VM alive for the process's lifetime; the activity reference is a JNI
    // global reference the loader owns.
    let vm = unsafe { JavaVM::from_raw(context.vm().cast()) }.ok()?;
    let mut env = vm.attach_current_thread().ok()?;
    let activity = unsafe { JObject::from_raw(context.context().cast()) };
    Some(f(&mut env, &activity))
}

/// Folder-picker hook for `vera20k::util::folder_picker`.
///
/// The picker is the one thing the NDK cannot do itself: a Storage Access
/// Framework tree is readable only through Java's `ContentResolver`, so the
/// activity starts `ACTION_OPEN_DOCUMENT_TREE` and reports back through
/// [`Java_org_vera20k_VeraActivity_nativeOnFolderPicked`].
fn request_folder_picker() -> bool {
    with_activity(|env, activity| {
        env.call_method(activity, "startFolderPicker", "()V", &[])
            .is_ok()
    })
    .unwrap_or(false)
}

fn cancel_import() -> bool {
    with_activity(|env, activity| {
        env.call_method(activity, "cancelImport", "()Z", &[])
            .and_then(|value| value.z())
            .unwrap_or(false)
    })
    .unwrap_or(false)
}

/// The activity's folder-picker answer: the chosen tree as a `content://` URI,
/// or an empty string when the player cancelled.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_vera20k_VeraActivity_nativeOnFolderPicked(
    mut env: jni::JNIEnv,
    activity: JObject,
    tree_uri: JString,
) {
    let picked = env
        .get_string(&tree_uri)
        .ok()
        .map(|text| text.to_string_lossy().into_owned())
        .filter(|text| !text.is_empty());
    match &picked {
        Some(uri) => {
            log::info!("asset import: folder picked: {uri}");
            // The dialog switches to its copying step before the copy's first
            // progress callback can arrive.
            vera20k::util::import_status::note(vera20k::util::import_status::ImportEvent::Started);
            // The copy itself runs on the Java side (only ContentResolver can
            // read a SAF tree); this starts it and returns immediately.
            if let Err(error) = env.call_method(
                &activity,
                "startImport",
                "(Ljava/lang/String;)V",
                &[jni::objects::JValue::Object(&tree_uri)],
            ) {
                let _ = env.exception_clear();
                vera20k::util::import_status::note(
                    vera20k::util::import_status::ImportEvent::Failed(format!(
                        "Could not start the copy: {error}"
                    )),
                );
            }
        }
        None => log::info!("asset import: folder picker cancelled"),
    }
    vera20k::util::folder_picker::deliver(picked);
}

/// The import finished: `ok:<path>` after the copy and config.toml are written,
/// `failed:<reason>` otherwise. Runs on the copy's own thread, so it only
/// records the outcome.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_vera20k_VeraActivity_nativeImportFinished(
    mut env: jni::JNIEnv,
    _activity: JObject,
    result: JString,
) {
    let text = env
        .get_string(&result)
        .ok()
        .map(|value| value.to_string_lossy().into_owned())
        .unwrap_or_default();
    use vera20k::util::import_status::{self, ImportEvent};
    if text == "cancelled" {
        import_status::note(ImportEvent::Cancelled);
    } else if let Some(path) = text.strip_prefix("ok:") {
        log::info!("asset import: copied into {path}; config.toml written");
        import_status::note(ImportEvent::Done(path.to_owned()));
    } else {
        let reason = text.strip_prefix("failed:").unwrap_or(&text).to_owned();
        log::error!("asset import: {reason}");
        import_status::note(ImportEvent::Failed(reason));
    }
    let _ = env.exception_clear();
}

/// Prepare the next config using the same editor as the desktop folder picker.
/// Java atomically publishes it only after the complete copy is ready.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_vera20k_VeraActivity_nativeConfigForImport(
    mut env: jni::JNIEnv,
    _activity: JObject,
    directory: JString,
) -> jstring {
    let result = (|| -> anyhow::Result<jstring> {
        let directory: String = env.get_string(&directory)?.into();
        let config_path = vera20k::util::platform_dir::get()
            .ok_or_else(|| anyhow::anyhow!("The app data directory is unavailable"))?
            .join("config.toml");
        let previous = match std::fs::read_to_string(config_path) {
            Ok(contents) => contents,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => String::new(),
            Err(error) => return Err(error.into()),
        };
        let contents = vera20k::util::config::set_paths_key(&previous, "ra2_dir", &directory);
        Ok(env.new_string(contents)?.into_raw())
    })();
    match result {
        Ok(contents) => contents,
        Err(error) => {
            let _ = env.throw_new("java/io/IOException", error.to_string());
            std::ptr::null_mut()
        }
    }
}

/// The import's copy progress, in bytes. Runs on the copy's own thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_vera20k_VeraActivity_nativeImportProgress(
    _env: jni::JNIEnv,
    _activity: JObject,
    copied_bytes: jni::sys::jlong,
) {
    use vera20k::util::import_status::{self, ImportEvent};
    import_status::note(ImportEvent::Progress(copied_bytes.max(0) as u64));
}

/// The activity's keyboard overlay: the finished run, committed into the chat
/// edit that currently takes keys.
///
/// NativeActivity has no input connection, so winit never asks for a system
/// keyboard here. The activity shows its own `EditText` and calls this; the
/// engine queues the run and commits it on the event thread, which is where
/// every other `Ime::Commit` is applied.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_vera20k_VeraActivity_nativeTypeText(
    mut env: jni::JNIEnv,
    _activity: JObject,
    text: JString,
) {
    match env.get_string(&text) {
        Ok(text) => {
            let text = text.to_string_lossy().into_owned();
            log::info!("ime overlay: committing {} chars", text.chars().count());
            vera20k::inject_ime_commit(&text);
        }
        Err(error) => {
            log::error!("ime overlay: cannot read the text: {error}");
            let _ = env.exception_clear();
        }
    }
}

/// Whether a chat edit takes text now. The overlay asks first: text typed while
/// none is open would be dropped by the commit's `None` arm, so the player is
/// told to open the chat instead of losing what they typed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_vera20k_VeraActivity_nativeImeChatOpen(
    _env: jni::JNIEnv,
    _activity: JObject,
) -> jni::sys::jboolean {
    if vera20k::ime_chat_open() { 1 } else { 0 }
}

/// Toggle one on-screen modifier chip. Returns whether that chip is now held.
///
/// Touch-screen players cannot hold Ctrl while tapping a target, and a
/// synthetic key event would be overwritten by winit's next modifier rebuild.
/// The flag lives in the engine's input layer instead, so "force attack" stays
/// armed until the same chip is tapped again.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_vera20k_VeraActivity_nativeSetModifier(
    mut env: jni::JNIEnv,
    _activity: JObject,
    name: JString,
    down: jni::sys::jboolean,
) -> jni::sys::jboolean {
    let held = match env.get_string(&name) {
        Ok(name) => vera20k::set_sticky_modifier(&name.to_string_lossy(), down != 0).unwrap_or(false),
        Err(error) => {
            log::error!("modifier chip: cannot read the name: {error}");
            let _ = env.exception_clear();
            false
        }
    };
    if held { 1 } else { 0 }
}

/// Which chips are lit: 1 Ctrl, 2 Shift, 4 Alt. Read after the overlay is
/// re-attached, so a rotation cannot leave the chips lying about the state.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_vera20k_VeraActivity_nativeModifierMask(
    _env: jni::JNIEnv,
    _activity: JObject,
) -> jni::sys::jint {
    i32::from(vera20k::sticky_modifier_mask())
}

/// Bind `nativeOnFolderPicked` to this activity's class.
///
/// Registered from `android_main`, not `JNI_OnLoad`: `FindClass` on the loading
/// thread uses the system class loader and cannot see `org.vera20k.VeraActivity`,
/// so the earlier attempt silently fell through to the symbol lookup that fails
/// on this device (`UnsatisfiedLinkError` for a name `readelf --dyn-syms` shows
/// in the packaged library). Here the activity object is at hand, and the class
/// it reports carries the app's own loader.
///
/// Returns whether the binding took; a caller reports it rather than letting a
/// later crash say so.
fn register_folder_picker_native() -> bool {
    with_activity(|env, activity| {
        let Ok(class) = env.get_object_class(activity) else {
            let _ = env.exception_clear();
            return false;
        };
        let methods = [
            jni::NativeMethod {
                name: "nativeOnFolderPicked".into(),
                sig: "(Ljava/lang/String;)V".into(),
                fn_ptr: Java_org_vera20k_VeraActivity_nativeOnFolderPicked as *mut std::ffi::c_void,
            },
            jni::NativeMethod {
                name: "nativeImportFinished".into(),
                sig: "(Ljava/lang/String;)V".into(),
                fn_ptr: Java_org_vera20k_VeraActivity_nativeImportFinished as *mut std::ffi::c_void,
            },
            jni::NativeMethod {
                name: "nativeImportProgress".into(),
                sig: "(J)V".into(),
                fn_ptr: Java_org_vera20k_VeraActivity_nativeImportProgress as *mut std::ffi::c_void,
            },
            jni::NativeMethod {
                name: "nativeConfigForImport".into(),
                sig: "(Ljava/lang/String;)Ljava/lang/String;".into(),
                fn_ptr: Java_org_vera20k_VeraActivity_nativeConfigForImport
                    as *mut std::ffi::c_void,
            },
            jni::NativeMethod {
                name: "nativeTypeText".into(),
                sig: "(Ljava/lang/String;)V".into(),
                fn_ptr: Java_org_vera20k_VeraActivity_nativeTypeText as *mut std::ffi::c_void,
            },
            jni::NativeMethod {
                name: "nativeImeChatOpen".into(),
                sig: "()Z".into(),
                fn_ptr: Java_org_vera20k_VeraActivity_nativeImeChatOpen as *mut std::ffi::c_void,
            },
            jni::NativeMethod {
                name: "nativeSetModifier".into(),
                sig: "(Ljava/lang/String;Z)Z".into(),
                fn_ptr: Java_org_vera20k_VeraActivity_nativeSetModifier as *mut std::ffi::c_void,
            },
            jni::NativeMethod {
                name: "nativeModifierMask".into(),
                sig: "()I".into(),
                fn_ptr: Java_org_vera20k_VeraActivity_nativeModifierMask as *mut std::ffi::c_void,
            },
        ];
        let registered = env.register_native_methods(class, &methods).is_ok();
        if !registered {
            let _ = env.exception_clear();
        }
        registered
    })
    .unwrap_or(false)
}

/// Ask the system to keep the display on while this window is visible.
///
/// The intro movie runs for minutes with nothing to touch, and Android's own
/// display timeout would otherwise cut it off; no synthetic input event needs
/// to stand in for a tap.
///
/// Only called before the event loop starts, on purpose:
/// `AndroidApp::set_window_flags` takes the activity's lock, and winit holds it
/// for the whole of an event callback, so setting the flag from inside one (say,
/// when a movie begins) deadlocks the loop. The flag lives with the window, so
/// one call at start-up covers every screen until the activity is destroyed.
fn keep_screen_on(app: &AndroidApp) {
    use android_activity::WindowManagerFlags;
    app.set_window_flags(
        WindowManagerFlags::KEEP_SCREEN_ON,
        WindowManagerFlags::empty(),
    );
}

/// Resolved by `android-activity`'s loader trampoline; the name is the ABI.
///
/// Android calls the symbol with the activity's handle, which
/// `android-activity` turns into an `AndroidApp`.
#[unsafe(no_mangle)]
fn android_main(app: AndroidApp) {
    keep_screen_on(&app);
    vera20k::util::folder_picker::install(request_folder_picker, cancel_import);
    // Android starts every process in "/" with no writable home. Hand the app's
    // private folder to the engine before anything opens a file: it becomes
    // both the working directory and the settings folder, so logs, config.toml,
    // VERA.INI, saves and replays all persist there.
    match app.internal_data_path() {
        Some(dir) => {
            if let Err(error) = vera20k::entry::adopt_android_data_dir(&dir) {
                eprintln!("cannot use the app data directory: {error:#}");
            }
        }
        None => eprintln!("no internal data path; logs and settings will not persist"),
    }

    let log_path = vera20k::entry::init_logging();
    vera20k::entry::log_banner(log_path.as_deref());
    // Bind the picker's callback before the player can reach the button, and say
    // whether it took: a silent failure here is what turned a missing folder
    // picker into an `UnsatisfiedLinkError` at the worst moment.
    log::info!(
        "asset import: folder-picker callback registered: {}",
        register_folder_picker_native()
    );

    // Android can start a second activity in this same, still-cached process
    // after the player exits to the launcher and taps the icon again. A second
    // winit event loop is impossible (winit keeps process-wide state) and the
    // guard says so, but returning would leave the new activity on its splash
    // for ever. End the process instead, so that tap is a cold start.
    if !claim_event_loop() {
        log::error!("android_main called again; ending this process for a cold relaunch");
        log::logger().flush();
        std::process::exit(0);
    }

    let event_loop: winit::event_loop::EventLoop<()> = match winit::event_loop::EventLoop::builder()
        .with_android_app(app)
        .build()
    {
        Ok(event_loop) => event_loop,
        Err(error) => {
            log::error!("cannot create the Android event loop: {error}");
            return;
        }
    };

    // Android has no retail command line: the launcher starts an ordinary
    // interactive game. `-SPAWN`, replay and capture launches are desktop
    // automation and stay out of this path.
    let launch_mode = vera20k::app::frontend::launch::AppLaunchMode::Interactive(
        vera20k::app::frontend::startup_options::RetailStartupOptions::default(),
    );

    match vera20k::entry::run_with_loop(launch_mode, false, event_loop) {
        Ok(0) => {}
        // Android has no `-SPAWN` status to propagate; a non-zero one still
        // belongs in the log before the activity finishes.
        Ok(status) => log::warn!("VERA20k exited with status {status}"),
        Err(error) => log::error!("VERA20k stopped: {error:#}"),
    }
    log::logger().flush();

    // One process runs one game. Exiting here (rather than returning and letting
    // Android cache the process) is what makes the launcher's next tap start
    // from a clean slate: the event loop above cannot be rebuilt, so a cached
    // process would serve the new activity nothing but its splash.
    std::process::exit(0);
}

/// Claims this process's single event loop.
///
/// winit keeps its event loop in a static and panics on a second build, and a
/// panic inside the JNI callback aborts the process with no log line. The first
/// caller wins; every later one is told so the activity can finish quietly.
fn claim_event_loop() -> bool {
    use std::sync::OnceLock;
    static CLAIMED: OnceLock<()> = OnceLock::new();
    CLAIMED.set(()).is_ok()
}

// ---------------------------------------------------------------------------
// MIX browsing for the Android file layer.
//
// `LauncherActivity` and `MixActivity` are plain Java activities that never run
// the engine, but the archive and thumbnail readers already live in
// `vera20k::assets` and `vera20k::map`. Exposing them over JNI keeps one
// implementation instead of a Kotlin port of RSA, Blowfish and LZO. Everything
// here is read-only: MIX bodies are plain bytes, and re-writing an archive needs
// the private key Westwood never shipped, so extraction is the only direction.
//
// Registration happens in `JNI_OnLoad` because these methods are static on an
// object the engine's own activity registration never sees.
// ---------------------------------------------------------------------------

use jni::objects::JClass;
use jni::sys::{jboolean, jbyteArray, jint, JNI_ERR, JNI_FALSE, JNI_TRUE, JNI_VERSION_1_6};
use std::ffi::c_void;
use std::path::Path;

/// Registers the MIX bridge as soon as the library is loaded.
///
/// Returns the JNI version even when registration fails: refusing to load the
/// library would take the whole game down over a file browser.
#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    let Ok(mut env) = vm.get_env() else {
        return JNI_VERSION_1_6;
    };
    let Ok(class) = env.find_class("org/vera20k/MixBridge") else {
        let _ = env.exception_clear();
        return JNI_VERSION_1_6;
    };
    let methods = [
        jni::NativeMethod {
            name: "nativeEntries".into(),
            sig: "(Ljava/lang/String;I)Ljava/lang/String;".into(),
            fn_ptr: Java_org_vera20k_MixBridge_nativeEntries as *mut c_void,
        },
        jni::NativeMethod {
            name: "nativeExtract".into(),
            sig: "(Ljava/lang/String;IILjava/lang/String;)Z".into(),
            fn_ptr: Java_org_vera20k_MixBridge_nativeExtract as *mut c_void,
        },
        jni::NativeMethod {
            name: "nativeMapPreview".into(),
            sig: "(Ljava/lang/String;)[B".into(),
            fn_ptr: Java_org_vera20k_MixBridge_nativeMapPreview as *mut c_void,
        },
    ];
    if env.register_native_methods(class, &methods).is_err() {
        let _ = env.exception_clear();
        return JNI_ERR;
    }
    JNI_VERSION_1_6
}

fn open_archive(path: &str, parent: i32) -> Result<vera20k::assets::mix_archive::MixArchive, String> {
    use vera20k::assets::mix_archive::MixArchive;
    let top = MixArchive::load(Path::new(path)).map_err(|error| error.to_string())?;
    if parent == 0 {
        return Ok(top);
    }
    top.nested_archive_by_id(parent)
        .map_err(|error| error.to_string())?
        .ok_or_else(|| "这个条目不是 MIX 归档".to_string())
}

/// `{"ok":true,"count":n,"bytes":n,"entries":[{id,size,offset,nested}]}`.
///
/// Hand-built rather than serde: the shape is three numbers per entry and adding
/// a dependency to the Android crate would touch the lockfile the build pins.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_vera20k_MixBridge_nativeEntries(
    mut env: jni::JNIEnv,
    _class: JClass,
    path: JString,
    parent: jint,
) -> jstring {
    let path_text = match env.get_string(&path) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(_) => {
            let _ = env.exception_clear();
            return json_reply(&mut env, "{\"ok\":false,\"error\":\"路径无效\"}");
        }
    };
    let archive = match open_archive(&path_text, parent) {
        Ok(archive) => archive,
        Err(error) => return json_reply(&mut env, &format!("{{\"ok\":false,\"error\":{}}}", quote(&error))),
    };

    let mut out = String::from("{\"ok\":true,\"count\":");
    out.push_str(&archive.entry_count().to_string());
    out.push_str(",\"bytes\":");
    out.push_str(&archive.byte_len().to_string());
    out.push_str(",\"entries\":[");
    for (index, entry) in archive.entries().iter().enumerate() {
        if index > 0 {
            out.push(',');
        }
        let nested = archive
            .get_by_id(entry.id)
            .map(vera20k::assets::mix_archive::MixArchive::looks_like_mix)
            .unwrap_or(false);
        out.push_str(&format!(
            "{{\"id\":{},\"size\":{},\"offset\":{},\"nested\":{}}}",
            entry.id, entry.size, entry.offset, nested
        ));
    }
    out.push_str("]}");
    json_reply(&mut env, &out)
}

/// Writes one entry to `out`. Returns whether the file landed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_vera20k_MixBridge_nativeExtract(
    mut env: jni::JNIEnv,
    _class: JClass,
    path: JString,
    parent: jint,
    id: jint,
    out: JString,
) -> jboolean {
    let read = |env: &mut jni::JNIEnv, value: &JString| -> Option<String> {
        env.get_string(value)
            .ok()
            .map(|text| text.to_string_lossy().into_owned())
    };
    let (Some(path_text), Some(out_text)) = (read(&mut env, &path), read(&mut env, &out)) else {
        let _ = env.exception_clear();
        return JNI_FALSE;
    };
    let Ok(archive) = open_archive(&path_text, parent) else {
        return JNI_FALSE;
    };
    let Some(bytes) = archive.get_by_id(id) else {
        return JNI_FALSE;
    };
    let target = Path::new(&out_text);
    if let Some(parent) = target.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    match std::fs::write(target, bytes) {
        Ok(()) => JNI_TRUE,
        Err(error) => {
            log::warn!("MIX extract to {out_text} failed: {error}");
            JNI_FALSE
        }
    }
}

/// The map's own preview, as `[width][height][RGBA...]`, or an empty array.
///
/// Retail maps carry `[PreviewPack]`: base64 LZO over 8-bit palette indices, the
/// picture the skirmish screen shows. Decoding it needs no MIX archive and no
/// terrain tiles, which is why this is the cheap way to replace the schematic.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_vera20k_MixBridge_nativeMapPreview(
    mut env: jni::JNIEnv,
    _class: JClass,
    path: JString,
) -> jbyteArray {
    let empty = |env: &mut jni::JNIEnv| -> jbyteArray {
        match env.new_byte_array(0) {
            Ok(array) => array.into_raw(),
            Err(_) => {
                let _ = env.exception_clear();
                std::ptr::null_mut()
            }
        }
    };
    let Ok(path_text) = env
        .get_string(&path)
        .map(|text| text.to_string_lossy().into_owned())
    else {
        let _ = env.exception_clear();
        return empty(&mut env);
    };
    let Ok(bytes) = std::fs::read(&path_text) else {
        return empty(&mut env);
    };
    let Some((width, height, pack)) = preview_fields(&bytes) else {
        return empty(&mut env);
    };
    let Ok(decoded) = vera20k::map::preview::decode_preview_pack(&pack, width, height) else {
        return empty(&mut env);
    };

    let mut payload = Vec::with_capacity(8 + decoded.rgba.len());
    payload.extend_from_slice(&(decoded.width as u32).to_le_bytes());
    payload.extend_from_slice(&(decoded.height as u32).to_le_bytes());
    payload.extend_from_slice(&decoded.rgba);
    match env.byte_array_from_slice(&payload) {
        Ok(array) => array.into_raw(),
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

/// `[Preview] Size` and the concatenated `[PreviewPack]` values.
fn preview_fields(bytes: &[u8]) -> Option<(u32, u32, String)> {
    let text = String::from_utf8_lossy(bytes);
    let mut section = String::new();
    let mut size: Option<(u32, u32)> = None;
    let mut pack = String::new();
    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with('[') && trimmed.ends_with(']') {
            section = trimmed[1..trimmed.len() - 1].trim().to_ascii_lowercase();
            continue;
        }
        let Some((key, value)) = trimmed.split_once('=') else {
            continue;
        };
        let key = key.trim().to_ascii_lowercase();
        match section.as_str() {
            // `Size` is a ReadRect: x, y, width, height.
            "preview" if key == "size" => {
                let numbers: Vec<i64> = value
                    .split(',')
                    .filter_map(|part| part.trim().parse().ok())
                    .collect();
                if let [_, _, width, height] = numbers.as_slice() {
                    if *width > 0 && *height > 0 {
                        size = Some((*width as u32, *height as u32));
                    }
                }
            }
            "previewpack" => pack.push_str(value.trim()),
            _ => {}
        }
    }
    size.map(|(width, height)| (width, height, pack))
}

fn quote(text: &str) -> String {
    let mut out = String::with_capacity(text.len() + 2);
    out.push('"');
    for character in text.chars() {
        match character {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            other => out.push(other),
        }
    }
    out.push('"');
    out
}

fn json_reply(env: &mut jni::JNIEnv, body: &str) -> jstring {
    match env.new_string(body) {
        Ok(value) => value.into_raw(),
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}
