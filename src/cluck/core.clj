(ns cluck.core
  (:require [cluck.internal :as internal])
  (:import  [java.util Random]))

;; Random dispatch
;; ===============
(defn- rand-fn*
  [^Random rnd fns]
  (fn [& args]
    (-> rnd
        (internal/rand-nth-uniform fns)
        (apply args))))

(defn rand-fn
  "Returns a function which, whenever called,
   will randomly (via `rand-nth`) call one of the supplied functions.
   If a supplied function is not a fn (test via `fn?`),
   it will be converted to one (via `constantly`)."
  [^Random rnd & fns]
  (rand-fn* (or rnd (Random. 47))
            (mapv internal/->fn fns)))

;; Probabilistic dispatch
;; ======================
(defn- prob-fn*
  [^Random rnd mappings]
  (let [cdf (->> mappings
                 (group-by first)     ;; there could be duplicate probabilities
                 internal/else->prob  ;; check for :else clause
                 internal/build-cdf)] ;; finally build the CDF

    (internal/compile-cdf cdf #(.nextDouble rnd))))


(defn prob-fn
  "Takes a java.util.Random object (or nil), followed by pairs of mappings (probability => fn/value),
   and returns a function which will (probabilistically) dispatch to the appropriate fn,
   according to the probabilities provided. These must either sum to 1, or an `:else`
   mapping must be provided. If the probability maps to a non-fn (test via `fn?`),
   it will be converted to one (via `constantly`). The function returned accepts variadic arguments,
   so it will never complain. However, realistically the arguments passed to it should be able to
   slot-in nicely to any of the functions provided in the mappings, which in turn means that
   those functions should have rather similar (or open) argument lists.
   Performance impact is virtually negligible, since the only extra work that the returned function has to do,
   is to generate a couple of random numbers, and find the right fn to invoke."
  [rnd & probabilities]

  (assert
    (let [probs (map first probabilities)]
      (or (some (partial = :else) probs)
          (== 1 (apply + probs))))
    "Probabilities do not sum to 1, and no :else clause was provided!")

  (prob-fn* (or rnd (Random. 47)) probabilities))


;; Weighted dispatch
;; =================
(defn- weight-fn*
  [^Random rnd mappings]
  (let [cdf (->> mappings
                 (group-by first)     ;; there could be duplicate weights
                 internal/build-cdf)
        high (-> cdf peek first)]

    (internal/compile-cdf cdf #(internal/rand-int-uniform rnd 0 high))))


(defn weight-fn
  "Takes a java.util.Random object (or nil), followed by pairs of mappings (weight => fn/value),
   and returns a function which will dispatch to the appropriate fn, according to the weights provided.
   Unlike probabilities, weights can be arbitrarily large (positive) values, and need not sum to 1.
   They don't have any meaning on their own, only when compared to other weights. Therefore the effect
   of a weight is always proportional (a weight 2 will fire roughly half the times than a weight 4).
   If the weight maps to a non-fn (test via `fn?`), it will be converted to one (via `constantly`).
   The function returned accepts variadic arguments, so it will never complain. However, realistically
   the arguments passed to it should be able to slot-in nicely to any of the functions provided in the
   mappings, which in turn means that those functions should have rather similar (or open) argument lists.
   Performance impact is virtually negligible, since the only extra work that the returned function has to do,
   is to generate a couple of random numbers, and find the right fn to invoke."
  [^Random rnd & weights]

  (assert (every? (every-pred pos? integer?)
                  (map first weights))
          "Non-positive, or non-integer weight(s) detected!")

  (weight-fn* (or rnd (Random. 47)) weights))
