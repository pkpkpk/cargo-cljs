# cargo-cljs


### Setup

1) [Install Cargo](https://doc.rust-lang.org/cargo/getting-started/installation.html)

2) Install the wasm toolchain

``` bash
$ rustup default nightly
$ rustup update
$ rustup component add wasm32-unknown-unknown --toolchain nightly
$ cargo install --git https://github.com/alexcrichton/wasm-gc
```

Run `$rustup update` periodically to update the toolchain.


## TODO
  + Using WASM
    + basics
      - ~~exports~~
      - imports
      - pointers, memory
    + Node
    + Browser
    + wasm-bindgen
    + error handling, debugging, source-maps
  + project templates
    - CLI?
  + Using Rust
    + ChildProcs
      - serde-json
    + FFI
    + Neon
  + independent browser helpers
  + fw-like push to client
  + api namespace
  + spec build configs
  + fill out cargo opts, tests
  + fressian
  + nsfw
  + explore packaging
