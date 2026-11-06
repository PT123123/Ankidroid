//! Native card layout kernel (IR v1).
//!
//! The Card IR is the single source of truth for a card. This crate is the
//! second half of the A2 route: it recomputes the layout of that IR at run
//! time with [Taffy](https://github.com/DioxusLabs/taffy) (Rust / MIT, CSS
//! Block + Flexbox + Grid), so a card can be laid out at any width or font
//! size without asking the compiler for a new variant.
//!
//! The crate is deliberately standalone:
//!
//! * it does **not** join `backend/`'s Cargo workspace — `backend/` is a path
//!   dependency on `rslib`, and the root `AGENTS.md` forbids touching `rslib`,
//!   the DB schema, the sync protocol or the apkg format;
//! * it has **no UI dependency**: it never opens a window, never touches a
//!   font file and never creates a browser instance;
//! * text and image measurement is either derived from the IR itself
//!   ([`measure::default_measure`]) or delegated to the host through
//!   [`measure::Measurer`].
//!
//! ## Usage from C++
//!
//! ```c
//! IrNodeRect rects[512];
//! size_t count = 0;
//! ir_layout_compute(json, json_len, "question", "light@800x1", 800.0f, 1.0f,
//!                   rects, 512, &count);
//! ```
//!
//! ## Usage from Rust
//!
//! ```no_run
//! # fn main() -> Result<(), Box<dyn std::error::Error>> {
//! let json: Vec<u8> = std::fs::read("card.json")?;
//! let card = anki_native_layout::parse_card(&json)?;
//! let result = anki_native_layout::layout(&card, "question", Some("light@800x1"), 800.0, None)?;
//! println!("{} nodes", result.rects.len());
//! # Ok(())
//! # }
//! ```

pub mod convert;
pub mod ir;
pub mod measure;
pub mod parse;

mod android_jni;

use std::collections::BTreeMap;
use std::ffi::{c_char, c_void, CStr};

use taffy::tree::{NodeId, TaffyTree};

pub use ir::{CardIr, Frame, Node, Rect, Run, SUPPORTED_IR_VERSION};
pub use measure::{MeasureRequest, MeasureSize, Measurer};

/// Status codes returned by the C ABI.
pub const IR_LAYOUT_OK: i32 = 0;
pub const IR_LAYOUT_ERR_JSON: i32 = 1;
pub const IR_LAYOUT_ERR_VERSION: i32 = 2;
pub const IR_LAYOUT_ERR_FRAME: i32 = 3;
pub const IR_LAYOUT_ERR_LAYOUT: i32 = 4;
pub const IR_LAYOUT_ERR_BUFFER: i32 = 5;

/// One node's absolute rect, in CSS pixels relative to the card content box.
#[derive(Debug, Clone, Copy, Default)]
pub struct NodeRect {
    pub ir_id: i64,
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
}

/// The result of one layout run.
#[derive(Debug, Clone, Default)]
pub struct LayoutResult {
    pub width: f32,
    pub height: f32,
    pub rects: Vec<NodeRect>,
    pub warnings: Vec<String>,
}

#[derive(Debug)]
pub enum LayoutError {
    Json(String),
    Version(String),
    Frame(String),
    Layout(String),
}

impl std::fmt::Display for LayoutError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            LayoutError::Json(m) => write!(f, "invalid card IR json: {m}"),
            LayoutError::Version(m) => write!(f, "unsupported IR version: {m}"),
            LayoutError::Frame(m) => write!(f, "no such frame: {m}"),
            LayoutError::Layout(m) => write!(f, "layout failed: {m}"),
        }
    }
}

impl std::error::Error for LayoutError {}

/// Parses a Card IR document.
pub fn parse_card(json: &[u8]) -> Result<CardIr, LayoutError> {
    let card: CardIr =
        serde_json::from_slice(json).map_err(|e| LayoutError::Json(e.to_string()))?;
    if card.ir_version != SUPPORTED_IR_VERSION {
        return Err(LayoutError::Version(card.ir_version));
    }
    Ok(card)
}

/// Picks the frame for a side, optionally pinning a variant.
pub fn select_frame<'a>(
    card: &'a CardIr,
    side: &str,
    variant: Option<&str>,
) -> Result<&'a Frame, LayoutError> {
    let sides = card
        .sides
        .get(side)
        .ok_or_else(|| LayoutError::Frame(format!("side '{side}' not found")))?;
    if let Some(variant) = variant {
        return sides
            .get(variant)
            .ok_or_else(|| LayoutError::Frame(format!("variant '{variant}' not found")));
    }
    sides
        .values()
        .next()
        .ok_or_else(|| LayoutError::Frame(format!("side '{side}' has no variant")))
}

/// Lays out one frame at the given width.
///
/// `measurer` is optional. Without it the built-in IR-derived measurement is
/// used, which reproduces the compile-time layout at the compile-time width
/// and approximates other widths.
pub fn layout(
    card: &CardIr,
    side: &str,
    variant: Option<&str>,
    width: f32,
    measurer: Option<&mut dyn Measurer>,
) -> Result<LayoutResult, LayoutError> {
    let frame = select_frame(card, side, variant)?;
    layout_frame(frame, width, measurer)
}

/// Lays out a frame that has already been selected.
pub fn layout_frame(
    frame: &Frame,
    width: f32,
    measurer: Option<&mut dyn Measurer>,
) -> Result<LayoutResult, LayoutError> {
    let mut tree: TaffyTree<measure::MeasureContext> = TaffyTree::with_capacity(64);
    let mut mapping: Vec<(i64, NodeId)> = Vec::with_capacity(frame.node_count as usize);
    let inherited: BTreeMap<String, String> = BTreeMap::new();
    let root = convert::build(&mut tree, &frame.root, &inherited, &mut mapping);

    measure::measure_frame(&mut tree, root, width, measurer)
        .map_err(|e| LayoutError::Layout(e.to_string()))?;

    let rects = absolute_rects(&tree, root, &mapping);
    let root_layout = tree
        .layout(root)
        .map_err(|e| LayoutError::Layout(e.to_string()))?;

    Ok(LayoutResult {
        width: root_layout.size.width,
        height: root_layout.size.height,
        rects,
        warnings: Vec::new(),
    })
}

/// Converts Taffy's parent-relative locations into absolute rects.
fn absolute_rects(
    tree: &TaffyTree<measure::MeasureContext>,
    root: NodeId,
    mapping: &[(i64, NodeId)],
) -> Vec<NodeRect> {
    let mut by_taffy: BTreeMap<usize, i64> = BTreeMap::new();
    for (ir_id, taffy_id) in mapping {
        by_taffy.insert((*taffy_id).into(), *ir_id);
    }

    let mut out = Vec::with_capacity(mapping.len());
    let mut stack = vec![(root, 0.0f32, 0.0f32)];
    while let Some((node, parent_x, parent_y)) = stack.pop() {
        let Some(layout) = tree.layout(node).ok() else {
            continue;
        };
        let x = parent_x + layout.location.x;
        let y = parent_y + layout.location.y;
        if let Some(ir_id) = by_taffy.get(&node.into()) {
            out.push(NodeRect {
                ir_id: *ir_id,
                x,
                y,
                w: layout.size.width,
                h: layout.size.height,
            });
        }
        if let Ok(children) = tree.children(node) {
            for child in children.into_iter().rev() {
                stack.push((child, x, y));
            }
        }
    }
    out.sort_by_key(|r| r.ir_id);
    out
}

// ---------------------------------------------------------------------------
// C ABI
// ---------------------------------------------------------------------------

/// A node rect as seen from C.
#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct IrNodeRect {
    pub ir_id: i64,
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
}

/// A measured size as seen from C.
#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct IrSize {
    pub width: f32,
    pub height: f32,
}

/// Node type discriminants handed to the host measure callback.
pub const IR_NODE_UNKNOWN: i32 = 0;
pub const IR_NODE_TEXT: i32 = 1;
pub const IR_NODE_BOX: i32 = 2;
pub const IR_NODE_IMAGE: i32 = 3;
pub const IR_NODE_SVG: i32 = 4;
pub const IR_NODE_RUBY: i32 = 5;
pub const IR_NODE_AUDIO: i32 = 6;
pub const IR_NODE_CLOZE: i32 = 7;
pub const IR_NODE_INPUT: i32 = 8;
pub const IR_NODE_BREAK: i32 = 9;

/// The host measure callback.
///
/// `available_width` / `available_height` are negative when the layout
/// algorithm did not constrain that axis.
pub type IrMeasureFn = extern "C" fn(
    user_data: *mut c_void,
    ir_id: i64,
    node_type: i32,
    text: *const c_char,
    available_width: f32,
    available_height: f32,
) -> IrSize;

fn node_type_code(node_type: &str) -> i32 {
    match node_type {
        "text" => IR_NODE_TEXT,
        "box" => IR_NODE_BOX,
        "image" => IR_NODE_IMAGE,
        "svg" => IR_NODE_SVG,
        "ruby" => IR_NODE_RUBY,
        "audio" => IR_NODE_AUDIO,
        "cloze" => IR_NODE_CLOZE,
        "input" => IR_NODE_INPUT,
        "break" => IR_NODE_BREAK,
        _ => IR_NODE_UNKNOWN,
    }
}

fn cstr_to_str<'a>(ptr: *const c_char) -> Option<&'a str> {
    if ptr.is_null() {
        return None;
    }
    // SAFETY: the caller guarantees the pointer is either null or a valid
    // NUL-terminated string that outlives the returned borrow.
    unsafe { CStr::from_ptr(ptr).to_str().ok() }
}

/// Computes the layout of one frame and writes the absolute rects.
///
/// `out_rects` may be null, in which case the required count is written to
/// `out_count` and nothing else happens.
///
/// # Safety
/// `ir_json` must point to `ir_json_len` readable bytes. `out_rects` must
/// point to `out_rects_cap` writable `IrNodeRect` values.
#[no_mangle]
pub unsafe extern "C" fn ir_layout_compute(
    ir_json: *const u8,
    ir_json_len: usize,
    side: *const c_char,
    variant: *const c_char,
    width: f32,
    scale: f32,
    out_rects: *mut IrNodeRect,
    out_rects_cap: usize,
    out_count: *mut usize,
) -> i32 {
    let result = compute(ir_json, ir_json_len, side, variant, width, scale, None);
    unsafe { finish(result, out_rects, out_rects_cap, out_count) }
}

/// Computes the layout using a host measure callback.
///
/// # Safety
/// Same contract as [`ir_layout_compute`]; `measure` must be a valid function
/// pointer and `user_data` is passed through unchanged.
#[no_mangle]
pub unsafe extern "C" fn ir_layout_compute_measured(
    ir_json: *const u8,
    ir_json_len: usize,
    side: *const c_char,
    variant: *const c_char,
    width: f32,
    scale: f32,
    measure: Option<IrMeasureFn>,
    user_data: *mut c_void,
    out_rects: *mut IrNodeRect,
    out_rects_cap: usize,
    out_count: *mut usize,
) -> i32 {
    let result = compute(
        ir_json,
        ir_json_len,
        side,
        variant,
        width,
        scale,
        measure.map(|m| (m, user_data)),
    );
    unsafe { finish(result, out_rects, out_rects_cap, out_count) }
}

/// The number of nodes a frame has, so the caller can size its buffer.
///
/// # Safety
/// `ir_json` must point to `ir_json_len` readable bytes.
#[no_mangle]
pub unsafe extern "C" fn ir_layout_node_count(
    ir_json: *const u8,
    ir_json_len: usize,
    side: *const c_char,
    variant: *const c_char,
    out_count: *mut usize,
) -> i32 {
    if ir_json.is_null() {
        return IR_LAYOUT_ERR_JSON;
    }
    let json = unsafe { std::slice::from_raw_parts(ir_json, ir_json_len) };
    let card = match parse_card(json) {
        Ok(card) => card,
        Err(_) => return IR_LAYOUT_ERR_JSON,
    };
    let side = match cstr_to_str(side) {
        Some(s) => s,
        None => return IR_LAYOUT_ERR_FRAME,
    };
    let variant = cstr_to_str(variant);
    match select_frame(&card, side, variant) {
        Ok(frame) => {
            if !out_count.is_null() {
                *out_count = frame.node_count as usize;
            }
            IR_LAYOUT_OK
        }
        Err(_) => IR_LAYOUT_ERR_FRAME,
    }
}

type MeasureBridge = (IrMeasureFn, *mut c_void);

fn compute(
    ir_json: *const u8,
    ir_json_len: usize,
    side: *const c_char,
    variant: *const c_char,
    width: f32,
    scale: f32,
    measure: Option<MeasureBridge>,
) -> Result<LayoutResult, i32> {
    if ir_json.is_null() {
        return Err(IR_LAYOUT_ERR_JSON);
    }
    let json = unsafe { std::slice::from_raw_parts(ir_json, ir_json_len) };
    let card = parse_card(json).map_err(|e| match e {
        LayoutError::Version(_) => IR_LAYOUT_ERR_VERSION,
        _ => IR_LAYOUT_ERR_JSON,
    })?;

    let side = cstr_to_str(side).ok_or(IR_LAYOUT_ERR_FRAME)?;
    let variant = cstr_to_str(variant);
    let frame = select_frame(&card, side, variant).map_err(|_| IR_LAYOUT_ERR_FRAME)?;

    // `scale` multiplies every length: the compiler baked CSS pixels into the
    // IR, and the runtime may need a different device scale.
    let scaled_width = if scale > 0.0 { width / scale } else { width };

    let mut host_measurer: HostMeasurer;
    let measurer: Option<&mut dyn Measurer> = match measure {
        Some((func, user_data)) => {
            host_measurer = HostMeasurer { func, user_data };
            Some(&mut host_measurer)
        }
        None => None,
    };

    let mut result =
        layout_frame(frame, scaled_width, measurer).map_err(|_| IR_LAYOUT_ERR_LAYOUT)?;
    if scale > 0.0 && (scale - 1.0).abs() > f32::EPSILON {
        for rect in &mut result.rects {
            rect.x *= scale;
            rect.y *= scale;
            rect.w *= scale;
            rect.h *= scale;
        }
        result.width *= scale;
        result.height *= scale;
    }
    Ok(result)
}

/// Writes the result into the caller's buffers.
///
/// # Safety
/// `out_rects` must be null or point to `out_rects_cap` writable
/// `IrNodeRect` values; `out_count` must be null or point to a writable
/// `usize`.
unsafe fn finish(
    result: Result<LayoutResult, i32>,
    out_rects: *mut IrNodeRect,
    out_rects_cap: usize,
    out_count: *mut usize,
) -> i32 {
    let result = match result {
        Ok(r) => r,
        Err(code) => return code,
    };
    if !out_count.is_null() {
        *out_count = result.rects.len();
    }
    if out_rects.is_null() {
        return IR_LAYOUT_OK;
    }
    if out_rects_cap < result.rects.len() {
        return IR_LAYOUT_ERR_BUFFER;
    }
    let slots = std::slice::from_raw_parts_mut(out_rects, out_rects_cap);
    for (slot, rect) in slots.iter_mut().zip(result.rects.iter()) {
        *slot = IrNodeRect {
            ir_id: rect.ir_id,
            x: rect.x,
            y: rect.y,
            w: rect.w,
            h: rect.h,
        };
    }
    IR_LAYOUT_OK
}

/// Bridges the C measure callback to the Rust [`Measurer`] trait.
struct HostMeasurer {
    func: IrMeasureFn,
    user_data: *mut c_void,
}

impl Measurer for HostMeasurer {
    fn measure(&mut self, request: &MeasureRequest<'_>) -> MeasureSize {
        let text = std::ffi::CString::new(request.text).unwrap_or_default();
        let size = (self.func)(
            self.user_data,
            request.ir_id,
            node_type_code(request.node_type),
            text.as_ptr(),
            request.available_width.unwrap_or(-1.0),
            request.available_height.unwrap_or(-1.0),
        );
        MeasureSize {
            width: size.width,
            height: size.height,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MINIMAL_IR: &str = r#"{
      "ir_version": "0.1.0",
      "card_id": 1,
      "notetype": "Basic",
      "variants": ["light@800x1"],
      "sides": {
        "question": {
          "light@800x1": {
            "width": 800,
            "height": 200,
            "node_count": 2,
            "text_run_count": 1,
            "root": {
              "id": 0,
              "type": "box",
              "style": {"display": "block", "width": "800px", "padding-top": "20px", "padding-left": "40px"},
              "rect": {"x": 0, "y": 0, "w": 800, "h": 200},
              "children": [
                {
                  "id": 1,
                  "type": "text",
                  "text": "hello",
                  "style": {},
                  "rect": {"x": 40, "y": 20, "w": 60, "h": 20},
                  "runs": [{"text": "hello", "rect": {"x": 40, "y": 20, "w": 60, "h": 20}}]
                }
              ]
            }
          }
        }
      }
    }"#;

    #[test]
    fn parses_and_lays_out_a_minimal_card() {
        let card = parse_card(MINIMAL_IR.as_bytes()).expect("parse");
        assert_eq!(card.notetype, "Basic");
        let result = layout(&card, "question", Some("light@800x1"), 800.0, None).expect("layout");
        assert_eq!(result.rects.len(), 2);
        let root = result.rects.iter().find(|r| r.ir_id == 0).expect("root");
        let text = result.rects.iter().find(|r| r.ir_id == 1).expect("text");
        assert!((root.w - 800.0).abs() < 0.5, "root width {root:?}");
        assert!((text.x - 40.0).abs() < 0.5, "text x {text:?}");
        assert!((text.y - 20.0).abs() < 0.5, "text y {text:?}");
    }

    #[test]
    fn rejects_an_unknown_ir_version() {
        let bad = MINIMAL_IR.replace("\"0.1.0\"", "\"9.9\"");
        assert!(matches!(
            parse_card(bad.as_bytes()),
            Err(LayoutError::Version(_))
        ));
    }
}
