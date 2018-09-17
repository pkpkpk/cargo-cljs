(ns cargo.api
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cargo.macros :refer [with-promise] :as mac])
  (:require [cljs.core.async :as casync :refer [close! put! take! promise-chan]]
            [cargo.cargo :as cargo]
            [cargo.report :as report]
            [cargo.util :as util]))

(defn cargo-build
  "Builds the artifact described by the config
   => pchan<[?err ?ok]>"
  ([cfg] (cargo/build! (assoc cfg :cmd "build"))))

(defn cargo-run
  "Builds and then runs src/bin/main.rs or main.rs.
     + Not applicable to wasm targets
     + Compiler warnings may still be present in a successful result
     + If your process emits json it will automatically be converted to edn
   => pchan<[?err ?ok]>"
  ([cfg] (cargo/build! (assoc cfg :cmd "run"))))

(defn cargo-test
  "Runs cargo's built in test runner.
    `$cargo test` leaves alot to be desired:
     - It suppresses output during builds
     - it obscures logging during tests (supposedly theres a --nocapture flag but cant get it to work)
     - there is no structured (json) test result output.
   Compiler warnings may still be present in a successful result
   => pchan<[?err ?ok]>"
  ([cfg] (cargo/build! (assoc cfg :cmd "run"))))

(defn clean-project [cfg]
  (cargo/clean-project cfg))

(defn build-wasm!
  "Builds a wasm project but goes the extra steps of exec'ing wasm-gc on the
   build artifact and returning it as an uninstantiated buffer
   => pchan<[?err ?buffer]>"
  ([cfg]
   (assert (= :wasm (get cfg :target)))
   (with-promise out
     (take! (cargo-build cfg)
        (fn [[err :as res]]
          (if err (put! out res)
            (take! (cargo/wasm-gc-and-slurp cfg) #(put! out %))))))))

(defn build-wasm-local!
  "Build and instantiate modules local to the build nodejs process.
   => pchan<[?err ?instantiated-module]>"
  ([cfg](build-wasm-local! cfg nil))
  ([cfg importOptions]
   (util/info "building wasm project" project-name)
   (with-promise out
     (take! (build-wasm! cfg)
       (fn [[err buffer]]
         (if err (put! out [err])
           (let [importOptions (or importOptions (get cfg :importOptions #js{}))]
             (take! (cargo/init-module buffer importOptions)
               (fn [[err compiled]]
                 (if err
                   (put! out [err])
                   (put! out [nil (.. compiled -instance)])))))))))))

(defn report-error
  [err]
  (report/report-error err))

; (defn wasm-auto-push [cfg server-cfg])

(defn last-result [] @cargo/last-result)

(defn get-stdout
  "should be stdout emitted from your app on last run/test"
  []
  (let [[e data] (last-result)]
    (if e
      (get-in e [:stdout])
      (get-in data [:stdout]))))

(defn get-cargo-out
  "compiler messages from last build"
  []
  (let [[e data] (last-result)]
    (if e
      (get-in e [:cargo/stdout])
      (get-in data [:cargo/stdout]))))

(defn get-stderr ;; TODO: separate out cargo stderr
  "stderr from last build"
  []
  (let [[e data] (last-result)]
    (if e
      (get-in e [:stderr])
      (get-in data [:stderr]))))

; (defn explain []
;   (when (some? (first @last-result))
;     (into []
;           (comp
;            (map #(get-in % [:message :code :explanation]))
;            (remove nil?))
;           (:cargo/stdout (first @last-result)))))
