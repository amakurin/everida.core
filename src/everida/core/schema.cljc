(ns everida.core.schema)
;; TODO: schema does exactly what clojure.spec does. And, as soon as clojure.spec exits now
;; schema fun-ty should be based on clojure.spec


(def data-types
  {:data-type/string
   {:predicate string?}
   :data-type/map
   {:predicate map?}
   :data-type/vector
   {:predicate vector?}
   :data-type/set
   {:predicate set?}
   :data-type/long
   {:predicate #?(:clj  (fn [v] (instance? Long v))
                  :cljs number?)}
   :data-type/double
   {:predicate #?(:clj  (fn [v] (instance? Double v))
                  :cljs number?)}
   :data-type/decimal
   {:predicate #?(:clj  decimal?
                  :cljs number?)}
   :data-type/number
   {:predicate number?}
   :data-type/boolean
   {:predicate #(or (true? %) (false %))}
   :data-type/keyword
   {:predicate keyword?}
   :data-type/instant
   {:predicate (fn [v]
                 (= (type v) #?(:clj java.util.Date :cljs js/Date)))}
   :data-type/uuid
   {:predicate (fn [v]
                 (= (type v) #?(:clj java.util.UUID :cljs cljs.core/UUID)))}
   })

(defn make-err-code [vark & [ns]]
  (let [ns (or ns "constraint.violation")]
    (keyword (str ns "." (namespace vark)) (name vark))))

(defn make-result [vark & [varv & addition-k-v]]
  (merge
    {:code (make-err-code vark)}
    (when varv {vark varv})
    (->>
      addition-k-v
      (partition 2)
      (map vec)
      (into {}))))

(defmulti validate-variant (fn [variant _ & _] (first variant)))

(defn errors? [sch v & [context]]
  (loop [variants (if (keyword? (first sch)) [sch] sch)]
    (when-let [variant (first variants)]
      (if-let [err (validate-variant variant v context)]
        [err]
        (recur (rest variants))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;A couple of macros for convenience
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn flatten-map-errors [errors]
  (->>
    errors
    (mapcat (fn [err]
              (if (and (map? err) (not (contains? err :code)))
                (mapcat (fn [[k v]]
                          (map (fn [e] (assoc e :field k)) v)) err)
                [err])))
    vec))

(defmacro comp-schema [& variants]
  `(->> [~@variants]
        (map (fn [v#] (cond
                        (map? v#) (seq v#)
                        (keyword? (first v#)) [v#]
                        :else v#)))
        (apply concat)
        vec))

(defmacro def-schema [schema-name & variants]
  `(def ~schema-name
     (comp-schema ~@variants)))

(defmacro with-context-schema [schema v context & [form]]
  (let [body (list form)]
    `(try
       (if-let [errors# (errors? ~schema ~v ~context)]
         {:status :error :errors (flatten-map-errors errors#)}
         {:status :success :result ~@body})
       (catch #?(:clj Exception :cljs js/Error) e#
         {:status :error :errors [{:code :exception
                                   :exeption
                                         {:code :internal-exception
                                          :info #?(:clj  (.getMessage e#)
                                                   :cljs e#)}}]}))))
(defmacro with-schema [schema v & form]
  `(with-context-schema ~schema ~v nil ~@form))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Predefind constraints
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def required-constraint
  [:data/requirement :data-requirement/required])

(def data-type-vector
  [:data/type :data-type/vector])

(def data-type-map
  [:data/type :data-type/map])

(def data-type-string
  [:data/type :data-type/string])

(def data-type-number
  [:data/type :data-type/number])

(def data-type-boolean
  [:data/type :data-type/boolean])

(def data-type-keyword
  [:data/type :data-type/keyword])

(def data-type-kw-or-num
  [:data/alternative [data-type-number data-type-keyword]])

(defn t [type]
  [:data/type type])

(defn t-rq [type]
  (comp-schema
    required-constraint
    (t type)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn required [schema & [requirement]]
  (vec (cons [:data/requirement (or requirement :data-requirement/required)]
             schema)))

(defn intersection [s1 s2]
  (->> s1
       (filter s2)
       set))

(defn difference [s1 s2]
  (->> s1
       (remove s2)
       set))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Generic data
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmethod validate-variant :data/type
  [[vk vv] v & _]
  (when v
    (let [dt-spec (get data-types vv)]
      (when-not (and dt-spec ((:predicate dt-spec) v))
        (make-result vk vv)))))

(defmethod validate-variant :data/requirement
  [[vk vv] v & [context]]
  (let [parent (:data/parent context)]
    (cond
      (and (= vv :data-requirement/required) (nil? v))
      (make-result vk vv)
      (and (map? vv) (:data-requirement/exclusive vv) parent)
      (let [exclusive-set (-> vv :data-requirement/exclusive
                              vector flatten set)
            k-set (-> parent keys set)
            intersection (intersection exclusive-set k-set)]
        (when
          (or
            (and (nil? v) (every? (fn [k] (nil? (k parent))) intersection))
            (and (not (nil? v)) (some (fn [k] (k parent))
                                      (disj intersection
                                            (:data/current-field context)))))
          (make-result vk vv)))
      (and (map? vv) (:data-requirement/alternative vv) parent)
      (let [alternative-set (-> vv :data-requirement/alternative
                                vector flatten set)
            k-set (-> parent keys set)
            intersection (intersection alternative-set k-set)]
        (when (and (nil? v) (every? (fn [k] (nil? (k parent))) intersection))
          (make-result vk vv))))))

(defmethod validate-variant :data/field-dependency
  [[vk vv] v context]
  (when v
    (let [required-field (:data/dependency-field vv)]
      (when (nil? (get-in context [:data/parent required-field]))
        (make-result vk required-field)))))

(defmethod validate-variant :data/alternative
  [[vk & vv] v context]
  (when v
    (when (every? (fn [schema] (errors? schema v context)) vv)
      (make-result vk vv))))

(defmethod validate-variant :data/enum
  [[vk & vv] v _]
  (when v
    (when (nil? ((set vv) v))
      (make-result vk vv))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Seq
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn not-in-range [v [min max]]
  (or (and min (< v min))
      (and max (> v max))))

(defmethod validate-variant :seq/not-empty
  [[vk _] v & _]
  (when (empty? v)
    (make-result vk)))

(defmethod validate-variant :seq/count
  [[vk {:keys [min max] :as vv}] v & _]
  (let [v (count v)]
    (when (not-in-range v [min max])
      (make-result vk vv))))

(defmethod validate-variant :seq/each
  [[vk schema] v & [context]]
  (let [context (merge context {:data/parent v})]
    (->> v
         (map (fn [vv]
                (errors? schema vv context)))
         (remove nil?)
         (#(when (seq %) (make-result vk schema)))
         )))

(defmethod validate-variant :seq/destruct
  [[vk & schemas] v & [context]]
  (when-let
    [[_ s _]
     (->>
       schemas
       (map (fn [vi s] [vi s]) v)
       (some (fn [[vi s]]
               (when-let [errors (errors? s vi context)]
                 [vi s errors]))))]
    (make-result vk s)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Maps
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmethod validate-variant :map/spec-closed
  [[_ spec] v & [context]]
  (when v
    (let [vks (-> v keys set)
          sks (-> spec keys set)
          diff (difference vks sks)]
      (if (seq diff)
        (make-result :map/unsupported-keys (vec diff))
        (validate-variant [:map/spec spec] v context)))))

(defmethod validate-variant :map/spec
  [[_ spec] v & [context]]
  (->> spec
       (map (fn [[mk mv]]
              [mk (errors?
                    mv (get v mk)
                    (merge context {:data/parent        v
                                    :data/current-field mk}))]))
       (remove #(->> % second nil?))
       (#(when (seq %) (into {} %)))))

(defmethod validate-variant :map/each
  [[vk [k-schema v-schema]] v & [context]]
  (when-let
    [_ (some (fn [[k v]]
               (when-let [error (or
                                  (errors? k-schema k context)
                                  (errors? v-schema v context))]
                 error)) v)]
    (make-result vk [k-schema v-schema])))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Default
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmethod validate-variant :default
  [[vk vv] v & [context]]
  (if-let [vf (get-in context [:schema/custom-validators vk])]
    (vf [vk vv] v context)
    (throw (ex-info (str "Unknown validator found: " vk)
                    {:validator [vk vv]
                     :context   context
                     :value     v}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;String
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn string-pre-check [v] (and v (string? v)))

(defmethod validate-variant :string/not-empty
  [[vk _] v & [{:keys [:data/parent :data/current-field]}]]
  (when (or
          (and parent current-field (contains? parent current-field)
               (or (nil? v) (empty? (.trim v))))
          (and (or (nil? parent) (nil? current-field))
               (or (nil? v) (empty? (.trim v)))))
    (make-result vk)))

(defmethod validate-variant :string/length
  [[vk {:keys [min max] :as vv}] v & _]
  (when (string-pre-check v)
    (let [length #?(:clj (.length v) :cljs (.-length v))]
      (when (not-in-range length [min max])
        (make-result vk vv)))))

(defn match-regexp [pattern v]
  (re-find (re-pattern pattern) v))

(defmethod validate-variant :string/regexp
  [[vk vv] v & _]
  (when (string-pre-check v)
    (let [match (match-regexp vv v)]
      (when (not (and match (= match v)))
        (make-result vk vv)))))

(defmethod validate-variant :string/regexp-required
  [[vk vv] v & _]
  (when (string-pre-check v)
    (let [match (match-regexp vv v)]
      (when (nil? match)
        (make-result vk vv)))))

(defmethod validate-variant :string/regexp-prohibited
  [[vk vv] v & _]
  (when (string-pre-check v)
    (let [match (match-regexp vv v)]
      (when match
        (make-result vk vv :found-match match)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Number
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn number-pre-check [v] (and v (number? v)))

(defmethod validate-variant :number/range
  [[vk {:keys [min max] :as vv}] v & _]
  (when (number-pre-check v)
    (when (not-in-range v [min max])
      (make-result vk vv))))


