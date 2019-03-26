# cluck

<img src="https://cdn.weasyl.com/~unfilledflag/submissions/1465908/53f99edba29057b2c740e3d679ebd3aae2947d3909b451654262679f809e9e35/unfilledflag-cluck-cluck-mother-hen.jpg">

## What

A dead-simple Clojure library offering function dispatch based on randomness or specific weights/probabilities.

## Where
FIXME

## How

The core namespace (`cluck.core`) is the only one required for typical usage, and it exposes three functions:

### cluck.core/rand-fn \[rnd \& fns\]
Takes a `java.util.Random` object (or `nil`) followed by some functions (or static values), and returns a new function which, whenever called, will randomly call one of the functions provided.

#### Example

```clj
(->> (rand-fn nil (constantly :a) 
                  (constantly :b) 
                  (constantly :c))
     (repeatedly 10000) 
     frequencies)
     
=> {:a 3386
    :b 3236 
    :c 3378}    


```

### cluck.core/prob-fn \[rnd \& probs\]
Takes a `java.util.Random` object (or `nil`) followed by some probability to function (or static value) mappings (seq of 2 elements). Naturally, probabilities must sum to 1. Returns a new function which, whenever called, will call one of the functions provided according to their respective probabilities. 

#### Example

```clj
(let [f (prob-fn nil ;; pass your own `java.util.Random` for repeatability 
                 [0.4 (constantly :a)]     ;; return :a 40% of the time
                 [0.4 (constantly :b)]     ;; return :b 40% of the time
                 [:else (constantly :c)])] ;; return :c the remaining (20%) of the time
  (frequencies 
    (repeatedly 10000 f)))
  
=> {:a 3956
    :b 4043
    :c 2001}
```

### cluck.core/weight-fn \[rnd \& weights\]
Takes a `java.util.Random` object (or `nil`) followed by some weight to function (or static value) mappings (seq of 2 elements). Returns a new function which, whenever called, will call one of the functions provided according to their respective weights. Unlike probabilities, weights need not sum to 1. That's because a weight means nothing when considered in isolation - it's only the ratio of two weights that matters. For instance, we expect a function weighted 6  to fire roughly twice as often than a function weighted 3, three times as often than a function weighted 2, and half the times than a function weighted 12. 

#### Example

```clj
let [f (weight-fn nil ;; pass your own `java.util.Random` for repeatability
                  [1 (constantly :a)]
                  [2 (constantly :b)]   ;; return :b twice as many times as :a
                  [4 (constantly :c)])] ;; return :c twice as many times as :b
  (frequencies 
    (repeatedly 10000 f)))
    
=> {:a 1394
    :b 2800
    :c 5806}  

```

### cluck.core/mcmc-fn \[rnd n-order states\]
Takes a `java.util.Random` object (or `nil`) followed by a sequence of observed/proposed/desired states (stationary distribution), and returns a function representing an nth-order Markov-Chain Monte-Carlo simulation. As such, it will produce random states in a stochastic manner (according to their previously observed transitions). It can be called with 0 (random initial state), 1 (specific initial state), or 2 arguments (initial-state & a boolean indicating whether to stop the simulation when an unseen state is produced - defaults to false), and returns a potentially infinite sequence of possible next states.


#### Example

```clj
;; let's build a high quality spam generator from some free literature

(require '[clojure.string :as str])

(def three-men-words
  (as-> "https://www.gutenberg.org/files/308/308-0.txt" $it
        (java.net.URL. $it)
        (slurp $it)
        (str/replace $it \’ \') ;; replace the right single-quotes with regular ones
        (re-seq #"\w+'?[a-zA-Z]*|[,.!?\-]" $it) ;; keep punctuation as separate tokens and avoid splitting on single-quotes
        (map str/trim $it)))
       
;; (count three-men-words)       => 81746
;; (count (set three-men-words)) => 7879       

(def generate-spam ;; this is our infinite spam generator
  (mcmc-fn nil 3 three-men-words))
  
(defn spam-paragraph [initial-state] 
  (->> initial-state
       generate-spam  
       (take 300)        
       (str/join \space)))  
       
;; now ready to produce some nice spam
(spam-paragraph ["It" "is" "a"]) => 

It is a veritable picture of an old country inn , with green , square courtyard in front , 
where , on seats beneath the trees , the old men shake their heads , for they have heard 
such tales before . And all the evening . Then he and eight other gentlemen of about the 
same age went down in a graceful attitude , and try to hide his feet . My first idea was 
that he , who didn't care for carved oak , should have his drawing - room , or coal - cellars , 
she laughed them all to come and have a smile , saying that he would spare the friends and 
relations , but it takes long practice before you can do with this work . Copyright laws 
in most countries are in a great hurry when he first dawned upon the vision , but , from
 what I have seen of the district , I am fond of locks . They pleasantly break the monotony 
 of the pull . I like to give the youngsters a chance . I notice that most of the conversation 
 being on his part that he knew how funny he was would have completely ruined it all . 
 As we drew nearer , and now drew nearer , and soon the boat from which they were worked 
 lay alongside us . It contained a party of provincial Arrys and Arriets , out for a moonlight 
 row , and the wind blowing a perfect hurricane across it , we felt that the time had come to 
 commence operations . Hector I think that was his name went on pulling while I unrolled the sail . 
 Then , after tea , the wind is consistently in your favour both ways

```



#### Permutations VS observed n-grams
In order to build a proper n-order probability matrix, one has to consider all the n-wise permutations of the unique states, and then adjust against what was observed - potentially assigning some zeros along the way. And that's where smoothing coming in to get rid of these zeros. 

In practice, computing all the possible n-wise permutations of all the unique states can sometimes be difficult, or even intractable. To put things in perspective consider this. According to `ngrams.info` there are around 155 million unique trigrams, and about 15 million unique bigrams (in a 430 million word corpus). We can use those to build two Marko-chains - a second-order from the trigrams, and a first-order from the bigrams. Summing those up, gives us about 160 million elements having to be stored in memory. Contrast that with the almost 1 billion elements we would need to store all the 3-wise permutations of just 1000 unique words (conservative estimate given 430 million words?). Now imagine doing 4-wise permutations! This gets out of hand very quickly, and so it's often preferable to store only what was actually observed, and deal with *useen states* some other way. 

The approach taken in `cluck` is rather simple, and is described in the literature as an `All-kth-order Markov model`. If the caller asks for an `n`th-order Markov-Chain, `cluck` will actually build `n` chains in descending order down to the first-order. At each iteration, if no transitions are found on the current level (i.e. the current sequence of states was not observed in the 'training data'), the prediction will be downgraded (i.e. the next level down will be considered). In the worst case scenario, the last (first-order) Markov-Chain will be considered for direct sampling, at which point we're guaranteed to find a state to transition to. Despite not sounding very intuitive, this approach can sometimes save significant space, partly because it allows for optimisations when implementing it. For instance, we don't need to actually compute all these chains, unless the simulation hits a point where it cannot progress (and we want it to progress). But this ultimately depends on the training-data (the states provided). It may well be the case that the training data is arranged in such a way that the nth-order chain will always be sufficient for direct sampling. In such cases, computing the other, lower-grade, chains can be avoided (i.e. they are wrapped with `delay`). 

This approach may not work great in cases where the state space is not particularly big (and therefore computing all their possible n-wise permutations may actually be preferable), but for whatever reason, not all n-order state transitions appear in the training data. 
    
#### Prebuilt n-grams
There are various (free or paid) online resources that can be used to obtain word n-grams from. These come in various forms, but in essence they represent the same thing - how many times a particular (fixed-size) sequence of words occurs in some corpus. For example, a collection of trigrams could be found as a flat list (e.g. csv file) with values such as `N, Word1, Word2, Word3`. This can be read as `word1 followed by word2 followed by word3, occured N times in the corpus`. We can transform such rows into something `cluck` can work with rather easily. The expected format looks like the following:

```clj
{
  [word1 word2] {word3 32 ...}
  [word3 word4] {word5 56 ...}
  ...
}       
``` 

Once we have such a map, we can build an n-1 order Markov-Chain. If we have access to all the n-grams down to bigrams, we can manually build all the chains that `cluck` would otherwise build, and provide those instead of raw states, like so:

```clj
{
 3 {[word1 word2 word3] {word4 N ...} ...} ;; based on 4-grams
 2 {[word1 word2] {word3 N ...} ...}       ;; based on 3-grams  
 1 {[word1] {word2 N ...} ...}             ;; based on 2-grams
}
``` 

One thing to consider here, is that by doing this we lose any opportunity for lazy-loading.  


## License

Copyright © 2019 Dimitrios Pilliouras

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
