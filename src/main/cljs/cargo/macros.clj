(ns cargo.macros
  (:require [cljs.compiler :as comp]
            [cljs.env :as env]
            [cljs.analyzer :as ana]
            [cljs.analyzer.api :as ana-api]))

; (defmacro nodejs? [] (= :nodejs (get-in @env/*compiler* [:options :target])))

(defmacro with-promise [name & body]
  `(let [~name (~'cljs.core.async/promise-chan)]
     ~@body
     ~name))