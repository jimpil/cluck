(ns cluck.graph
  (:require [cluck.internal :as internal]
            [clojure.set :as set]
            [clojure.string :as st])
  (:import (clojure.lang ILookup Associative Counted)
           (java.io Writer)))


;; A graph is a map.
;; Each map entry holds a key and a fn constructed via
;; `node` (so that the right meta-data are populated).
;; Within the arg-vector of each node you can refer directly
;; to symbols that the fn depends on from the greater graph
;; (e.g you can use symbol `n` which will mean the result of the node :n).
;;EXAMPLE:



(defn- get-or-throw
  [m k]
  (or (get m k)
      (throw (ex-info (format "Node %s is NOT present in the graph!" k)
                      {:graph m
                       :missing-key k}))))

(defn- deps-set [node]
  (-> node meta ::deps set))

(defn- score-for
  [g initset f]
  (if-let [deps-diff (some-> f deps-set (set/difference initset))]
    (reduce (fn [score dep]
              (if (->> dep (get g) fn?) ;; node that depends on other node
                (unchecked-inc (score-for g initset (get g dep)))
                score))
            1 ;; node that depends only on init(s)
            deps-diff)
    0)) ;; init

#_(defn- explain-flow-order
    "Helper for debugging. Returns an ordered version of <G> (a sequence),
     where each item is a map entry from node key to score.
     NOT USED BY ANY OTHER FUNCTION!"
    [G inits]
    (sort-by second
             (loop [g (seq G)
                    scores G]
               (if (empty? g)
                 scores
                 (let [[k f] ((juxt first second) (first g))]
                   (recur (rest g)
                          (assoc scores k (score-for G inits f))))))))



;; CYCLIC DETECTION
(defn- find-cycles
  ""
  [cur {:keys [seen root stack graph] :as state}]
  (some (fn [c]
          (if (= c root)
            (conj stack c)
            (find-cycles (get graph c)
                         (-> state
                             (update :stack conj c)
                             (update :seen conj c)))))
        (remove seen cur)))

(def ^:private lazy-get
  "Like to `clojure.core/get`, but intended to be used
   for pulling out delayed nodes. If you use regular `get`
   on a delayed node, you'll get a Delay object back, and
   that in turn could give you back a future (e.g. `pnode`)."
  (comp 
    internal/deref-if-future-or-delay
    get))

(defn- lazy-select-key
  [m k]
  (when-let [v (lazy-get m k)]
    {k v}))

(deftype DelayedGraph [graph]
  ;; The whole point of a DelayedGraph is that nodes are computed on-demand.
  ;; In other words, you wouldn't typically compute them all at once.
  ;; Therefore, supporting keyword access, `get` & `select-keys` suffices.
  ;; `count`, `assoc` & `contains?` are supported purely as a bonus :)
  ILookup
  (valAt [_ k]
    (lazy-get graph k))

  Associative
  (containsKey [_ k]
    (contains? graph k))
  (entryAt [_ k]
    (first (lazy-select-key graph k)))
  (assoc [_ k v] ;; better not use this
    (DelayedGraph. (assoc graph k v)))

  Counted
  (count [_]
    (count graph))

  Object
  (equals [_ other]
    (if (instance? DelayedGraph other)
      (= graph (.-graph ^DelayedGraph other))
      false))

  (toString [_]
    (str graph))
  )

(defmethod print-method DelayedGraph [dg ^Writer w]
  (print-method
    (->> dg
         :graph
         (internal/map-vals internal/deref-if-realised))
    w))


;; CORE FUNCTIONS
(defn- compute-node
  "Given a graph <g>, and one of its entries,
   returns <g> with the node at key <k> computed.
   Throws exception when some dependency is missing from the graph."
  [delayed? g [k node]]
  (if-let [deps (some-> node meta ::deps not-empty)]
    (let [compute* #(apply node (map (comp internal/deref-if-future-or-delay
                                           (partial get-or-throw g))
                                     deps))]
      (assoc g k (if (cond-> node meta :parallel?)
                   (if delayed?
                     (delay (future (compute*)))
                     (future (compute*)))
                   (if delayed?
                     (delay (compute*))
                     (compute*)))))
    (assoc g k (internal/invoke-if-fn node))))

(defn- compute-graph
  "Given a graph <g> with all inits computed & an ordered flow <oflow>,
   compute all the nodes of the graph.
   Returns <g> but with computed values instead of nodes."
  [g oflow delayed?]
  (cond->> ;; first pass is the actual graph computation
    (reduce (partial compute-node delayed?) g oflow)
    delayed? ->DelayedGraph
    ;; second pass to deref any futures that were not needed
    ;; as ::deps during the first pass
    (not delayed?) (internal/map-vals internal/deref-if-future)))

(defn get-cycles
  "Retuns a list of cyclic dependencies for given <graph>."
  [graph]
  (let [graph* (internal/map-vals deps-set graph)]
    (keep (fn [[root deps]]
            (find-cycles deps {:seen #{}
                               :stack [root]
                               :graph graph*
                               :root root}))
          graph*)))

(defn- acyclic?
  "Returns true if there are no cyclic dependencies in graph <g>."
  [g]
  (-> g get-cycles empty?))

(def ^:private empty-deps?
  (comp empty? ::deps meta))

(defn- find-init-keys
  [graph]
  (->> graph
       (internal/filter-vals empty-deps?)
       keys))

(defn- order-nodes
  [graph init-keys]
  (sort-by (comp (partial score-for graph init-keys)
                 val)
           graph))

;========================<PUBLIC API>========================================
;;

(defmacro node
  "Like `clojure.core/fn`, but adds certain specific metadata to the fn.
   More limited than `fn` in that it doesn't support doc-string nor metadata.
   Only a param vector and body."
  [args & body]
  `(with-meta (fn [~@args] ~@body)
              {:cluck.graph/deps (mapv keyword '~args)}))

(defmacro pnode
  "Like `node`, but adds extra metadata.
   Use this for expensive nodes only!"
  [args & body]
  `(vary-meta (cluck.graph/node ~args ~@body)
              merge
              {:parallel? true}))


(defmacro defnode
  "Like `clojure.core/defn` but adds certain specific metadata to the fn.
   More limited than `defn` in that it doesn't support doc-string nor metadata.
   Only a name, a param-vector and body."
  [symbo args & body]
  `(def ~symbo
     (cluck.graph/node ~args ~@body)))

(defmacro defpnode
  "Like `defnode` but returns a parallel node (via `pnode`)."
  [symbo args & body]
  `(def ~symbo
     (cluck.graph/pnode ~args ~@body)))

(defn valid-graph?
  "Returns true if <g> is a valid graph, false otherwise."
  [g]
  (let [[ids nodes] ((juxt keys vals) g)]
    (and (every? keyword? ids)
         (every? fn? nodes)
         ;; no cyclic dependencies (would deadlock otherwise!)
         (acyclic? g))))

(defn graph-fn*
  [graph]
  (let [upfront-init-keys (find-init-keys graph)
        upfront-init-map (select-keys graph upfront-init-keys)
        init-keys-promise (promise) ;; optimisation (we only need to calculate the order once!)
        ordered-flow (delay (order-nodes graph @init-keys-promise))]
    ;; if the inits have already been provided 'install' them now
    (when (seq upfront-init-keys)
      (deliver init-keys-promise upfront-init-keys)
      (force ordered-flow))
    (fn graph-fn
      ([]
       (graph-fn {}))
      ([init-map]
       (graph-fn init-map true))
      ([init-map return-inits]
       (graph-fn init-map return-inits false))
      ([init-map return-inits? delayed?]
       (let [init-map (merge upfront-init-map init-map)
             graph-with-inits (merge graph init-map) ;; potentially replace existing inits
             init-keys (or (seq (keys init-map))
                           (throw (ex-info "No init(s) found!"
                                           {:graph graph
                                            :inits init-map
                                            :delayed? delayed?})))
             _ (deliver init-keys-promise init-keys) ;; has no effect if already delivered
             ret (compute-graph graph-with-inits
                                (cond->> @ordered-flow
                                         ;; include inits in the flow as first
                                         (seq init-map) (apply conj (vec init-map)))
                                delayed?)]
         (if (or delayed? return-inits?)
           ret ;; don't dissoc anything from a delayed graph!
           (apply dissoc ret init-keys))))))
  )

(defn graph-fn
  "Given a graph <g>, return a fn with 4 arities (0, 1, 2 & 3 args).
   The 0-arg arity assumes the graph already contains all the inits and so it
   delegates to the next one with an empty map.
   The 1-arg arity expects the map of inits, whereas the 2-arg one
   also expects a boolean denoting whether the inits themselves should be in
   the result returned (defaults to true). The 3-arg one can be used to
   specify whether the computation of this graph (via calling the returned fn)
   should be delayed (defaults to false)."
  [g]
  (assert (valid-graph? g) "Invalid or cyclic graph!")
  (graph-fn* g))


(defn compute-now
  "Computes the <graph> now.
   Returns a map with the results."
  ([graph]
   (compute-now graph nil))
  ([graph inits]
   (compute-now graph inits false))
  ([graph inits return-inits?]
   (let [ffn (graph-fn graph)]
     (ffn inits return-inits? false))))

(defn compute-later
  "Returns an instance of `DelayedGraph`,
   which looks very much like the original graph (a map).
   Looking up elements (via :keyword access) triggers
   the required node(s) to start computing.
   The rest remain delayed."
  ([graph]
   (compute-later graph nil))
  ([graph inits]
   (compute-later graph inits false))
  ([graph inits return-inits?]
   (let [ffn (graph-fn graph)]
     (ffn inits return-inits? true))))


(defn compute-while!
  "Executes <graph> as long as the stateful <condition!> is met.
   Only useful in cases where the graph already contains the init-key(s)
   and they are function(s) that produce different result(s) upon each invocation.
   Returns nil, and so implies that the entire graph computation
   is something stateful (i.e. no one cares about the end result)."
  [condition! graph]
  (let [ffn (graph-fn graph)]
    (while (condition!)
      (ffn nil))))

(defn delayed-graph?
  "Returns true if <g> is an instance of `DelayedGraph`,
   false otherwise."
  [g]
  (instance? DelayedGraph g))

