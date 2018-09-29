# cargo-cljs


### Setup Rust

1) [Install Cargo](https://doc.rust-lang.org/cargo/getting-started/installation.html)

2) Install the wasm toolchain

``` bash
$ rustup default nightly
$ rustup update
$ rustup component add wasm32-unknown-unknown --toolchain nightly
$ cargo install --git https://github.com/alexcrichton/wasm-gc
```

Run `$rustup update` periodically to update the toolchain.


### Basic Usage (nodejs)

```clojure
(ns your-app.core
  (:require [cljs.core.async :refer [take!]]
            [cargo.api :as cargo]))

(def cfg
  {:project-name "your-project"
   :dir "path/to/your-project" ;; must contain Cargo.toml
   :target :wasm ;; thats it!
   :verbose true ;; toggle status messages
   :release? true})

(defn build []
  (take! (api/build-wasm cfg) ;<-- all build fns return promise chans
    (fn [[err {:keys [buffer]}]] ;<-- channels yield [?err ?ok]
      (if err
         (api/report-error err) ;<---just a logging helper
         (send-module-somewhere buffer))))
```

This library works by converting your configuration map into an appropriate cargo shell command+env, spawning out a cargo process, and simplifying the output into a map. If the command fails, the map will yield to your promise-chan as:

```clojure
;; To understand output maps, please read the docstring for `cargo/report.cljs`
[{
  :type :some-error
  :warnings [{}..] ;<-- you are failing lint checks
  :errors [{}..] ;<-- compilation failed
  :stdout [...] ;<-- everything not recognized as a compiler message (ie from your bin if any)
  :stderr [...] ;<-- human readable status from cargo + from your bin if any
  }]
```

Each build command is asynchronous. and there may be several async commands chained together before they reach the user. If any one of them fails, the point of failure will include a `:type :description` entry and will short circuit back to you as `[{:type ...}]`.

If the command succeeds, the result will resemble:

```clojure
[nil {:warnings [...]
      :stdout [...]}]
```
Notice the warnings key! Even if your build succeeded, there may still be warnings. Don't forget to fix them.

<hr>

#### There are 5 basic commands...
  + `(cargo.api/cargo-build cfg)`
    - builds the artifact described by the config, nothing more
  + `(cargo.api/cargo-run cfg)`
    - builds and runs main.rs
    - pass args to your bin with `:bin-args ""`
    - your bin's output if any should show up in stdout/stderr
    - not applicable to wasm
  + `(cargo.api/cargo-test cfg)`
    - runs cargo's built-in test runner
    - [no structured output](https://github.com/rust-lang/rfcs/pull/2318)
    - not applicable to wasm
  + `(cargo.api/build-wasm cfg)`
    - compile a wasm project and return the binary in a nodejs buffer
    - automatically runs wasm-gc
  + `(cargo.api/build-wasm-local cfg ?importOptions)`
    - same as build-wasm but instantiates it local to the build process, returning its instance

#### ...and a bunch of repl helpers
  + `(cargo.api/report-error err)`
    - accepts an error result from any build step and tries to log it in a sensible way
  + `(cargo.api/last-result)`
    - every build result is stored in an atom for easy retrieval and inspection at the repl
  + `(cargo.api/get-stdout)`
    - get stdout from last build
  + `(cargo.api/get-stderr)`
    - get stderr from last build
  + `(cargo.api/get-warnings)`
    - get warnings from last build
  + `(cargo.api/get-errors)`
    - get errors from last build
  + `(cargo.api/render-warnings ?warnings)`
    - log the ascii art from the warnings
    - if omitted, will default to `(get-warnings)`
  + `(cargo.api/render-errors ?errors)`
    - log the ascii art from the errors
    - if omitted, will default to `(get-errors)`

#### Logging

The default logging scheme found in `cargo.util` is intended to work with [chrome devtools](https://nodejs.org/en/docs/guides/debugging-getting-started/#chrome-devtools-55) rigged up to your nodejs process. It is worthwhile to take the time to set this up, or even use electron. There are many chrome extensions for doing this automatically: be wary of them! A little convenience is not worth the risk.

Each function just adds some simple coloring cues. You can override every log fn with `(cargo.util/override-all! f)` or individually with `(cargo.util/override-map! {...})` if you dislike them.


<hr>

### More Config Options

```clojure
{
 :target :wasm ;; if omitted uses system native default. TODO other targets
 :release true ;; TODO there are configurable opt levels too
 :cargo-verbose false ;; not terribly useful
 :bin-args "a string that is passed to your binary ie give it json"
 :features ["passed" "to" "--features" "flag"]
 :env {} ;; merged with env created by rest of cfg, will override clashes
 :rustflags {:cfg ["some_cfg_opt"]
             :allow [:dead_code
                     :unused_imports
                     :non_snake_case
                     :unused_parens
                     :unused_variables
                     :non_camel_case_types]}}
```
<hr>

### Please raise an issue if:
  + You get an error and you find it obscure or unhelpful
  + Anything is confusing or poorly documented
  + There is something preventing you from incorporating this into your workflow
  + A well formed json message from cargo ends up in the wrong place
  + There is some cargo/rustc  arg you want to use that is not working
  + You have some ideas to make things even better!

Writing rust is hard enough without wrangling the angry elder god that is rustc. Let's make it as easy as possible!

<hr>

## TODO
  + spec build configs
    - fill out cargo opts
      + llvm args
        - support importing memory (llvm flag)
    - test projects
  + other cargo commands (where they make sense)
    + wasm snip
    + run individual tests
  + structured test output
  + wee-alloc
  + project templates
    - CLI/main?
  + browser helpers
    - reload on success?
  + packaging
  + nsfw
  + Examples
    + Wasm
      + basics
      + wasm-bindgen
      + fressian
    + spawns
      - serde-json
    + FFI
    + Neon


