(ns everida.core.msg-filters
  "Core messages (events\requests) filtering. Supports msg-filters as
  * :core/any - will satisfy any message
  * keyword - will compare with :qn of message
  * #{keyword+ } - will apply to :qn of message
  * map - will be used as mask for message
  * DTL-query - will be used with comparison transformer over message
  NOTE: :nsqn key will be addded to a message map, with value of keywordized namespace of qn"
  (:require
    [everida.dtl.core :as dtl]
    [everida.dtl.transformers.comparison :as comparison]))

(def empty-filter :core/any)

(defn filter-empty? [msg-filter]
  (= msg-filter empty-filter))

(defn get-msqn [qn]
  (when-let [ns (namespace qn)] (keyword ns)))

(defn add-nsqn [msg]
  (assoc msg :nsqn (get-msqn (:qn msg))))

(defn rule->qn [rule]
  (let [path (first (second rule))]
    (when (or (= path :qn) (= path [:qn]))
      (first rule))))

(defn rules->qn [rules]
  (let [qn (if (= :dtlrules (first rules))
             (->> rules
                  (remove keyword?)
                  (remove map?)
                  (some rule->qn))
             (rule->qn rules))]
    (when (keyword? qn) qn)))

(defn msg-filter->qn [msg-filter]
  (cond
    (keyword? msg-filter)
    msg-filter
    (and (vector? msg-filter) (seq msg-filter))
    (rules->qn msg-filter)
    (map? msg-filter)
    (:qn msg-filter)))

(defn msg-filter->fn [msg-filter]
  (cond
    (filter-empty? msg-filter)
    (fn [_] true)
    (keyword? msg-filter)
    (fn [msg-data] (= (:qn msg-data) msg-filter))
    (and (set? msg-filter) (seq msg-filter) (every? keyword? msg-filter))
    (fn [msg-data] (msg-filter (:qn msg-data)))
    (and (vector? msg-filter) (seq msg-filter))
    (fn [msg-data]
      (dtl/transform (add-nsqn msg-data) msg-filter comparison/transformer))
    (and (map? msg-filter) (seq msg-filter))
    (let [dtl-rules (dtl/map->dtl msg-filter)]
      (fn [msg-data]
        (dtl/transform (add-nsqn msg-data) dtl-rules comparison/transformer)))
    :else
    (throw (ex-info "Wrong msg filter spec" {:msg-filter msg-filter}))))

(defn msg-filter->transducer [msg-filter]
  (filter (msg-filter->fn msg-filter)))

