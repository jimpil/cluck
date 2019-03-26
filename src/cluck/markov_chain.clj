(ns cluck.markov-chain
  (:require [cluck.internal :as internal])
  (:import [java.util Random]
           (clojure.lang ITransientCollection)))

(defn- next-state
  "Given a cumulative distribution, select randomly."
  [^Random rnd cdf]
  (let [high (or (-> cdf meta :high) ;; optimisation
                 (-> cdf peek first))
        next! (internal/compile-cdf cdf rnd (partial internal/rand-int-uniform rnd 0 high))]
    (next!)))

(defn- most-specific-cdf
  [state matrices]
  (reduce
    (fn [state matrix]
      (if-let [cdf (cond-> matrix
                           (delay? matrix) deref
                           true (get state))]
        (reduced cdf)
        (not-empty (pop state))))
    state
    matrices))

(defn- generate*
  ""
  [matrices rnd stop-on-unseen? current-state]
  (when-let [cdf (if stop-on-unseen?
                   (get (first matrices) current-state)
                   (most-specific-cdf current-state matrices))]
    (-> current-state
        pop
        (conj (next-state rnd cdf)))))


(defn simulate
  "Monte-Carlo simulation based on Markov Chains."

  ([matrices]
   (simulate matrices (Random. internal/RND_SEED)))

  ([matrices rnd]
   (simulate
     matrices
     rnd
     (internal/rand-nth-uniform rnd (keys (first matrices)))))


  ([matrices rnd initial-state]
   (simulate matrices rnd initial-state false))

  ([matrices rnd initial-state stop-on-unseen?]
   (cond->> (internal/queue initial-state)
            true (iterate (partial generate* matrices rnd stop-on-unseen?))
            stop-on-unseen? (take-while some?)
            true (map first))))

(defn- ngrams
  [n xs]
  (partition n 1 xs))


(defn- transition-matrix
  "Builds an n-order transition matrix with tally-counts as vals."
  [order states]
  (persistent!
    (reduce
      (fn [ret value]
        (let [[past-states [fut-state]] (split-at order value)]
          (internal/update-in! ret [past-states fut-state] internal/safe-inc)))
      (transient (hash-map))
      ;; for an nth-order MC, we need n+1-grams
      (ngrams (inc order) states))))

(defn- counts->cdf
  [counts]
  (let [counts (cond-> counts (internal/transient? counts) persistent!)]
    (internal/build-cdf (group-by val counts) first)))



(defn transition-cdf
  "Builds an n-order transition matrix with cumulative distributions
   (derived from the tally-counts) as vals. Better to pay this cost once!"
  [order states]
  (cond->> states
           ;; states could be ready ngrams (a map)
           ;; in which case we can skip building our own
           (sequential? states)
           (transition-matrix order)

           true
           (internal/map-vals counts->cdf)))

(defn n-matrices
  [n delayed? training]
  (if delayed?
    ;; raw-states - build n-grams from scratch delaying the lower-order ones
    (map #(delay (transition-cdf % training))
         (range n 0 -1)) ;; descending order
    ;; already constructed n-grams - just use them
    (map (fn [[n grams]]
           ;; grams is a map of the form
           ;; {[w1 w2] {w3 17 w4 43} ...} - example trigram transitions
           (transition-cdf n grams))
         (sort-by key > training)))
  )

