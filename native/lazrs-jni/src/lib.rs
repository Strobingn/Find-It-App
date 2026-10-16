//! Streaming laz-rs decoder for Find-It terrain.
//!
//! Kotlin opens a [Session] for one file, then starts a decode over an explicit list of
//! compressed chunk indices (all chunks for a full pass, or the [LazChunkIndex] selection for a
//! viewport refine). Each rayon worker owns its own `LasZipDecompressor` and seeks chunk by chunk
//! through the LAZ chunk table, decompresses into a reusable scratch buffer, applies exactly the
//! laszip4j filter rule (half-open rect `min <= v < max`, global point stride) and packs the kept
//! returns into a pooled output buffer. Kotlin pulls chunks strictly in slot order as direct
//! ByteBuffers (no copy), bins them, then releases the slot so its buffer is reused. At most
//! `window` chunks are in flight, so memory is O(workers × chunk), never O(file).
//!
//! Packed record (24 bytes, little endian): f64 x, f64 y, f32 z, u8 classification (raw LAS
//! byte, as laszip4j reports it), u8 flags (bit0 key-point, bit1 withheld), u16 pad.
//!
//! Every entry point catches panics and reports failure, so Kotlin can fall back to laszip4j.

use jni::objects::{JClass, JDoubleArray, JIntArray, JLongArray, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jobject, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use las::Read as _;
use laz::{DecompressionSelection, LasZipDecompressor, LazVlr};
use std::fs::File;
use std::io::{BufReader, Seek, SeekFrom};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

pub const RECORD_BYTES: usize = 24;
const LASZIP_USER_ID: &str = "laszip encoded";
const LASZIP_RECORD_ID: u16 = 22204;

/// Header facts the decoder needs; parsed once per session.
#[derive(Clone)]
pub struct FileInfo {
    pub path: String,
    pub vlr_bytes: Vec<u8>,
    pub point_offset: u64,
    pub record_len: usize,
    pub point_format: u8,
    pub point_count: u64,
    pub chunk_size: u64,
    pub scale: [f64; 3],
    pub offset: [f64; 3],
}

pub fn open_info(path: &str) -> Result<FileInfo, String> {
    let reader = las::Reader::from_path(path).map_err(|e| e.to_string())?;
    let header = reader.header();
    let vlr_bytes = header
        .vlrs()
        .iter()
        .chain(header.evlrs())
        .find(|v| v.user_id == LASZIP_USER_ID && v.record_id == LASZIP_RECORD_ID)
        .map(|v| v.data.clone())
        .ok_or("no laszip vlr")?;
    let vlr = LazVlr::from_buffer(&vlr_bytes).map_err(|e| e.to_string())?;
    if vlr.uses_variable_size_chunks() {
        return Err("variable-size chunks not supported".into());
    }
    let t = header.transforms();
    let point_count = header.number_of_points();
    let raw = header.clone().into_raw().map_err(|e| e.to_string())?;
    let record_len = raw.point_data_record_length as usize;
    let point_format = raw.point_data_record_format & 0x3F;
    let min_len = if point_format >= 6 { 17 } else { 16 };
    if record_len < min_len || vlr.items_size() as usize != record_len {
        return Err("unexpected record layout".into());
    }
    Ok(FileInfo {
        path: path.to_string(),
        vlr_bytes,
        point_offset: u64::from(raw.offset_to_point_data),
        record_len,
        point_format,
        point_count,
        chunk_size: u64::from(vlr.chunk_size()),
        scale: [t.x.scale, t.y.scale, t.z.scale],
        offset: [t.x.offset, t.y.offset, t.z.offset],
    })
}

/// Filter + options shared by all workers of one decode.
#[derive(Clone)]
pub struct Plan {
    pub chunks: Vec<u32>,
    pub rect: Option<[f64; 4]>,
    pub stride: u64,
}

pub struct ChunkOut {
    pub data: Vec<u8>,
    pub kept: usize,
    pub raw: usize,
}

enum Slot {
    Pending,
    Ready(ChunkOut),
    Failed(String),
    Taken,
}

struct State {
    slots: Vec<Slot>,
    next_claim: usize,
    released: usize,
    pool: Vec<Vec<u8>>,
    active_workers: usize,
}

pub struct Decode {
    state: Mutex<State>,
    ready: Condvar,
    cancel: AtomicBool,
    window: usize,
}

impl Decode {
    pub fn cancel(&self) {
        self.cancel.store(true, Ordering::SeqCst);
        self.ready.notify_all();
    }

    /// Blocks up to `timeout` for `slot`. Ok(None) = not ready yet.
    pub fn take(&self, slot: usize, timeout: Duration) -> Result<Option<ChunkOut>, String> {
        let mut st = self.state.lock().map_err(|_| "poisoned")?;
        let deadline = std::time::Instant::now() + timeout;
        loop {
            if slot >= st.slots.len() {
                return Err("slot out of range".into());
            }
            match std::mem::replace(&mut st.slots[slot], Slot::Taken) {
                Slot::Ready(out) => return Ok(Some(out)),
                Slot::Failed(e) => return Err(e),
                Slot::Taken => return Err("slot already taken".into()),
                Slot::Pending => st.slots[slot] = Slot::Pending,
            }
            if st.active_workers == 0 {
                return Err("workers stopped before slot was decoded".into());
            }
            let now = std::time::Instant::now();
            if now >= deadline {
                return Ok(None);
            }
            st = self.ready.wait_timeout(st, deadline - now).map_err(|_| "poisoned")?.0;
        }
    }

    /// Returns a consumed buffer to the pool and opens the window for one more chunk.
    pub fn release(&self, buffer: Vec<u8>) {
        if let Ok(mut st) = self.state.lock() {
            st.released += 1;
            st.pool.push(buffer);
        }
        self.ready.notify_all();
    }

    fn wait_and_claim(&self) -> Option<(usize, Vec<u8>)> {
        let mut st = self.state.lock().ok()?;
        loop {
            if self.cancel.load(Ordering::SeqCst) || st.next_claim >= st.slots.len() {
                return None;
            }
            if st.next_claim - st.released < self.window {
                let slot = st.next_claim;
                st.next_claim += 1;
                let buf = st.pool.pop().unwrap_or_default();
                return Some((slot, buf));
            }
            st = self.ready.wait(st).ok()?;
        }
    }

    fn publish(&self, slot: usize, value: Slot) {
        if let Ok(mut st) = self.state.lock() {
            st.slots[slot] = value;
        }
        self.ready.notify_all();
    }

    fn worker_exit(&self) {
        if let Ok(mut st) = self.state.lock() {
            st.active_workers -= 1;
        }
        self.ready.notify_all();
    }

    /// Waits until every worker has left (after cancel or completion).
    pub fn join(&self) {
        if let Ok(mut st) = self.state.lock() {
            while st.active_workers > 0 {
                match self.ready.wait(st) {
                    Ok(g) => st = g,
                    Err(_) => return,
                }
            }
        }
    }
}

struct Worker {
    decompressor: LasZipDecompressor<'static, BufReader<File>>,
    scratch: Vec<u8>,
}

impl Worker {
    fn new(info: &FileInfo) -> Result<Self, String> {
        let vlr = LazVlr::from_buffer(&info.vlr_bytes).map_err(|e| e.to_string())?;
        let mut file = BufReader::with_capacity(256 * 1024, File::open(&info.path).map_err(|e| e.to_string())?);
        file.seek(SeekFrom::Start(info.point_offset)).map_err(|e| e.to_string())?;
        let selection = DecompressionSelection(
            DecompressionSelection::Z | DecompressionSelection::CLASSIFICATION | DecompressionSelection::FLAGS,
        );
        let decompressor = LasZipDecompressor::selective(file, vlr, selection).map_err(|e| e.to_string())?;
        Ok(Self { decompressor, scratch: Vec::new() })
    }

    fn decode_chunk(&mut self, info: &FileInfo, plan: &Plan, chunk: u64, mut out: Vec<u8>) -> Result<ChunkOut, String> {
        let first = chunk * info.chunk_size;
        if first >= info.point_count {
            return Err(format!("chunk {chunk} past end"));
        }
        let n = (info.point_count - first).min(info.chunk_size) as usize;
        self.decompressor.seek(first).map_err(|e| e.to_string())?;
        let bytes = n * info.record_len;
        if self.scratch.len() < bytes {
            self.scratch.resize(bytes, 0);
        }
        self.decompressor
            .decompress_many(&mut self.scratch[..bytes])
            .map_err(|e| e.to_string())?;
        out.clear();
        let kept = pack(info, plan, first, &self.scratch[..bytes], &mut out);
        Ok(ChunkOut { data: out, kept, raw: n })
    }
}

/// Same rule as laszip4j's chunk decoder: rect first (half-open), then global stride.
pub fn pack(info: &FileInfo, plan: &Plan, first: u64, raw: &[u8], out: &mut Vec<u8>) -> usize {
    let rl = info.record_len;
    let ext = info.point_format >= 6;
    let [sx, sy, sz] = info.scale;
    let [ox, oy, oz] = info.offset;
    let mut kept = 0usize;
    for (i, rec) in raw.chunks_exact(rl).enumerate() {
        let x = i32::from_le_bytes([rec[0], rec[1], rec[2], rec[3]]) as f64 * sx + ox;
        let y = i32::from_le_bytes([rec[4], rec[5], rec[6], rec[7]]) as f64 * sy + oy;
        if let Some(r) = plan.rect {
            if x < r[0] || x >= r[2] || y < r[1] || y >= r[3] {
                continue;
            }
        }
        if plan.stride > 1 && (first + i as u64) % plan.stride != 0 {
            continue;
        }
        let z = (i32::from_le_bytes([rec[8], rec[9], rec[10], rec[11]]) as f64 * sz + oz) as f32;
        let (class, key, held) = if ext {
            (rec[16], rec[15] & 0x02 != 0, rec[15] & 0x04 != 0)
        } else {
            (rec[15], rec[15] & 0x40 != 0, rec[15] & 0x80 != 0)
        };
        out.extend_from_slice(&x.to_le_bytes());
        out.extend_from_slice(&y.to_le_bytes());
        out.extend_from_slice(&z.to_le_bytes());
        out.push(class);
        out.push(u8::from(key) | (u8::from(held) << 1));
        out.extend_from_slice(&[0, 0]);
        kept += 1;
    }
    kept
}

/// Starts `workers` rayon tasks, each owning one decompressor, over `plan.chunks`.
pub fn start(info: Arc<FileInfo>, plan: Plan, workers: usize) -> Result<Arc<Decode>, String> {
    let workers = workers.clamp(1, 16).min(plan.chunks.len().max(1));
    let decode = Arc::new(Decode {
        state: Mutex::new(State {
            slots: (0..plan.chunks.len()).map(|_| Slot::Pending).collect(),
            next_claim: 0,
            released: 0,
            pool: Vec::new(),
            active_workers: workers,
        }),
        ready: Condvar::new(),
        cancel: AtomicBool::new(false),
        window: workers * 2,
    });
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(workers)
        .thread_name(|i| format!("lazrs-{i}"))
        .build()
        .map_err(|e| e.to_string())?;
    let plan = Arc::new(plan);
    for _ in 0..workers {
        let (d, info, plan) = (decode.clone(), info.clone(), plan.clone());
        pool.spawn(move || {
            let result = catch_unwind(AssertUnwindSafe(|| run_worker(&d, &info, &plan)));
            if result.is_err() {
                d.cancel();
                if let Ok(mut st) = d.state.lock() {
                    for s in st.slots.iter_mut() {
                        if matches!(s, Slot::Pending) {
                            *s = Slot::Failed("native worker panicked".into());
                        }
                    }
                }
            }
            d.worker_exit();
        });
    }
    // Dropping the handle lets the pool threads exit once the spawned loops return.
    drop(pool);
    Ok(decode)
}

fn run_worker(d: &Decode, info: &FileInfo, plan: &Plan) {
    let mut worker = match Worker::new(info) {
        Ok(w) => Some(w),
        Err(e) => {
            if let Some((slot, _)) = d.wait_and_claim() {
                d.publish(slot, Slot::Failed(e));
            }
            d.cancel();
            None
        }
    };
    while let Some((slot, buf)) = d.wait_and_claim() {
        let w = match worker.as_mut() {
            Some(w) => w,
            None => break,
        };
        let value = match w.decode_chunk(info, plan, u64::from(plan.chunks[slot]), buf) {
            Ok(out) => Slot::Ready(out),
            Err(e) => {
                // A failed seek/decode leaves the decompressor in an unknown state; reopen.
                worker = Worker::new(info).ok();
                Slot::Failed(e)
            }
        };
        d.publish(slot, value);
    }
}

// ---------------------------------------------------------------- JNI

struct Session {
    info: Arc<FileInfo>,
    decode: Option<Arc<Decode>>,
    /// Chunks handed to Kotlin but not yet released, by slot.
    lent: std::collections::HashMap<usize, Vec<u8>>,
}

impl Drop for Session {
    fn drop(&mut self) {
        if let Some(d) = self.decode.take() {
            d.cancel();
            d.join();
        }
    }
}

fn guard<T>(fallback: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(fallback)
}

unsafe fn session<'a>(handle: jlong) -> Option<&'a mut Session> {
    (handle as *mut Session).as_mut()
}

#[no_mangle]
pub extern "system" fn Java_com_example_data_LazRsNativeDecoder_nativeAvailable(_env: JNIEnv, _c: JClass) -> jboolean {
    JNI_TRUE
}

/// Returns a session handle (0 on failure). `header` receives
/// `[pointCount, chunkSize, pointFormat, recordLength]`.
#[no_mangle]
pub extern "system" fn Java_com_example_data_LazRsNativeDecoder_nativeOpen<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    path: JString<'l>,
    header: JLongArray<'l>,
) -> jlong {
    guard(0, || {
        let path: String = match env.get_string(&path) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };
        let info = match open_info(&path) {
            Ok(i) => i,
            Err(_) => return 0,
        };
        let facts = [info.point_count as i64, info.chunk_size as i64, info.point_format as i64, info.record_len as i64];
        if env.set_long_array_region(&header, 0, &facts).is_err() {
            return 0;
        }
        Box::into_raw(Box::new(Session { info: Arc::new(info), decode: None, lent: Default::default() })) as jlong
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_data_LazRsNativeDecoder_nativeStart<'l>(
    env: JNIEnv<'l>,
    _c: JClass<'l>,
    handle: jlong,
    chunks: JIntArray<'l>,
    rect: JDoubleArray<'l>,
    stride: jint,
    workers: jint,
) -> jboolean {
    guard(JNI_FALSE, || {
        let s = match unsafe { session(handle) } {
            Some(s) if s.decode.is_none() => s,
            _ => return JNI_FALSE,
        };
        let n = match env.get_array_length(&chunks) {
            Ok(n) => n as usize,
            Err(_) => return JNI_FALSE,
        };
        let mut ids = vec![0i32; n];
        if env.get_int_array_region(&chunks, 0, &mut ids).is_err() {
            return JNI_FALSE;
        }
        let total_chunks = s.info.point_count.div_ceil(s.info.chunk_size.max(1));
        if ids.iter().any(|&c| c < 0 || c as u64 >= total_chunks) {
            return JNI_FALSE;
        }
        let rect = if rect.is_null() {
            None
        } else {
            let mut r = [0f64; 4];
            if env.get_double_array_region(&rect, 0, &mut r).is_err() {
                return JNI_FALSE;
            }
            Some(r)
        };
        let plan = Plan { chunks: ids.into_iter().map(|c| c as u32).collect(), rect, stride: stride.max(1) as u64 };
        match start(s.info.clone(), plan, workers.max(1) as usize) {
            Ok(d) => {
                s.decode = Some(d);
                JNI_TRUE
            }
            Err(_) => JNI_FALSE,
        }
    })
}

/// Waits up to `timeoutMs` for `slot`. Returns a direct ByteBuffer over the packed records and
/// writes `[kept, raw]` into `counts`; null with counts[0] = -2 on timeout, -1 on error.
#[no_mangle]
pub extern "system" fn Java_com_example_data_LazRsNativeDecoder_nativeNext<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    handle: jlong,
    slot: jint,
    counts: JLongArray<'l>,
    timeout_ms: jint,
) -> jobject {
    let null = JObject::null().into_raw();
    let status = guard(Err(-1i64), || {
        let s = unsafe { session(handle) }.ok_or(-1i64)?;
        let d = s.decode.clone().ok_or(-1i64)?;
        match d.take(slot as usize, Duration::from_millis(timeout_ms.max(0) as u64)) {
            Ok(None) => Err(-2),
            Err(_) => Err(-1),
            Ok(Some(mut out)) => {
                if out.data.capacity() == 0 {
                    out.data.reserve(RECORD_BYTES);
                }
                let ptr = out.data.as_mut_ptr();
                let len = out.kept * RECORD_BYTES;
                let (kept, raw) = (out.kept as i64, out.raw as i64);
                s.lent.insert(slot as usize, out.data);
                Ok((ptr, len.max(0), kept, raw))
            }
        }
    });
    match status {
        Ok((ptr, len, kept, raw)) => {
            let buffer = unsafe { env.new_direct_byte_buffer(ptr, len) };
            match buffer {
                Ok(b) if len > 0 => {
                    let _ = env.set_long_array_region(&counts, 0, &[kept, raw]);
                    b.into_raw()
                }
                _ if len == 0 => {
                    // Empty chunk (all filtered): hand back a zero-length heap-free marker.
                    let _ = env.exception_clear();
                    let _ = env.set_long_array_region(&counts, 0, &[0, raw]);
                    match unsafe { env.new_direct_byte_buffer(ptr, 0) } {
                        Ok(b) => b.into_raw(),
                        Err(_) => {
                            let _ = env.exception_clear();
                            null
                        }
                    }
                }
                _ => {
                    let _ = env.exception_clear();
                    let _ = env.set_long_array_region(&counts, 0, &[-1, 0]);
                    null
                }
            }
        }
        Err(code) => {
            let _ = env.set_long_array_region(&counts, 0, &[code, 0]);
            null
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_data_LazRsNativeDecoder_nativeRelease(_env: JNIEnv, _c: JClass, handle: jlong, slot: jint) {
    guard((), || {
        if let Some(s) = unsafe { session(handle) } {
            if let (Some(buf), Some(d)) = (s.lent.remove(&(slot as usize)), s.decode.as_ref()) {
                d.release(buf);
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_data_LazRsNativeDecoder_nativeCancel(_env: JNIEnv, _c: JClass, handle: jlong) {
    guard((), || {
        if let Some(d) = unsafe { session(handle) }.and_then(|s| s.decode.as_ref()) {
            d.cancel();
        }
    })
}

/// Cancels, joins the workers and frees the session. Kotlin must not touch lent buffers after this.
#[no_mangle]
pub extern "system" fn Java_com_example_data_LazRsNativeDecoder_nativeClose(_env: JNIEnv, _c: JClass, handle: jlong) {
    guard((), || {
        if handle != 0 {
            drop(unsafe { Box::from_raw(handle as *mut Session) });
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_autzen_all_chunks() {
        let path = "/workspace/lazdata/autzen-classified.laz";
        if !std::path::Path::new(path).exists() {
            return;
        }
        let info = Arc::new(open_info(path).unwrap());
        let chunks = info.point_count.div_ceil(info.chunk_size) as u32;
        let d = start(info.clone(), Plan { chunks: (0..chunks).collect(), rect: None, stride: 1 }, 4).unwrap();
        let mut total = 0u64;
        for slot in 0..chunks as usize {
            let out = loop {
                if let Some(o) = d.take(slot, Duration::from_secs(5)).unwrap() {
                    break o;
                }
            };
            total += out.raw as u64;
            d.release(out.data);
        }
        d.join();
        assert_eq!(total, info.point_count);
    }
}
