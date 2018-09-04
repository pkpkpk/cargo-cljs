
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
