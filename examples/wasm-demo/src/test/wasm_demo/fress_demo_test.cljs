(ns wasm-demo.fress-demo-test
  (:require-macros [cargo.macros :as mac])
  (:require [cljs.test :refer-macros [deftest is testing async are run-tests] :as test]
            [cljs.core.async :as casync :refer [close! put! take! alts! <! >! chan promise-chan]]
            [cargo.cargo :as cargo]
            [cargo.util :as util :refer [log]]
            [wasm-demo.fress-demo :as fress-demo]))

(def path (js/require "path"))

(defn mod-tests [module]
  (with-redefs [fress-demo/module (atom module)]
    (let [Module #js{:memory (.. module -exports -memory)}]
      (is (= (fress-demo/hello) [[["hello" "from" "wasm!"] ["isn't" "this" "exciting?!"]]]))
      (is (= (fress-demo/homo-maps) [{"a" 0 "b" 1} {{"a" 0 "b" 1} [0 1 2]}]))
      )))

(deftest fressian-demo-test
  (async done
    (set! cargo/*verbose* false)
    (take! (cargo/build! fress-demo/cfg)
      (fn [[e buffer]]
        (if-not (is (and (nil? e) (instance? js/Buffer buffer)))
          (done)
          (take! (cargo/init-module buffer #js{})
            (fn [[e compiled]]
              (if-not (is (and (nil? e) (instance? js/WebAssembly.Instance (.. compiled -instance))))
                (done)
                (do
                  (mod-tests (.. compiled -instance))
                  (set! cargo/*verbose* true)
                  (done))))))))))