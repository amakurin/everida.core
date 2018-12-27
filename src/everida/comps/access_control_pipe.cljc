(ns everida.comps.access-control-pipe
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go]]))
  (:require
    [everida.core.api :as api]
    [everida.core.msg-filters :as mf]
    #?(:clj
    [clojure.core.async :as async :refer [go]]
       :cljs [cljs.core.async :as async])))

(def event-group-strategies-key :access-control-pipe/event-group-strategies)
(def request-group-strategies-key :access-control-pipe/request-group-strategies)
(def msg->user-id-fn-key :access-control-pipe/msg->user-id-fn)

(defn release [msg ch]
  (async/put! ch msg)
  (async/close! ch))

(defn allow [_ msg ch _]
  (release msg ch))

(defmulti deny (fn [msg-type & _] msg-type))

(defmethod deny :event
  [_ event ch _]
  (release {:qn         :authorization/permission-denied
            :denied-msg event
            :generated-by :pipe}
           ch))

(defmethod deny :request
  [_ request ch core]
  (api/respond-error core request
                     {:code       :authorization/permission-denied
                      :denied-msg request})
  (async/close! ch))

(defn remote? [msg] (:remote? (meta msg)))

(defn msg->rule [group-key acp msg]
  (let [rules (group-key acp)]
    (some (fn [rule] (when ((:ffn rule) msg) rule)) rules)))

(defn msg-info [acp msg]
  {:msg     msg
   :remote? (remote? msg)
   :user-id ((msg->user-id-fn-key acp) acp msg)})

(defn msg-with-auth-result [msg-info auth-result]
  (let [{:keys [msg user-id]} msg-info]
    (with-meta msg
               (assoc (meta msg) :auth-result
                                 (assoc auth-result :user-id user-id)))))

(defn pipe-msg [msg-type spec msg ch]
  (let [acp (:module spec)
        core (:core acp)
        group-key (if (= :request msg-type)
                    request-group-strategies-key event-group-strategies-key)
        rule (msg->rule group-key acp msg)
        strategy-fn (:sfn rule)
        msginf (msg-info acp msg)]
    (go
      (let [response (async/<! (strategy-fn acp msginf))
            result (:result response)]
        (if (api/success? core response)
          (if (:permitted? result)
            (allow msg-type
                   (msg-with-auth-result msginf result)
                   ch core)
            (deny msg-type msg ch core))
          (throw (ex-info "Authorization error" {:error-response response})))))))

(defn auth-response [acp permitted?]
  (api/success-response (:core acp) {:permitted? permitted?}))

(defn strategy->fn [strategy]
  (let [strategy-variant
        (cond (vector? strategy) (first strategy)
              (fn? strategy) :fn
              :else strategy)]
    (case strategy-variant
      :deny
      (fn [acp _]
        (go (auth-response acp false)))
      :allow-local
      (fn [acp msg-info]
        (go (auth-response acp (not (:remote? msg-info)))))
      :allow
      (fn [acp _]
        (go (auth-response acp true)))
      :call-async
      (fn [acp msg-info]
        (api/call-async (:core acp) (assoc msg-info :qn (second strategy))))
      :fn
      (fn [acp msg-info]
        (go (strategy acp msg-info)))
      (throw (ex-info "Unknown authorize strategy" {:strategy strategy})))))

(defn compile-rules [rules]
  (mapv (fn [rule]
          (assoc rule :ffn (mf/msg-filter->fn (:msg-filter rule))
                      :sfn (strategy->fn (:strategy rule))))
        rules))

(defrecord AccessControlPipe []
  api/IModule
  api/IComponent
  (start [acp]
    (-> acp
        (update event-group-strategies-key compile-rules)
        (update request-group-strategies-key compile-rules)))
  (stop [acp] acp))

(defn new-acp [& [m]]
  (map->AccessControlPipe
    (merge
      {api/id-key :access-control-pipe
       api/event-pipes-key
                  [{:msg-filter  mf/empty-filter
                    :priority    (or (:pipe-priority m) 0)
                    :parallelism 8
                    :handler     (partial pipe-msg :event)}]
       api/request-pipes-key
                  [{:msg-filter  mf/empty-filter
                    :priority    (or (:pipe-priority m) 0)
                    :parallelism 8
                    :keep-messaging-order?
                                 false
                    :handler     (partial pipe-msg :request)}]
       msg->user-id-fn-key
                  (fn [_ msg] (get-in (meta msg) [:ring-req :session :user-id]))
       event-group-strategies-key
                  [{:msg-filter mf/empty-filter
                    :strategy   :allow-local}]
       request-group-strategies-key
                  [{:msg-filter mf/empty-filter
                    :strategy   :allow-local}]}
      m)))
