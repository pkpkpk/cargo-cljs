(ns wasm-demo.fress-demo
  (:require-macros [wasm-demo.macros :as dmac])
  (:require [cljs.core.async :as casync :refer [close! put! take! promise-chan]]
            [cargo.cargo :as cargo]
            [cargo.util :refer [log]]
            [wasm-demo.util :as util]
            [fress.api :as api]))

(def path (js/require "path"))
(set! cargo/*verbose* true)

(def cfg
  {:project-name "fressian-wasm-demo"
   :dir (path.join (dmac/example-path) "rust"  "fressian-wasm-demo")
   :target :wasm
   :release? true})

(defonce module (atom nil))

(defn build []
  (take! (cargo/build! cfg)
    (fn [[e buffer]]
      (if e
        (cargo/report-error e)
        (let [importOptions #js{}]
          (take! (cargo/init-module buffer importOptions)
            (fn [[e compiled]]
              (if e
                (cargo/report-error e)
                (do
                  (reset! module (.. compiled -instance)))))))))))

(defn clean []
  (cargo/clean-project cfg)
  (reset! module nil))

(defn hello []
  (if-let [module @module]
    (let [ptr ((.. module -exports -hello))]
      (fress.api/read-all (.. module -exports -memory) :offset ptr))
    (throw (js/Error. "missing module"))))

(defn homo-maps []
  (if-let [module @module]
    (let [ptr ((.. module -exports -homo_maps))]
      (fress.api/read-all (.. module -exports -memory) :offset ptr))
    (throw (js/Error. "missing module"))))

