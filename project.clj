;  https://clojureverse.org/t/combining-tools-deps-with-leiningen/658/4
(require '[clojure.edn :as edn])

; {foo {:mvn/verion "string"}
;  bar {:mvn/version "string"}... } => [[foo "string" :exculsions [...]] ...]
(defn deps->vec [deps]
  (into []
    (map
      (fn [[dep {:keys [:mvn/version exclusions]}]]
        (cond-> [dep version]
          exclusions (conj :exclusions exclusions))))
    deps))

(def raw-deps (edn/read-string (slurp "deps.edn")))

(let [proj-deps (deps->vec (get raw-deps :deps))
      src-paths (vec (get raw-deps :paths))]
  (defproject cargo-cljs "0.1.0"
    :description "A toolkit for using rust with clojurescript"
    :url "https://github.com/pkpkpk/cargo-cljs"
    :repositories [["clojars" {:sign-releases false}]]
    :license {:name "Eclipse Public License"
              :url "http://www.eclipse.org/legal/epl-v10.html"}
    :dependencies ~proj-deps
    :source-paths ~src-paths))

