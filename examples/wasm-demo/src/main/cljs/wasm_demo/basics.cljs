(ns wasm-demo.basics
  (:require-macros [cargo.macros :as mac]
                   [wasm-demo.macros :as dmac])
  (:require [cljs.core.async :as casync :refer [close! put! take! alts! <! >! chan promise-chan]]
            [cargo.cargo :as cargo]
            [cargo.util :as util :refer [log]]))

(def path (js/require "path"))

(def cfg
  {:project-name "basics"
   :dir (path.join (dmac/example-path) "rust"  "wasm-basics")
   :release? true
   :silent? false
   :target :wasm})

(defonce module (atom nil))

(defn build []
  (set! cargo/*verbose* true)
  (take! (cargo/build! cfg)
    (fn [[e buffer]]
      (if e
        (cargo/report-error e)
        (let [importOptions #js{}]
          (take! (cargo/init-module buffer importOptions)
            (fn [[e compiled]]
              (if e
                (cargo/report-error e)
                (reset! module (.. compiled -instance))))))))))

(defn get-42 []
  (if-let [module @module]
    ((.. module -exports -add_42))
    (throw (js/Error. "missing basics module"))))

(defn add-42 [arg]
  (if-let [module @module]
    ((.. module -exports -add_42) arg)
    (throw (js/Error. "missing basics module"))))

(defn unsafe-long []
  (if-let [module @module]
    ((.. module -exports -unsafe_long))
    (throw (js/Error. "missing basics module"))))