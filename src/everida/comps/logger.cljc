(ns everida.comps.logger
  #?(:cljs
     [:require-macros [cljs.core.async.macros :refer [go]]])
  (:require
    [everida.core.api :as api]
    [clojure.string :as clostr]
    #?(:clj
    [clojure.core.async :as async :refer [<! <!! >! >!! put! go]]
       :cljs [cljs.core.async :as async :refer [<! >! put!]])
    [everida.dtl.core :as dtl]
    [everida.utils :as u]
    [everida.dtl.transformers.str :as str])
  #?(:clj
     (:import (clojure.lang IFn)
              (java.util Date))))

(def ignore-units-key :logger/ignore-units)

(defprotocol ILogAppender
  (append [appender log-entry]))

(defn level-str [_ level]
  (if (keyword? level) (clostr/upper-case (name level)) level))

(defn source-str [_ source]
  (str "SRC[" source "]"))

(defn instant-str [appender instant]
  (dtl/transform (u/parse-date instant) (:instant-dtl appender)
                 str/transformer))

(defrecord ConsoleAppender []
  ILogAppender
  (append [appender log-entry]
    #?(:cljs (enable-console-print!))
    (let [fieldset (:out-fieldset appender)
          {:keys [level source instant args]} log-entry]
      (->> [(when (fieldset :instant) (instant-str appender instant))
            (when (fieldset :source) (source-str appender source))
            (when (fieldset :level) (level-str appender level))
            "-"]
           (remove nil?)
           (#(concat % args))
           (apply println))))
  api/IComponent
  (start [appender]
    #?(:cljs (enable-console-print!))
    appender)
  (stop [appender] appender))

(defn new-console-appender [& [m]]
  (map->ConsoleAppender
    (merge
      {api/id-key    :console-log-appender
       :out-fieldset #{:source :level}
       :instant-dtl  ["%02d-%02d-%02d %s:%02d:%02d"
                      [:year :month :day :hour :minute :second]]}
      m)))

(defn get-injected [logger k & [default]]
  (get-in logger [:injected-to k] default))

(defn level-weight [level]
  (get {nil -1 :trace 0 :debug 1 :info 2 :warn 2 :error 3 :fatal 4} level -1))

(defn level-limit [logger]
  (let [unit-level (get-injected logger api/log-level-key)
        logger-level (api/get-conf logger api/log-level-key)]
    (cond
      (and unit-level logger-level
           (> (level-weight unit-level) (level-weight logger-level)))
      unit-level
      (and unit-level logger-level
           (< (level-weight unit-level) (level-weight logger-level)))
      logger-level
      unit-level unit-level
      logger-level logger-level
      :else :debug)))

(defn get-instant []
  #?(:clj  (Date.)
     :cljs (js/Date.)))

(defn log! [logger level & args]
  (let [source (get-injected logger api/id-key)]
    (when (and (nil? ((ignore-units-key logger) source))
               (>= (level-weight level) (level-weight (level-limit logger))))
      (let [entry {:level   level
                   :source  source
                   :instant (get-instant)
                   :args    args}]
        (put! (:logger-chan logger) entry)))))

(defn start-logger-loop [logger]
  (let [ch (async/chan 1)]
    (go
      (loop []
        (when-let [log-entry (<! ch)]
          (doseq [appender (:appenders logger)]
            (append appender log-entry))
          (recur))))
    (assoc logger :logger-chan ch)))

(defn stop-logger-loop [logger]
  (when-let [ch (:logger-chan logger)]
    (async/close! ch))
  (dissoc logger :logger-chan))

(defrecord Logger []
  IFn
  #?(:clj  (invoke [logger level] (log! logger level))
     :cljs (-invoke [logger level] (log! logger level)))
  #?(:clj  (invoke [logger level a] (log! logger level a))
     :cljs (-invoke [logger level a] (log! logger level a)))
  #?(:clj  (invoke [logger level a b] (log! logger level a b))
     :cljs (-invoke [logger level a b] (log! logger level a b)))
  #?(:clj  (invoke [logger level a b c] (log! logger level a b c))
     :cljs (-invoke [logger level a b c] (log! logger level a b c)))
  #?(:clj  (invoke [logger level a b c d] (log! logger level a b c d))
     :cljs (-invoke [logger level a b c d] (log! logger level a b c d)))
  #?(:clj  (invoke [logger level a b c d e] (log! logger level a b c d e))
     :cljs (-invoke [logger level a b c d e] (log! logger level a b c d e)))
  #?(:clj  (invoke [logger level a b c d e f] (log! logger level a b c d e f))
     :cljs (-invoke [logger level a b c d e f] (log! logger level a b c d e f)))
  #?(:clj  (invoke [logger level a b c d e f g] (log! logger level a b c d e f g))
     :cljs (-invoke [logger level a b c d e f g] (log! logger level a b c d e f g)))
  #?(:clj  (invoke [logger level a b c d e f g h] (log! logger level a b c d e f g h))
     :cljs (-invoke [logger level a b c d e f g h] (log! logger level a b c d e f g h)))
  #?(:clj  (invoke [logger level a b c d e f g h i] (log! logger level a b c d e f g h i))
     :cljs (-invoke [logger level a b c d e f g h i] (log! logger level a b c d e f g h i)))
  #?(:clj  (invoke [logger level a b c d e f g h i j] (log! logger level a b c d e f g h i j))
     :cljs (-invoke [logger level a b c d e f g h i j] (log! logger level a b c d e f g h i j)))
  #?(:clj  (invoke [logger level a b c d e f g h i j k] (log! logger level a b c d e f g h i j k))
     :cljs (-invoke [logger level a b c d e f g h i j k] (log! logger level a b c d e f g h i j k)))
  #?(:clj  (invoke [logger level a b c d e f g h i j k l] (log! logger level a b c d e f g h i j k l))
     :cljs (-invoke [logger level a b c d e f g h i j k l] (log! logger level a b c d e f g h i j k l)))
  #?(:clj  (invoke [logger level a b c d e f g h i j k l m] (log! logger level a b c d e f g h i j k l m))
     :cljs (-invoke [logger level a b c d e f g h i j k l m] (log! logger level a b c d e f g h i j k l m)))
  #?(:clj  (invoke [logger level a b c d e f g h i j k l m n] (log! logger level a b c d e f g h i j k l m n))
     :cljs (-invoke [logger level a b c d e f g h i j k l m n] (log! logger level a b c d e f g h i j k l m n)))
  #?(:clj  (invoke [logger level a b c d e f g h i j k l m n o] (log! logger level a b c d e f g h i j k l m n o))
     :cljs (-invoke [logger level a b c d e f g h i j k l m n o] (log! logger level a b c d e f g h i j k l m n o)))
  #?(:clj  (invoke [logger level a b c d e f g h i j k l m n o p] (log! logger level a b c d e f g h i j k l m n o p))
     :cljs (-invoke [logger level a b c d e f g h i j k l m n o p] (log! logger level a b c d e f g h i j k l m n o p)))
  #?(:clj  (invoke [logger level a b c d e f g h i j k l m n o p q] (log! logger level a b c d e f g h i j k l m n o p q))
     :cljs (-invoke [logger level a b c d e f g h i j k l m n o p q] (log! logger level a b c d e f g h i j k l m n o p q)))
  #?(:clj  (invoke [logger level a b c d e f g h i j k l m n o p q r] (log! logger level a b c d e f g h i j k l m n o p q r))
     :cljs (-invoke [logger level a b c d e f g h i j k l m n o p q r] (log! logger level a b c d e f g h i j k l m n o p q r)))
  #?(:clj  (invoke [logger level a b c d e f g h i j k l m n o p q r s] (log! logger level a b c d e f g h i j k l m n o p q r s))
     :cljs (-invoke [logger level a b c d e f g h i j k l m n o p q r s] (log! logger level a b c d e f g h i j k l m n o p q r s)))
  #?(:clj  (invoke [logger level a b c d e f g h i j k l m n o p q r s t] (log! logger level a b c d e f g h i j k l m n o p q r s t))
     :cljs (-invoke [logger level a b c d e f g h i j k l m n o p q r s t] (log! logger level a b c d e f g h i j k l m n o p q r s t)))

  api/IComponent
  (start [logger] logger)
  (stop [logger]
    (stop-logger-loop logger)))

(defn do-injection [logger unit]
  (assoc logger :injected-to
                {api/id-key         (api/id-key unit)
                 api/stand-mode-key (api/get-conf unit api/stand-mode-key :dev)
                 api/log-level-key  (api/get-conf unit api/log-level-key :debug)}))

(defn new-logger [& [m]]
  (map->Logger
    (merge
      {api/id-key       :logger
       api/dynamic-reverse-dependency-pred-key
                        (fn [u] (not (satisfies? ILogAppender u)))
       api/post-start-pred-key
                        (fn [u] (satisfies? ILogAppender u))
       api/post-start-action-key
                        (fn [logger appenders]
                          (->
                            logger
                            (assoc :appenders (if (seq appenders) appenders [(api/start (new-console-appender))]))
                            (start-logger-loop)))
       api/injection-action-key
                        do-injection
       ignore-units-key #{}
       }
      m)))
