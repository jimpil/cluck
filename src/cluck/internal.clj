(ns cluck.internal
  (:import (java.util Random)
           (clojure.lang PersistentQueue ITransientCollection)))

(defonce RND_SEED 42)
(defonce sum      (fnil + 0))
(defonce safe-inc (fnil inc 0))

(defn ->fn
  "Converts a plain value to a function via `constantly`."
  [x]
  (if (fn? x)
    x
    (constantly x)))

(defn build-cdf
  "Helper for building the cumulative distribution."
  ([grouped]
   (build-cdf grouped second))
  ([grouped extract-val]
   (let [cdf (reduce-kv
               (fn [acc n fns]
                 (conj acc [(sum (first (peek acc))
                                 (* n (count fns)))
                            (mapv (comp ->fn extract-val) fns)]))
               []
               grouped)]
     (with-meta cdf {:high (-> cdf peek first)}))))

(defn else->prob
  "Helper for handling a potential :else clause."
  [grouped]
  (if-let [else* (:else grouped)]
    (let [max-prob (->> grouped
                        (filter (comp number? key))
                        (map (fn [[p fns]]
                               (* p (count fns))))
                        (apply +))
          rem-prob (- 1 max-prob)]
      (-> grouped
          (assoc rem-prob else*)
          (dissoc :else)))
    grouped))

(defn rand-int-uniform
  "Uniform distribution from low (inclusive) to high (exclusive).
   Defaults to range of Java long."
  ([low high]
   (rand-int-uniform (Random. 42) low high))
  ([^Random rnd low high]
   (-> (.nextDouble rnd)
       (* (- high low))
       (+ low)
       Math/floor
       long)))

(defn rand-nth-uniform
  ([coll]
   (rand-nth-uniform (Random. 42) coll))
  ([^Random rnd coll]
   (->> coll
        count
        (rand-int-uniform rnd 0)
        (nth coll))))

(defn compile-cdf
  [cdf rnd rnd!]
  (fn [& args]
    (let [r (rnd!)]
      (-> (some
            (fn [[prob fns]]
              (and (< r prob)
                   (rand-nth-uniform rnd fns))) ;; for duplicates choose randomly
            cdf)
          (apply args)))))

(defn update-in!
  "Same as `clojure.core/update-in` but for transient collections.
   WARNING: Assumes <m> is transient all the way down!"
  [m [k & ks] f & args]
  (if ks
    (assoc! m k (apply update-in! (or (get m k)
                                      (transient {})) ks f args))
    (assoc! m k (apply f (get m k) args))))


(defn map-vals
  "Maps <f> to all values of map <m>."
  [f m]
  (persistent!
    (reduce-kv
      (fn [m k v]
        (assoc! m k (f v)))
      (transient {})
      m)))

(defn filter-vals
  "Removes entries from <m>, where `(pred value)`, returns logical false."
  [pred m]
  (persistent!
    (reduce-kv
      (fn [m k v]
        (if (pred v)
          m
          (dissoc! m k)))
      (transient m)
      m)))

(defn invoke-if-fn
  "If <init> is a fn invoke it with no arguments.
   Useful for producing values on init-nodes."
  [init]
  (if (fn? init)
    (init)
    init)) ;; plain value

(defn deref-if-future
  [x]
  (cond-> x (future? x) deref))

(defn deref-if-realised
  [x]
  (cond-> x (realized? x) deref))

(defn deref-if-future-or-delay
  [x]
  (cond-> (force x) ;; `force` returns x when it's not a Delay
          true deref-if-future))

(defn queue
  ([] (PersistentQueue/EMPTY))
  ([coll] (reduce conj PersistentQueue/EMPTY coll)))


(defn transient?
  [coll]
  (instance? ITransientCollection coll))

(defn empirical-probs
  [all-observed state]
  (let [total (apply + (vals all-observed))
        state-score (get all-observed state)]
    (/ state-score total)))