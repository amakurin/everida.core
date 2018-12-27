(ns everida.tests.core
  (:require
    [everida.core.api :as api]
    [everida.core.impl :as impl]
    [everida.core.msg-filters :as mf]
    [clojure.core.async :as a]
    #?(:clj  [clojure.test :refer [deftest is testing run-tests use-fixtures]]
       :cljs [cljs.test :refer-macros [deftest is testing run-tests use-fixtures async]])
    ))

(def server-channel (atom nil))

(def event-channel (atom nil))
(def event-channel2 (atom nil))

(def pipe-req-log-channel1 (atom nil))
(def pipe-req-log-channel2 (atom nil))
(def pipe-req-log-channel3 (atom nil))

(def pipe-event-log-channel1 (atom nil))
(def pipe-event-log-channel2 (atom nil))
(def pipe-event-log-channel3 (atom nil))


(defn mk-echo-handler
  "Creates handler that responds with map of :handler-id (id argument), :req (request), :module (module associated with this handler)"
  [id]
  (fn [module req] (api/respond (:core module) req {:handler-id id :req req :module module})))

(defn async-handler
  "Responds asynchronously multiple times with map of :id (order id of response) :req (request map)"
  [module req]
  (let [ch (api/get-response-chan (:core module) req)
        result-size 5]
    (loop [cnt 1]
      (when (<= cnt result-size)
        (a/put! ch {:id cnt :req req})
        (recur (inc cnt))))
    (a/close! ch)))

(defn event-handler
  "Just forwards event to event channel"
  [_ req]
  (a/put! @event-channel req))

(defn mk-pipe-log-handler
  "Creates pipe handler that logs passing messages to `log-channel`"
  [log-channel]
  (fn [spec msg ch]
    (println :pipe-logger (:key spec) (:priority spec) msg)
    (a/put! log-channel msg)
    (a/put! ch msg)
    (a/close! ch)))

(defn wrap-msg [msg]
  {:qn :test/was-wrapped :wrapped? true :original msg})

(defn pipe-wrapper-handler [_ msg ch]
  "Wraps passing by msgs to simple wrapper"
  (a/put! ch (wrap-msg msg))
  (a/close! ch))

(defn pipe-deny-unsupported-handler [spec msg ch]
  "Assumes `msg` is request, checks if there is server for it, in not - drops request and generates system event."
  (let [core (-> spec :module :core)]
    (if (api/request-supported? core msg)
      (a/put! ch msg)
      (do
        (api/pub-event core {:qn :test/unsupported-request-event :request msg})
        (api/respond-error core msg "unsupported")))
    (a/close! ch)))

(defn pipe-silence-handler [_ _ ch]
  "Drops all passing by messages."
  (a/close! ch))

(def core (atom nil))

(defn start-core-fixture [f]
  (swap! server-channel a/chan)
  (swap! event-channel a/chan)
  (swap! event-channel2 a/chan)
  (swap! pipe-req-log-channel1 a/chan)
  (swap! pipe-req-log-channel2 a/chan)
  (swap! pipe-req-log-channel3 a/chan)
  (swap! pipe-event-log-channel1 a/chan)
  (swap! pipe-event-log-channel2 a/chan)
  (swap! pipe-event-log-channel3 a/chan)
  (swap! core (fn [_] (let [c (impl/new-core)] (api/start c) c)))


  ;;; SERVERS

  ;; simple server with handler
  (api/register-server @core
                       {:msg-filter :test/echo
                        :handler    (mk-echo-handler 1)})

  ;; current version doesn't support multiple handlers so we get error response on :test/echo-mult request
  (api/register-server @core
                       {:msg-filter :test/echo-mult
                        :handler    (mk-echo-handler 2)})

  (api/register-server @core
                       {:msg-filter :test/echo-mult
                        :handler    (mk-echo-handler 3)})

  ;; server with multiple responses to response channel
  (api/register-server @core
                       {:msg-filter :test/async
                        :handler    async-handler})

  ;; server without predefined handler
  (api/register-server @core
                       {:msg-filter :test/async-chan
                        :channel    @server-channel})

  ;;; SUBSCRIBERS

  ;; subscribe event with handler
  (api/subscribe-event @core
                       {:msg-filter :test/event
                        :handler    event-handler})

  ;; subscribe event without handler directly connecting to event pipe
  (api/subscribe-event @core
                       {:msg-filter :test/event-chan
                        :channel    @event-channel})

  ;; will check event filtering (only event-channel2 will receive this event)
  (api/subscribe-event @core
                       {:msg-filter :test/event-chan2
                        :channel    @event-channel2})

  ;;; PIPES

  (let [common-filter #{:test/should-be-wrapped-event :test/was-wrapped
                        :test/generic :test/should-be-silent-event
                        :test/unsupported-request :test/unsupported-request-event
                        :test/should-authenticate :test/should-authenticate-authorize}]

    ;;; EVENT PIPELINE

    ;; this logger should log ALL msgs ASS IS
    (api/register-event-pipe @core
                             {:key        :logger1
                              :msg-filter common-filter
                              :priority   10
                              :handler    (mk-pipe-log-handler @pipe-event-log-channel1)})

    ;; wrapper
    (api/register-event-pipe @core
                             {:key        :wrapper
                              :msg-filter :test/should-be-wrapped-event
                              :priority   9
                              :handler    pipe-wrapper-handler})

    ;; like previous logger, but :qn :test/should-be-wrapped-event have to appear wrapped
    (api/register-event-pipe @core
                             {:key        :logger2
                              :msg-filter common-filter
                              :priority   8
                              :handler    (mk-pipe-log-handler @pipe-event-log-channel2)})

    ;; passes all msgs except :qn :test/should-be-silent-event, which is dropped
    (api/register-event-pipe @core
                             {:key        :silencer
                              :msg-filter :test/should-be-silent-event
                              :priority   7
                              :handler    pipe-silence-handler})

    ;; like previous logger, but don't see dropped msgs
    (api/register-event-pipe @core
                             {:key        :logger3
                              :msg-filter common-filter
                              :priority   6
                              :handler    (mk-pipe-log-handler @pipe-event-log-channel3)})


    ;; to see what we get on subscribers after all pipes processed
    (api/subscribe-event @core
                         {:msg-filter common-filter
                          :handler    event-handler})

    ;;; REQUEST PIPELINE

    ;; logger
    (api/register-request-pipe @core
                               {:key        :logger1
                                :msg-filter common-filter
                                :priority   10
                                :handler    (mk-pipe-log-handler @pipe-req-log-channel1)})

    ;; support-checker
    (api/register-request-pipe @core
                               {:key        :support-checker
                                :msg-filter common-filter
                                :priority   9
                                :handler    pipe-deny-unsupported-handler})

    ;; authenticator
    (api/register-request-pipe @core
                               {:key        :authenticator
                                :msg-filter #{:test/should-authenticate :test/should-authenticate-authorize}
                                :priority   8
                                :handler    (fn [_ req ch] (a/put! ch (with-meta req (-> req meta (assoc :auth-id 1)))) (a/close! ch))})

    ;; logger
    (api/register-request-pipe @core
                               {:key        :logger2
                                :msg-filter common-filter
                                :priority   7
                                :handler    (mk-pipe-log-handler @pipe-req-log-channel2)})

    ;; authorizer
    (api/register-request-pipe @core
                               {:key        :authorizer
                                :msg-filter #{:test/should-authenticate :test/should-authenticate-authorize}
                                :priority   6
                                :handler    (fn [_ req ch]
                                              (if (and (-> req meta :auth-id) (= :test/should-authenticate-authorize (:qn req)))
                                                (a/put! ch (with-meta req (-> req meta (assoc :allowed-actions [:a :b]))))
                                                (a/put! ch req))
                                              (a/close! ch))})

    ;; logger
    (api/register-request-pipe @core
                               {:key        :logger3
                                :msg-filter common-filter
                                :priority   5
                                :handler    (mk-pipe-log-handler @pipe-req-log-channel3)})

    ;; to see what we get on server side
    (api/register-server @core
                         {:msg-filter (disj common-filter :test/unsupported-request)
                          :handler    (mk-echo-handler 1)})

    )

  (f)

  (swap! server-channel a/close!)
  (swap! event-channel a/close!)
  (swap! event-channel2 a/close!)
  (swap! pipe-req-log-channel1 a/close!)
  (swap! pipe-req-log-channel2 a/close!)
  (swap! pipe-req-log-channel3 a/close!)
  (swap! pipe-event-log-channel1 a/close!)
  (swap! pipe-event-log-channel2 a/close!)
  (swap! pipe-event-log-channel3 a/close!)

  (swap! core (fn [c] (api/stop c) nil))
  )

(use-fixtures :once start-core-fixture)

;; requests

(defn test-async
  [ch]
  #?(:clj
     (a/<!! ch)
     :cljs
     (async done (a/take! ch (fn [_] (done))))))

(deftest request-echo
  (let [req {:qn :test/echo}
        ch (api/call-async @core req)]
    (test-async
      (a/go (let [result (a/<! ch)]
              (is (= req (:req result)))
              (is (= @core (:core (:module result)))))))))

(deftest request-mult
  (let [req {:qn :test/echo-mult}
        ch (api/call-async @core req)]
    (test-async
      (a/go (let [result (a/<! ch)]
              (is (= result {:status       :error,
                             :errors       [{:code      :exception,
                                             :exception {:code :multiple-servers-processing-not-implemented,
                                                         :info {:found-servers [{:msg-filter :test/echo-mult}
                                                                                {:msg-filter :test/echo-mult}]}}}],
                             :request-info req})))))))

(deftest request-missing
  (let [req {:qn :test/echo-missing}
        ch (api/call-async @core req)]
    (test-async
      (a/go (let [result (a/<! ch)]
              (is (= result {:status       :error,
                             :errors       [{:code      :exception,
                                             :exception {:code :missing-server-for-request}}],
                             :request-info req})))))))

(deftest request-async
  (let [req {:qn :test/async}
        ch (api/call-async @core req)]
    (test-async
      (a/go
        (loop [cnt 1]
          ;; TODO: about this alts! Actually it is good to do so in all tests - consider rewrite them.
          ;; And the reason of alts! is right here is some hanging in (run-tests) which needs further investigation.
          ;; Current knowledge is:
          ;; - it is always not first run but at least second continuous execution of (run-test) in single REPL
          ;; - it appears only when pipes are added (and maybe count of pipes implies hang likelihood)
          ;; - reason of hang is lost connection between first pipe and in channel of core (put! to in successful)
          ;; - suggested ways of further investigation:
          ;; - - write function that builds whole core and execute all operations without involving core.test
          ;; - - write custom debugging buffer to visualize state of in core channel, and in channel of first pipe
          (let [[res port] (a/alts! [ch (a/timeout 100)])
                timeout-exipred? (not= port ch)]
            (is (not timeout-exipred?))
            (when (and (some? res) (not timeout-exipred?))
              (is (= cnt (:id res)))
              (is (= req (:req res)))
              (recur (inc cnt))))
          )))))

(deftest request-async-chan
  (a/go
    (when-let [req (a/<! @server-channel)]
      (async-handler {:core @core} req)))
  (let [req {:qn :test/async-chan}
        ch (api/call-async @core req)]
    (test-async
      (a/go
        (loop [cnt 1]
          (when-let [res (a/<! ch)]
            (is (= cnt (:id res)))
            (is (= req (:req res)))
            (recur (inc cnt))))))))

;; events

(deftest event-handle
  (let [event {:qn :test/event}
        _ (api/pub-event @core event)]
    (test-async
      (a/go
        (is (= (a/<! @event-channel) event))))))

(deftest event-chan
  (let [event {:qn :test/event-chan}
        _ (api/pub-event @core event)]
    (test-async
      (a/go
        (is (= (a/<! @event-channel) event))))))

(deftest event-chan-multi-check
  (let [event {:qn :test/event-chan2}
        _ (api/pub-event @core event)
        _ (a/put! @event-channel :none)]
    (test-async
      (a/go
        (let [ev-ch1v (a/<! @event-channel)
              ev-ch2v (a/<! @event-channel2)]
          (is (= ev-ch1v :none))
          (is (= ev-ch2v event)))))))

(deftest batch-events
  (let [event1 {:qn :test/event-chan}
        event2 {:qn :test/event-chan2}
        _ (api/pub-events @core [event1 event2])]
    (test-async
      (a/go
        (let [ev-ch1v (a/<! @event-channel)
              ev-ch2v (a/<! @event-channel2)]
          (is (= ev-ch1v event1))
          (is (= ev-ch2v event2)))))))


;; pipes

(deftest pipe-event-wrap
  (let [event {:qn :test/should-be-wrapped-event}
        wrapped (wrap-msg event)
        _ (api/pub-event @core event)]
    (test-async
      (a/go
        (is (= (a/<! @pipe-event-log-channel1) event))
        (is (= (a/<! @pipe-event-log-channel2) wrapped))
        (is (= (a/<! @pipe-event-log-channel3) wrapped))
        (is (= (a/<! @event-channel) wrapped))
        ))))

(deftest pipe-event-drop
  (let [event {:qn :test/should-be-silent-event}
        _ (api/pub-event @core event)
        _ (a/put! @pipe-event-log-channel3 :none)
        _ (a/put! @event-channel :none)]
    (test-async
      (a/go
        (is (= (a/<! @pipe-event-log-channel1) event))
        (is (= (a/<! @pipe-event-log-channel2) event))
        (is (= (a/<! @pipe-event-log-channel3) :none))
        (is (= (a/<! @event-channel) :none))
        ))))


(deftest pipe-request-generic
  (let [req {:qn :test/generic}
        ch (api/call-async @core req)]
    (test-async
      (a/go
        (let [result (a/<! ch)]
          (is (= (a/<! @pipe-req-log-channel1) req))
          (is (= (a/<! @pipe-req-log-channel2) req))
          (is (= (a/<! @pipe-req-log-channel3) req))
          (is (= req (:req result))))))))

(deftest pipe-request-unsupported
  (let [req {:qn :test/unsupported-request}
        ch (api/call-async @core req)
        _ (a/put! @pipe-req-log-channel2 :none)
        _ (a/put! @pipe-req-log-channel3 :none)]
    (test-async
      (a/go
        (let [result (a/<! ch)
              unsup {:qn :test/unsupported-request-event :request req}]
          (is (= (a/<! @pipe-req-log-channel1) req))
          (is (= (a/<! @pipe-req-log-channel2) :none))
          (is (= (a/<! @pipe-req-log-channel3) :none))
          (is (= result {:status :error, :errors "unsupported", :request-info req}))
          (is (= (a/<! @pipe-event-log-channel1) unsup))
          (is (= (a/<! @pipe-event-log-channel2) unsup))
          (is (= (a/<! @pipe-event-log-channel3) unsup))
          (is (= (a/<! @event-channel) unsup)))))))

(deftest pipe-request-authenticate
  (let [req {:qn :test/should-authenticate}
        ch (api/call-async @core req)]
    (test-async
      (a/go
        (let [log1v (a/<! @pipe-req-log-channel1)
              log2v (a/<! @pipe-req-log-channel2)
              log3v (a/<! @pipe-req-log-channel3)
              result (a/<! ch)
              test-auth-id 1]
          (is (= log1v req))
          (is (= log2v req))
          (is (= test-auth-id (-> log2v meta :auth-id)))
          (is (= log3v req))
          (is (= test-auth-id (-> log3v meta :auth-id)))
          (is (= req (:req result)))
          (is (= test-auth-id (-> result :req meta :auth-id)))
          (is (nil? (-> result :req meta :allowed?))))))))

(deftest pipe-request-authorize
  (let [req {:qn :test/should-authenticate-authorize}
        ch (api/call-async @core req)]
    (test-async
      (a/go
        (let [log1v (a/<! @pipe-req-log-channel1)
              log2v (a/<! @pipe-req-log-channel2)
              log3v (a/<! @pipe-req-log-channel3)
              result (a/<! ch)
              test-auth-id 1]
          (is (= log1v req))
          (is (= log2v req))
          (is (= test-auth-id (-> log2v meta :auth-id)))
          (is (= log3v req))
          (is (= test-auth-id (-> log3v meta :auth-id)))
          (is (= req (:req result)))
          (is (= test-auth-id (-> result :req meta :auth-id)))
          (is (= [:a :b] (-> result :req meta :allowed-actions ))))))))

#_(require '[everida.tests.core])
#_(run-tests)