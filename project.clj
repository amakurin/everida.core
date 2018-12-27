(defproject amakurin/everida.core "0.1.0"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :jvm-opts ["-Xms512m" "-Xmx1024m"]
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.10.439"]
                 [org.clojure/core.async "0.4.490"]
                 [com.stuartsierra/component "0.3.2"]
				 [amakurin/everida.utils "0.1.0"]
				 [amakurin/everida.dtl "0.1.0"]]
  :source-paths ["src"]
  )
