(ns everida.core.conveyor
  #?(:clj
     (:require [clojure.core.async :as async
                :refer [<! <!! >! >!! put! alts! chan go go-loop]])
     :cljs
     (:require-macros [cljs.core.async.macros :refer [go go-loop]]))
  (:require
    #?(:cljs [cljs.core.async :as async :refer [<! >! put! alts! chan]])
    [everida.core.msg-filters :as mf]))

(defn closable-pipe
  ([from to control close?]
   (go-loop []
     (let [[v _] (alts! [from control])]
       (if (nil? v)
         (when close? (async/close! to))
         (when (>! to v) (recur)))))))

(defn unordered-pipeline-async
  ([n to af from] (unordered-pipeline-async n to af from true))
  ([n to af from close?]
   (assert (pos? n))
   (let [jobs (chan n)
         async (fn [job]
                 (let [res (chan 1)]
                   (go-loop []
                     (let [v (<! res)]
                       (when (some? v)
                         (if (>! to v)
                           (recur)
                           (async/close! jobs)))))
                   (af job res)))]
     (dotimes [_ n]
       (go-loop []
         (let [job (<! jobs)]
           (if (nil? job)
             (when close? (async/close! to))
             (do (async job) (recur))))))
     (go-loop []
       (let [v (<! from)]
         (if (nil? v)
           (async/close! jobs)
           (when (>! jobs v) (recur))))))))

(defprotocol IPipelineConveyor
  (-add-pipe [_ pipe-spec])
  (-del-pipe [_ pipe-key])
  (-pipes [_])
  (-inner-chans [_]))

(defn create-buffer [{:keys [type size]}]
  (let [size (or size 1)]
    (case type
      :sliding (async/sliding-buffer size)
      :dropping (async/dropping-buffer size)
      (async/buffer size))))

(defn make-pipeline-handler [{:keys [handler msg-filter] :as pipe-spec}]
  (let [handler (if (mf/filter-empty? msg-filter)
                  handler
                  (let [filt (mf/msg-filter->fn msg-filter)]
                    (fn [spec ev ch]
                      (if (filt ev)
                        (handler spec ev ch)
                        (do (put! ch ev) (async/close! ch))))))]
    (partial handler (dissoc pipe-spec :handler))))

(defn rebuild-conveyor [in out buffer inner-chref specs msg-on-rebuilt?]
  (when-let [{:keys [inner-in inner-in-ctrl inner-out]} @inner-chref]
    (when inner-in-ctrl (async/close! inner-in-ctrl))
    (when inner-in (async/close! inner-in))
    (when inner-out (async/close! inner-out)))
  (let [{:keys [inner-in inner-in-ctrl inner-out]}
        (reset! inner-chref
                {:inner-in      (async/chan 1)
                 :inner-in-ctrl (async/chan)
                 :inner-out     (async/chan (create-buffer buffer))})]
    (closable-pipe in inner-in inner-in-ctrl true)
    (loop [specs specs current-in inner-in]
      (if-let [{:keys [parallelism buffer keep-messaging-order?]
                :or   {parallelism 1 keep-messaging-order? true} :as spec} (first specs)]
        (let [pipeline (if keep-messaging-order? async/pipeline-async unordered-pipeline-async)
              spec-out (async/chan (create-buffer buffer))]
          (pipeline parallelism spec-out (make-pipeline-handler spec) current-in true)
          (recur (rest specs) spec-out))
        (async/pipe current-in inner-out true)))
    (async/pipe inner-out out false)
    (when msg-on-rebuilt?
      (put! in msg-on-rebuilt?))))

(defn pipeline-conveyor-ctor [in out & [opts-map]]
  (let [state (atom [])
        {:keys [buffers msg-on-rebuilt?]} opts-map
        inner-chref (atom {:inner-in nil :inner-out nil})]
    (rebuild-conveyor
      in out buffers inner-chref @state msg-on-rebuilt?)
    (reify
      IPipelineConveyor
      (-pipes [_] @state)
      (-inner-chans [_] @inner-chref)
      (-add-pipe [_ pipe-spec]
        (assert (:handler pipe-spec) "Pipe spec :handler is required to add pipe")
        (assert (:key pipe-spec) "Pipe spec :key is required to add pipe")
        (when (->> @state (map :key) (some #{(:key pipe-spec)}))
          (throw (ex-info "Duplicate pipe key" {:key (:key pipe-spec)})))
        (let [specs (swap! state
                           (fn [st] (->>
                                      (conj st (update pipe-spec :priority #(or % 0)))
                                      (sort-by :priority >) vec)))]
          (rebuild-conveyor in out buffers inner-chref specs msg-on-rebuilt?)))
      (-del-pipe [_ pipe-key]
        (let [cache @state
              specs (swap! state #(vec (remove (fn [spec] (#{pipe-key} (:key spec))) %)))]
          (when (not= cache specs)
            (rebuild-conveyor in out buffers inner-chref specs msg-on-rebuilt?)))))))
