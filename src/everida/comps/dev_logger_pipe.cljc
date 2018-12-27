(ns everida.comps.dev-logger-pipe
  (:require [everida.core.api :as api]
            [everida.core.msg-filters :as mf]
    #?(:clj
            [clojure.core.async :as async] :cljs [cljs.core.async :as async])))

(defn msg-filter-fn [filt]
  (if filt
    (mf/msg-filter->fn filt)
    (fn [_] false)))

(defrecord DevLoggerPipe []
  api/IModule
  api/IComponent
  (start [dlp]
    (->
      dlp
      (update :ignore-request-filter msg-filter-fn)
      (update :ignore-event-filter msg-filter-fn)))
  (stop [dlp] dlp))

(defn ignore? [spec msg msg-type]
  (let [f ((if (= :request msg-type) :ignore-request-filter :ignore-event-filter)
            (:module spec))]
    (f msg)))

(defn log-msg [msg-type spec msg ch]
  (when-not (ignore? spec msg msg-type)
    #?(:cljs (enable-console-print!))
    (let [logger (get-in spec [:module :logger])]
      (logger :debug
             "------" msg-type (:qn msg) "\n" msg "\n")))
  (async/put! ch msg)
  (async/close! ch))

(defn new-dev-logger-pipe [& [m]]
  (map->DevLoggerPipe
    (merge
      {api/id-key             :dev-logger
       api/event-pipes-key
                              [{:msg-filter mf/empty-filter
                                :priority   (or (:pipe-priority m) 100)
                                :handler    (partial log-msg :event)}]
       api/request-pipes-key
                              [{:msg-filter mf/empty-filter
                                :priority   (or (:pipe-priority m) 100)
                                :handler    (partial log-msg :request)}]
       :ignore-request-filter nil
       :ignore-event-filter   nil}
      m)))