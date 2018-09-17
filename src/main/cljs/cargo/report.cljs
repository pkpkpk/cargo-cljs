(ns cargo.report
  (:require [cargo.util :as util]))

(defn warning? [msg] (= "warning" (get-in msg [:message :level])))

(defn message? [msg] (= "compiler-message" (get msg :reason)))

(defn artifact? [msg] (= "compiler-artifact" (get msg :reason)))

(defn get-renderings [msgs]
  (into []
        (comp
         (map #(get-in % [:message :rendered]))
         (remove nil?)
         (distinct))
        msgs))


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
  (util/err (:cargo/type error))
  (condp = (:cargo/type error)
    :cargo/compilation-failure
    (let [rs (get-renderings (get error :cargo/stdout))]
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


