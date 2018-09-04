(ns cargo.cargo
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cargo.macros :refer [with-promise] :as mac])
  (:require [cljs.core.async :as casync :refer [close! put! take! alts! <! >! chan promise-chan]]
            [clojure.string :as string]
            [cljs-node-io.proc :as proc]
            [cljs-node-io.async :as nasync :refer [go-proc]]
            [cljs-node-io.core :as io :refer [aslurp slurp spit]]
            [cljs-node-io.fs :as fs]
            [cargo.spawn :as spawn]
            [cargo.util :as util]))

(def path (js/require "path"))

;; bindings will not survive ticks so must set!
(def ^:dynamic *verbose* true)

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
    (fs/rm-r p)))

(defn target->arg [target]
  (condp = target
    :wasm "--target=wasm32-unknown-unknown"
    nil))

(defn cargo-msg? [m]
  (or
   (some? (:message m))
   (some? (:reason m))))

(defn collect-build
  "Sorts spawn output into failed and result, returning a promise-chan yielding
   nodeback vector:

   if error
      => [{::type :error-type
           :stdout stdout
           :stderr stderr}]
   else
      => [nil {:value (peek stdout)
               :stdout Vec<stdout from your app (cargo run only), edn if applicable>
               :cargo/stdout Vec<compiler-messages>
               :stderr stderr}]"
  [spawn-chan]
  (with-promise out
    (take! spawn-chan
     (fn [[spawn-error res]]
       (if (some? spawn-error)
         (put! out [{::type :spawn-error :value spawn-error}])
         (let [[exit-code stdout stderr] res
               msgs (filterv cargo-msg? stdout)
               stdout (filterv (complement cargo-msg?) stdout)
               base {:stdout stdout
                     :stderr stderr
                     :cargo/stdout msgs}]
           (if (zero? exit-code)
             (do
               (when (and *verbose* (not (empty? stderr)))
                 (util/info (apply str stderr)))
               (put! out [nil (assoc base :value (peek stdout))]))
             (put! out
                   (cond
                     (string/includes? (peek stderr) "Running")
                     [(merge base {::type :cargo/run-failure
                                   :value msgs
                                   :cargo/stdout msgs})]

                     (some #(string/includes? % "fatal runtime") stderr)
                     [(merge base {::type :cargo/fatal-runtime
                                   :value msgs
                                   :cargo/stdout msgs})]

                     (string/includes? (peek stderr) "test failed")
                     [(merge base {::type :cargo/test-failure
                                   :value msgs
                                   :cargo/stdout msgs})]

                     (string/includes? (peek stderr) "could not find `Cargo.toml`")
                     [(merge base {::type :cargo/missing-toml
                                   :value msgs
                                   :cargo/stdout msgs})]

                     (some-> (first stderr) (string/includes?  "parse manifest"))
                     [(merge base {::type :cargo/bad-toml
                                   :value msgs
                                   :cargo/stdout msgs})]


                     :else
                     [(merge base {::type :cargo/compilation-failure
                                   :value msgs
                                   :cargo/stdout msgs})])))))))))

(def cargo-arg->str
  (let [sb (goog.string.StringBuffer.)]
    (fn [arg]
      (.append sb "--")
      (.append sb (name arg))
      (let [s (.toString sb)]
        (.clear sb)
        s))))


(defn cfg->build-args [{:keys [target release? features
                               bin-args cargo-args rustc-args verbose?] :as cfg}]
  (when bin-args (assert (string? bin-args)))
  (when features (assert (or (string? features) (vector? features))))
  (let [args (cond-> [(target->arg target)
                      (when release? "--release")
                      "--message-format=json"]
                verbose? (conj "--verbose")
                features (conj "--features" (if (string? features)
                                              features
                                              (string/join " " features)))
                rustc-args (into rustc-args)
                bin-args (conj "--" bin-args))]
    (filterv some? args)))

(def kws->args
  (let [sb (goog.string.StringBuffer.)]
    (fn [kws flag]
      (doseq [k kws]
        (.append sb flag)
        (.append sb " ")
        (.append sb (name k))
        (.append sb " "))
      (let [s (.toString sb)]
        (.clear sb)
        s))))

(defn allows->str
  [allows]
  (kws->args allows "-A"))

(defn cfgs->str
  [cs]
  (kws->args cs "--cfg"))

(defn cfg->rustflags
  [{:keys [allow cfg]}]
  {:post [string?]}
  (let [allow (allows->str allow)
        rustc-cfg (cfgs->str cfg)]
    (string/join " " (concat allow rustc-cfg))))

(defn build-rust-flags [{ :as cfg}]
  (let [allow (allows->str (get-in cfg [:rustflags :allow]))
        rustc-cfg (cfgs->str (get-in cfg [:rustflags :cfg]))]
    (str allow " " rustc-cfg)))

; RUST_BACKTRACE env opt, values #{"1", "full"}

(defn spawn-cargo
  "opts are merged with cfg to build cmd line opts."
  ([cmd cfg] (spawn-cargo cmd cfg nil))
  ([cmd {:keys [project-name] :as cfg} opts]
   (assert (#{"build" "run" "test"} cmd) (str "unsupport cargo cmd " cmd))
   (let [location (project-path cfg)]
     (assert (fs/fexists? location) (str "cannot compile `" project-name"`, location " location " does not exist."))
     (let [args (into [cmd] (cfg->build-args (merge cfg opts)))
           rustflags (build-rust-flags cfg)
           opts (merge {:cwd location
                        :json->edn? true
                        :silent? true
                        :env (merge {"RUSTFLAGS" rustflags} (get cfg :env))} opts)
           out (collect-build (spawn/collected-spawn "cargo" args opts))]
       (when *verbose* (util/status "$ " (string/join " " (into ["cargo"] args))))
       (when *verbose* (util/status (pr-str opts)))
       out))))

(defn cargo-build
  ([cfg] (cargo-build cfg nil))
  ([cfg opts] (spawn-cargo "build" cfg opts)))

(defn cargo-run
  "Builds and then runs src/bin/main.rs. Not applicable to wasm targets"
  ([cfg] (cargo-run cfg nil))
  ([cfg opts] (spawn-cargo "run" cfg opts)))

(defn cargo-test
  "`$cargo test` leaves alot to be desired:
     - It suppresses output during builds
     - it obscures logging during tests (supposedly theres a --nocapture flag but cant get it to work)
     - there is no structured (json) test result output."
  ([cfg] (cargo-test cfg nil))
  ([cfg opts] (spawn-cargo "test" cfg opts)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn wasm-gc [{:keys [project-name release? build-dir] :as cfg}]
  (with-promise out
    (if-not (fs/fexists? build-dir)
      (put! out [{::type :wasm/path-missing-before-wasm-gc :path build-dir}])
      (let [filename (dot-wasm-file cfg)]
        (when *verbose* (util/info (str  "running wasm-gc on " (path.join  build-dir filename))))
         ;;just overwriting for now until can compile to given name
        (take! (proc/aexec (string/join " " ["wasm-gc"  filename filename]) {:cwd build-dir})
          (fn [[err stdout stderr]]
            (if err
              (put! out [{::type :wasm/wasm-gc-failure :value err :stdout stdout :stderr stderr}])
              (if-not (fs/fexists? (get cfg :dot-wasm-path))
                (put! out [{::type :wasm/path-missing-after-wasm-gc}])
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
                [{::type :wasm/instantiation-failure :value e}])))))))

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

(defn build-wasm! ;=> pchan<[?err ?buffer]>
  "1. cargo to builds a fat wasm file
   2. exec wasm-gc to shrink fat wasm
   3. slurp final wasm"
  [{:keys [project-name] :as cfg}]
  (assert (and (map? cfg) (= :wasm (get cfg :target))))
  (when *verbose* (util/info "building wasm project" project-name))
  (let [cfg (config-wasm-paths cfg)]
    (with-promise out
      (take! (cargo-build cfg)
        (fn [[e data :as res]]
          (when *verbose* (util/status "cargo exit"))
          (if e (put! out res)
            (take! (wasm-gc cfg)
              (fn [[e :as res]]
                (if e (put! out res)
                  (do
                    (when *verbose* (util/status "wasm gc success"))
                    (let [path (get cfg :dot-wasm-path)]
                      (take! (aslurp path :encoding "")
                        (fn [[ioerr buffer]]
                          (if ioerr
                            (put! out [{::type :wasm/slurp-module-failure :value ioerr}])
                            (do
                              (when *verbose* (util/success "wasm compilation success, returning compiled module"))
                              (put! out [nil buffer]))))))))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce last-result (atom nil))

(defn build! [cfg]
  (when-let [cmd (and (not= (get cfg :target) :wasm) (get cfg :cmd))]
    (assert (#{:test :run} cmd) "only support :target :wasm builds, '$cargo run', or '$cargo test'"))
  (with-promise out
    (->
     (if (= :wasm (get cfg :target))
       (build-wasm! cfg)
       (let [cmd (get cfg :cmd)]
         (condp = cmd
           :test (cargo-test cfg)
           :run (cargo-run cfg)
           (cargo-build cfg))))
     (take! #(put! out (reset! last-result %))))))

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



(def error-types
  [:spawn-error

   :cargo/compilation-failure
   :cargo/run-failure
   :cargo/fatal-runtime
   :cargo/test-failure
   :cargo/missing-toml
   :cargo/bad-toml

   :wasm/path-missing-before-wasm-gc
   :wasm/wasm-gc-failure
   :wasm/path-missing-after-wasm-gc
   :wasm/slurp-module-failure
   :wasm/instantiation-failure])

(defn report-error [error]
  (util/err (::type error))
  (condp = (::type error)
    :cargo/compilation-failure
    (let [rs (into []
                   (comp
                    (map #(get-in % [:message :rendered]))
                    (remove nil?)
                    (distinct))
                   (:cargo/stdout error))]
      (run! util/err rs))

    :cargo/fatal-runtime
    (run! util/err (get error :stderr))

    :cargo/test-failure
    (run! util/info (get error :stdout))

    :cargo/missing-toml
    (do
      (util/err (first (get error :stderr)))
      (util/err "(case sensitive)")) ;repair would be cool

    :cargo/bad-toml
    (run! util/err (get error :stderr))

    (util/log error)))


(defn get-stdout []
  (let [[e data] @last-result]
    (if e
      (get-in e [:stdout])
      (get-in data [:stdout]))))

(defn get-stderr [] ;; need to separate out cargo stderr
  (let [[e data] @last-result]
    (if e
      (get-in e [:stderr])
      (get-in data [:stderr]))))

(defn explain []
  (when (some? (first @last-result))
    (into []
          (comp
           (map #(get-in % [:message :code :explanation]))
           (remove nil?))
          (:cargo/stdout (first @last-result)))))

