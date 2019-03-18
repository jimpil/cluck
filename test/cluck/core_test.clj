(ns cluck.core-test
  (:require [clojure.test :refer :all]
            [fudje.sweet :as sweet]
            [cluck.core :refer :all])
  (:import [java.util Random]))

(deftest weight-fn-tests

  (testing "a simple `weight-fn` example - allowing 5% tolerance"
    (let [f (weight-fn (Random. (rand-int 1000))
                       [1 (constantly :a)]
                       [2 (constantly :b)]   ;; return :b twice as many times as :a
                       [4 (constantly :c)])  ;; return :c twice as many times as :b
          freqs (frequencies (repeatedly 10000 f))]

      (is (compatible
            (sweet/just {:a (sweet/roughly 1400 1400/20)
                         :b (sweet/roughly 2850 2850/20)
                         :c (sweet/roughly 5750 5750/20)})
            freqs))
      )
    )
  )

(deftest prob-fn-tests

  (testing "a simple `prob-fn` example - allowing 5% tolerance"
    (let [f (prob-fn (Random. (rand-int 1000))
                     [0.4 (constantly :a)]   ;; return :a 40% of the time
                     [0.4 (constantly :b)]   ;; return :b 40% of the time
                     [:else (constantly :c)]);; return :c 20% of the time
          freqs (frequencies (repeatedly 10000 f))]

      (is (compatible
            (sweet/just {:a (sweet/roughly 4000 4000/20)
                         :b (sweet/roughly 4000 4000/20)
                         :c (sweet/roughly 2000 2000/20)})
            freqs))
      )
    )

  (testing "a more involved `prob-fn` example - allowing 5% tolerance"
    ;; https://rosettacode.org/wiki/Probabilistic_choice

    (let [names [:aleph :beth :gimel :daleth :he :waw :zayin :heth]
          probs [1/5 1/6 1/7 1/8 1/9 1/10 1/11 1759/27720]
          prob-clauses (zipmap probs names) ;; we can use a map because they are all unique!
          f (apply prob-fn (Random. (rand-int 1000)) prob-clauses)
          freqs (frequencies (repeatedly 1000000 f))]

      (is (compatible
            (sweet/just {:aleph  (sweet/roughly 200000 200000/20)
                         :beth   (sweet/roughly 166666 166666/20)
                         :gimel  (sweet/roughly 142857 142857/20)
                         :daleth (sweet/roughly 125000 125000/20)
                         :he     (sweet/roughly 111111 111111/20)
                         :waw    (sweet/roughly 100000 100000/20)
                         :heth   (sweet/roughly 63456  63456/20)
                         :zayin  (sweet/roughly 90909  90909/20)})
            freqs))
      )
    )
  )

(defn- slot-machine
  [gen-fn n choices]
  (->> choices
       (apply gen-fn (Random. (rand-int 1000)))
       (partial repeatedly n)))

(defn take-until
  "Returns a lazy sequence of successive items from <coll> until
  `(pred item)` returns true, including that item. `pred` must be free of side-effects.
   Returns a transducer when no collection is provided."
  ([pred]
   (fn [rf]
     (fn
       ([] (rf))
       ([result] (rf result))
       ([result input]
        (if (pred input)
          (ensure-reduced (rf result input))
          (rf result input))))))
  ([pred coll]
   (lazy-seq
     (when-let [s (seq coll)]
       (if (pred (first s))
         (cons (first s) nil)
         (cons (first s)
               (take-until pred (rest s))))))))


(deftest slot-machines
  (testing "fair VS biased slot-machines"

    (let [fair-slot-machine (slot-machine rand-fn 3 [:star :cherry :banana :cat :dollar])
          biased-slot-machine (slot-machine weight-fn 3 [[2 :star]
                                                         [2 :cherry]
                                                         [2 :banana]
                                                         [2 :cat]
                                                         [1 :dollar]]) ;; jackpot symbol - double payout

          fair-jackpot (->> fair-slot-machine
                            repeatedly
                            (take-until (partial apply = :dollar))
                            count)
          biased-jackpot (->> biased-slot-machine
                              repeatedly
                              (take-until (partial apply = :dollar))
                              count)]
      ;; it took more attempts for jackpot
      (is (> biased-jackpot fair-jackpot))
      )
    )
  )

;; the same stuff as above, but for REPL experimentation
(comment
  ;; simple example
  (let [f (prob-fn nil
                   [0.4 (constantly :a)]   ;; return :a 40% of the time
                   [0.4 (constantly :b)]   ;; return :b 40% of the time
                   [:else (constantly :c)])] ;; return :c the rest (20%) of the time
    (frequencies
      (repeatedly 10000 f)))

  {:a 3956,
   :b 4043,
   :c 2001}

  ;; more involved example
  ;; https://rosettacode.org/wiki/Probabilistic_choice
  (let [names [:aleph :beth :gimel :daleth :he :waw :zayin :heth]
        probs [1/5 1/6 1/7 1/8 1/9 1/10 1/11 1759/27720]
        ;; we can use zipmap because they are all unique!
        prob-clauses (zipmap probs names)
        f (apply prob-fn nil prob-clauses)]
    (frequencies
      (repeatedly 1000000 f)))

  {:aleph 199818,
   :zayin 91517,
   :he 110849,
   :beth 166470,
   :gimel 142702,
   :waw 100379,
   :heth 63187,
   :daleth 125078}


  )