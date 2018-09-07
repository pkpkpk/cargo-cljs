#[macro_use]
extern crate maplit; // macros for map literals

use std::collections::{HashMap, BTreeMap};

#[macro_use]
extern crate serde_derive;
extern crate serde;
extern crate serde_fressian;

use serde::Serialize;
use serde_fressian::ser::{Serializer};

#[no_mangle]
pub extern "C" fn hello() -> *const u8 {
    let data = vec![["hello", "from", "wasm!"], ["isn't", "this", "exciting?!"]];
    let mut fressian_writer = Serializer::from_vec(Vec::new());
    data.serialize(&mut fressian_writer).unwrap();
    fressian_writer.write_footer(); //<-- you have to write this or js read oob
    fressian_writer.get_ref().as_ptr()
}

#[no_mangle]
pub extern "C" fn homo_maps() -> *const u8 {
    let mut fressian_writer = Serializer::from_vec(Vec::new());

    let map0: BTreeMap<String,i64> = btreemap!{
                                        "a".to_string() => 0,
                                        "b".to_string() => 1
                                    };

    map0.serialize(&mut fressian_writer).unwrap();

    // HashMap will let anything be a key as long as it implements Hash
    //... BTreeMap's implement hash so we can use that as a key, but HashMap's themselves don't
    let map1: HashMap<BTreeMap<String,i64>,Vec<i64>> = hashmap!{ map0 => vec![0,1,2]};

    map1.serialize(&mut fressian_writer).unwrap();
    fressian_writer.write_footer(); //<-- you have to write this or js read oob
    fressian_writer.get_ref().as_ptr()
}

