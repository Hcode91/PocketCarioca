//! PocketCarioca: the JNI side of the native Android frontend.
//!
//! The Kotlin app owns everything the user sees — the library, settings, the touch controller,
//! the menus — and a `GLSurfaceView` whose render thread calls into here. Every call that touches
//! the session or GL (`open`, `frame`, the save-state calls, `screenshot`, `close`) must come from
//! that GL thread: the session's GPU rasterizer keeps its textures in that context.
//!
//! Licensed GPL-2.0-or-later, like the Zeebx core it drives.

use std::ffi::{CStr, c_char, c_void};
use std::path::{Path, PathBuf};
use std::cell::RefCell;
use std::sync::{Arc, Mutex};
use std::time::Instant;

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jfloat, jint, jintArray, jobjectArray, jstring, JNI_FALSE, JNI_TRUE};

use zeebx::input::Pad;
use zeebx::input::bindings::Device;
use zeebx::session::{MAX_SLICE, Session, Step};
use zeebx::storage::StoragePaths;
use zeebx::ui::gpu::{GlPainter, Viewport};
use zeebx::ui::save_slots;
use zeebx::ui::settings::Settings;

// ---------------------------------------------------------------------------------------------
// GL function loading. Android exposes core GLES entry points through dlsym on libGLESv3, and
// extensions through eglGetProcAddress; try both.

unsafe extern "C" {
    fn eglGetProcAddress(name: *const c_char) -> *const c_void;
    fn dlopen(filename: *const c_char, flag: i32) -> *mut c_void;
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
}
#[link(name = "EGL")]
unsafe extern "C" {}

fn gl_loader() -> impl FnMut(&CStr) -> *const c_void {
    let lib = unsafe { dlopen(c"libGLESv3.so".as_ptr(), 2 /* RTLD_NOW */) };
    move |name: &CStr| unsafe {
        let mut p = if lib.is_null() { std::ptr::null() } else { dlsym(lib, name.as_ptr()) as *const c_void };
        if p.is_null() {
            p = eglGetProcAddress(name.as_ptr());
        }
        p
    }
}

// ---------------------------------------------------------------------------------------------
// State
//
// `Session` is not `Send` (the CPU and the rasterizer hold `Rc`s), so it can only live on the GL
// thread, in a thread-local. What other threads need — the sensor thread's motion, the UI
// thread's slot list — goes through `SHARED`, and the GL thread applies it on the next frame.

struct Paths {
    storage: StoragePaths,
    states: PathBuf,
}

#[derive(Clone, Copy)]
struct Options {
    gpu: bool,
    internal_resolution: u8,
    smooth: bool,
    audio: bool,
    volume: u8,
    /// 0 = keep the frame's shape, 1 = stretch, 2 = 16:9 widescreen render (GPU only)
    aspect: u8,
    boomerang: bool,
}

const DEFAULT_OPTIONS: Options =
    Options { gpu: true, internal_resolution: 1, smooth: true, audio: true, volume: 100, aspect: 0, boomerang: false };

struct Shared {
    paths: Option<Paths>,
    options: Options,
    last_error: Option<String>,
    /// The open game's state id and title, for the UI thread.
    state_id: Option<String>,
    title: String,
    /// Latest accelerometer reading, in g, in the Boomerang's frame.
    motion: Option<[f32; 3]>,
    /// Keyboard events waiting for the GL thread.
    keys: Vec<(u32, bool)>,
}

static SHARED: Mutex<Shared> = Mutex::new(Shared {
    paths: None,
    options: DEFAULT_OPTIONS,
    last_error: None,
    state_id: None,
    title: String::new(),
    motion: None,
    keys: Vec::new(),
});

fn shared() -> std::sync::MutexGuard<'static, Shared> {
    SHARED.lock().unwrap_or_else(|e| e.into_inner())
}

struct Running {
    session: Session,
    state_id: String,
    last: Instant,
}

#[derive(Default)]
struct GlState {
    gl: Option<Arc<glow::Context>>,
    painter: Option<GlPainter>,
    running: Option<Running>,
    settings: Option<Settings>,
}

thread_local! {
    static GL: RefCell<GlState> = RefCell::new(GlState::default());
}

fn with_gl<R>(f: impl FnOnce(&mut GlState) -> R) -> R {
    GL.with(|g| f(&mut g.borrow_mut()))
}

fn set_closed() {
    let mut sh = shared();
    sh.state_id = None;
    sh.title.clear();
}

fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|s| s.into()).unwrap_or_default()
}

// ---------------------------------------------------------------------------------------------
// Lifecycle (any thread)

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hcode91_pocketcarioca_Core_init(
    mut env: JNIEnv,
    _: JClass,
    context: JObject,
    files_dir: JString,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info).with_tag("PocketCarioca"),
    );
    std::panic::set_hook(Box::new(|info| log::error!("panic: {info}")));

    // cpal (Oboe) finds the JavaVM and the Context through ndk-context, which NativeActivity
    // would normally set up. We are a plain JNI library, so we do it ourselves, once.
    static ONCE: std::sync::Once = std::sync::Once::new();
    if let (Ok(vm), Ok(global)) = (env.get_java_vm(), env.new_global_ref(&context)) {
        ONCE.call_once(|| {
            let ctx_ptr = global.as_obj().as_raw() as *mut c_void;
            std::mem::forget(global); // lives for the whole process
            unsafe { ndk_context::initialize_android_context(vm.get_java_vm_pointer() as *mut c_void, ctx_ptr) };
        });
    }

    let base = PathBuf::from(jstr(&mut env, &files_dir));
    let storage_base = base.join(".config");
    let storage = StoragePaths::for_frontend(&storage_base, Some(&storage_base));
    if let Err(e) = storage.create_dirs() {
        log::error!("could not create storage: {e}");
    }
    let states = base.join("states");
    let _ = std::fs::create_dir_all(&states);
    shared().paths = Some(Paths { storage, states });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hcode91_pocketcarioca_Core_setOptions(
    _: JNIEnv,
    _: JClass,
    gpu: jboolean,
    internal_resolution: jint,
    smooth: jboolean,
    audio: jboolean,
    volume: jint,
    aspect: jint,
    boomerang: jboolean,
) {
    shared().options = Options {
        gpu: gpu == JNI_TRUE,
        internal_resolution: internal_resolution.clamp(1, 4) as u8,
        smooth: smooth == JNI_TRUE,
        audio: audio == JNI_TRUE,
        volume: volume.clamp(0, 100) as u8,
        aspect: aspect.clamp(0, 2) as u8,
        boomerang: boomerang == JNI_TRUE,
    };
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hcode91_pocketcarioca_Core_setSoundfont(mut env: JNIEnv, _: JClass, path: JString) {
    let p = jstr(&mut env, &path);
    zeebx::audio::soundfont::set_soundfont((!p.is_empty()).then(|| PathBuf::from(p)));
}

/// Accelerometer reading in g, already in the Boomerang's frame. Called from the sensor thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hcode91_pocketcarioca_Core_motion(_: JNIEnv, _: JClass, x: jfloat, y: jfloat, z: jfloat) {
    shared().motion = Some([x, y, z]);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hcode91_pocketcarioca_Core_key(_: JNIEnv, _: JClass, avk: jint, pressed: jboolean) {
    shared().keys.push((avk as u32, pressed == JNI_TRUE));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hcode91_pocketcarioca_Core_isRunning(_: JNIEnv, _: JClass) -> jboolean {
    if shared().state_id.is_some() { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hcode91_pocketcarioca_Core_lastError(env: JNIEnv, _: JClass) -> jstring {
    let e = shared().last_error.take();
    opt_jstring(env, e)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hcode91_pocketcarioca_Core_title(env: JNIEnv, _: JClass) -> jstring {
    let t = shared().title.clone();
    opt_jstring(env, Some(t))
}

// ---------------------------------------------------------------------------------------------
// Library (any thread)

/// Returns `[title0, path0, title1, path1, ...]`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hcode91_pocketcarioca_Core_scan(mut env: JNIEnv, _: JClass, dir: JString) -> jobjectArray {
    let dir = jstr(&mut env, &dir);
    let games = zeebx::library::scan(Path::new(&dir));
    let Ok(string_class) = env.find_class("java/lang/String") else { return std::ptr::null_mut() };
    let Ok(arr) = env.new_object_array((games.len() * 2) as i32, &string_class, JObject::null()) else {
        return std::ptr::null_mut();
    };
    for (i, g) in games.iter().enumerate() {
        if let (Ok(t), Ok(p)) = (env.new_string(&g.title), env.new_string(g.path.to_string_lossy())) {
            let _ = env.set_object_array_element(&arr, (i * 2) as i32, t);
            let _ = env.set_object_array_element(&arr, (i * 2 + 1) as i32, p);
        }
    }
    arr.into_raw()
}

/// Cover art as `[width, height, argb...]`, or null.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hcode91_pocketcarioca_Core_cover(mut env: JNIEnv, _: JClass, path: JString) -> jintArray {
    let p = jstr(&mut env, &path);
    let Some(img) = zeebx::library::cover(Path::new(&p)) else { return std::ptr::null_mut() };
    let img = img.downscaled(256);
    let mut out = Vec::with_capacity(2 + img.pixels());
    out.push(img.width as i32);
    out.push(img.height as i32);
    for px in img.rgba.chunks_exact(4) {
        out.push(argb(px[3], px[0], px[1], px[2]));
    }
    int_array(env, &out)
}

// ---------------------------------------------------------------------------------------------
// Save-state slots that only read files (any thread)

/// Which slots are filled, as a bitmask, for the open game.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hcode91_pocketcarioca_Core_slots(_: JNIEnv, _: JClass) -> jint {
    let sh = shared();
    match (sh.paths.as_ref(), sh.state_id.as_deref()) {
        (Some(p), Some(id)) => save_slots::list(&p.states, id)
            .iter()
            .enumerate()
            .fold(0, |m, (i, s)| if s.is_some() { m | (1 << i) } else { m }),
        _ => 0,
    }
}

/// Slot thumbnail as `[width, height, argb...]`, or null.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hcode91_pocketcarioca_Core_slotThumbnail(env: JNIEnv, _: JClass, slot: jint) -> jintArray {
    let thumb = {
        let sh = shared();
        let (Some(p), Some(id)) = (sh.paths.as_ref(), sh.state_id.as_deref()) else { return std::ptr::null_mut() };
        save_slots::list(&p.states, id).into_iter().nth(slot as usize).flatten().and_then(|s| s.thumbnail)
    };
    let Some(t) = thumb else { return std::ptr::null_mut() };
    let mut out = vec![t.width as i32, t.height as i32];
    for px in t.rgb565.chunks_exact(2) {
        let v = u16::from_le_bytes([px[0], px[1]]) as u32;
        let r = ((v >> 11) & 0x1f) * 255 / 31;
        let g = ((v >> 5) & 0x3f) * 255 / 63;
        let b = (v & 0x1f) * 255 / 31;
        out.push(argb(0xff, r as u8, g as u8, b as u8));
    }
    int_array(env, &out)
}

// ---------------------------------------------------------------------------------------------
// GL thread

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hcode91_pocketcarioca_Core_surfaceCreated(_: JNIEnv, _: JClass) {
    with_gl(|g| {
        // A new EGL context: everything in the old one is gone, including a running session's
        // textures. The Kotlin side asks GLSurfaceView to preserve the context on pause, so this
        // normally happens once per activity.
        if g.running.take().is_some() {
            log::warn!("EGL context was recreated while a game was running; closing it");
            set_closed();
        }
        if let Some(old) = g.painter.take()
            && let Some(gl) = &g.gl
        {
            old.release(gl);
        }
        let mut loader = gl_loader();
        let gl = Arc::new(unsafe { glow::Context::from_loader_function_cstr(|name| loader(name)) });
        g.painter = GlPainter::create(&gl).map_err(|e| log::error!("painter: {e}")).ok();
        g.gl = Some(gl);
    });
}

/// Opens a game. Returns null on success or an error message.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hcode91_pocketcarioca_Core_open(mut env: JNIEnv, _: JClass, path: JString) -> jstring {
    let path = PathBuf::from(jstr(&mut env, &path));
    let r = with_gl(|g| open(g, &path));
    err_to_jstring(env, r)
}

fn open(g: &mut GlState, path: &Path) -> Result<(), String> {
    g.running = None;
    set_closed();
    let settings = g.settings.get_or_insert_with(Settings::default);
    let (options, content, storage) = {
        let sh = shared();
        let paths = sh.paths.as_ref().ok_or("not initialised")?;
        let content = paths.storage.content_id(path).map_err(|e| e.to_string())?;
        if let Err(e) = paths.storage.migrate_legacy_package_writes(path, &content) {
            log::warn!("could not migrate old saves: {e}");
        }
        (sh.options, content, paths.storage.clone())
    };
    let on_gpu = options.gpu && g.gl.is_some();
    let device = if options.boomerang { Device::Boomerang } else { Device::Controller };
    let mut session = Session::start_with_storage_installed(
        path,
        [Some(device), None],
        None,
        on_gpu,
        g.gl.clone().filter(|_| on_gpu),
        settings.z_wheel,
        &storage,
        &[],
    )
    .map_err(|e| e.to_string())?;

    session.set_internal_resolution(options.internal_resolution as usize);
    session.set_aspect_ratio(if options.aspect == 2 { Some(16.0 / 9.0) } else { None });
    session.set_fog_allowed(true);
    // Safe before the first step: `start_*` only prepares, EVT_APP_START goes out on the first
    // advance.
    if let Some(e) = session.set_audio(options.audio, options.volume) {
        log::error!("no sound: {e}");
    }
    log::info!("opened {}", session.title());
    {
        let mut sh = shared();
        sh.state_id = Some(content.as_str().to_string());
        sh.title = session.title().to_string();
        sh.motion = None;
        sh.keys.clear();
    }
    g.running = Some(Running { session, state_id: content.as_str().to_string(), last: Instant::now() });
    Ok(())
}

/// Runs the game for the wall time since the last call and draws it into the current surface.
///
/// Returns 0 while running, 1 when the game exited on its own, 2 when it stopped with an error
/// (see `lastError`), 3 when nothing is open.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hcode91_pocketcarioca_Core_frame(
    _: JNIEnv,
    _: JClass,
    width: jint,
    height: jint,
    buttons: jint,
    axis_x: jfloat,
    axis_y: jfloat,
    fast_forward: jboolean,
    paused: jboolean,
) -> jint {
    with_gl(|g| frame(g, width, height, buttons as u32, axis_x, axis_y, fast_forward == JNI_TRUE, paused == JNI_TRUE))
}

#[allow(clippy::too_many_arguments)]
fn frame(g: &mut GlState, width: i32, height: i32, buttons: u32, ax: f32, ay: f32, ff: bool, paused: bool) -> i32 {
    let Some(gl) = g.gl.clone() else { return 3 };
    if g.running.is_none() {
        reset_for_present(&gl, width, height);
        return 3;
    }
    let Some(run) = g.running.as_mut() else { return 3 };
    let (options, motion, keys) = {
        let mut sh = shared();
        (sh.options, sh.motion, std::mem::take(&mut sh.keys))
    };
    let session = &mut run.session;

    session.freeze_audio(paused);
    // **Intermediate screens first, one per frame, without advancing.** A game that calls
    // `IDISPLAY_Update` inside a single long callback (a loading loop, the Z-Wheel's slide) leaves
    // one screen per call queued. Upstream measured what happens when a frontend ignores them:
    // with the queue full, `advance` reports "presented" forever without running the guest, and
    // the game freezes. Alice in Wonderland froze on a black screen exactly like that here.
    let intermediate = !paused && session.show_intermediate_frame();
    if paused || intermediate {
        run.last = Instant::now();
    } else {
        let elapsed = run.last.elapsed().min(MAX_SLICE);
        run.last = Instant::now();
        let mut pad = Pad { buttons, ..Default::default() };
        let range = zeebx::input::AXIS_RANGE as f32;
        pad.axes[0] = (ax.clamp(-1.0, 1.0) * range).round() as i32;
        pad.axes[1] = (ay.clamp(-1.0, 1.0) * range).round() as i32;
        session.set_port_pad(0, pad);
        if let Some(m) = motion {
            session.set_port_motion(0, m);
        }
        for (avk, pressed) in keys {
            session.set_key(avk, pressed);
        }
        // Our own painter drew in this context since the last step; the GL state mirror must
        // be told before the game draws again. See `Session::resume_context`.
        session.resume_context();
        let pace = g.settings.get_or_insert_with(Settings::default).speed.pace_with(ff);
        let stopped = session.advance(elapsed, &pace) == Step::Stopped;
        if stopped || session.exited_on_its_own() {
            let normal = session.exited_on_its_own() || session.exited_normally();
            let reason = session.stopped_reason().unwrap_or_default();
            g.running = None;
            set_closed();
            if normal {
                return 1;
            }
            shared().last_error = Some(reason);
            return 2;
        }
    }

    let Some(run) = g.running.as_mut() else { return 3 };
    let Some(painter) = g.painter.as_mut() else { return 0 };
    let session = &mut run.session;
    // An intermediate screen is the CPU screen, even when 3D is on the GPU.
    let frame_gl = if options.gpu && !intermediate { session.gpu_frame() } else { None };
    let (fw, fh) = match &frame_gl {
        Some(f) => (f.ratio, 1.0),
        None => (session.screen().width() as f32, session.screen().height() as f32),
    };
    let vp = fit(width, height, fw, fh, options.aspect);
    reset_for_present(&gl, width, height);
    match frame_gl {
        Some(f) => painter.draw_texture(&gl, f, vp, options.smooth),
        None => {
            let screen = session.screen();
            let key = (screen.generation(), screen.writes());
            let bytes = screen.to_rgb565_bytes();
            painter.draw_frame(&gl, &bytes, screen.width() as i32, screen.height() as i32, key, vp, options.smooth);
        }
    }
    0
}

/// Puts the context back to the state `GlPainter` assumes before it draws the frame.
///
/// The painter sets only its program, texture and viewport: it was written to run inside
/// egui's paint callback, and egui resets the rest first. Here nothing sits between the game's
/// last draw and ours, so whatever the game left enabled applies to our full-screen triangle.
/// Depth test against a never-cleared depth buffer, blending with the zero alpha some games
/// clear to, culling, or a sampler object bound to unit 0 each turn the quad black.
/// The game's own state is not lost: `Session::resume_context` re-applies it before the next
/// step.
fn reset_for_present(gl: &glow::Context, width: i32, height: i32) {
    use glow::HasContext;
    unsafe {
        gl.bind_framebuffer(glow::FRAMEBUFFER, None);
        for cap in [
            glow::DEPTH_TEST,
            glow::STENCIL_TEST,
            glow::BLEND,
            glow::CULL_FACE,
            glow::SCISSOR_TEST,
            glow::POLYGON_OFFSET_FILL,
            glow::SAMPLE_ALPHA_TO_COVERAGE,
            glow::SAMPLE_COVERAGE,
            glow::DITHER,
        ] {
            gl.disable(cap);
        }
        gl.color_mask(true, true, true, true);
        gl.depth_mask(true);
        gl.stencil_mask(0xff);
        gl.front_face(glow::CCW);
        gl.active_texture(glow::TEXTURE0);
        gl.bind_sampler(0, None);
        gl.bind_buffer(glow::ARRAY_BUFFER, None);
        gl.bind_buffer(glow::PIXEL_UNPACK_BUFFER, None);
        gl.pixel_store_i32(glow::UNPACK_ROW_LENGTH, 0);
        gl.pixel_store_i32(glow::UNPACK_SKIP_ROWS, 0);
        gl.pixel_store_i32(glow::UNPACK_SKIP_PIXELS, 0);
        gl.viewport(0, 0, width, height);
        gl.clear_color(0.0, 0.0, 0.0, 1.0);
        gl.clear_depth_f32(1.0);
        gl.clear(glow::COLOR_BUFFER_BIT | glow::DEPTH_BUFFER_BIT | glow::STENCIL_BUFFER_BIT);
    }
}

/// Letterboxes the frame into the surface. `aspect`: 1 stretches, anything else keeps the frame's
/// shape (the 16:9 option already made the frame itself wider).
fn fit(sw: i32, sh: i32, fw: f32, fh: f32, aspect: u8) -> Viewport {
    if aspect == 1 || fw <= 0.0 || fh <= 0.0 || sw <= 0 || sh <= 0 {
        return Viewport { x: 0, bottom_y: 0, width: sw, height: sh };
    }
    let target = fw / fh;
    let (w, h) = if (sw as f32 / sh as f32) > target {
        ((sh as f32 * target) as i32, sh)
    } else {
        (sw, (sw as f32 / target) as i32)
    };
    Viewport { x: (sw - w) / 2, bottom_y: (sh - h) / 2, width: w, height: h }
}

/// A plain-text status report for the in-app Diagnostics screen (GL thread): the user develops
/// from a phone with no adb, so this is how logs reach a bug report.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hcode91_pocketcarioca_Core_diagnostics(env: JNIEnv, _: JClass) -> jstring {
    let options = shared().options;
    let text = with_gl(|g| {
        let mut out = String::new();
        out += &format!(
            "gpu option {} | gl context {} | painter {}\n",
            options.gpu,
            g.gl.is_some(),
            g.painter.is_some()
        );
        match g.running.as_mut() {
            None => out += "no game running\n",
            Some(run) => {
                let s = &mut run.session;
                let screen = s.screen();
                let lit = screen.to_argb().iter().filter(|p| (**p & 0xff_ffff) != 0).count();
                let (heap_used, objects) = s.memory();
                out += &format!(
                    "title {} | clock {} ms | api calls {} | speed {:.0}%\n",
                    s.title(),
                    s.clock_ms(),
                    s.api_calls(),
                    s.reached_speed() * 100.0
                );
                out += &format!(
                    "screen {}x{} lit {} | gpu frame {} | heap {} bytes, {} objects\n",
                    screen.width(),
                    screen.height(),
                    lit,
                    s.gpu_frame().is_some(),
                    heap_used,
                    objects
                );
                if let Some(reason) = s.stopped_reason() {
                    out += &format!("stopped: {reason}\n");
                }
                out += "--- log (last 60 lines) ---\n";
                let log = s.log();
                for line in log.iter().skip(log.len().saturating_sub(60)) {
                    out += line;
                    out += "\n";
                }
            }
        }
        out
    });
    opt_jstring(env, Some(text))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hcode91_pocketcarioca_Core_close(_: JNIEnv, _: JClass) {
    with_gl(|g| g.running = None);
    set_closed();
}

/// Saves to slot 0..9 (GL thread: the thumbnail is read from the GPU frame). Null or an error.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hcode91_pocketcarioca_Core_saveSlot(env: JNIEnv, _: JClass, slot: jint) -> jstring {
    let r = slot_op(slot, true);
    err_to_jstring(env, r)
}

/// Loads slot 0..9 (GL thread: the GPU textures are rebuilt). Null or an error.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hcode91_pocketcarioca_Core_loadSlot(env: JNIEnv, _: JClass, slot: jint) -> jstring {
    let r = slot_op(slot, false);
    err_to_jstring(env, r)
}

fn slot_op(slot: i32, save: bool) -> Result<(), String> {
    let states = shared().paths.as_ref().map(|p| p.states.clone()).ok_or("not initialised")?;
    with_gl(|g| {
        let run = g.running.as_mut().ok_or("no game running")?;
        let slot = usize::try_from(slot).map_err(|_| "bad slot")?;
        let r = match save {
            true => save_slots::save(&states, &run.state_id, slot, &mut run.session).map(|_| ()),
            false => save_slots::load(&states, &run.state_id, slot, &mut run.session).map(|_| ()),
        };
        // Saving or loading takes real time; don't count it against the game's clock.
        run.last = Instant::now();
        r
    })
}

/// Screenshot as `[width, height, argb...]` (GL thread), or null.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hcode91_pocketcarioca_Core_screenshot(env: JNIEnv, _: JClass) -> jintArray {
    let shot = with_gl(|g| g.running.as_mut().map(|r| r.session.capture()));
    let Some((w, h, rgb)) = shot else { return std::ptr::null_mut() };
    let mut out = Vec::with_capacity(2 + (w * h) as usize);
    out.push(w as i32);
    out.push(h as i32);
    for px in rgb.chunks_exact(3) {
        out.push(argb(0xff, px[0], px[1], px[2]));
    }
    int_array(env, &out)
}

// ---------------------------------------------------------------------------------------------
// helpers

fn argb(a: u8, r: u8, g: u8, b: u8) -> i32 {
    ((a as u32) << 24 | (r as u32) << 16 | (g as u32) << 8 | b as u32) as i32
}

fn int_array(env: JNIEnv, data: &[i32]) -> jintArray {
    let Ok(arr) = env.new_int_array(data.len() as i32) else { return std::ptr::null_mut() };
    let _ = env.set_int_array_region(&arr, 0, data);
    arr.into_raw()
}

fn opt_jstring(env: JNIEnv, s: Option<String>) -> jstring {
    match s {
        Some(s) => env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

fn err_to_jstring(env: JNIEnv, r: Result<(), String>) -> jstring {
    opt_jstring(env, r.err())
}
