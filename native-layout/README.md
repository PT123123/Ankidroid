# Android Card IR layout kernel

This crate packages the standalone Rust/Taffy kernel from
`anki-qt/native-layout` for the Android app. Keep `src/convert.rs`, `ir.rs`,
`measure.rs`, `parse.rs`, `lib.rs`, and the base Cargo dependencies in sync with
that crate when updating the shared layout contract. Android-specific JNI
exports live in `src/android_jni.rs`.

Gradle builds the `cdylib` for all four APK ABIs and packages it as
`libanki_native_layout.so`. `TaffyCardLayout` is the Kotlin boundary: it passes
Card IR JSON, a side, a variant, and a width, then receives node rectangles.
The UI must treat a missing library, incompatible IR, or layout error as a
rendering failure and retain the WebView fallback.

Android supplies a host measurer backed by `TextPaint` and `StaticLayout`, so
Taffy can size text with the font Android will actually draw and reflow text
when a card is narrower than its captured frame. IR v0 still does not carry
line boxes, so mixed inline formatting retains captured geometry or falls back
to WebView when the capture marks it unsupported.
