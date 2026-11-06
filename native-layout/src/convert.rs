//! IR node tree -> Taffy style tree.
//!
//! The IR already contains *resolved* styles (the compiler ran the real
//! cascade in Chromium), so this module is a pure translation table: CSS
//! property -> Taffy style field. No cascade, no `var()`, no selector.
//!
//! Two rules from the CSS spec matter here:
//!
//! * A `text` node carries no style of its own in the IR (the compiler does
//!   not invent one), so it inherits from the nearest element ancestor. Only
//!   properties that CSS actually inherits may be taken over — geometry
//!   properties like `width` must never leak from the parent box into a
//!   text leaf.
//! * `display`, `width`, `margin`, ... never inherit. Every box node in the
//!   IR carries its own complete computed style, so boxes use their own map.

use std::collections::BTreeMap;

use taffy::geometry::Point;
use taffy::prelude::*;
use taffy::style::{
    AlignContent, AlignItems, BoxSizing, Dimension, Direction, Display, FlexDirection, FlexWrap,
    LengthPercentage, LengthPercentageAuto, Overflow, Position, Style, TextAlign, TrackSizingFunction,
};
use taffy::style_helpers::{FromFr, FromLength};
use taffy::tree::TaffyTree;

use crate::ir::Node;
use crate::measure::MeasureContext;
use crate::parse::{self, CssLength, Track};

/// The CSS properties a text node inherits from its element ancestor.
///
/// Everything geometric is deliberately absent: a text leaf must not inherit
/// the parent box's width, margins or flex settings.
pub const INHERITED_PROPERTIES: &[&str] = &[
    "color",
    "background-color",
    "font-family",
    "font-size",
    "font-style",
    "font-weight",
    "font-variant",
    "letter-spacing",
    "line-height",
    "text-align",
    "text-decoration-color",
    "text-decoration-line",
    "text-decoration-style",
    "text-indent",
    "text-transform",
    "white-space",
    "word-break",
    "word-spacing",
    "direction",
    "writing-mode",
    "unicode-bidi",
    "vertical-align",
    "visibility",
    "opacity",
    "list-style-position",
    "list-style-type",
];

/// Converts one IR node into a Taffy style.
pub fn style_for(node: &Node, inherited: &BTreeMap<String, String>) -> Style {
    let src = if node.style.is_empty() { inherited } else { &node.style };
    let mut style = Style::default();

    apply_display(node, src, &mut style);
    apply_flex(node, src, &mut style);
    apply_alignment(node, src, &mut style);
    apply_size(node, src, &mut style);
    apply_spacing(node, src, &mut style);
    apply_grid(node, src, &mut style);

    if let Some(overflow) = src.get("overflow").and_then(|v| parse::parse_overflow(v)) {
        let o = match overflow {
            "hidden" => Overflow::Hidden,
            "clip" => Overflow::Clip,
            // Taffy has no `auto` overflow: `scroll` is the closest match, the
            // difference only shows up when the content actually overflows.
            "scroll" | "auto" => Overflow::Scroll,
            _ => Overflow::Visible,
        };
        style.overflow = Point { x: o, y: o };
    }

    if let Some(direction) = src.get("direction") {
        style.direction = match direction.trim() {
            "rtl" => Direction::Rtl,
            _ => Direction::Ltr,
        };
    }

    if let Some(text_align) = src.get("text-align") {
        style.text_align = match text_align.trim() {
            "center" => TextAlign::LegacyCenter,
            "right" => TextAlign::LegacyRight,
            // Taffy has no `justify`; text alignment inside a block is handled
            // by the renderer anyway.
            _ => TextAlign::LegacyLeft,
        };
    }

    style
}

fn apply_display(node: &Node, src: &BTreeMap<String, String>, style: &mut Style) {
    let display = src.get("display").and_then(|v| parse::parse_display(v)).unwrap_or("block");
    style.display = match display {
        "none" => Display::None,
        "flex" | "inline-flex" => Display::Flex,
        "grid" | "inline-grid" => Display::Grid,
        // Taffy has no inline formatting context: an inline box is laid out as
        // a block box that shrinks to its content.
        _ => Display::Block,
    };

    if let Some(position) = src.get("position").and_then(|v| parse::parse_position(v)) {
        // The IR does not carry `left`/`top` offsets, so an absolutely
        // positioned box cannot be placed. Degrading it to `relative` keeps it
        // in the flow instead of dropping it at the origin.
        style.position = match position {
            "absolute" | "fixed" => Position::Relative,
            "relative" => Position::Relative,
            _ => Position::Static,
        };
    }

    if let Some(sizing) = src.get("box-sizing").and_then(|v| parse::parse_box_sizing(v)) {
        style.box_sizing = match sizing {
            "content-box" => BoxSizing::ContentBox,
            _ => BoxSizing::BorderBox,
        };
    }

    // Images, SVG and the replay button are replaced elements: they have an
    // intrinsic size and do not stretch like text does.
    style.item_is_replaced = matches!(node.node_type.as_str(), "image" | "svg");
}

fn apply_flex(_node: &Node, src: &BTreeMap<String, String>, style: &mut Style) {
    if let Some(direction) = src.get("flex-direction").and_then(|v| parse::parse_flex_direction(v)) {
        style.flex_direction = match direction {
            "row" => FlexDirection::Row,
            "row-reverse" => FlexDirection::RowReverse,
            "column" => FlexDirection::Column,
            "column-reverse" => FlexDirection::ColumnReverse,
            _ => FlexDirection::Row,
        };
    }
    if let Some(wrap) = src.get("flex-wrap").and_then(|v| parse::parse_flex_wrap(v)) {
        style.flex_wrap = match wrap {
            "wrap" => FlexWrap::Wrap,
            "wrap-reverse" => FlexWrap::WrapReverse,
            _ => FlexWrap::NoWrap,
        };
    }
    if let Some(grow) = src.get("flex-grow").and_then(|v| parse::parse_number(v)) {
        style.flex_grow = grow;
    }
    if let Some(shrink) = src.get("flex-shrink").and_then(|v| parse::parse_number(v)) {
        style.flex_shrink = shrink;
    }
    if let Some(basis) = src.get("flex-basis").and_then(|v| parse::parse_length(v)) {
        style.flex_basis = dimension(basis);
    }
}

fn apply_alignment(_node: &Node, src: &BTreeMap<String, String>, style: &mut Style) {
    if let Some(align) = src.get("align-items").and_then(|v| parse::parse_align_items(v)) {
        style.align_items = align_items(align);
    }
    if let Some(align) = src.get("align-self").and_then(|v| parse::parse_align_self(v)) {
        style.align_self = Some(align_items(align));
    }
    if let Some(justify) = src.get("justify-content").and_then(|v| parse::parse_justify_content(v)) {
        style.justify_content = match justify {
            "flex-start" | "start" | "left" => AlignContent::FLEX_START,
            "center" => AlignContent::CENTER,
            "flex-end" | "end" | "right" => AlignContent::FLEX_END,
            "space-between" => AlignContent::SPACE_BETWEEN,
            "space-around" => AlignContent::SPACE_AROUND,
            "space-evenly" => AlignContent::SPACE_EVENLY,
            _ => AlignContent::START,
        };
    }
    if let Some(content) = src.get("align-content") {
        style.align_content = match content.trim() {
            "center" => AlignContent::CENTER,
            "flex-end" | "end" => AlignContent::FLEX_END,
            "space-between" => AlignContent::SPACE_BETWEEN,
            "space-around" => AlignContent::SPACE_AROUND,
            "space-evenly" => AlignContent::SPACE_EVENLY,
            "stretch" => AlignContent::STRETCH,
            _ => AlignContent::START,
        };
    }
}

fn apply_size(node: &Node, src: &BTreeMap<String, String>, style: &mut Style) {
    let width = src.get("width").and_then(|v| parse::parse_length(v));
    let height = src.get("height").and_then(|v| parse::parse_length(v));
    style.size = Size {
        width: dimension(width.unwrap_or(CssLength::Auto)),
        height: dimension(height.unwrap_or(CssLength::Auto)),
    };

    let min_width = src.get("min-width").and_then(|v| parse::parse_length(v));
    let min_height = src.get("min-height").and_then(|v| parse::parse_length(v));
    style.min_size = Size {
        width: length_auto(min_width.unwrap_or(CssLength::Auto)),
        height: length_auto(min_height.unwrap_or(CssLength::Auto)),
    };

    let max_width = src.get("max-width").and_then(|v| parse::parse_length(v));
    let max_height = src.get("max-height").and_then(|v| parse::parse_length(v));
    style.max_size = Size {
        width: length_auto(max_width.unwrap_or(CssLength::Auto)),
        height: length_auto(max_height.unwrap_or(CssLength::Auto)),
    };

    // A text leaf is sized by its content; giving it the parent's width would
    // turn it into a fixed-size box and break the line breaking.
    if node.is_text() {
        style.size = Size { width: Dimension::auto(), height: Dimension::auto() };
    }
}

fn apply_spacing(_node: &Node, src: &BTreeMap<String, String>, style: &mut Style) {
    let margin = |side: &str| {
        src.get(&format!("margin-{side}"))
            .and_then(|v| parse::parse_length(v))
            .unwrap_or(CssLength::Px(0.0))
    };
    style.margin = Rect {
        left: length_auto(margin("left")),
        right: length_auto(margin("right")),
        top: length_auto(margin("top")),
        bottom: length_auto(margin("bottom")),
    };

    let padding = |side: &str| {
        src.get(&format!("padding-{side}"))
            .and_then(|v| parse::parse_length(v))
            .unwrap_or(CssLength::Px(0.0))
    };
    style.padding = Rect {
        left: length_percentage(padding("left")),
        right: length_percentage(padding("right")),
        top: length_percentage(padding("top")),
        bottom: length_percentage(padding("bottom")),
    };

    let border = |side: &str| {
        src.get(&format!("border-{side}-width"))
            .and_then(|v| parse::parse_length(v))
            .unwrap_or(CssLength::Px(0.0))
    };
    style.border = Rect {
        left: length_percentage(border("left")),
        right: length_percentage(border("right")),
        top: length_percentage(border("top")),
        bottom: length_percentage(border("bottom")),
    };

    let gap = src.get("gap").and_then(|v| parse::parse_length(v)).unwrap_or(CssLength::Px(0.0));
    let row_gap = src
        .get("row-gap")
        .and_then(|v| parse::parse_length(v))
        .unwrap_or(gap);
    let column_gap = src
        .get("column-gap")
        .and_then(|v| parse::parse_length(v))
        .unwrap_or(gap);
    style.gap = Size {
        width: length_percentage(column_gap),
        height: length_percentage(row_gap),
    };
}

fn apply_grid(_node: &Node, src: &BTreeMap<String, String>, style: &mut Style) {
    if style.display != Display::Grid {
        return;
    }
    if let Some(columns) = src.get("grid-template-columns").and_then(|v| parse::parse_track_list(v)) {
        for track in columns {
            style.grid_template_columns.push(grid_track(track).into());
        }
    }
    if let Some(rows) = src.get("grid-template-rows").and_then(|v| parse::parse_track_list(v)) {
        for track in rows {
            style.grid_template_rows.push(grid_track(track).into());
        }
    }
}

fn grid_track(track: Track) -> TrackSizingFunction {
    match track {
        Track::Fixed(px) => TrackSizingFunction::from_length(px),
        Track::Fraction(fr) => TrackSizingFunction::from_fr(fr),
        Track::Auto => taffy::style_helpers::auto(),
        Track::MinContent => taffy::style_helpers::min_content(),
        Track::MaxContent => taffy::style_helpers::max_content(),
    }
}

fn dimension(length: CssLength) -> Dimension {
    match length {
        CssLength::Auto => Dimension::auto(),
        CssLength::None => Dimension::auto(),
        CssLength::Px(px) => Dimension::length(px),
        CssLength::Percent(p) => Dimension::percent(p / 100.0),
    }
}

fn length_auto(length: CssLength) -> LengthPercentageAuto {
    match length {
        CssLength::Auto => LengthPercentageAuto::auto(),
        CssLength::None => LengthPercentageAuto::auto(),
        CssLength::Px(px) => LengthPercentageAuto::length(px),
        CssLength::Percent(p) => LengthPercentageAuto::percent(p / 100.0),
    }
}

fn length_percentage(length: CssLength) -> LengthPercentage {
    match length {
        CssLength::Auto | CssLength::None => LengthPercentage::length(0.0),
        CssLength::Px(px) => LengthPercentage::length(px),
        CssLength::Percent(p) => LengthPercentage::percent(p / 100.0),
    }
}

fn align_items(value: &str) -> AlignItems {
    match value {
        "flex-start" => AlignItems::FLEX_START,
        "start" => AlignItems::START,
        "center" => AlignItems::CENTER,
        "flex-end" => AlignItems::FLEX_END,
        "end" => AlignItems::END,
        "baseline" => AlignItems::BASELINE,
        "stretch" => AlignItems::STRETCH,
        // `space-*` is an align-content value, not an align-items one.
        _ => AlignItems::NORMAL,
    }
}

/// Builds the Taffy tree for one IR subtree.
///
/// Returns the Taffy node id of the subtree root plus the mapping from IR node
/// id to Taffy node id (document order).
pub fn build(
    tree: &mut TaffyTree<MeasureContext>,
    node: &Node,
    inherited: &BTreeMap<String, String>,
    mapping: &mut Vec<(i64, taffy::tree::NodeId)>,
) -> taffy::tree::NodeId {
    let style = style_for(node, inherited);

    // Children are built first so the parent can adopt them.
    let mut child_ids = Vec::with_capacity(node.children.len());
    let mut child_inherited = inherited.clone();
    if !node.style.is_empty() {
        for key in INHERITED_PROPERTIES {
            if let Some(value) = node.style.get(*key) {
                child_inherited.insert((*key).to_string(), value.clone());
            }
        }
    }

    for child in &node.children {
        child_ids.push(build(tree, child, &child_inherited, mapping));
    }

    let context = MeasureContext::for_node(node);
    let taffy_id = if child_ids.is_empty() {
        tree.new_leaf_with_context(style, context).expect("taffy leaf")
    } else {
        tree.new_with_children(style, &child_ids).expect("taffy node")
    };
    mapping.push((node.id, taffy_id));
    taffy_id
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ir::Rect;

    fn node(style: &[(&str, &str)]) -> Node {
        let mut map = BTreeMap::new();
        for (k, v) in style {
            map.insert((*k).to_string(), (*v).to_string());
        }
        Node {
            id: 1,
            node_type: "box".into(),
            tag: None,
            text: None,
            src: None,
            style: map,
            rect: Rect::default(),
            runs: Vec::new(),
            children: Vec::new(),
        }
    }

    #[test]
    fn maps_display_and_flex() {
        let n = node(&[("display", "flex"), ("flex-direction", "column"), ("gap", "8px")]);
        let s = style_for(&n, &BTreeMap::new());
        assert_eq!(s.display, Display::Flex);
        assert_eq!(s.flex_direction, FlexDirection::Column);
    }

    #[test]
    fn text_leaf_does_not_inherit_geometry() {
        let parent = node(&[("width", "720px"), ("height", "489px"), ("font-size", "22px")]);
        let mut inherited = BTreeMap::new();
        for key in INHERITED_PROPERTIES {
            if let Some(value) = parent.style.get(*key) {
                inherited.insert((*key).to_string(), value.clone());
            }
        }
        let mut child = node(&[]);
        child.node_type = "text".into();
        let s = style_for(&child, &inherited);
        assert_eq!(s.size.width, Dimension::auto());
        assert_eq!(s.size.height, Dimension::auto());
    }

    #[test]
    fn absolute_position_degrades_to_relative() {
        let n = node(&[("position", "absolute")]);
        let s = style_for(&n, &BTreeMap::new());
        assert_eq!(s.position, Position::Relative);
    }
}
