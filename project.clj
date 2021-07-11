(defproject cheshire-cat "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [compojure "1.6.1"]
                 [ring/ring-defaults "0.3.2"]
                 [ring/ring-json "0.4.0"]
                 [ring-cors "0.1.13"]
                 [org.clojure/java.jdbc "0.6.1"]
                 [org.postgresql/postgresql "9.4-1201-jdbc41"]
                 [com.github.seancorfield/honeysql "2.0.0-rc2"]
                 [cheshire "5.10.0"]
                 [buddy/buddy-hashers "1.8.1"]
                 [buddy/buddy-sign "3.4.1"]
                 [honeysql "1.0.461"]
                 [yogthos/config "1.1.1"]
                 [clj-time "0.15.2"]]
  :plugins [[lein-ring "0.12.5"]]
  :ring {:handler cheshire-cat.handler/app
         :port 8000}
  :profiles
  {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                        [ring/ring-mock "0.3.2"]]
         :resource-paths ["config/dev"]}
   :prod {:resource-paths ["config/prod"]}})
