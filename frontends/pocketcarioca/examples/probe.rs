//! Desktop probe: opens a game exactly the way the Android bridge does (same storage layout,
//! same `start_with_storage_installed` call, same per-frame `advance`), runs it, and writes the
//! screen to a BMP. `cargo run --release -p pocketcarioca-jni --example probe -- <game> <out.bmp> [seconds]`
use std::path::Path;
use std::time::Duration;
use zeebx::input::bindings::Device;
use zeebx::session::{Session, Step};
use zeebx::storage::StoragePaths;
use zeebx::ui::settings::Settings;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let game = Path::new(&args[1]);
    let out = &args[2];
    let seconds: u32 = args.get(3).and_then(|s| s.parse().ok()).unwrap_or(15);
    let base = std::env::temp_dir().join("pc_probe");
    let storage_base = base.join(".config");
    let storage = StoragePaths::for_frontend(&storage_base, Some(&storage_base));
    storage.create_dirs().unwrap();
    let content = storage.content_id(game).unwrap();
    let _ = storage.migrate_legacy_package_writes(game, &content);
    let settings = Settings::default();
    let mut s = Session::start_with_storage_installed(
        game, [Some(Device::Controller), None], None, false, None, settings.z_wheel, &storage, &[],
    ).expect("start");
    s.set_internal_resolution(1);
    s.set_aspect_ratio(None);
    s.set_fog_allowed(true);
    let pace = settings.speed.pace_with(false);
    let frames = seconds * 60;
    let mut last = std::time::Instant::now();
    let start = last;
    for i in 0..frames {
        // Paced by the wall clock, like the GLSurfaceView loop.
        std::thread::sleep(Duration::from_micros(16_667));
        let elapsed = last.elapsed().min(zeebx::session::MAX_SLICE);
        last = std::time::Instant::now();
        // Same as the bridge: an intermediate screen is shown instead of advancing.
        if s.show_intermediate_frame() { continue; }
        let step = s.advance(elapsed, &pace);
        if step == Step::Stopped {
            println!("stopped at frame {i}: {:?} normal={}", s.stopped_reason(), s.exited_normally());
            break;
        }
        if s.exited_on_its_own() { println!("exited on its own at {i}"); break; }
        if i % 30 == 0 {
            let lit = s.screen().to_argb().iter().filter(|p| (**p & 0xffffff) != 0).count();
            println!("real {:5.1}s  virtual {:6} ms  lit pixels {lit}", start.elapsed().as_secs_f32(), s.clock_ms());
        }
    }
    println!("clock {} ms, title {:?}", s.clock_ms(), s.title());
    let (w, h, rgb) = s.capture();
    let mut img = Vec::new();
    let row = (w * 3 + 3) & !3;
    let size = 54 + row * h;
    img.extend_from_slice(b"BM"); img.extend_from_slice(&size.to_le_bytes()); img.extend_from_slice(&[0;4]); img.extend_from_slice(&54u32.to_le_bytes());
    img.extend_from_slice(&40u32.to_le_bytes()); img.extend_from_slice(&(w as i32).to_le_bytes()); img.extend_from_slice(&(h as i32).to_le_bytes());
    img.extend_from_slice(&1u16.to_le_bytes()); img.extend_from_slice(&24u16.to_le_bytes()); img.extend_from_slice(&[0;24]);
    for y in (0..h).rev() {
        let mut r = Vec::new();
        for x in 0..w { let p = ((y * w + x) * 3) as usize; r.extend_from_slice(&[rgb[p+2], rgb[p+1], rgb[p]]); }
        r.resize(row as usize, 0); img.extend_from_slice(&r);
    }
    std::fs::write(out, img).unwrap();
}
