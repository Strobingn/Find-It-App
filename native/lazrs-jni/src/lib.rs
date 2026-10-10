use jni::objects::{JByteBuffer, JClass, JString};
use jni::sys::{jboolean, jint, JNI_TRUE};
use jni::JNIEnv;
use las::{Read as LasRead, Reader};
use laz::{LazVlr, ParLasZipDecompressor};
use std::fs::File;
use std::io::{Seek, SeekFrom};
use std::path::Path;

/// Packed record: f32 x, f32 y, f32 z, u8 class, u8 flags, u16 pad (16 bytes, LE).
const RECORD_BYTES: usize = 16;
const LASZIP_USER_ID: &str = "laszip encoded";
const LASZIP_RECORD_ID: u16 = 22204;

#[no_mangle]
pub extern "system" fn Java_com_example_data_LazRsNativeDecoder_nativeAvailable(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_example_data_LazRsNativeDecoder_nativeDecode(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    destination: JByteBuffer,
) -> jint {
    let path: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    match decode_file(Path::new(&path), &mut env, destination) {
        Ok(count) => count as jint,
        Err(_) => -1,
    }
}

fn decode_file(
    path: &Path,
    env: &mut JNIEnv,
    destination: JByteBuffer,
) -> Result<usize, ()> {
    let addr = env.get_direct_buffer_address(&destination).map_err(|_| ())?;
    let cap = env.get_direct_buffer_capacity(&destination).map_err(|_| ())?;
    let out = unsafe { std::slice::from_raw_parts_mut(addr, cap) };

    if let Ok(count) = decode_laz_parallel(path, out) {
        return Ok(count);
    }
    decode_with_las_reader(path, out)
}

/// Parallel per-chunk LAZ decompression via laz-rs (`parallel` feature + chunk table).
fn decode_laz_parallel(path: &Path, out: &mut [u8]) -> Result<usize, ()> {
    let reader = Reader::from_path(path).map_err(|_| ())?;
    let header = reader.header();
    let vlr_bytes = header
        .vlrs()
        .iter()
        .chain(header.evlrs())
        .find(|vlr| vlr.user_id == LASZIP_USER_ID && vlr.record_id == LASZIP_RECORD_ID)
        .map(|vlr| vlr.data.clone())
        .ok_or(())?;
    let laz_vlr = LazVlr::from_buffer(&vlr_bytes).map_err(|_| ())?;
    let point_count = header.number_of_points() as usize;
    let transforms = header.transforms();
    let raw = header.clone().into_raw().map_err(|_| ())?;
    let record_len = raw.point_data_record_length as usize;
    if point_count == 0 || record_len < 15 {
        return Err(());
    }
    let point_format = raw.point_data_record_format & 0x3F;
    let mut source = File::open(path).map_err(|_| ())?;
    source
        .seek(SeekFrom::Start(u64::from(raw.offset_to_point_data)))
        .map_err(|_| ())?;
    let mut decompressor = ParLasZipDecompressor::new(source, laz_vlr).map_err(|_| ())?;
    let mut raw_points = vec![0u8; point_count.saturating_mul(record_len)];
    decompressor
        .decompress_many(&mut raw_points)
        .map_err(|_| ())?;
    Ok(pack_raw_records(
        &raw_points,
        record_len,
        point_format,
        transforms.x.scale,
        transforms.y.scale,
        transforms.z.scale,
        transforms.x.offset,
        transforms.y.offset,
        transforms.z.offset,
        out,
    ))
}

fn decode_with_las_reader(path: &Path, out: &mut [u8]) -> Result<usize, ()> {
    let mut reader = Reader::from_path(path).map_err(|_| ())?;
    let mut written = 0usize;
    for next in reader.points() {
        let point = next.map_err(|_| ())?;
        let start = written * RECORD_BYTES;
        if start + RECORD_BYTES > out.len() {
            break;
        }
        let dest = &mut out[start..start + RECORD_BYTES];
        dest[0..4].copy_from_slice(&(point.x as f32).to_le_bytes());
        dest[4..8].copy_from_slice(&(point.y as f32).to_le_bytes());
        dest[8..12].copy_from_slice(&(point.z as f32).to_le_bytes());
        dest[12] = u8::from(point.classification);
        dest[13] = if point.is_key_point { 1 } else { 0 };
        dest[14] = 0;
        dest[15] = 0;
        written += 1;
    }
    Ok(written)
}

fn pack_raw_records(
    raw: &[u8],
    record_len: usize,
    point_format: u8,
    scale_x: f64,
    scale_y: f64,
    scale_z: f64,
    offset_x: f64,
    offset_y: f64,
    offset_z: f64,
    out: &mut [u8],
) -> usize {
    let class_at = if point_format >= 6 { 16 } else { 15 };
    let mut written = 0usize;
    let mut offset = 0usize;
    while offset + record_len <= raw.len() {
        let start = written * RECORD_BYTES;
        if start + RECORD_BYTES > out.len() {
            break;
        }
        let rec = &raw[offset..offset + record_len];
        if rec.len() < class_at + 1 {
            break;
        }
        let x = i32::from_le_bytes(rec[0..4].try_into().unwrap()) as f64 * scale_x + offset_x;
        let y = i32::from_le_bytes(rec[4..8].try_into().unwrap()) as f64 * scale_y + offset_y;
        let z = i32::from_le_bytes(rec[8..12].try_into().unwrap()) as f64 * scale_z + offset_z;
        let (class, key) = if point_format >= 6 {
            (rec[16], rec[15] & 0x02 != 0)
        } else {
            (rec[15] & 0x1F, rec[15] & 0x40 != 0)
        };
        let dest = &mut out[start..start + RECORD_BYTES];
        dest[0..4].copy_from_slice(&(x as f32).to_le_bytes());
        dest[4..8].copy_from_slice(&(y as f32).to_le_bytes());
        dest[8..12].copy_from_slice(&(z as f32).to_le_bytes());
        dest[12] = class;
        dest[13] = if key { 1 } else { 0 };
        dest[14] = 0;
        dest[15] = 0;
        written += 1;
        offset += record_len;
    }
    written
}

