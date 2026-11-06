//! CSS value parsing for the values the compiler baked into the IR.
//!
//! The IR stores *resolved* values (`800px`, `50%`, `auto`, `flex-end`), so
//! there is no cascade and no `var()` to resolve here. The only thing this
//! module does is turn those strings into the enums Taffy wants.

/// A CSS length as it appears in a resolved computed style.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum CssLength {
    Auto,
    None,
    Px(f32),
    Percent(f32),
}

impl CssLength {
    pub fn is_auto(&self) -> bool {
        matches!(self, CssLength::Auto)
    }
}

/// Parses a resolved CSS length.
///
/// Accepts `auto`, `none`, `<number>px`, `<number>%` and bare `<number>` (which
/// CSS treats as a zero-pixel length in the context of `0`).
pub fn parse_length(value: &str) -> Option<CssLength> {
    let v = value.trim();
    if v.is_empty() {
        return None;
    }
    if v.eq_ignore_ascii_case("auto") {
        return Some(CssLength::Auto);
    }
    if v.eq_ignore_ascii_case("none") {
        return Some(CssLength::None);
    }
    if let Some(rest) = v.strip_suffix('%') {
        return rest.trim().parse::<f32>().ok().map(CssLength::Percent);
    }
    if let Some(rest) = v.strip_suffix("px") {
        return rest.trim().parse::<f32>().ok().map(CssLength::Px);
    }
    if let Some(rest) = v.strip_suffix("em") {
        // The IR never stores em, but a template could resolve to it when the
        // root font size is expressed in em. Fall back to the raw number.
        return rest.trim().parse::<f32>().ok().map(CssLength::Px);
    }
    if let Ok(n) = v.parse::<f32>() {
        return Some(CssLength::Px(n));
    }
    None
}

/// Parses a resolved number (`flex-grow: 0`, `order: 2`).
pub fn parse_number(value: &str) -> Option<f32> {
    value.trim().parse::<f32>().ok()
}

/// Parses a resolved number into an integer, rounding towards zero.
pub fn parse_int(value: &str) -> Option<i32> {
    parse_number(value).map(|n| n.round() as i32)
}

fn keyword(value: &str) -> &str {
    value.trim()
}

pub fn parse_display(value: &str) -> Option<&'static str> {
    match keyword(value) {
        "none" => Some("none"),
        "block" => Some("block"),
        "inline" => Some("inline"),
        "inline-block" => Some("inline-block"),
        "flex" => Some("flex"),
        "inline-flex" => Some("inline-flex"),
        "grid" => Some("grid"),
        "inline-grid" => Some("inline-grid"),
        _ => None,
    }
}

pub fn parse_flex_direction(value: &str) -> Option<&'static str> {
    match keyword(value) {
        "row" => Some("row"),
        "row-reverse" => Some("row-reverse"),
        "column" => Some("column"),
        "column-reverse" => Some("column-reverse"),
        _ => None,
    }
}

pub fn parse_flex_wrap(value: &str) -> Option<&'static str> {
    match keyword(value) {
        "nowrap" => Some("nowrap"),
        "wrap" => Some("wrap"),
        "wrap-reverse" => Some("wrap-reverse"),
        _ => None,
    }
}

pub fn parse_align_items(value: &str) -> Option<&'static str> {
    match keyword(value) {
        "flex-start" => Some("flex-start"),
        "center" => Some("center"),
        "flex-end" => Some("flex-end"),
        "baseline" => Some("baseline"),
        "stretch" => Some("stretch"),
        "space-between" => Some("space-between"),
        "space-around" => Some("space-around"),
        "space-evenly" => Some("space-evenly"),
        "start" => Some("start"),
        "end" => Some("end"),
        "normal" => Some("normal"),
        "auto" => Some("auto"),
        _ => None,
    }
}

pub fn parse_align_self(value: &str) -> Option<&'static str> {
    parse_align_items(value)
}

pub fn parse_justify_content(value: &str) -> Option<&'static str> {
    match keyword(value) {
        "flex-start" => Some("flex-start"),
        "center" => Some("center"),
        "flex-end" => Some("flex-end"),
        "space-between" => Some("space-between"),
        "space-around" => Some("space-around"),
        "space-evenly" => Some("space-evenly"),
        "start" => Some("start"),
        "end" => Some("end"),
        "left" => Some("left"),
        "right" => Some("right"),
        "normal" => Some("normal"),
        "stretch" => Some("stretch"),
        _ => None,
    }
}

pub fn parse_position(value: &str) -> Option<&'static str> {
    match keyword(value) {
        "static" => Some("static"),
        "relative" => Some("relative"),
        "absolute" => Some("absolute"),
        "fixed" => Some("fixed"),
        "sticky" => Some("sticky"),
        _ => None,
    }
}

pub fn parse_box_sizing(value: &str) -> Option<&'static str> {
    match keyword(value) {
        "content-box" => Some("content-box"),
        "border-box" => Some("border-box"),
        _ => None,
    }
}

pub fn parse_overflow(value: &str) -> Option<&'static str> {
    match keyword(value) {
        "visible" => Some("visible"),
        "hidden" => Some("hidden"),
        "clip" => Some("clip"),
        "scroll" => Some("scroll"),
        "auto" => Some("auto"),
        _ => None,
    }
}

/// Splits a `grid-template-columns` value into its track list.
///
/// Supports the shapes that show up in real templates: a plain px track list
/// (`120px 1fr 80px`), a single `repeat(N, X)` and the `none` / `auto`
/// keywords. Anything else yields `None` so the caller can fall back to auto
/// tracks instead of producing a wrong layout.
pub fn parse_track_list(value: &str) -> Option<Vec<Track>> {
    let v = keyword(value);
    if v.is_empty() || v.eq_ignore_ascii_case("none") {
        return None;
    }
    if let Some(rest) = strip_repeat(v) {
        let (count, track) = rest;
        let count = count as usize;
        let single = parse_single_track(&track)?;
        return Some(vec![single; count]);
    }
    let parts: Vec<&str> = v.split_whitespace().collect();
    if parts.is_empty() {
        return None;
    }
    let mut out = Vec::with_capacity(parts.len());
    for part in parts {
        out.push(parse_single_track(part)?);
    }
    Some(out)
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Track {
    Fixed(f32),
    Fraction(f32),
    Auto,
    MinContent,
    MaxContent,
}

fn parse_single_track(value: &str) -> Option<Track> {
    let v = keyword(value);
    if v.eq_ignore_ascii_case("auto") {
        return Some(Track::Auto);
    }
    if v.eq_ignore_ascii_case("min-content") {
        return Some(Track::MinContent);
    }
    if v.eq_ignore_ascii_case("max-content") {
        return Some(Track::MaxContent);
    }
    if let Some(rest) = v.strip_suffix("fr") {
        return rest.trim().parse::<f32>().ok().map(Track::Fraction);
    }
    if let Some(rest) = v.strip_suffix("px") {
        return rest.trim().parse::<f32>().ok().map(Track::Fixed);
    }
    None
}

/// `repeat(3, 1fr)` -> `Some((3, "1fr"))`.
fn strip_repeat(value: &str) -> Option<(u32, String)> {
    let rest = value.strip_prefix("repeat(")?;
    let inner = rest.strip_suffix(')')?;
    let (count, track) = inner.split_once(',')?;
    let count = count.trim().parse::<u32>().ok()?;
    Some((count, track.trim().to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_lengths() {
        assert_eq!(parse_length("800px"), Some(CssLength::Px(800.0)));
        assert_eq!(parse_length("19.1875px"), Some(CssLength::Px(19.1875)));
        assert_eq!(parse_length("50%"), Some(CssLength::Percent(50.0)));
        assert_eq!(parse_length("auto"), Some(CssLength::Auto));
        assert_eq!(parse_length("none"), Some(CssLength::None));
        assert_eq!(parse_length("0"), Some(CssLength::Px(0.0)));
        assert_eq!(parse_length(""), None);
        assert_eq!(parse_length("banana"), None);
    }

    #[test]
    fn parses_track_lists() {
        assert_eq!(
            parse_track_list("120px 1fr 80px"),
            Some(vec![Track::Fixed(120.0), Track::Fraction(1.0), Track::Fixed(80.0)])
        );
        assert_eq!(
            parse_track_list("repeat(3, 1fr)"),
            Some(vec![Track::Fraction(1.0), Track::Fraction(1.0), Track::Fraction(1.0)])
        );
        assert_eq!(parse_track_list("none"), None);
        assert_eq!(parse_track_list("mystery"), None);
    }

    #[test]
    fn parses_keywords() {
        assert_eq!(parse_display("flex"), Some("flex"));
        assert_eq!(parse_flex_direction("column"), Some("column"));
        assert_eq!(parse_align_items("center"), Some("center"));
        assert_eq!(parse_justify_content("space-between"), Some("space-between"));
        assert_eq!(parse_box_sizing("border-box"), Some("border-box"));
        assert_eq!(parse_position("relative"), Some("relative"));
    }
}
