(ns wasm-demo.util
  (:require-macros [wasm-demo.macros :as mac])
  (:require [goog.crypt :as gcrypt]))

(def TextEncoder
  (if (exists? js/TextEncoder)
    (js/TextEncoder. "utf8")
    (if ^boolean (mac/nodejs?)
      (let [te (.-TextEncoder (js/require "util"))]
        (new te))
      (reify Object
        (encode [_ s]
          (js/Int8Array. (gcrypt/stringToUtf8ByteArray s)))))))

(def TextDecoder
  (if (exists? js/TextDecoder)
    (js/TextDecoder. "utf8")
    (if ^boolean (mac/nodejs?)
      (let [td (.-TextDecoder (js/require "util"))]
        (new td "utf8"))
      (reify Object
        (decode [this bytes]
          (gcrypt/utf8ByteArrayToString bytes))))))

