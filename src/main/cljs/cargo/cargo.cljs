(ns cargo.cargo
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cargo.macros :refer [with-promise] :as mac])
  (:require [cljs.core.async :as casync :refer [close! put! take! promise-chan]]
            [clojure.string :as string]
            [cljs-node-io.proc :as proc]
            [cljs-node-io.async :as nasync :refer [go-proc]]
            [cljs-node-io.core :as io :refer [aslurp slurp spit]]
            [cljs-node-io.fs :as fs]
            [cargo.spawn :as spawn]
            [cargo.util :as util]
            [cargo.report :as report]))

(def path (js/require "path"))

;; bindings will not survive ticks so must set!
(def ^:dynamic *verbose* true)

(defn verbose-state
  "helper for propagating verbose state across async chains"
  [cfg]
  (if (boolean? (get cfg :verbose))
    (get cfg :verbose)
    *verbose*))

(defn project-path [{:keys [project-name dir] :as cfg}]
  (or dir (path.join "rust" project-name)))

(defn src-path [{:keys [project-name] :as cfg}]
  (path.join (project-path cfg) "src"))

(defn dot-wasm-file [{:keys [project-name] :as cfg}]
  (str (string/replace project-name "-" "_") ".wasm"))

(defn wasm-release-dir [{:keys [project-name] :as cfg}]
  (path.join (project-path cfg) "target" "wasm32-unknown-unknown" "release"))

(defn wasm-release-path [{:keys [project-name] :as cfg}]
  (path.join (wasm-release-dir cfg) (dot-wasm-file cfg)))

(defn wasm-debug-dir [{:keys [project-name] :as cfg}]
  (path.join (project-path cfg) "target" "wasm32-unknown-unknown" "debug"))

(defn wasm-debug-path [{:keys [project-name] :as cfg}]
  (path.join (wasm-debug-dir cfg) (dot-wasm-file cfg)))

(defn clean-project [{:keys [project-name] :as cfg}]
  (let [p (path.join (project-path cfg) "target")]
    (when (fs/fexists? p)
      (fs/rm-r p))))

(defn target->arg [target]
  (condp = target
    :wasm "--target=wasm32-unknown-unknown"
    nil))

(defn collect-build
  "Sorts spawn output into failed and result, returning a promise-chan yielding
   nodeback vector:

   if error
      => [{:type :error-type
           :warnings [{}..]
           :errors [{}..]
           :stdout [...]
           :stderr [...]}]
   else
      => [nil {:warnings [{}...]
               :stdout [<-- your app output--> ]
               :stderr ['status' 'messages']}]"
  [spawn-chan]
  (with-promise out
    (take! spawn-chan
     (fn [[spawn-error [exit-code stdout stderr :as res]]]
       (if (some? spawn-error)
         (put! out [{:type :spawn-error :value spawn-error}])
         (let [base (-> (report/sort-cargo-stdout stdout)
                        (assoc :stderr stderr))]
           (if (zero? exit-code)
             (do
               (when (and (verbose-state cfg) (not-empty (get base :warnings)))
                 (util/warn "found warnings"))
               (put! out [nil base]))
             (let [key (cond
                         (string/includes? (peek stderr) "Running")
                         :cargo/run-failure

                         (some #(string/includes? % "fatal runtime") stderr)
                         :cargo/fatal-runtime

                         (string/includes? (peek stderr) "test failed")
                         :cargo/test-failure

                         (string/includes? (peek stderr) "could not find `Cargo.toml`")
                         :cargo/missing-toml

                         (some-> (first stderr) (string/includes?  "parse manifest"))
                         :cargo/bad-toml

                         :else
                         :cargo/compilation-failure)]
               (put! out [(assoc base :type key)])))))))))

(def cargo-arg->str
  (let [sb (goog.string.StringBuffer.)]
    (fn [arg]
      (.append sb "--")
      (.append sb (name arg))
      (let [s (.toString sb)]
        (.clear sb)
        s))))


(defn cfg->build-args [{:keys [target release? features
                               bin-args cargo-args rustc-args cargo-verbose] :as cfg}]
  (when bin-args (assert (string? bin-args)))
  (when features (assert (or (string? features) (vector? features))))
  (let [args (cond-> [(target->arg target)
                      (when release? "--release")
                      "--message-format=json"
                      ; "--lib"
                      ; "--test" "NAME"
                      ]
                cargo-verbose (conj "--verbose")
                features (conj "--features" (if (string? features)
                                              features
                                              (string/join " " features)))
                rustc-args (into rustc-args)
                bin-args (conj "--" bin-args))]
    (filterv some? args)))

(def ^{:doc "combines a flag with each kw arg as a string. '-A unused_parens ...'"}
  flagged-kw-args
  (let [sb (goog.string.StringBuffer.)]
    (fn [flag kws]
      (doseq [k kws]
        (.append sb flag)
        (.append sb " ")
        (.append sb (name k))
        (.append sb " "))
      (let [s (.toString sb)]
        (.clear sb)
        s))))

;; per alexchrichton: importing memory is done now with a custom linker flag to LLD
;; rustc with `-C link-args=--import-memory`
; (when provide-memory? (assert (= target :wasm)))
;  :provide-memory?
;  :silent?

(defn cfg->rustflags [{ :as cfg}]
  (let [allow (flagged-kw-args "-A" (get-in cfg [:rustflags :allow]))
        rustc-cfg (flagged-kw-args "--cfg" (get-in cfg [:rustflags :cfg]))]
    (string/join " " [allow rustc-cfg])))

; "-C" "link-args=--import-memory"  llvm args?
; RUST_BACKTRACE env opt, values #{"1", "full"}

(defn spawn-cargo
  [cmd {:keys [project-name silent?] :as cfg}]
  (assert (#{"build" "run" "test"} cmd) (str "unsupport cargo cmd " cmd))
  (let [location (project-path cfg)]
    (assert (fs/fexists? location) (str "cannot compile `" project-name"`, location " location " does not exist."))
    (let [args (into [cmd] (cfg->build-args cfg))
          rustflags (cfg->rustflags cfg)
          opts {:cwd location
                :json->edn? true
                :silent? true
                :env (merge {"RUSTFLAGS" rustflags} (get cfg :env))}
          out (collect-build (spawn/collected-spawn "cargo" args opts))]
      (when (verbose-state cfg)
        (util/status (string/join " " (into ["$" "cargo"] args)))
        (util/status opts))
      out)))

(defn wasm-gc [{:keys [project-name release? build-dir] :as cfg}]
  (with-promise out
    (if-not (fs/fexists? build-dir)
      (put! out [{:type :wasm/path-missing-before-wasm-gc :path build-dir}])
      (let [filename (dot-wasm-file cfg)]
        (when (verbose-state cfg)
          (util/info (str  "running wasm-gc on " (path.join  build-dir filename))))
         ;;just overwriting for now until can compile to given name
        (take! (proc/aexec (string/join " " ["wasm-gc"  filename filename]) {:cwd build-dir})
          (fn [[err stdout stderr]]
            (if err
              (put! out [{:type :wasm/wasm-gc-failure :value err :stdout stdout :stderr stderr}])
              (if-not (fs/fexists? (get cfg :dot-wasm-path))
                (put! out [{:type :wasm/path-missing-after-wasm-gc}])
                (put! out [nil])))))))))

(defn p->ch
  "convert promise to nodeback style [?err ?data] yielding promise-chan"
  ([promise](p->ch promise (promise-chan)))
  ([promise c]
   (let []
      (.then promise
        (fn [value] (put! c [nil value]))
        (fn [reason](put! c [reason])))
     c)))

(defn init-module
  ([array-buffer](init-module array-buffer #js{}))
  ([array-buffer importOptions]
   (p->ch (js/WebAssembly.instantiate array-buffer importOptions)
          (promise-chan
           (map
            (fn [[e mod]]
              (if-not e
                [nil mod]
                [{:type :wasm/instantiation-failure :value e}])))))))

(defn config-wasm-paths [{:keys [release?] :as cfg}]
  (let [build-dir (if release?
                    (wasm-release-dir cfg)
                    (wasm-debug-dir cfg))
        dot-wasm-path  (if release?
                          (wasm-release-path cfg)
                          (wasm-debug-path cfg))]
    (assoc cfg
           :build-dir build-dir
           :dot-wasm-path dot-wasm-path)))

(defn wasm-gc-and-slurp
  "1. exec wasm-gc to shrink fat wasm file per the config
   2. slurp final wasm
   => pchan<[?err ?buffer]>"
  [cfg]
  (assert (= :wasm (get cfg :target)))
  (let [cfg (config-wasm-paths cfg)]
    (with-promise out
      (take! (wasm-gc cfg)
        (fn [[err :as res]]
          (if err (put! out res)
            (let [path (get cfg :dot-wasm-path)]
              (take! (aslurp path :encoding "")
                (fn [[ioerr buffer]]
                  (if ioerr
                    (put! out [{:type :wasm/slurp-module-failure :value ioerr}])
                    (do
                      (when (verbose-state cfg)
                        (util/success "wasm compilation success, returning compiled module"))
                      (put! out [nil buffer]))))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce last-result (atom nil))

(defn build!
  "we route everything through here to simplify storing build results in last-result"
  [cfg]
  (when-let [cmd (and (not= (get cfg :target) :wasm) (get cfg :cmd))]
    (assert (#{:test :run} cmd) "only support :target :wasm builds, '$cargo run', or '$cargo test'"))
  (with-promise out
    (-> (case (get cfg :cmd)
          :test (spawn-cargo "test" cfg)
          :run (spawn-cargo "run" cfg)
          (spawn-cargo "build" cfg))
     (take! #(put! out (reset! last-result %))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce watchers (atom {:project-name :watch-instance}))

;; replace with nsfw
(defn watch-project [{:keys [project-name] :as cfg}]
  (when-not (get @watchers project-name)
    (let [watch (fs/watch (src-path cfg) {:key project-name
                                          :buf-or-n (casync/sliding-buffer 1)})]
      (swap! watchers assoc project-name watch)
      watch)))

(defn autobuild!
  [{:keys [project-name] :as cfg} handler]
  (when-let [watch (watch-project cfg)]
    (go-proc watch
      (fn [[_ [event]]]
        (when (not= :close event)
          (take! (build! cfg) #(handler %)))))
    (take! (build! cfg) #(handler %))))

(defn cancel-autobuild! [{:keys [project-name] :as cfg}]
  (when-let [watch (watch-project cfg)]
    (when *verbose* (util/status "canceling " project-name  " autobuild"))
    (.close watch)
    (swap! watchers dissoc project-name)))


