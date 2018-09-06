(ns wasm-demo.basics
  (:require-macros [cargo.macros :as mac]
                   [wasm-demo.macros :as dmac])
  (:require [cljs.core.async :as casync :refer [close! put! take! alts! <! >! chan promise-chan]]
            [cargo.cargo :as cargo]
            [cargo.util :refer [log]]
            [wasm-demo.util :as util]))

(def path (js/require "path"))
(set! cargo/*verbose* true)

(def cfg
  {:project-name "basics"
   :dir (path.join (dmac/example-path) "rust"  "wasm-basics")
   :release? true
   :silent? true
   :target :wasm})

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

(defn ^string collectCString
  "Create a string from the given ptr up to the first null string terminator \\0.
   An alternative implementation is to simply increment the ptr while valid
   and call (.subarray buf ptr ptr'). For whatever reason, typedarray prototype
   functions are slow/buggy on chrome.
   @param {!ArrayBuffer} membuf
   @param {!Number} ptr
   @return {string} buffer containing a UTF-8 string"
  [membuf ptr]
  (let [buf (js/Uint8Array. membuf)
        acc #js[]]
    (loop [ptr ptr]
      (let [n (aget buf ptr)]
        (if (undefined? n)
          (throw (js/Error. "undefined memory"))
          (if (identical? 0 n)
            (.decode util/TextDecoder (js/Uint8Array.from acc))
            (do
              (.push acc n)
              (recur (inc ptr)))))))))

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

(defn hola []
  (if-let [Mod @module]
    (let [ptr ((.. Mod -exports -hola))]
      ;; notice that we get the ptr BEFORE grabbing a reference to memory
      ;; The creation of that ptr may mean that wasm must allocate more memory
      ;; and replace the arraybuffer backing with a new one.
      ;; Carrying a ref across a grow call can end w/ undefined data!
      (collectCString (.. Mod -exports -memory -buffer) ptr))
    (throw (js/Error. "missing basics module"))))