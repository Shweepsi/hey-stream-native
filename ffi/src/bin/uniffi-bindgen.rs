//! Générateur des liaisons :
//! `cargo run -p heystream-ffi --features bindgen --bin uniffi-bindgen -- generate …`
fn main() {
    uniffi::uniffi_bindgen_main()
}
