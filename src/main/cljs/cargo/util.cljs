(ns cargo.util
  (:require [clojure.string :as string]
            [cljs.pprint :as pp]))

(def default-palette
  {:info-color "black"
   :info-bg "#0098ff"
   :status-color "black"
   :status-bg "violet"
   :success-color "black"
   :success-bg "#17ca65"
   :warn-bg "#ffaa2c"
   :warn-color "black"
   :err-bg "#c00"
   :err-color "white"})

(def ^:dynamic *palette* default-palette)

(defn key->style [key]
  (condp = key
    :info    (str "background-color: " (*palette* :info-bg) "; color:" (*palette* :info-color) ";")
    :status  (str "background-color: " (*palette* :status-bg) "; color:" (*palette* :status-color) ";")
    :success (str "background-color: " (*palette* :success-bg) "; color:" (*palette* :success-color) ";")
    :warn    (str "background-color: " (*palette* :warn-bg) "; color:" (*palette* :warn-color) ";")
    :err     (str "background-color: " (*palette* :err-bg) "; color:" (*palette* :err-color) ";")
    nil))

(def ^:dynamic *pprint* true)

(defn style-args
  "coerces data structures to strings for flat styling"
  [args key]
  (let [style (key->style key)
        sb (goog.string.StringBuffer. "%c")]
    (loop [args args]
      (if-not (seq args)
        [(.toString sb) style]
        (let [item (first args)]
          (if (string? item)
            (.append sb " " item)
            (.append sb  (if ^boolean *pprint*
                           (with-out-str (pp/pprint item))
                           (pr-str item))))
          (recur (rest args)))))))

(def ^{:dynamic true :doc "should be fn<var-args>"} *log*)
(def ^{:dynamic true :doc "should be fn<var-args>"} *info*)
(def ^{:dynamic true :doc "should be fn<var-args>"} *status*)
(def ^{:dynamic true :doc "should be fn<var-args>"} *success*)
(def ^{:dynamic true :doc "should be fn<var-args>"} *warn*)
(def ^{:dynamic true :doc "should be fn<var-args>"} *warning*)
(def ^{:dynamic true :doc "should be fn<var-args>"} *err*)
(def ^{:dynamic true :doc "should be fn<var-args>"} *error*)

(defn log
  "applies no styling"
  [& args]
  (if *log*
    (apply *log* args)
    (apply js/console.log args)))

(def ^{:doc "formatted info msg"} info
  (if (exists? js/window.devtools)
    (fn [& args]
      (if *info*
        (apply *info* args)
        (let [args (style-args args :info)]
          (apply js/console.info args))))
    (fn [& args]
      (if *info*
        (apply *info* args)
        (apply println args)))))

(def ^{:doc "formatted status msg. An alternative format to info"} status
  (if (exists? js/window.devtools)
    (fn [& args]
      (if *status*
        (apply *status* args)
        (let [args (style-args args :status)]
          (apply js/console.info args))))
    (fn [& args]
      (if *status*
        (apply *status* args)
        (apply info args)))))

(def ^{:doc "formatted success msg"} success
  (if (exists? js/window.devtools)
    (fn [& args]
      (if *success*
        (apply *success* args)
        (let [args (style-args args :success)]
          (apply js/console.info args))))
    (fn [& args]
      (if *success*
        (apply *success* args)
        (apply info args)))))

(def ^{:doc "soft formatted warning msg, no stack trace"} warn
  (if (exists? js/window.devtools)
    (fn [& args]
      (if *warn*
        (apply *warn* args)
        (let [args (style-args args :warn)]
          (apply js/console.info args))))
    (fn [& args]
      (if *warn*
        (apply *warn* args)
        (apply js/console.warn (map pr-str args))))))

(defn warning
  "log a hard warning with generated stack trace"
  [& args]
  (if *warning*
    (apply *warning* args)
    (apply js/console.warn args)))

(def ^{:doc "soft formatted error msg, no stack trace"} err
  (if (exists? js/window.devtools)
    (fn [& args]
      (if *err*
        (apply *err* args)
        (let [args (style-args args :err)]
          (apply js/console.info args))))
    (fn [& args]
      (if *err*
        (apply *err* args)
        (apply js/console.error (map pr-str args))))))

(defn error
  "log a error warning with generated stack trace"
  [& args]
  (if *error*
    (apply *error* args)
    (apply js/console.error args)))