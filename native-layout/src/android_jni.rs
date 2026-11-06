//! JNI entry point used by the Android card surface.
//!
//! The returned `long[]` stores records of five values: IR node id, then the
//! IEEE-754 bits of x, y, width, and height. Keeping ids as longs avoids
//! losing precision for large IR ids.

use jni::objects::{JByteArray, JClass, JFloatArray, JObject, JString, JValue};
use jni::sys::{jfloat, jlongArray};
use jni::JNIEnv;

use crate::measure::{MeasureRequest, MeasureSize, Measurer};
use crate::{layout_frame, parse_card, select_frame};

struct AndroidMeasurer<'local> {
    env: *mut JNIEnv<'local>,
    callback: JObject<'local>,
}

impl Measurer for AndroidMeasurer<'_> {
    fn measure(&mut self, request: &MeasureRequest<'_>) -> MeasureSize {
        let env = unsafe { &mut *self.env };
        let Ok(text) = env.new_string(request.text) else {
            return MeasureSize::default();
        };
        let text = JObject::from(text);
        let args = [
            JValue::Long(request.ir_id),
            JValue::Object(&text),
            JValue::Float(request.available_width.unwrap_or(-1.0)),
            JValue::Float(request.available_height.unwrap_or(-1.0)),
        ];
        let Ok(value) = env.call_method(
            &self.callback,
            "measure",
            "(JLjava/lang/String;FF)[F",
            &args,
        ) else {
            let _ = env.exception_clear();
            return MeasureSize::default();
        };
        let Ok(array) = value.l() else {
            return MeasureSize::default();
        };
        let array = JFloatArray::from(array);
        let mut values = [0.0f32; 2];
        if env.get_float_array_region(&array, 0, &mut values).is_err() {
            let _ = env.exception_clear();
            return MeasureSize::default();
        }
        MeasureSize { width: values[0], height: values[1] }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ichi2_anki_pages_cardir_TaffyCardLayout_layoutNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    ir_json: JByteArray<'local>,
    side: JString<'local>,
    variant: JString<'local>,
    width: jfloat,
    callback: JObject<'local>,
) -> jlongArray {
    let result = (|| {
        let json = env.convert_byte_array(ir_json).ok()?;
        let side = env.get_string(&side).ok()?.to_string_lossy().into_owned();
        let variant = env.get_string(&variant).ok()?.to_string_lossy().into_owned();
        let card = parse_card(&json).ok()?;
        let frame = select_frame(&card, &side, Some(&variant)).ok()?;
        let mut measurer = AndroidMeasurer { env: &mut env, callback };
        let layout = layout_frame(frame, width, Some(&mut measurer)).ok()?;
        drop(measurer);

        let mut values = Vec::with_capacity(layout.rects.len() * 5);
        for rect in layout.rects {
            values.extend([
                rect.ir_id,
                rect.x.to_bits() as i32 as i64,
                rect.y.to_bits() as i32 as i64,
                rect.w.to_bits() as i32 as i64,
                rect.h.to_bits() as i32 as i64,
            ]);
        }

        let output = env.new_long_array(values.len() as i32).ok()?;
        env.set_long_array_region(&output, 0, &values).ok()?;
        Some(output.into_raw())
    })();

    result.unwrap_or(std::ptr::null_mut())
}
