(ns cargo.api
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cargo.macros :refer [with-promise] :as mac])
  (:require [cljs.core.async :as casync :refer [close! put! take! promise-chan]]
            [cargo.cargo :as cargo]
            [cargo.report :as report]
            [cargo.util :as util]))

(defn cargo-build
  "Builds the artifact described by the config
     + Compiler warnings may still be present in a successful result
   => pchan<[?err ?ok]>"
  ([cfg] (cargo/build! (assoc cfg :cmd :build))))

(defn cargo-run
  "Builds and then runs src/bin/main.rs or main.rs.
     + Not applicable to wasm targets
     + Compiler warnings may still be present in a successful result
     + If your process emits json it will automatically be converted to edn
       under the ok's :stdout key
   => pchan<[?err ?ok]>"
  ([cfg] (cargo/build! (assoc cfg :cmd :run))))

(defn cargo-test
  "Run cargo's built-in test runner.
    + `$cargo test` leaves alot to be desired:
      - It suppresses output during builds
      - it obscures logging during tests (supposedly theres a --nocapture flag but cant get it to work)
      - there is no structured (json) test result output.
    + Compiler warnings may still be present in a successful result
   => pchan<[?err ?ok]>"
  ([cfg] (cargo/build! (assoc cfg :cmd :test))))

(defn clean-project
  "delete compiled artifacts"
  [cfg] (cargo/clean-project cfg))

(defn build-wasm!
  "Builds a wasm project but with the extra steps of exec'ing wasm-gc on the
   build artifact and returning it as an uninstantiated buffer
     + Compiler warnings may still be present in a successful result
   => pchan<[?err ?{:buffer js/Buffer, ...}]>"
  ([cfg]
   (assert (= :wasm (get cfg :target)))
   (util/info "building wasm project" (get cfg :project-name))
   (with-promise out
     (take! (cargo-build cfg)
       (fn [[err :as comp-res]]
         (if err (put! out comp-res)
           (take! (cargo/wasm-gc-and-slurp cfg)
            (fn [[err buffer :as gc-res]]
              (if err (put! out gc-res)
                (put! out (reset! cargo/last-result (assoc-in comp-res [1 :buffer] buffer))))))))))))

(defn build-wasm-local!
  "Build and instantiate modules local to the build nodejs process.
     + Compiler warnings may still be present in a successful result
   => pchan<[?err ?instantiated-module]>"
  ([cfg](build-wasm-local! cfg nil))
  ([cfg importOptions]
   (assert (= :wasm (get cfg :target)))
   (with-promise out
     (take! (build-wasm! cfg)
       (fn [[err {:keys [buffer]} :as comp-res]]
         (if err (put! out comp-res)
           (let [importOptions (or importOptions (get cfg :importOptions #js{}))]
             (take! (cargo/init-module buffer importOptions)
               (fn [[err compiled :as init-res]]
                 (if err (put! out init-res)
                   (put! out [nil (.. compiled -instance)])))))))))))



; (defn wasm-auto-push [cfg server-cfg])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; debug helpers

(defn report-error
  [err]
  (report/report-error err))

(defn last-result [] @cargo/last-result)

(defn get-stdout
  "should be stdout emitted from your app on last run/test"
  []
  (let [[e data] (last-result)]
    (if e
      (get-in e [:stdout])
      (get-in data [:stdout]))))

(defn get-stderr
  "stderr from last build"
  []
  (let [[e data] (last-result)]
    (if e
      (get-in e [:stderr])
      (get-in data [:stderr]))))

(defn get-warnings
  "warnings from last build"
  []
  (let [[e data] (last-result)]
    (if e
      (get e :warnings)
      (get data :warnings))))

(defn get-errors
  "errors from last build"
  []
  (let [[e data] (last-result)]
    (if e
      (get e :errors)
      (get data :errors))))

(defn render-warnings
  ([] (render-warnings (get-warnings)))
  ([warnings] (report/render-warnings warnings)))

(defn render-errors
  ([] (render-errors (get-errors)))
  ([errors] (report/render-errors errors)))

; (defn explain []
;   (when (some? (first @last-result))
;     (into []
;           (comp
;            (map #(get-in % [:message :code :explanation]))
;            (remove nil?))
;           (:cargo/stdout (first @last-result)))))
