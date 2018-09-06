(ns wasm-demo.basics-test
  (:require-macros [cargo.macros :as mac])
  (:require [cljs.test :refer-macros [deftest is testing async are run-tests] :as test]
            [cljs.core.async :as casync :refer [close! put! take! alts! <! >! chan promise-chan]]
            [cargo.cargo :as cargo]
            [cargo.util :as util :refer [log]]
            [wasm-demo.basics :as basics]))

(def path (js/require "path"))

(defn mod-tests [module]
  (with-redefs [basics/module (atom module)]
    (let [Module #js{:memory (.. module -exports -memory)}]
      (is (= 42 (basics/get-42)))
      (is (= 42 (basics/add-42 0)))
      (is (= 45 (basics/add-42 js/Math.PI)))
      (is (= 2147483647 (basics/add-42 (- 2147483647 42))))
      (if (get basics/cfg :release?)
        (is (= -2147483607 (basics/add-42  2147483647)))
        (is (thrown-with-msg? #"unreachable" (basics/add-42  2147483647))))
      (is (thrown-with-msg? js/Error #"invalid type" (basics/unsafe_long)))
      (is (= "hola" (basics/hola)))
      (is (= "" (basics/bonjour))))))

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