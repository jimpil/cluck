(ns cluck.core
  (:require [cluck.internal :as internal]
            [cluck.markov-chain :as mc])
  (:import  [java.util Random]))

;; Random dispatch
;; ===============
(defn rand-fn*
  [^Random rnd fns]
  (fn [& args]
    (-> rnd
        (internal/rand-nth-uniform fns)
        (apply args))))

(defn rand-fn
  "Returns a function which, whenever called,
   will randomly dispatch to one of the provided functions.
   If a supplied function is not a fn (test via `fn?`),
   it will be converted to one (via `constantly`)."
  [^Random rnd & fns]
  (rand-fn* (or rnd (Random. internal/RND_SEED))
            (mapv internal/->fn fns)))

;; Probabilistic dispatch
;; ======================
(defn prob-fn*
  [^Random rnd mappings]
  (let [cdf (->> mappings
                 (group-by first)     ;; there could be duplicate probabilities
                 internal/else->prob  ;; check for :else clause
                 internal/build-cdf)] ;; finally build the CDF

    (internal/compile-cdf cdf rnd #(.nextDouble rnd))))


(defn prob-fn
  "Takes a java.util.Random object (or nil), followed by pairs of mappings (probability => fn/value),
   and returns a function which will (probabilistically) dispatch to the appropriate fn,
   according to the probabilities provided. These must either sum to 1, or an `:else`
   mapping must be provided. If the probability maps to a non-fn (test via `fn?`),
   it will be converted to one (via `constantly`). The function returned accepts variadic arguments,
   so it will never complain. However, realistically the arguments passed to it should be able to
   slot-in nicely to any of the functions provided in the mappings. Performance impact is virtually
   negligible, since the only extra work that the returned function has to do, is to generate
   a couple of random numbers, and find the right fn to invoke."
  [rnd & probabilities]

  (assert
    (let [probs (map first probabilities)]
      (or (some (partial = :else) probs)
          (== 1 (apply + probs))))
    "Probabilities do not sum to 1, and no :else clause was provided!")

  (prob-fn* (or rnd (Random. internal/RND_SEED)) probabilities))


;; Weighted dispatch
;; =================
(defn weight-fn*
  [^Random rnd mappings]
  (let [cdf (->> mappings
                 (group-by first)     ;; there could be duplicate weights
                 internal/build-cdf)
        high (or (-> cdf meta :high)
                 (-> cdf peek first))]

    (internal/compile-cdf cdf rnd #(internal/rand-int-uniform rnd 0 high))))


(defn weight-fn
  "Takes a java.util.Random object (or nil), followed by pairs of mappings (weight => fn/value),
   and returns a function which will (proportionally) dispatch to the appropriate fn,
   according to the weights provided. Unlike probabilities, weights can be arbitrarily large
   (positive) values, and need not sum to 1. They don't have any meaning on their own,
   only when compared to other weights. Therefore the effect of a weight is always proportional
   (a weight 2 will fire roughly half the times than a weight 4). If the weight maps to a non-fn
   (test via `fn?`), it will be converted to one (via `constantly`). The function returned accepts variadic arguments,
   so it will never complain. However, realistically the arguments passed to it should be able to slot-in nicely
   to any of the functions provided in the mappings. Performance impact is virtually negligible,
   since the only extra work that the returned function has to do, is to generate a couple of random numbers,
   and find the right fn to invoke."
  [^Random rnd & weights]

  (assert (every? (every-pred pos? integer?)
                  (map first weights))
          "Non-positive, or non-integer weight(s) detected!")

  (weight-fn* (or rnd (Random. internal/RND_SEED)) weights))


(defn mc-fn ;; Markov-Chain
  ""
  [n states]

  (assert (pos-int? n) "<n> must be a positive integer!")
  (assert (sequential? states) "<states> must be a map (prebuilt n-grams), or something sequential (raw states)!")

  (let [[matrix-n & _] (mc/n-matrices n true states)
        matrix-n (force matrix-n)]

    (fn [state-seq next-state]
      (if-let [observed (get matrix-n state-seq)]
        (/ (get observed next-state)  ;; state frequency
           (apply + (vals observed))) ;; total
        0))) ;; zero probability for anything non-observed
  )


(defn mcmc-fn ;; Markov-Chain-Monte-Carlo
  "Takes a java.util.Random object (or nil), an order <n>,
   and a sequence of <states> (e.g observed or desired).
   Returns a function which will (stochastically) produce random states,
   according to their n-order observed transitions (derived from the provided states).
   The returned function can be called with 0 (random initial state),
   or 1 (specific initial state), or 2 arguments (initial-state & a boolean indicating
   whether to stop the simulation when an unseen state is produced - defaults to false,
   in which case the n-1 order chain will be considered for sampling),
   and returns a potentially infinite sequence of possible next states (Monte-Carlo simulation)."

  [^Random rnd n states]

  (assert (pos-int? n) "<n> must be a positive integer!")
  (assert (or (map? states)
              (sequential? states)) "<states> must be a map (prebuilt n-grams), or something sequential (raw states)!")

  (let [delayed? (sequential? states)
        [matrix-n & lower-n] (mc/n-matrices n delayed? states)
        rnd (or rnd (Random. internal/RND_SEED))]

    (partial mc/simulate
             ;; realise the (requested) n-order matrix
             (apply conj [(cond-> matrix-n delayed? force)] lower-n)
             rnd)))

