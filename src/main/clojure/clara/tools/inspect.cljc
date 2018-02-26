(ns clara.tools.inspect
  "Tooling to inspect a rule session. The two major methods here are:

   * inspect, which returns a data structure describing the session that can be used by tooling.
   * explain-activations, which uses inspect and prints a human-readable description covering
     why each rule activation or query match occurred."
  (:require #?(:clj [clara.rules.engine :as eng])
            #?(:cljs [clara.rules.engine :as eng :refer [RootJoinNode
                                                         HashJoinNode
                                                         ExpressionJoinNode
                                                         NegationNode
                                                         NegationWithJoinFilterNode]])
            [clara.rules.schema :as schema]
            [clara.rules.memory :as mem]
            #?(:cljs [goog.string :as gstr])
            [schema.core :as s])
  #?(:clj
    (:import [clara.rules.engine
              RootJoinNode
              HashJoinNode
              ExpressionJoinNode
              NegationNode
              NegationWithJoinFilterNode])))

(s/defschema ConditionMatch
  "A structure associating a condition with the facts that matched them.  The fields are:
   :fact - A fact propagated from this condition in a rule or query.  For non-accumulator conditions,
           this will be the fact matched by the condition.  For accumulator conditions, it will be the result
           of the accumulation.  So, for example, if we have a condition like

           [?cold <- Cold]

           a ConditionMatch for this condition will have a Cold fact in its :fact field.  If we have a condition like

           [?min-cold <- (acc/min :temperature) :from [Cold]]
     
           the value of :fact will be the minimum temperature returned by the accumulator.

    :condition - A structure representing this condition.  This is the same structure used inside the structures defining
                 rules and queries.

    :facts-accumulated (nullable) : When the condition is an accumulator condition, this will contain the individual facts over 
                                    which the accumulator ran.  For example, in the case above with the condition

                                    [?min-cold <- (acc/min :temperature) :from [Cold]]
                                    
                                    this will contain the individual Cold facts over which we accumulated, while the :fact field
                                    will contain the result of the accumulation."
  {:fact s/Any
   :condition schema/Condition
   (s/optional-key :facts-accumulated) [s/Any]})

;; A structured explanation of why a rule or query matched.
;; This is derived from the Rete-style tokens, but this token
;; is designed to propagate all context needed to easily inspect
;; the state of rules.  
(s/defrecord Explanation [matches :- [ConditionMatch]
                          bindings :- {s/Keyword s/Any}]) ; Bound variables

;; Schema of an inspected rule session.
(def InspectionSchema
  {:rule-matches {schema/Rule [Explanation]}
   :query-matches {schema/Query [Explanation]}
   :condition-matches {schema/Condition [s/Any]}
   :insertions {schema/Rule [{:explanation Explanation :fact s/Any}]}})

(defn- get-condition-matches
  "Returns facts matching each condition"
  [nodes memory]
  (let [node-class->node-type (fn [node]
                                (get {ExpressionJoinNode :join
                                      HashJoinNode :join
                                      RootJoinNode :join
                                      NegationNode :negation
                                      NegationWithJoinFilterNode :negation} (type node)))

        join-node-ids (for [beta-node nodes
                            :let [node-type (node-class->node-type beta-node)]
                            ;; Unsupported and irrelevant node types will have a node-type of nil
                            ;; since the map in node-class->node-type won't contain an entry
                            ;; for them, so this check will remove them.
                            :when (contains? #{:join :negation}
                                             node-type)]
                        [(:id beta-node) (:condition beta-node) node-type])]
    (reduce
     (fn [matches [node-id condition node-type]]
       (update-in matches
                  (condp = node-type

                    :join
                    [condition]

                    ;; Negation nodes store the fact that they are a negation
                    ;; in their :node-type and strip the information out of the
                    ;; :condition field.  We reconstruct the negation boolean condition
                    ;; that is contained in rule and query data structures created by defrule
                    ;; and that conforms to the Condition schema.
                    :negation
                    [[:not condition]])
                  concat (map :fact (mem/get-elements-all memory {:id node-id}))))
     {}
     join-node-ids)))

(defn- to-explanations
  "Helper function to convert tokens to explanation records."
  [session tokens]
  (let [memory (-> session eng/components :memory)
        id-to-node (get-in (eng/components session) [:rulebase :id-to-node])]

    (for [{:keys [matches bindings] :as token} tokens]
      (->Explanation
       ;; Convert matches to explanation structure.
       (for [[fact node-id] matches
             :let [node (id-to-node node-id)
                   condition (if (:accum-condition node)

                               {:accumulator (get-in node [:accum-condition :accumulator])
                                :from {:type (get-in node [:accum-condition :from :type])
                                       :constraints (or (seq (get-in node [:accum-condition :from :original-constraints]))
                                                        (get-in node [:accum-condition :from :constraints]))}}

                               {:type (:type (:condition node))
                                :constraints (or (seq (:original-constraints (:condition node)))
                                                 (:constraints (:condition node)))})]]
         (if (:accum-condition node)
           {:fact fact
            :condition condition
            :facts-accumulated (eng/token->matching-elements node memory token)}
           {:fact fact
            :condition condition}))

       ;; Remove generated bindings from user-facing explanation.
       (into {} (remove (fn [[k v]]
                          #?(:clj (.startsWith (name k) "?__gen__"))
                          #?(:cljs (gstr/startsWith (name k) "?__gen__")))
                        bindings))))))

(defn ^:private gen-fact->explanations
  [session]

  (let [{:keys [memory rulebase]} (eng/components session)
        {:keys [productions production-nodes query-nodes]} rulebase
        rule-to-rule-node (into {} (for [rule-node production-nodes]
                                           [(:production rule-node) rule-node]))]
    (apply merge-with into
           (for [[rule rule-node] rule-to-rule-node
                 token (keys (mem/get-insertions-all memory rule-node))
                 insertion-group (mem/get-insertions memory rule-node token)
                 insertion insertion-group]
             {insertion [{:rule rule
                          :explanation (first (to-explanations session [token]))}]}))))
        
(s/defn inspect
  " Returns a representation of the given rule session useful to understand the
   state of the underlying rules.

   The returned structure includes the following keys:

   * :rule-matches -- a map of rule structures to their matching explanations.
   * :query-matches -- a map of query structures to their matching explanations.
   * :condition-matches -- a map of conditions pulled from each rule to facts they match.
   * :insertions -- a map of rules to a sequence of {:explanation E, :fact F} records
     to allow inspection of why a given fact was inserted.
   * :fact->explanations -- a map of facts inserted to a sequence 
     of maps of the form {:rule rule-structure :explanation explanation}, 
     where each such map justifies a single insertion of the fact.

   Users may inspect the entire structure for troubleshooting or explore it
   for specific cases. For instance, the following code snippet could look
   at all matches for some example rule:

   (defrule example-rule ... )

   ...

   (get-in (inspect example-session) [:rule-matches example-rule])

   ...

   The above segment will return matches for the rule in question."
  [session] :- InspectionSchema
  (let [{:keys [memory rulebase]} (eng/components session)
        {:keys [productions production-nodes query-nodes id-to-node]} rulebase

        ;; Map of queries to their nodes in the network.
        query-to-nodes (into {} (for [[query-name query-node] query-nodes]
                                  [(:query query-node) query-node]))

        ;; Map of rules to their nodes in the network.
        rule-to-nodes (into {} (for [rule-node production-nodes]
                                 [(:production rule-node) rule-node]))]

    {:rule-matches (into {}
                         (for [[rule rule-node] rule-to-nodes]
                           [rule (to-explanations session
                                                  (mem/get-tokens-all memory rule-node))]))

     :query-matches (into {}
                          (for [[query query-node] query-to-nodes]
                            [query (to-explanations session
                                                    (mem/get-tokens-all memory query-node))]))

     :condition-matches (get-condition-matches (vals id-to-node) memory)

     :insertions (into {}
                       (for [[rule rule-node] rule-to-nodes]
                         [rule
                          (for [token (keys (mem/get-insertions-all memory rule-node))
                                insertion-group (get (mem/get-insertions-all memory rule-node) token)
                                insertion insertion-group]
                            {:explanation (first (to-explanations session [token])) :fact insertion})]))

     :fact->explanations (gen-fact->explanations session)}))

(defn- explain-activation
  "Prints a human-readable explanation of the facts and conditions that created the Rete token."
  ([explanation] (explain-activation explanation ""))
  ([explanation prefix]
     (doseq [{:keys [fact condition]} (:matches explanation)]
       (if (:from condition)
         ;; Explain why the accumulator matched.
         (let [{:keys [accumulator from]} condition]
           (println prefix fact)
           (println prefix "  accumulated with" accumulator)
           (println prefix "  from" (:type from))
           (println prefix "  where" (:constraints from)))

         ;; Explain why a condition matched.
         (let [{:keys [type constraints]} condition]
           (println prefix fact)
           (println prefix "  is a" type)
           (println prefix "  where" constraints))))))

(defn explain-activations
  "Prints a human-friendly explanation of why rules and queries matched in the given session.
  A caller my optionally pass a :rule-filter-fn, which is a predicate

  (clara.tools.inspect/explain-activations session
         :rule-filter-fn (fn [rule] (re-find my-rule-regex (:name rule))))"

  [session & {:keys [rule-filter-fn] :as options}]
  (let [filter-fn (or rule-filter-fn (constantly true))]

    (doseq [[rule explanations] (:rule-matches (inspect session))
            :when (filter-fn rule)
            :when (seq explanations)]
      (println "rule"  (or (:name rule) (str "<" (:lhs rule) ">")))
      (println "  executed")
      (println "   " (:rhs rule))
      (doseq [explanation explanations]
        (println "  with bindings")
        (println "    " (:bindings explanation))
        (println "  because")
        (explain-activation explanation "    "))
      (println))

    (doseq [[rule explanations] (:query-matches (inspect session))
            :when (filter-fn rule)
            :when (seq explanations)]
      (println "query"  (or (:name rule) (str "<" (:lhs rule) ">")))
      (doseq [explanation explanations]
        (println "  with bindings")
        (println "    " (:bindings explanation))
        (println "  qualified because")
        (explain-activation explanation "    "))
      (println))))