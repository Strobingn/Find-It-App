use jni::objects::{JByteBuffer, JClass, JString};
use jni::sys::{jboolean, jint, JNI_TRUE};
use jni::JNIEnv;
use las::Reader;
use std::path::Path;

/// Packed record: f32 x, f32 y, f32 z, u8 class, u8 flags, u16 pad (16 bytes, LE).
const RECORD_BYTES: usize = 16;

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
