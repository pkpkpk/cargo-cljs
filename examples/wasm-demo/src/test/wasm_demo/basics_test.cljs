(ns wasm-demo.basics-test
  (:require-macros [cargo.macros :as mac])
  (:require [cljs.test :refer-macros [deftest is testing async are run-tests] :as test]
            [cljs.core.async :as casync :refer [close! put! take! alts! <! >! chan promise-chan]]
            [cargo.cargo :as cargo]
            [cargo.util :as util :refer [log]]
            [wasm-demo.basics :as basics]))

(def path (js/require "path"))

(set! cargo/*verbose* false)

(defn mod-tests [module]
  (let [Module #js{:memory (.. module -exports -memory)
                   :get_42  (.. module -exports -get_42)
                   :add_42  (.. module -exports -add_42)
                   :unsafe_long (.. module -exports -unsafe_long)}]
    (is (= 42 (.get_42 Module)))
    (is (= 42 (.add_42 Module 0)))
    (is (= 45 (.add_42 Module js/Math.PI)))
    (is (= -2147483607 (.add_42 Module 2147483647)))
    (is (thrown-with-msg? js/Error #"invalid type" (.unsafe_long Module)))))

(deftest basics-test
  (async done
    (set! cargo/*verbose* false)
    (take! (cargo/build! basics/cfg)
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