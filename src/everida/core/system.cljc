(ns everida.core.system
  (:require [com.stuartsierra.component :as component]
            [everida.core.api :as api]))

(defn- -check-system-units [units]
  (cond
    (->> units (map api/id-key) (apply distinct?) not)
    (throw (ex-info
             "Some system unit's ids are not unique."
             {:ids (mapv api/id-key units)}))

    (seq (remove api/component? units))
    (throw (ex-info
             "Unknown system units were found. Should be IComponent + IModule (optional)."
             {:unknowns (vec (remove api/component? units))}))
    :else
    units))

(defn- -map-deps [deps deps-map]
  {:pre [(vector? deps) (map? deps-map)]}
  (->>
    deps
    (map #(if-let [dep-alias (get deps-map %)]
           [% dep-alias] [% %]))
    (into {})))

(defn- -add-order-dependencies-direct [units]
  (map (fn [u]
         (let [order-pred (api/order-dependency-pred-key u)
               post-start-pred (api/post-start-pred-key u)]
           (if (or order-pred post-start-pred)
             (update
               u :order-deps
               (fn [d]
                 (->>
                   units
                   (remove #(= % u))
                   (filter (fn [test-unit] (or (and order-pred (order-pred test-unit))
                                               (and post-start-pred (post-start-pred test-unit)))))
                   (map api/get-alias)
                   (concat d)
                   distinct
                   vec)))
             u)))
       units))

(defn- -add-order-dependencies-reverse [units]
  (map (fn [u]
         (let [u-alias (api/get-alias u)]
           (->
             u
             (update
               :order-deps
               (fn [d]
                 (->>
                   units
                   (filter api/order-reverse-dependency-pred-key)
                   (map (fn [u] [(api/get-alias u)
                                 (api/order-reverse-dependency-pred-key u)]))
                   (remove #(= (first %) u-alias))
                   (filter #((second %) u))
                   (map first)
                   (concat d)
                   distinct
                   vec))))))
       units))

(defn- -add-dynamic-dependencies-direct [units]
  (map (fn [u]
         (if-let [pred (api/dynamic-dependency-pred-key u)]
           (update
             u api/static-deps-key
             (fn [d]
               (->>
                 units
                 (remove #(= % u))
                 (filter pred)
                 (map api/get-alias)
                 (concat d)
                 distinct
                 vec)))
           u))
       units))

(defn- -add-dynamic-dependencies-reverse [units]
  (map (fn [u]
         (let [u-alias (api/get-alias u)]
           (->
             u
             (update
               api/static-deps-key
               (fn [d]
                 (->>
                   units
                   (filter api/dynamic-reverse-dependency-pred-key)
                   (map (fn [u] [(api/get-alias u)
                                 (api/dynamic-reverse-dependency-pred-key u)]))
                   (remove #(= (first %) u-alias))
                   (filter #((second %) u))
                   (map first)
                   (concat d)
                   distinct
                   vec))))))
       units))

(defn- -build-using-map [units]
  (->> units
       (map (fn [unit]
              (when-let [deps (-> (api/static-deps-key unit)
                                  (concat (:order-deps unit))
                                  distinct seq)]
                [(api/get-alias unit)
                 (-map-deps (vec deps) (or (api/deps-map-key unit) {}))])))
       (keep identity)
       (into {})))

(defn- -build-system-map [units]
  (->>
    units
    (mapv (fn [unit] [(api/get-alias unit) unit]))
    (mapcat vec)
    (apply component/system-map)))

(defn- -set-up-configuration [units configuration]
  (mapv (fn [unit]
          (if-let [unit-conf (get configuration (api/id-key unit))]
            (update unit api/configuration-key merge unit-conf)
            unit))
        units))

(defn- -remove-order-dependencies [unit]
  (apply dissoc unit
         (remove (set (api/static-deps-key unit))
                 (:order-deps unit))))

(defn- -do-start-action [unit pred-key action-key units]
  (if-let [pred (pred-key unit)]
    (if-let [action (action-key unit)]
      (action unit (filterv pred units))
      unit)
    unit))

(defn- -do-post-start-step-tasks [tasks started-unit]
  (doseq [{:keys [pred task arg1]} tasks]
    (when (pred started-unit)
      (try
        (task arg1 started-unit)
        (catch #?(:clj Throwable :cljs :default) e
          (throw (ex-info "Post start task exception"
                          {:started-unit (api/id-key started-unit)
                           :exception-message
                                         #?(:clj  (.getMessage e)
                                            :cljs (.-message e))
                           :arg1         arg1})))))))

(defn- -add-post-start-step-task [tasks-state started-unit]
  (when-let [rev-pred (api/post-start-step-pred-key started-unit)]
    (when-let [post-start (api/post-start-step-action-key started-unit)]
      (swap! tasks-state conj
             {:pred rev-pred
              :task post-start
              :arg1 started-unit}))))

(defn- -do-injection-actions [unit]
  (let [deps (api/static-deps-key unit)]
    (->> deps
         (select-keys unit)
         (filter (fn [[_ v]] (api/injection-action-key v)))
         (map (fn [[k v]]
                (let [action (api/injection-action-key v)]
                  [k (action v unit)])))
         (reduce (fn [result [k v]] (assoc result k v)) unit))))

(defn build-system [configuration & components-and-modules]
  (let [units (->
                components-and-modules
                -check-system-units
                -add-dynamic-dependencies-direct
                -add-dynamic-dependencies-reverse
                -add-order-dependencies-direct
                -add-order-dependencies-reverse
                (-set-up-configuration configuration))
        system-map (-build-system-map units)
        using-map (-build-using-map units)]
    (component/system-using system-map using-map)))

(defn roll-back-starts [started-units logfn]
  (doseq [unit (reverse started-units)]
    (try
      (logfn :info "Rolling back unit" (api/id-key unit))
      (api/stop unit)
      (catch #?(:clj Throwable :cljs :default) _))))

(defn check-action-result [result unit step]
  (if (api/component? result)
    result
    (throw (ex-info "Wrong unit start result. IComponent is required."
                    {:unit-id (api/id-key unit)
                     :step    step}))))

(defn logger [unit]
  (or (:logger unit) println))

(defn start-system [system]
  (let [started-units (atom [])
        post-start-step-tasks (atom [])]
    (component/update-system
      system (keys system)
      (fn [unit]
        (let [logfn (logger unit)]
          (try
            (logfn :info "Starting unit" (api/id-key unit))
            (let [started-unit (->
                                 (-remove-order-dependencies unit)
                                 (update :logger (fn [logger] (or logger println)))
                                 -do-injection-actions
                                 (-do-start-action api/pre-start-pred-key api/pre-start-action-key (vals system))
                                 (check-action-result unit :pre-start)
                                 api/start
                                 (check-action-result unit :start)
                                 (-do-start-action api/post-start-pred-key api/post-start-action-key @started-units)
                                 (check-action-result unit :post-start))]
              (-do-post-start-step-tasks @post-start-step-tasks started-unit)
              (-add-post-start-step-task post-start-step-tasks started-unit)
              (swap! started-units conj started-unit)
              started-unit)
            (catch #?(:clj Throwable :cljs :default) e
              (roll-back-starts @started-units logfn)
              (println :error
                       "starting unit" (api/id-key unit)
                       "ex-msg:" #?(:clj  (.getMessage e)
                                    :cljs (.-message e))
                       "ex-data:" (ex-data e))
              (throw e))))))))

(defn stop-system [system]
  (component/update-system-reverse
    system (keys system)
    (fn [unit]
      (let [logfn (logger unit)]
        (try
          (logfn :info "Stopping unit" (api/id-key unit))
          (let [stopped (api/stop unit)]
            stopped)
          (catch #?(:clj Throwable :cljs :default) e
            (logfn :error "stoppping unit" (api/id-key unit)
                   "ex-msg:" #?(:clj  (.getMessage e)
                                :cljs (.-message e))
                   "ex-data:" (ex-data e))
            (throw e)))))))

