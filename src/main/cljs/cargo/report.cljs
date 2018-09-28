(ns cargo.report
  ^{:doc
  "Utilities for working with structured data output by Cargo.

   Cargo emits line delimited json messages to stdout and simple human readable
   status messages to stderr. We collect all output and put it in a map:
       {
        :warnings [{..} ...]   <--- cargo stdout warnings parsed to edn
        :errors [{..} ...]     <--- cargo stdout errors parsed to edn
        :stderr ['string' ...] <--- everything emitted to stderr (edn if applicable)
        :stdout ['']           <--- everything not recognized as a compiler message
       }

   The :stdout entry is everything that is not picked up as a compiler message.
   In the ideal case this is empty for cargo-test, cargo-build, or is whatever
   your app emits to stdout for cargo-run. Sometimes however cargo will emit ill
   formatted json compiler messages. These will show up in :stdout as json
   string fragments

   The json messages sent to stdout roughly makeup the following categories:
     + build configuration + artifact emission notices
       - we don't care about these, they are discarded
     + warnings
       - your code compiles but you are breaking a lint
     + error messages
       - your code will not compile (get used to this)

   Error messages always occur with non-zero exit-codes, so our result vec's
   will resemble
      [{:errors [...]
        :warnings [...]}]

   Even if your process exits cleanly, you may still have warnings. So even an
   ok result vec may resemble
       [nil {:warnings [...]}]

   You can instrument the compiler to tone these warnings down, but don't
   forget to fix them.

   Compiler Messages themselves, both warnings & errors, are wrapped in maps
   designating their project context
      {
       :package_id '...' <-- what we compiled
       :target {...}  <----- compilation details
       :reason '...'  <----- message type
       :message {...}    <----- **actual problem details**
      }

   The inner :message map is the useful part and varies depending on the issue
      {
        :message 'the problem' <--- human readable problem name
        :children [...]   <--- sub problems
        :code {:code '...' <--- formal problem name
               :explanation '...'} <-- description of problem, can be lengthy
        :level 'warning'  <--- or 'error'
        :spans [{} ..]    <--- src file details: filename, line, col, etc
        :rendered '..'    <--- ascii art highlighting the problem in your src
      }"}
  (:require [cargo.util :as util]))



(defn warning? [msg] (= "warning" (get-in msg [:message :level])))

(defn error? [msg] (= "error" (get-in msg [:message :level])))

(defn message? [msg] (= "compiler-message" (get msg :reason)))

(defn artifact? [msg] (= "compiler-artifact" (get msg :reason)))

(defn build-script? [msg] (= "build-script-executed" (get msg :reason)))

(defn cargo-msg? [msg]
  (or
   (message? msg)
   (some? (:reason m))
   (some? (:message m))))


(defn group-file-warnings
  [msgs]
  (group-by #(get-in % [:code :code]) msgs))

(defn all-warnings->by-project
  [warnings]
  (group-by #(get-in % [:target :src_path]) warnings))

(defn project-warnings->by-file
  [project-warnings]
  (group-by #(get-in % [:spans 0 :file_name]) (map :message project-warnings)))


(defn warnings-table
  "Given a seq of warnings, arrange them into a table by project & file.
   This will automatically unwrap project wrapper objects into inner messages.
    {'project/entry.rs' {'/./src-file' [{:warning :map} ...]}}"
  [msgs]
  (let [warnings (filter warning? msgs)]
    (into {}
      (for [[project project-warnings] (all-warnings->by-project warnings)]
        [project
         (into {}
            (for [[file file-warnings] (project-warnings->by-file project-warnings)]
              [file file-warnings]))]))))

(defn sort-cargo-stdout
  "organize cargo output json into categories"
  [msgs]
  (let [msgs (into []
                   (comp
                    (remove artifact?)
                    (remove build-script?))
                   msgs)]
    {:warnings (filterv warning? msgs)
     :errors (filterv error? msgs)
     :stdout (vec (remove (fn [msg] (or (warning? msg) (error? msg)))  msgs))}))

(defn get-renderings [msgs]
  (into []
        (comp
         (map (fn [msg]
                (or
                   (get-in msg [:message :rendered])
                   (get-in msg [:rendered]))))
         (remove nil?)
         (distinct))
        msgs))

(defn render-warnings [warnings]
  (let [rs (get-renderings warnings)]
    (run! util/warn rs)))

(defn render-errors [errors]
  (let [rs (get-renderings errors)]
    (run! util/err rs)))


(def error-types
  [:spawn-error

   :cargo/compilation-failure
   :cargo/run-failure
   :cargo/fatal-runtime
   :cargo/test-failure
   :cargo/missing-toml
   :cargo/bad-toml

   :wasm/path-missing-before-wasm-gc
   :wasm/wasm-gc-failure
   :wasm/path-missing-after-wasm-gc
   :wasm/slurp-module-failure
   :wasm/instantiation-failure])


(defn report-error [error]
  (util/err (:type error))
  (condp = (:type error)

    :cargo/compilation-failure
    (render-errors (get error :errors))

    :cargo/fatal-runtime
    (run! util/err (get error :stderr))

    :cargo/test-failure
    (let [test-output (get error :stdout)]
      (run! util/status (get error :stderr))
      (run! util/info test-output))

    :cargo/missing-toml
    (do
      (util/err (first (get error :stderr)))
      (util/err "(case sensitive)")) ;repair would be cool

    :cargo/bad-toml
    (run! util/err (get error :stderr))

    (util/log error)))


