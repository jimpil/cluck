# cluck

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
                  [4 (constantly :c)])  ;; return :c twice as many times as :b]
  (frequencies 
    (repeatedly 10000 f)))
    
=> {:a 1394
    :b 2800
    :c 5806}  

```


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
