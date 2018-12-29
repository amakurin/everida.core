(ns everida.readers.io-edn-resource
  (:require [everida.core.api :as api]
            [everida.readers.primitive-data-resource :as primitive]
            [clojure.java.io :as io]
            [clojure.tools.reader :as reader]
            ))

(defrecord Reader []
  api/IGenericResourceReader
  (read-resource [reader]
    (let [path (:resource-file-relative-path reader)]
      (if-let [file (io/resource path)]
        (binding [clojure.tools.reader/*data-readers* *data-readers*]
          (try
            (reader/read-string (slurp file))
            (catch Exception ex
              (let [data (ex-data ex)]
                (throw (ex-info (.getMessage ex)
                                {:code (:type data :internal-exception)
                                 :resource-file-path path}
                                ex))))))
        (throw (ex-info "Edn resource reader can't find resource file"
                        {:code :resource-file-not-found
                         :resource-file-path path}))))))

(defn new-reader [resource-file-relative-path & [single-read-on-construction]]
  (let [params {:resource-file-relative-path resource-file-relative-path}
        reader (map->Reader params)]
    (if single-read-on-construction
      (primitive/new-reader (api/read-resource reader) params)
      reader)))

