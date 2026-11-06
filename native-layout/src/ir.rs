//! Card IR data model.
//!
//! Only the parts the layout kernel needs are modelled. The IR itself stays
//! the single source of truth: this crate never re-interprets HTML, it only
//! turns the resolved styles that the compiler baked in into Taffy styles.

use std::collections::BTreeMap;

use serde::{Deserialize, Deserializer};

/// IR protocol version this crate understands.
///
/// Must stay in sync with `docs/native-card-render/compiler/validate.mjs`
/// (`VERSION`) and `schema/card_ir.schema.json`.
pub const SUPPORTED_IR_VERSION: &str = "0.1.0";

/// Reads a value that may be a JSON string or a JSON number as a string.
///
/// The compiler writes `card_id` as a string (Anki ids are 64 bit and do not
/// survive a round trip through a JavaScript number), but older fixtures use
/// a plain number.
fn flexible_string<'de, D>(deserializer: D) -> Result<String, D::Error>
where
    D: Deserializer<'de>,
{
    let value = serde_json::Value::deserialize(deserializer)?;
    Ok(match value {
        serde_json::Value::String(s) => s,
        serde_json::Value::Number(n) => n.to_string(),
        _ => String::new(),
    })
}

#[derive(Debug, Clone, Deserialize)]
pub struct CardIr {
    pub ir_version: String,
    #[serde(default, deserialize_with = "flexible_string")]
    pub card_id: String,
    #[serde(default)]
    pub notetype: String,
    /// `bounds` (v0: the compiler measured every rect) or `taffy` (v1: the
    /// runtime recomputes the layout).
    #[serde(default)]
    pub layout_mode: Option<String>,
    #[serde(default)]
    pub variants: Vec<String>,
    #[serde(default)]
    pub sides: BTreeMap<String, BTreeMap<String, Frame>>,
    #[serde(default)]
    pub warnings: Vec<String>,
    #[serde(default)]
    pub unsupported: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Frame {
    #[serde(default)]
    pub width: f32,
    #[serde(default)]
    pub height: f32,
    pub root: Node,
    #[serde(default)]
    pub node_count: u32,
    #[serde(default)]
    pub text_run_count: u32,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Node {
    pub id: i64,
    #[serde(rename = "type")]
    pub node_type: String,
    #[serde(default)]
    pub tag: Option<String>,
    #[serde(default)]
    pub text: Option<String>,
    #[serde(default)]
    pub src: Option<String>,
    #[serde(default)]
    pub style: BTreeMap<String, String>,
    #[serde(default)]
    pub rect: Rect,
    #[serde(default)]
    pub runs: Vec<Run>,
    #[serde(default)]
    pub children: Vec<Node>,
}

#[derive(Debug, Clone, Copy, Default, Deserialize)]
pub struct Rect {
    #[serde(default)]
    pub x: f32,
    #[serde(default)]
    pub y: f32,
    #[serde(default)]
    pub w: f32,
    #[serde(default)]
    pub h: f32,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Run {
    #[serde(default)]
    pub text: String,
    #[serde(default)]
    pub rect: Rect,
}

impl Node {
    /// The node type as a stable enum-ish string. Unknown types are kept as
    /// the raw string so a new IR node type never breaks the kernel.
    pub fn is_text(&self) -> bool {
        self.node_type == "text"
    }

    pub fn is_leaf_content(&self) -> bool {
        matches!(
            self.node_type.as_str(),
            "text" | "image" | "svg" | "audio" | "input" | "break"
        )
    }

    /// Style lookup with the CSS inheritance rule the compiler relies on:
    /// a `text` node carries no style of its own and inherits from the nearest
    /// element ancestor.
    pub fn inherited_style<'a>(&'a self, ancestor: &'a BTreeMap<String, String>) -> BTreeMap<String, String> {
        if self.style.is_empty() {
            ancestor.clone()
        } else {
            self.style.clone()
        }
    }
}

/// Walks the node tree in document order, handing every node to `f`.
pub fn walk<'a, F: FnMut(&'a Node)>(root: &'a Node, f: &mut F) {
    f(root);
    for child in &root.children {
        walk(child, f);
    }
}

/// Collects the nodes of a frame in document order.
pub fn flatten(root: &Node) -> Vec<&Node> {
    let mut out = Vec::new();
    walk(root, &mut |n| out.push(n));
    out
}
