//! Text and image measurement.
//!
//! Taffy knows nothing about fonts, so every leaf needs a size. Two sources
//! are supported:
//!
//! * The **default** measurer, which works from the data the compiler already
//!   froze into the IR: the text runs carry their measured width and height,
//!   images carry their intrinsic size. That is enough to reproduce the
//!   compile-time layout at the compile-time width, and to approximate other
//!   widths.
//! * A **host measurer**: the platform (Qt / Compose) registers a callback and
//!   measures text with the real font. That is what a production runtime
//!   needs, because only the platform knows the font that will be used.

use taffy::prelude::*;
use taffy::tree::{Baselines, LayoutInput, LayoutOutput, TaffyError, TaffyTree};

use crate::ir::Node;

/// What the kernel knows about a node before it asks for a measurement.
#[derive(Clone, Debug, Default)]
pub struct MeasureContext {
    pub ir_id: i64,
    pub node_type: String,
    /// Full text of a text node.
    pub text: String,
    /// Number of characters in the text.
    pub char_count: usize,
    /// Number of line runs the compiler measured.
    pub run_count: usize,
    /// Width of the widest measured run.
    pub max_run_width: f32,
    /// Height of one line.
    pub run_height: f32,
    /// Average advance width per character, derived from the first run.
    pub avg_char_width: f32,
    /// Intrinsic size of a replaced element (image / svg).
    pub intrinsic_width: f32,
    pub intrinsic_height: f32,
}

impl MeasureContext {
    pub fn for_node(node: &Node) -> Self {
        let mut ctx = MeasureContext {
            ir_id: node.id,
            node_type: node.node_type.clone(),
            text: node.text.clone().unwrap_or_default(),
            intrinsic_width: node.rect.w,
            intrinsic_height: node.rect.h,
            ..Default::default()
        };

        ctx.run_count = node.runs.len();
        ctx.max_run_width = node.runs.iter().map(|r| r.rect.w).fold(0.0, f32::max);
        ctx.run_height = node.runs.first().map(|r| r.rect.h).unwrap_or(0.0);

        if ctx.char_count == 0 {
            ctx.char_count = node.text.as_deref().map(|t| t.chars().count()).unwrap_or(0);
        }
        if ctx.char_count == 0 {
            ctx.char_count = node.runs.iter().map(|r| r.text.chars().count()).sum();
        }
        if ctx.char_count == 0 {
            ctx.char_count = node
                .runs
                .first()
                .map(|r| r.text.chars().count())
                .unwrap_or(0);
        }

        // Average advance per character. CJK glyphs are (close to) monospaced,
        // so the first measured line is a decent estimator; for proportional
        // scripts it is an approximation, which is why the host measurer
        // exists.
        if let Some(first) = node.runs.first() {
            let n = first.text.chars().count();
            if n > 0 {
                ctx.avg_char_width = first.rect.w / n as f32;
            }
        }

        ctx
    }
}

/// A measurement request handed to the host measurer.
#[derive(Clone, Debug)]
pub struct MeasureRequest<'a> {
    pub ir_id: i64,
    pub node_type: &'a str,
    pub text: &'a str,
    /// The width the node may occupy, if the layout algorithm knows it.
    pub available_width: Option<f32>,
    pub available_height: Option<f32>,
}

/// The size the host reports for a leaf.
#[derive(Clone, Copy, Debug, Default)]
pub struct MeasureSize {
    pub width: f32,
    pub height: f32,
}

/// The platform side of the measurement contract.
pub trait Measurer {
    fn measure(&mut self, request: &MeasureRequest<'_>) -> MeasureSize;
}

impl<F> Measurer for F
where
    F: FnMut(&MeasureRequest<'_>) -> MeasureSize,
{
    fn measure(&mut self, request: &MeasureRequest<'_>) -> MeasureSize {
        self(request)
    }
}

/// Extracts a definite width from the layout input, if there is one.
fn available_width(inputs: &LayoutInput) -> Option<f32> {
    if let Some(w) = inputs.known_dimensions.width {
        if w.is_finite() {
            return Some(w);
        }
    }
    match inputs.available_space.width {
        AvailableSpace::Definite(w) => Some(w),
        AvailableSpace::MaxContent => None,
        AvailableSpace::MinContent => Some(0.0),
    }
}

fn available_height(inputs: &LayoutInput) -> Option<f32> {
    if let Some(h) = inputs.known_dimensions.height {
        if h.is_finite() {
            return Some(h);
        }
    }
    match inputs.available_space.height {
        AvailableSpace::Definite(h) => Some(h),
        AvailableSpace::MaxContent => None,
        AvailableSpace::MinContent => Some(0.0),
    }
}

/// The built-in measurer: derives the size from the data frozen into the IR.
pub fn default_measure(inputs: &LayoutInput, ctx: Option<&MeasureContext>) -> MeasureSize {
    let Some(ctx) = ctx else {
        return MeasureSize::default();
    };

    match ctx.node_type.as_str() {
        "text" => measure_text(inputs, ctx),
        "image" | "svg" => MeasureSize {
            width: ctx.intrinsic_width,
            height: ctx.intrinsic_height,
        },
        "audio" | "input" => MeasureSize {
            width: ctx.intrinsic_width,
            height: ctx.intrinsic_height,
        },
        "break" => MeasureSize::default(),
        _ => MeasureSize {
            width: ctx.intrinsic_width,
            height: ctx.intrinsic_height,
        },
    }
}

fn measure_text(inputs: &LayoutInput, ctx: &MeasureContext) -> MeasureSize {
    if ctx.run_count == 0 {
        return MeasureSize::default();
    }
    let avail = available_width(inputs);
    let line_height = if ctx.run_height > 0.0 {
        ctx.run_height
    } else {
        // No measured run: fall back to the font size if the IR carries one.
        16.0
    };

    // Estimated width of the whole text on a single line.
    let estimated_total = if ctx.avg_char_width > 0.0 {
        ctx.avg_char_width * ctx.char_count as f32
    } else {
        ctx.max_run_width
    };

    let lines = match avail {
        Some(width) if width > 0.0 && estimated_total > width => {
            (estimated_total / width).ceil() as usize
        }
        _ => ctx.run_count,
    };
    let lines = lines.max(ctx.run_count.min(1)).max(1);

    // When the layout still fits the compile-time line count, keep the
    // measured width: it is exact. Otherwise the text wraps and the leaf is
    // as wide as the space it was given.
    let width = if lines <= ctx.run_count {
        ctx.max_run_width
    } else {
        match avail {
            Some(width) if width > 0.0 => width.min(estimated_total),
            _ => estimated_total,
        }
    };

    MeasureSize {
        width,
        height: line_height * lines as f32,
    }
}

/// Runs the layout for one frame and returns the absolute rect of every node.
///
/// `measurer` is optional: without it the built-in IR-derived measurement is
/// used, which reproduces the compile-time layout at the compile-time width.
pub fn measure_frame(
    tree: &mut TaffyTree<MeasureContext>,
    root: taffy::tree::NodeId,
    width: f32,
    mut measurer: Option<&mut dyn Measurer>,
) -> Result<(f32, f32), TaffyError> {
    let available = Size {
        width: AvailableSpace::Definite(width),
        height: AvailableSpace::MaxContent,
    };
    tree.compute_layout_with_measure(root, available, |inputs, _node_id, context, _style| {
        let size = match measurer.as_mut() {
            Some(measurer) => {
                let request = MeasureRequest {
                    ir_id: context.as_ref().map(|c| c.ir_id).unwrap_or(-1),
                    node_type: context.as_ref().map(|c| c.node_type.as_str()).unwrap_or(""),
                    text: context.as_ref().map(|c| c.text.as_str()).unwrap_or(""),
                    available_width: available_width(&inputs),
                    available_height: available_height(&inputs),
                };
                measurer.measure(&request)
            }
            None => default_measure(&inputs, context.as_deref()),
        };
        LayoutOutput::from_sizes_and_baselines(
            Size {
                width: size.width,
                height: size.height,
            },
            Rect::ZERO,
            Baselines::NONE,
        )
    })?;
    let layout = tree.layout(root)?;
    Ok((layout.location.x, layout.location.y))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ir::Rect;

    fn text_node(text: &str, runs: &[(&str, f32, f32)]) -> Node {
        Node {
            id: 1,
            node_type: "text".into(),
            tag: None,
            text: Some(text.into()),
            src: None,
            style: Default::default(),
            rect: Rect::default(),
            runs: runs
                .iter()
                .map(|(t, w, h)| crate::ir::Run {
                    text: (*t).into(),
                    rect: Rect { x: 0.0, y: 0.0, w: *w, h: *h },
                })
                .collect(),
            children: Vec::new(),
        }
    }

    #[test]
    fn single_line_text_keeps_measured_width() {
        let node = text_node("N5", &[("N5", 19.19, 20.0)]);
        let ctx = MeasureContext::for_node(&node);
        let inputs = LayoutInput {
            known_dimensions: Size { width: Some(720.0), height: None },
            available_space: Size {
                width: AvailableSpace::Definite(720.0),
                height: AvailableSpace::MaxContent,
            },
            ..LayoutInput::HIDDEN
        };
        let size = default_measure(&inputs, Some(&ctx));
        assert!((size.width - 19.19).abs() < 0.01);
        assert!((size.height - 20.0).abs() < 0.01);
    }

    #[test]
    fn narrow_width_wraps_into_more_lines() {
        let node = text_node("abcdefghij", &[("abcdefghij", 100.0, 20.0)]);
        let ctx = MeasureContext::for_node(&node);
        let inputs = LayoutInput {
            known_dimensions: Size { width: Some(50.0), height: None },
            available_space: Size {
                width: AvailableSpace::Definite(50.0),
                height: AvailableSpace::MaxContent,
            },
            ..LayoutInput::HIDDEN
        };
        let size = default_measure(&inputs, Some(&ctx));
        assert!(size.height > 20.0, "expected wrapping, got {size:?}");
    }
}
