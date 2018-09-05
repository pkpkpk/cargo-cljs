// #![feature(wasm_import_memory)]
// #![wasm_import_memory]


// '#[no_mangle]' tells rustc to preserve the function name
//
// 'pub extern "C"' tells rustc to make a public function
// that follows the C calling convention (with the wasm spec follows).
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

// use std::mem;
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
// byte, and convert those bytes to a javscript string with the TextDecoder
#[no_mangle]
pub extern "C" fn hola() -> *mut c_char {
    CString::new("hola").unwrap().into_raw()
}



