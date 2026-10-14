//! ABI 2: bounded selected ranges; integer XYZ preserves LAS quantization until gridding.
use jni::objects::{JByteBuffer, JClass, JString};
use jni::sys::{jint, jlong};
use jni::JNIEnv;
use las::{Read as LasRead, Reader};
use laz::{LazVlr, ParLasZipDecompressor};
use std::collections::HashMap;
use std::fs::File;
use std::io::{Seek, SeekFrom};
use std::sync::{atomic::{AtomicI64, Ordering}, Mutex, OnceLock};

const RECORD_BYTES: usize = 16;
const MAX_SCRATCH: usize = 16 * 1024 * 1024;
struct Decoder {
    reader: ParLasZipDecompressor<File>,
    pool: rayon::ThreadPool,
    record_len: usize,
    format: u8,
    points: u64,
    position: u64,
    scratch: Vec<u8>,
}
static NEXT: AtomicI64 = AtomicI64::new(1);
static READERS: OnceLock<Mutex<HashMap<i64, Decoder>>> = OnceLock::new();
fn readers() -> &'static Mutex<HashMap<i64, Decoder>> { READERS.get_or_init(|| Mutex::new(HashMap::new())) }
fn open(path: &str, workers: usize) -> Result<Decoder, ()> {
    let source = Reader::from_path(path).map_err(|_| ())?;
    let h = source.header();
    let data = h.vlrs().iter().chain(h.evlrs()).find(|v| v.user_id == "laszip encoded" && v.record_id == 22204).ok_or(())?;
    let vlr = LazVlr::from_buffer(&data.data).map_err(|_| ())?;
    let raw = h.clone().into_raw().map_err(|_| ())?;
    let len = raw.point_data_record_length as usize;
    let format = raw.point_data_record_format & 0x3f;
    let minimum = [20,28,26,34,57,63,30,36,38,59,67];
    if format > 10 || len < minimum[format as usize] || len > 4096 { return Err(()); }
    let points = h.number_of_points();
    drop(source);
    let mut file = File::open(path).map_err(|_| ())?;
    file.seek(SeekFrom::Start(raw.offset_to_point_data as u64)).map_err(|_| ())?;
    Ok(Decoder {
        reader: ParLasZipDecompressor::new(file, vlr).map_err(|_| ())?,
        pool: rayon::ThreadPoolBuilder::new().num_threads(workers.clamp(1,8)).build().map_err(|_| ())?,
        record_len: len, format, points, position: 0, scratch: Vec::new(),
    })
}
fn pack(raw: &[u8], len: usize, format: u8, out: &mut [u8]) -> Result<usize, ()> {
    if raw.len() % len != 0 || out.len() < raw.len() / len * RECORD_BYTES { return Err(()); }
    let mut n = 0;
    for (record, destination) in raw.chunks_exact(len).zip(out.chunks_exact_mut(RECORD_BYTES)) {
        destination[..12].copy_from_slice(&record[..12]);
        let (class, key, withheld) = if format >= 6 {
            (record[16], record[15] & 2 != 0, record[15] & 4 != 0)
        } else { (record[15] & 31, record[15] & 64 != 0, record[15] & 128 != 0) };
        destination[12] = class;
        destination[13] = u8::from(key) | (u8::from(withheld) << 1);
        destination[14] = 0; destination[15] = 0;
        n += 1;
    }
    Ok(n)
}
impl Decoder {
    fn read(&mut self, start: u64, count: usize, out: &mut [u8]) -> Result<usize, ()> {
        if count == 0 || count > self.max_batch() || start.checked_add(count as u64).filter(|end| *end <= self.points).is_none() || out.len() < count * RECORD_BYTES { return Err(()); }
        if self.position != start { self.reader.seek(start).map_err(|_| ())?; }
        self.scratch.resize(count * self.record_len, 0);
        let reader = &mut self.reader;
        let scratch = &mut self.scratch;
        self.pool.install(|| reader.decompress_many(scratch)).map_err(|_| ())?;
        self.position = start + count as u64;
        pack(&self.scratch, self.record_len, self.format, out)
    }
    fn max_batch(&self) -> usize { (MAX_SCRATCH / self.record_len).min(200_000) }
}
#[no_mangle]
pub extern "system" fn Java_com_example_data_LazRsNativeDecoder_nativeAbi(_env: JNIEnv, _class: JClass) -> jint { 2 }
#[no_mangle]
pub extern "system" fn Java_com_example_data_LazRsNativeDecoder_nativeOpen(mut env: JNIEnv, _class: JClass, path: JString, workers: jint) -> jlong {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let path: String = env.get_string(&path).map_err(|_| ())?.into();
        let decoder = open(&path, workers.max(1) as usize)?;
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        readers().lock().map_err(|_| ())?.insert(id, decoder);
        Ok::<_, ()>(id)
    }));
    result.ok().and_then(Result::ok).unwrap_or(0)
}
#[no_mangle]
pub extern "system" fn Java_com_example_data_LazRsNativeDecoder_nativeMaxBatch(_env: JNIEnv, _class: JClass, id: jlong) -> jint {
    readers().lock().ok().and_then(|r| r.get(&id).map(|d| d.max_batch() as jint)).unwrap_or(0)
}
#[no_mangle]
pub extern "system" fn Java_com_example_data_LazRsNativeDecoder_nativeRead(env: JNIEnv, _class: JClass, id: jlong, start: jlong, count: jint, destination: JByteBuffer) -> jint {
    if start < 0 || count <= 0 { return -1; }
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let addr = env.get_direct_buffer_address(&destination).map_err(|_| ())?;
        let cap = env.get_direct_buffer_capacity(&destination).map_err(|_| ())?;
        // JVM owns the direct buffer throughout this synchronous JNI invocation.
        let out = unsafe { std::slice::from_raw_parts_mut(addr, cap) };
        let mut guard = readers().lock().map_err(|_| ())?;
        let decoder = guard.get_mut(&id).ok_or(())?;
        decoder.read(start as u64, count as usize, out).map(|n| n as jint)
    })).ok().and_then(Result::ok).unwrap_or(-1)
}
#[no_mangle]
pub extern "system" fn Java_com_example_data_LazRsNativeDecoder_nativeClose(_env: JNIEnv, _class: JClass, id: jlong) {
    if let Ok(mut r) = readers().lock() { r.remove(&id); }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn raw_integer_flags_and_capacity() {
        for format in [0,6] {
            let len = if format == 0 { 20 } else { 30 };
            let mut raw = vec![0; len];
            raw[..4].copy_from_slice(&i32::MIN.to_le_bytes());
            raw[4..8].copy_from_slice(&i32::MAX.to_le_bytes());
            if format == 0 { raw[15] = 2 | 64 | 128; } else { raw[16] = 2; raw[15] = 2 | 4; }
            let mut out = [0;16];
            assert_eq!(pack(&raw,len,format,&mut out),Ok(1));
            assert_eq!(&out[..8],&raw[..8]); assert_eq!(out[12],2); assert_eq!(out[13],3);
            assert!(pack(&raw,len,format,&mut out[..15]).is_err());
        }
    }
    #[test] fn chunk_seeks_keep_exact_integers_and_short_tail() {
        let path = concat!(env!("CARGO_MANIFEST_DIR"),"/../../app/src/test/resources/laz/viewport-chunks.laz");
        let mut d = open(path,2).unwrap();
        let mut out = vec![0;200_000*16];
        assert_eq!(d.read(0,200_000,&mut out),Ok(200_000));
        assert_eq!(d.read(250_000,123,&mut out),Ok(123));
        assert_eq!(i32::from_le_bytes(out[..4].try_into().unwrap()),0);
        assert_eq!(i32::from_le_bytes(out[4..8].try_into().unwrap()),1_500_000);
        assert_eq!(d.read(50_000,50_000,&mut out),Ok(50_000));
        assert_eq!(out[10*16+12],7); assert_eq!(out[11*16+13]&2,2);
        assert!(d.read(250_000,124,&mut out).is_err());
    }
}
