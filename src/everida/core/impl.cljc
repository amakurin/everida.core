(ns everida.core.impl
  #?(:clj
     (:require [clojure.core.async :as async
                :refer [<! <!! >! >!! put! alts! chan go go-loop]])
     :cljs
     (:require-macros [cljs.core.async.macros :refer [go go-loop]]))
  (:require
    #?(:cljs [cljs.core.async :as async :refer [<! >! put! alts! chan]])
    [everida.core.api :as api]
    [everida.utils :as u]
    [everida.core.msg-filters :as f]
    [everida.core.conveyor :as convey]
    #?(:cljs
       [cljs.core.async.impl.buffers :as implbuf])

    #?(:cljs
       [cljs.core.async.impl.protocols :as impl]
       :clj
    [clojure.core.async.impl.protocols :as impl]))
  #?(:clj
     (:import (java.util LinkedList)
              (clojure.lang Counted))))

(def core-state-key :core/system-state)
(def core-instance-id-key :core/instance-id)
(def internals-store :core/internals)
(def modules-store :core/modules)

(defn transact-state [core korks f & args]
  (let [system-state (core-state-key core)
        path (u/korks-path korks)]
    (if (empty? path)
      (apply swap! system-state f args)
      (get-in
        (apply swap! system-state update-in path f args)
        path))))

(defn read-state [core & korks]
  (let [system-state (core-state-key core)
        path (u/korks-path korks)]
    (get-in @system-state path)))

#?(:clj
   (deftype DroppingBuffer [^LinkedList buf ^long n logger]
     impl/UnblockingBuffer
     impl/Buffer
     (full? [_] false)
     (remove! [_]
       (.removeLast buf))
     (add!* [this itm]
       (if-not (>= (.size buf) n)
         (.addFirst buf itm)
         (when logger (logger :warn "Buffer overflows size " n ". Dropping item: " itm)))
       this)
     (close-buf! [_])
     Counted
     (count [_] (.size buf)))
   :cljs
   (deftype DroppingBuffer [buf n logger]
     impl/UnblockingBuffer
     impl/Buffer
     (full? [_] false)
     (remove! [_]
       (.pop buf))
     (add!* [this itm]
       (if-not (== (.-length buf) n)
         (.unshift buf itm)
         (when logger (logger :warn "Buffer overflows size " n ". Dropping item: " itm)))
       this)
     (close-buf! [_])
     cljs.core/ICounted
     (-count [_] (.-length buf))))

(defn dropping-buffer [n logger]
  #?(:clj  (DroppingBuffer. (LinkedList.) n logger)
     :cljs (DroppingBuffer. (implbuf/ring-buffer n) n logger)))

(defn create-event-chan [core]
  (let [in (chan (dropping-buffer 2048 (:logger core)))
        out (async/chan 1)
        pipeline-conveyor (convey/pipeline-conveyor-ctor in out)]
    {:in                in
     :pipeline-conveyor pipeline-conveyor
     :out               out
     :mul               (async/mult out)}))

(defn create-request-chan [_]
  (let [in (chan 2048)
        out (async/chan 1024)
        pipeline-conveyor (convey/pipeline-conveyor-ctor in out)]
    {:in                in
     :pipeline-conveyor pipeline-conveyor
     :out               out
     :servers           []}))

(defn create-internals [core]
  {:event-chan   (create-event-chan core)
   :request-chan (create-request-chan core)})

(defn close-internals [internals]
  (let [{:keys [in out mul]} (:event-chan internals)]
    (when in (async/close! in))
    (when out (async/close! out))
    (when mul (async/untap-all mul)))
  (let [{:keys [in out servers]} (:request-chan internals)]
    (when in (async/close! in))
    (when out (async/close! out))
    (when servers
      (doseq [{:keys [channel]} servers]
        (async/close! channel)))))

(defn find-servers-for-request [core request]
  (filter #((:filter-fn %) request)
          (read-state core internals-store [:request-chan :servers])))

(defn start-request-loop [core]
  (let [request-chan (read-state core internals-store [:request-chan :out])]
    (dotimes [_ 2]
      (go-loop []
        (when-let [request (<! request-chan)]
          (let [servers (find-servers-for-request core request)
                send-timeout (or (:send-timeout request) 1000)]
            (case (count servers)
              0 (api/respond-exception core request :missing-server-for-request)
              1 (let [channel (:channel (first servers))
                      [_ ch] (alts! [[channel request] (async/timeout send-timeout)])]
                  (when-not (= ch channel)
                    (api/respond-exception
                      core request
                      :send-request-timeout-expired
                      [:send-timeout]
                      {:send-timeout send-timeout
                       :msg          "Timeout expired while sending request to servers. Looks like nobody is reading!"}
                      )))
              (api/respond-exception
                core request :multiple-servers-processing-not-implemented []
                {:found-servers (mapv #(select-keys % [:module-id :msg-filter]) servers)})))
          (recur))))))

(defn resolve-handler [handler]
  (if (var? handler)
    #?(:clj  (var-get handler)
       :cljs (throw (ex-info "Var handlers not supported for clojurescript"
                             {:handler      handler
                              :handler-meta (meta handler)})))
    handler))

(defn do-handler [core message-type handler module msg]
  (let [handler (resolve-handler handler)]
    (try
      (handler module msg)
      (catch #?(:clj Exception :cljs js/Error) e
        (when (= message-type :request)
          (api/respond-exception core msg :internal-exception []
                                 {:ex-msg #?(:clj  (.getMessage e)
                                             :cljs (.-message e))
                                  :ex-data (ex-data e)}))))))

(defn attach-handler [core message-type
                      {:keys [module channel handler parallelism]}]
  (let [{:keys [n type] :or {n 1 type :go}} parallelism
        handler
        (if (= :stand-mode/production (api/get-conf core api/stand-mode-key))
          (resolve-handler handler) handler)]
    (when handler
      (when #?(:clj (= type :go) :cljs true)
        (dotimes [_ n]
          (go
            (loop []
              (when-let [msg (<! channel)]
                (do-handler core message-type handler module msg)
                (recur))))))
      #?(:clj
         (when (= type :thread)
           (dotimes [_ n]
             (async/thread
               (loop []
                 (when-let [msg (<!! channel)]
                   (do-handler core message-type handler module msg)
                   (recur))))))))))

(defn -register-pipe
  [internal-key core pipe-spec]
  (let [conveyor (read-state core internals-store [internal-key :pipeline-conveyor])]
    (convey/-add-pipe conveyor (update pipe-spec :key
                                       #(or % (api/id-key (:module pipe-spec)))))))

(defn new-or-pipe [channel & [transducer]]
  (let [ch (chan 64 transducer)]
    (when channel (async/pipe ch channel))
    ch))

(defn ensure-module [spec core]
  (update spec :module #(or % {:core core api/id-key :core/dynamic-module})))

(defn extend-module-spec [spec core channel]
  (-> spec
      (assoc :channel channel)
      (ensure-module core)))

(defn -subscribe-event
  [core {:keys [msg-filter handler channel] :as spec}]
  {:pre [(or handler channel)]}
  (let [src-mult (read-state core internals-store [:event-chan :mul])
        channel (new-or-pipe channel (f/msg-filter->transducer msg-filter))]
    (when-not (read-state core [internals-store :request-chan :in])
      (throw (ex-info "Subscribe-event over stopped core." {:spec spec})))
    (async/tap src-mult channel)
    (when handler (attach-handler core :event (extend-module-spec spec core channel)))))

(defn -register-server
  [core {:keys [msg-filter handler channel] :as spec}]
  {:pre [(or handler channel)]}
  (let [channel (new-or-pipe channel)
        ffn (f/msg-filter->fn msg-filter)]
    (when-not (read-state core [internals-store :request-chan :in])
      (throw (ex-info "Register-server over stopped core." {:spec spec})))
    (when handler (attach-handler core :request (extend-module-spec spec core channel)))
    (transact-state
      core
      [internals-store :request-chan :servers]
      #(conj % {:msg-filter msg-filter
                :channel    channel
                :filter-fn  ffn}))))

(defn flatten-properties [by-key modules]
  (mapcat
    (fn [module] (map #(assoc % :module module) (get module by-key)))
    modules))

(defn process-module-instructions [module-instructions modules]
  (doseq [[instruction handler] module-instructions]
    (doseq [spec (flatten-properties instruction modules)]
      (handler spec))))

(defn attach-response-chan [core request]
  (if (nil? (api/get-response-chan core request))
    (api/set-response-chan core request (chan 1)) request))

(defrecord Core []
  api/ICore
  (get-instance-id
    [core]
    (core-instance-id-key core))

  (subscribe-event
    [core spec]
    (-subscribe-event core spec))

  (register-server
    [core spec]
    (-register-server core spec))

  (register-event-pipe
    [core spec]
    (-register-pipe :event-chan core (ensure-module spec core)))

  (register-request-pipe
    [core spec]
    (-register-pipe :request-chan core (ensure-module spec core)))

  (register-module
    [core module]
    (let [module-instructions
          [[api/event-pipes-key
            (partial -register-pipe :event-chan core)]
           [api/request-pipes-key
            (partial -register-pipe :request-chan core)]
           [api/event-subs-key
            (partial -subscribe-event core)]
           [api/request-servers-key
            (partial -register-server core)]]]
      (process-module-instructions
        module-instructions [(update module :core #(or % core))])))

  (request-supported?
    [core request]
    (not (empty? (find-servers-for-request core request))))

  (call-async
    [core request]
    {:pre [(map? request) (some? (:qn request))]}
    (let [request (attach-response-chan core request)]
      (if-let [in (read-state core [internals-store :request-chan :in])]
        (do (put! in request)
            (api/get-response-chan core request))
        (throw (ex-info "Call-async over stopped core." {:request request})))))

  (pub-event
    [core event]
    {:pre [(map? event) (keyword? (:qn event))]}
    (if-let [in (read-state core [internals-store :event-chan :in])]
      (put! in event)
      (throw (ex-info "Pub-event over stopped core." {:event event}))))

  (pub-events
    [core events]
    {:pre [(every? map? events) (every? #(keyword? (:qn %)) events)]}
    (if-let [in (read-state core [internals-store :event-chan :in])]
      (async/onto-chan in events false)
      (throw (ex-info "Pub-events over stopped core." {:events events}))))

  (success?
    [_ response]
    (= (:status response) :success))

  (error?
    [_ response]
    (= (:status response) :error))

  (success-response
    [_]
    {:status :success})

  (success-response
    [_ result]
    {:status :success
     :result result})

  (error-response
    [core request error-s]
    (api/error-response core request error-s []))

  (error-response
    [_ request error-s request-keys-to-select]
    {:status       :error
     :errors       (if (map? error-s) [error-s] error-s)
     :request-info (select-keys request (conj request-keys-to-select :qn))})

  (exception-response
    [core request exception-code]
    (api/exception-response core request exception-code nil nil))

  (exception-response
    [core request exception-code request-keys-to-select]
    (api/exception-response core request exception-code request-keys-to-select nil))

  (exception-response
    [core request exception-code request-keys-to-select info]
    (api/error-response
      core
      request
      {:code      :exception
       :exception (if (some? info)
                    {:code exception-code :info info}
                    {:code exception-code})}
      request-keys-to-select))

  (get-response-chan
    [_ request]
    (:response-chan (meta request)))

  (set-response-chan
    [_ request ch]
    (with-meta
      request
      (assoc (meta request) :response-chan ch)))

  (respond
    [core request]
    (api/respond core request nil))

  (respond
    [core request response]
    (if-let [response-chan (api/get-response-chan core request)]
      (do (when response (put! response-chan response))
          (async/close! response-chan))
      (throw (ex-info "Missing response-chan for request" {:request request})))
    response)
  (respond-success
    [core request]
    (api/respond core request (api/success-response core)))
  (respond-success
    [core request result]
    (api/respond core request (api/success-response core result)))
  (respond-error
    [core request error-s]
    (api/respond-error core request error-s nil))
  (respond-error
    [core request error-s request-keys-to-select]
    (api/respond core request
                 (api/error-response core request error-s request-keys-to-select)))
  (respond-exception
    [core request exception-code]
    (api/respond-exception core request exception-code nil nil))
  (respond-exception
    [core request exception-code request-keys-to-select]
    (api/respond-exception core request exception-code request-keys-to-select nil))
  (respond-exception
    [core request exception-code request-keys-to-select info]
    (api/respond
      core request
      (api/exception-response
        core request exception-code request-keys-to-select info)))
  api/IComponent
  (start [core]
    (transact-state
      core [] assoc
      internals-store (create-internals core)
      modules-store {})
    (start-request-loop core)
    core)
  (stop [core]
    (close-internals (read-state core internals-store))
    (transact-state
      core [] dissoc internals-store modules-store)
    core))


(defn new-core [& [m]]
  (map->Core
    (merge {api/id-key           :core
            api/dynamic-reverse-dependency-pred-key
                                 api/module?
            api/post-start-step-pred-key
                                 api/module?
            api/post-start-step-action-key
                                 #'api/register-module
            core-state-key       (atom {})
            core-instance-id-key (str (u/guid))}
           m)))
