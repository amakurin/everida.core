(ns everida.core.api)

(defprotocol IComponent
  "A marker for system Component record and component lifecycle.
  NOTE: dynamic dependencies are used only for proper starting order.
  Supported keys are (namespace qualified :sys.component/some-key):
  * id - component id (required)
  * alias - component alias (optional)
  * static-deps - vector of component ids, that this component depends on (optional)
  * dynamic-dependency-pred - fn of one argument (unit) to decide about dependency of this component from other units (optional)
  * dynamic-dependency-reverse-pred - fn of one argument (unit) to decide about dependency of other units from this component (optional)
  * order-dependency-pred -  same as dynamic-dependency-pred, but just start ordering without injection
  * order-reverse-dependency-pred - same as dynamic-dependency-reverse-pred, but just start ordering without injection
  * pre-start-pred - fn of one arguments [not-started-unit] used to filter units and call pre-start action for component. (optional)
  * pre-start-action - fn of two arguments [this-component not-started-units] used to register units in component before start. Should return updated component. (optional)
  * post-start-pred - fn of one arguments [started-unit] used to filter units and call post-start action for component. (optional)
  * post-start-action - fn of two arguments [this-component started-units] used to register units in component after start. Should return updated component. (optional)
  * post-start-step-pred - same as post-start-pred for step-action. Does not change order dependency. (optional)
  * post-start-step-action - fn of two arguments [this-component started-unit] used to register started unit after unit after it has been started. Result ignored. Used for chicken-egg cases. (optional)
  * injection-action - fn of two arguments [this-component target-inj-unit] called each time before this-component injection. Should return updated component which will be injected finally. (optional)
  * deps-map - mapping of dependency key (optional)
  * configuration - will be set on start, if supplied (optional)"
  (start [component]
    "Begins operation of this component. Synchronous, does not return
 until the component is started. Returns an updated version of this
 component.")
  (stop [component]
    "Ceases operation of this component. Synchronous, does not return
  until the component is stopped. Returns an updated version of this
  component."))
(def id-key :sys.component/id)
(def alias-key :sys.component/alias)
(def static-deps-key :sys.component/static-dependencies)
(def dynamic-dependency-pred-key :sys.component/dynamic-dependency-predicate)
(def dynamic-reverse-dependency-pred-key :sys.component/dynamic-reverse-dependency-predicate)
(def deps-map-key :sys.component/deps-map)
(def configuration-key :sys.component/configuration)
(def order-dependency-pred-key :sys.component/order-dependency-predicate)
(def order-reverse-dependency-pred-key :sys.component/order-reverse-dependency-predicate)
(def pre-start-pred-key :sys.component/pre-start-predicate)
(def pre-start-action-key :sys.component/pre-start-action)
(def post-start-pred-key :sys.component/post-start-predicate)
(def post-start-action-key :sys.component/post-start-action)
(def post-start-step-pred-key :sys.component/post-start-step-pred)
(def post-start-step-action-key :sys.component/post-start-step-action)
(def injection-action-key :sys.component/injection-action-action)
(def stand-mode-key :sys.component/stand-mode)
(def log-level-key :sys.component/log-level)

(defn get-conf
  ([component] (get component configuration-key))
  ([component k] (get-conf component k nil))
  ([component k default] (get-in component [configuration-key k] default)))

(defn get-in-conf
  ([component ks] (get-in-conf component ks nil))
  ([component ks default] (get-in component
                                  (cons configuration-key (vec ks))
                                  default)))

(defn component-mode [component]
  (get-conf component stand-mode-key :dev))

(defn dev-mode? [component]
  (= :dev (component-mode component)))

(defn get-alias [component]
  (alias-key component (id-key component)))

(defn component? [u]
  (satisfies? IComponent u))

(defn has-key? [k u]
  (some? (k u)))

(defprotocol IModule
  "A marker for system Module record, just to show this doc.
  Supported keys are (namespace qualified :sys.module/some-key):
  * event-pipelines - vector of event pipelines description map (optional, read-only)
  * event-subs - vector of event subscriber description map (optional, read-only)
  * request-pipelines - vector of request pipelines description map (optional, read-only)
  * request-servers - vector of request server description map (optional, read-only)
  * servers-deps - vector of request qn that module depends on (optional, read-only)
  * routes - vector of route descriptors")

(def event-pipes-key :sys.module/event-pipes)
(def event-subs-key :sys.module/event-subs)
(def request-pipes-key :sys.module/request-pipes)
(def request-servers-key :sys.module/request-servers)
(def order-servers-dependency-key :sys.module/order-servers-dependency-key)
(def routes-key :sys.module/routes)

(defn module? [u]
  (satisfies? IModule u))

(defprotocol ICore
  (get-instance-id
    [core])
  (subscribe-event
    [core spec])
  (register-server
    [core spec]
    "Spec is map with :msg-filter(mandatory), :handler OR :channel.
    Both :handler and :channel implementations have to respond to request
    (see api/get-response-chan OR api/respond* methods)")
  (register-event-pipe
    [core spec])
  (register-request-pipe
    [core spec])
  (register-module
    [core module])
  (request-supported?
    [core request])
  (call-async
    [core request])
  (pub-event
    [core event])
  (pub-events
    [core events])
  (success?
    [core response])
  (error?
    [core response])
  (success-response
    [core] [core result])
  (error-response
    [core request error-s]
    [core request error-s request-keys-to-select])
  (exception-response
    [core request exception-code]
    [core request exception-code request-keys-to-select]
    [core request exception-code request-keys-to-select info])
  (get-response-chan
    [core request])
  (set-response-chan
    [core request ch])
  (respond
    [core request]
    [core request response])
  (respond-success
    [core request]
    [core request result])
  (respond-error
    [core request error-s]
    [core request error-s request-keys-to-select])
  (respond-exception
    [core request exception-code]
    [core request exception-code request-keys-to-select]
    [core request exception-code request-keys-to-select info]))

(defprotocol IGenericResourceReader
  (read-resource [reader]))

(defprotocol
  IGenericKvStoreReader
  (read-val [reader korks])
  (read-vals [reader] [reader read-specification]))

(defprotocol
  IGenericKvStoreManager
  (write-val [manager k data])
  (update-val [manager korks data])
  (delete-val [manager k]))

(defprotocol IGenericBuilder
  (register-specifications [builder specs] [builder component specs])
  (build-result
    [builder] [builder arg1] [builder arg1 arg2] [builder arg1 arg2 arg3]))

#?(:clj (defprotocol IClientStateProvider
          (provide-client-state [provider ring-request])))

#?(:clj (def client-state-unit-id-key :client-state-unit-id))

#?(:clj (defprotocol IHasher
          (hash-value [hasher str-value] [hasher str-value work-factor])
          (verify-hash [hasher str-value str-hash])))

(defprotocol IDataProvider
  (get-db [data-provider])
  (get-db-filtered
    [data-provider]
    [data-provider filter-ctors]
    "filter-ctors - vector of fn of one argument plain-db, returns filter-fn")
  (get-conn [data-provider]))

#?(:cljs (defprotocol ISpa
           (close-message-box [spa])
           (show-message-box [spa message-box-info])
           (change-route [spa route-info])
           (mount-component [spa placeholder-id mount-info])
           (register-menu-source [spa source-id source])
           (register-root-menu-group [spa group-items] [spa group-items group-id])
           (unregister-root-menu-group [spa group-id])))

#?(:cljs (defprotocol ISpaDynamicMenuSource
           (get-source-ref [dynamic-source])
           (refresh-source [dynamic-source])))

#_(defn get-entity-spec [entity-type-or-item]
    (a/get-entity-spec system-state entity-type-or-item))

#_(defn get-entity-actions [entity-type-or-spec]
    (a/get-entity-actions system-state entity-type-or-spec))

