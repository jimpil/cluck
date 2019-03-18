(ns cluck.internal
  (:import (java.util Random)))

(defonce sum
  (fnil + 0))

(defn ->fn
  "Converts a plain value to a function via `constantly`."
  [x]
  (if (fn? x)
    x
    (constantly x)))

(defn build-cdf
  "Helper for building the cumulative distribution."
  [grouped]
  (reduce-kv
    (fn [acc n fns]
      (conj acc [(sum (first (peek acc))
                      (* n (count fns)))
                 (mapv (comp ->fn second) fns)]))
    []
    grouped))

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
  "Uniform distribution from lo (inclusive) to hi (exclusive).
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
  [cdf rnd!]
  (fn [& args]
    (let [r (rnd!)]
      (-> (some
            (fn [[prob fns]]
              (when (< r prob)
                (rand-nth fns))) ;; for duplicates choose randomly
            cdf)
          (apply args)))))
