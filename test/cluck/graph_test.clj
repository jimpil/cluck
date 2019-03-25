(ns cluck.graph-test
  (:require [clojure.test :refer :all]
            [clojure.string :as string]
            [cluck.graph :refer :all]))


(def ^:private  graph
  {;; init coll - doesn't need to be here at creation time
   :xs #(range 100)
   ;; count
   :n  (node [xs] (count xs))
   ;; mean
   :m  (node [xs n]
             (Thread/sleep 200)
             (/ (apply + xs) n))
   ;; mean-squared
   :m2 (node [xs n]
             (Thread/sleep 200)
             (/ (apply + (map * xs xs)) n))
   ;; variance
   :v  (node [m m2] (- m2 (* m m)))
   ;; standard-deviation
   :sd (node [^double v] (Math/sqrt v))
   })

(def  ^:private  pgraph
  {;; init coll - doesn't need to be here at creation time
   :xs #(range 100)
   ;; count
   :n  (node [xs] (count xs))
   ;; mean
   :m  (pnode [xs n]
              (Thread/sleep 200)
              (/ (apply + xs) n))
   ;; mean-squared
   :m2 (pnode [xs n]
              (Thread/sleep 200)
              (/ (apply + (map * xs xs)) n))
   ;; variance
   :v  (node [m m2] (- m2 (* m m)))
   ;; standard-deviation
   :sd (node [^double v] (Math/sqrt v))
   })


(deftest compute-tests
  (is (= {:m  99/2
          :m2 6567/2
          :n  100
          :v  3333/4
          :sd 28.86607004772212}
         (time (compute-now graph))
         (time (compute-now pgraph))))
  (let [res (time (compute-later graph))]
    (is (== 28.86607004772212
            (time (:sd res)))))

  (comment ;;typical output for the 4 `time` expressions above
    "Elapsed time: 402.622851 msecs"
    "Elapsed time: 402.815111 msecs"
    "Elapsed time: 1.2838 msecs"
    "Elapsed time: 401.397201 msecs"
    )

  )

(def ^:private  cyclic-graph
  {:xs #(range 100)
   ;; count
   :n  (node [xs] (count xs))
   ;; mean
   :m  (node [xs n] (/ (apply + xs) n))
   ;; mean-squared
   :m2 (node [xs v] (/ (apply + (map * xs xs)) v))
   ;; variance
   :v  (node [m m2] (- m2 (* m m)))
   })

(deftest get-cycles-test
  (is (empty? (get-cycles graph)))
  (is (= [[:m2 :v :m2]
          [:v :m2 :v]]
         (get-cycles cyclic-graph)))
  (is (thrown? AssertionError ;; pre-conditions throw assertion errors
               (compute-now cyclic-graph)))
  )

(defn ngrams [n xs]
  (partition n 1 xs))

(def text-mining-graph
  {:text (constantly "The fox jumped over the lazy dog.\nHere come the boogie man!")
   :sentences (pnode [text] (string/split text #"[.!?]\s?"))
   :sentence-tokens (pnode [sentences] (mapv #(string/split  % #" ") sentences))
   :tokens (node [sentence-tokens] (mapcat identity sentence-tokens))
   :word-bigrams (pnode [tokens] (ngrams 2 tokens))
   :word-trigrams (pnode [tokens] (ngrams 3 tokens))
   :char-bigrams (pnode [tokens] (mapv (partial ngrams 2) tokens))
   :char-trigrams (pnode [tokens] (mapv (partial ngrams 3) tokens))
   :names (pnode [sentence-tokens] (Thread/sleep 2000) ["fox" "dog" "man"])
   :sentiments (pnode [sentence-tokens] (Thread/sleep 1200) ["fear"])
   })
