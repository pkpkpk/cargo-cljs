
// '#[no_mangle]' tells rustc to preserve the function name
//
// 'pub extern "C"' tells rustc to make a public function
// that follows the C calling convention (which the wasm spec follows).
//
// When this module is compiled, externed functions will be exposed to
// javascript by their string names in the Module.instance.exports object
#[no_mangle]
pub extern "C" fn get_42() -> f64 {
    42.0
}

// the arg is i32 but javascript Numbers are doubles.
// Your number will be coerced to an int and truncated
//   Mod.add_42(0) //=> 42
//   Mod.add_42(Math.PI) //=> 45
// A arg that causes x + 42 to exceed i32::MAX_VALUE will simply wrap
//   NOTE: this seems to change with optimizations levels.
//     - On a debug build It may throw a wasm "unreachable" error.
//     - You shouldn't do stuff like this anyways -P
//   Mod.add_42(2147483647) //=> -2147483607
#[no_mangle]
pub extern "C" fn add_42(x: i32) -> i32 {
    x + 42
}

// javascript doubles cannot accomodate i64s
// calling from js will throw an 'invalid type' error
#[no_mangle]
pub extern "C" fn unsafe_long() -> i64 {
    42
}

// If you want to use richer types, you need to communicate via pointers on the wasm memory module

use std::ffi::{CString, CStr};
use std::os::raw::{c_char, c_void};

// Rust strings in short:
//   'str' --> a constant array of utf8 chars (think string literal)
//   'String' --> heap allocated Vec of utf8 chars (think string-buffer)
// Both types are guaranteed to contain valid utf8 and rust will not let you
// break that without doing unsafe stuff
//
// Here we are importing the CString type so we can produce a null-terminated string.
// We need the null terminator on the javascript side to know when to stop reading
// from memory. When called we are going to pass a pointer (*mut c_char) to the run
// of c_chars. Javascript will take this pointer, collect each byte until the null
// byte, and convert those bytes to a javascript string with the TextDecoder
#[no_mangle]
pub extern "C" fn hola() -> *mut c_char {
    CString::new("hola").unwrap().into_raw()
}

// This time we return a *const c_char. Compare the docs for CString::as_ptr() vs CString::into_raw()
//    https://doc.rust-lang.org/std/ffi/struct.CString.html#method.as_ptr
//    https://doc.rust-lang.org/std/ffi/struct.CString.html#method.into_raw
// into_raw() says it 'Consumes the CString and transfers ownership of the string to a C caller'.
//   - This mean rust will disregard that slab of memory until it is returned to it with a from_raw
//     call.
// Compare this to as_ptr() which states: 'The returned pointer will be valid for as long as self'.
//   - 'self' is the bonjour CString we create. since we do not transfer ownership of the string to
//     the caller, when the function scope exits rust will 'drop' the string and immediately erase
//     the string's allocated memory.
//   - Our caller then receives a pointer to nothing, causing undefined behavior. If that memory
//     remains empty, javascript will read off an empty string. If it gets filled in, it may
//     keep reading bytes until whenever the next null byte occurs
//   - This isn't to say you couldn't use as_ptr() successfully, you just have to understand rust's
//     ownership rules. Most of the dangerous areas will be here at the FFI borders of the system;
//     Rustc wouldn't let your module compile if you tried to do this internally.
#[no_mangle]
pub extern "C" fn bonjour() -> *const c_char {
    CString::new("bonjour").unwrap().as_ptr()
}

