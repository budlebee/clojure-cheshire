(ns cheshire-cat.handler
  (:require [compojure.core :refer [defroutes GET POST PUT DELETE]]
            [compojure.route :as route]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults api-defaults]]
            [ring.middleware.json :as ring-json]
            [ring.util.response :as rr]
            [clojure.java.jdbc :as sql]))

(defroutes app-routes
  (GET "/" [] (do (println "someone comes in!")
                  (rr/response {:name "home" :status "good"})))
  (GET "/cat/:name/profile" [name]
    (rr/response
     {:name name
      :status :grinning}))
  (GET "/db" []
    (do (println (sql/query "postgresql://localhost:5432/testdb"
                            ["select * from testing"]))
        (str "wow" "<h1>hello!</h1>" "data in DB: " (:data (first (sql/query "postgresql://localhost:5432/testdb"
                                                                             ["select * from testing"]))))))
  (POST "/post" []
    (rr/response {:name "post" :status "good"}))
  (PUT "/put" []
    (rr/response {:name "put" :status "good"}))
  (DELETE "/delete" []
    (rr/response {:name "delete" :status "good"}))
  (route/not-found "Not Found"))

;(sql/db-do-commands "postgresql://localhost:5432/testdb"
;                    (sql/create-table-ddl :testing [[:data :text] [:id :int]]))
 ;(sql/db-do-commands "postgresql://localhost:5432/testdb"
 ;                      "drop table testing")                  

;(sql/insert! "postgresql://localhost:5432/testdb"
 ;            :testing {:data "Hello second!" :id 1})
; (sql/query "postgresql://localhost:5432/testdb"
; [select * from testing] )
;


(def app
  (-> app-routes
      (ring-json/wrap-json-response)
      (wrap-defaults api-defaults)))
