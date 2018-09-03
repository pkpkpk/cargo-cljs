(ns cargo.spawn
  "Utilities for spawning processes and managing their lifecycle.

   Linux Note: if you spawn a process that spawns its own children, killing
   the parent will not kill its children and you may be left with a zombie
   if you are not careful. See kill-r below."
  (:require-macros [cljs.core.async.macros :refer [go-loop]]
                   [cargo.macros :refer [with-promise]])
  (:require [cljs.core.async :as async :refer [put! take! promise-chan]]
            [cljs.core.match :refer-macros [match]]
            [clojure.string :as string]
            [cljs-node-io.spawn :as nspawn]
            [cljs-node-io.proc :refer [exec]]
            [cargo.util :as util]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;  linux only

(defn pgrep-P
  "Linux only.
   if no arg looks for children of current node process.
   if pid arg, looks for its children.
   @return {?IVector}"
  ([pid]
   (try ;; pgrep exits non-zero for no match
     (string/split-lines (.toString (exec (str "pgrep -P " pid))))
     (catch js/Error _
       nil)))
  ([]
   (let [children (pgrep-P (.-pid js/process))
         self (first children)] ;will contain execSync call
     (vec (remove #(= % self) children)))))

(defn kill-9
  ([pid]
   (try
     (exec (str "kill -9" pid))
     (catch js/Error e
       (when-not (string/includes? (.-message e) "No such process")
         (throw e))))))

(defn kill-r
  "kill a process and its children recursively"
  [pid]
  (doseq [cp (pgrep-P pid)] (kill-r cp))
  (kill-9 pid))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce CHILD_PROCESSES (atom {})) ;; id -> ChildProcess

(defn get-CP [id] (get @CHILD_PROCESSES id))
(defn remove-CP! [id] (swap! CHILD_PROCESSES dissoc id))
(defn add-CP! [id cp] (swap! CHILD_PROCESSES assoc id cp)) ;enforce uniqueness?

(defn get-child-procs [] @CHILD_PROCESSES)

(defn- kill-CP! [id]
  (if-let [cp (get-CP id)]
    (.kill cp)
    false))

(defn kill!
  "given a child-process id, kill it and remove reference from memory.
   Returns false if killing fails."
  [id]
  (and (kill-CP! id) (remove-CP! id) true))

(defn kill-all! [] (every? true? (map kill! (keys @CHILD_PROCESSES))))

(defonce _
  (.on js/process "exit"
    (fn []
      (when-not (kill-all!)
        (js/console.error (str "orphan PIDs" (mapv :pid (vals @CHILD_PROCESSES))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ?json->edn
  [line]
  (try
    (-> line js/JSON.parse (js->clj :keywordize-keys true))
    (catch js/Error e nil)))

(defn collected-spawn
  "collect CP output as if it were an async exec call, but allow output logging
   during process execution. Spawns are not persistent but may be long running.

   Returns pchan<[ ?js/error ?[exit-code stdout stderr] ]> where stdout/stderr
   json is automatically converted to edn where applicable. Cargo writes line
   delimited json to stdout regardless if it represents a clean exit or not.
    [0 [{:some :stdout} {:more :stdout}] ['some stderr' 'more stderr']"
  [cmd args {:keys [key silent? json->edn?] :as opts}]
  (with-promise out
    (try
      (when key (assert (nil? (get @CHILD_PROCESSES key)) (str "cannot overwrite CP with key: " key)))
      (let [CP (nspawn/spawn cmd args (assoc opts :encoding "utf8"))
            key (or key (gensym))
            _(add-CP! key CP)
            exit-code (atom nil)
            stdout #js[]
            stderr #js[]]
        (go-loop []
          (try
            (if-let [msg (<! CP)]
              (do
                (match msg
                 [pipe [:data [data]]]
                 (do
                   (when-not silent?
                     (util/log pipe (str (pr-str (subs data 0 25)) "...")))
                   (let [?edn (when json->edn?
                                (let [lines (string/split-lines (string/trim data))]
                                  (mapv #(or (?json->edn %) %) lines)))
                         pipe (if (= :stdout pipe) stdout stderr)]
                     (if ?edn
                       (doseq [v ?edn]
                         (.push pipe v))
                       (.push pipe data))))
                 [_ [(:or :close :exit :finish :end) _]] nil
                 [:close _] nil
                 [:exit [code _]] (reset! exit-code code)
                 :else (util/warn "spawn unmatched" msg))
                (recur))
              (do
                (remove-CP! key)
                (put! out [nil [@exit-code (vec stdout) (vec stderr)]])))
            (catch js/Error e
              (do
                (put! out [e])
                (util/err e)
                (kill! key))))))
      (catch js/Error e
        (put! out [e])))))