(ns refactor-nrepl.analyzer
  (:refer-clojure :exclude [macroexpand-1 read read-string])
  (:require [clojure.tools.analyzer :as ana]
            [clojure.tools.analyzer.passes.source-info :as ana.si]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [clojure.tools.analyzer.ast :refer :all]
            [clojure.tools.analyzer.env :refer [with-env deref-env]]
            [clojure.tools.analyzer.utils :refer [source-info]]
            [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :as rts]
            [clojure.tools.namespace.parse :refer [read-ns-decl deps-from-ns-decl]])
  (:import java.io.PushbackReader))

(def e (ana.jvm/empty-env))

;; these two fns could go to clojure.tools.namespace.parse: would worth a pull request
(defn get-alias [as v]
  (cond as (first v)
        (= (first v) :as) (get-alias true (rest v))
        :else (get-alias nil (rest v))))

(defn parse-ns
  "For now returns tuples with the ns as the first element and
   a map of the aliases for the namespace as the second element
   in the same format as ns-aliases"
  [body]
  (let [ns-decl (read-ns-decl (PushbackReader. (java.io.StringReader. body)))
        aliases (->> ns-decl
                     (filter list?)
                     (some #(when (#{:require} (first %)) %))
                     rest
                     (filter #(contains? (into #{} %) :as))
                     (#(zipmap (map (partial get-alias nil) %)
                               (map first %))))]
    [(second ns-decl) aliases]))

(defn create-env [ns-info]
  (let [ns (first ns-info)]
    (atom {:namespaces {ns {:mappings {}
                          :aliases (last ns-info)
                          :ns ns}}})))

(defn read-all-forms [reader]
  (let [eof (reify)]
    (loop [forms []]
      (let [form (r/read reader nil eof)]
        (if (identical? form eof)
          forms
          (recur (conj forms form)))))))

(defn string-ast [string]
     (binding [ana/macroexpand-1 ana.jvm/macroexpand-1
               ana/create-var    ana.jvm/create-var
               ana/parse         ana.jvm/parse
               ana/var?          var?]
       (try
         (let [[_ aliases] (parse-ns string)]
           (with-env (ana.jvm/global-env)
             (-> string
                 rts/indexing-push-back-reader
                 read-all-forms
                 (ana/analyze e)
                 (prewalk ana.si/source-info)
                 (assoc :alias-info aliases))))
         (catch Exception e
           (.printStackTrace e)
           {}))))
