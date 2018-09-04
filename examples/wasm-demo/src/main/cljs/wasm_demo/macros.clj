(ns wasm-demo.macros
  (:require [cljs.env :as env]
            [cljs.analyzer :as ana]
            [cljs.analyzer.api :as ana-api]
            [cljs.util :as util]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

(defn root-file []
  (let [caller-file (io/file ana/*cljs-file*)]
    (loop [f (.getParentFile caller-file)]
      (if (= "cargo-cljs" (.getName f))
        f
        (recur (.getParentFile f))))))

(defmacro example-path []
  (.getPath (io/file (root-file) "examples")))