(ns cheshire-cat.handler
  (:require [compojure.core :refer [defroutes GET POST PUT DELETE]]
            [compojure.route :as route]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults api-defaults]]
            [ring.middleware.json :as ring-json]
            [ring.util.response :as rr]
            [ring.middleware.cors :refer [wrap-cors]]

            [clojure.java.jdbc :as sql]))

(def db "postgresql://localhost:5432/testdb")

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
  (POST "/get-sentence" req
    (println (type (sql/query db
                              ["select * from sentences"])))
    (rr/response (sql/query db
                            ["select * from sentences"])))
  (POST "/add-sentence" req
    ;(def addedBy (get (get req :body) "addedBy"))
    ; addedby 에는 사용자 id 값, int 가 들어가야함.
    (def text (get (get req :body) "text"))
    (def con (sql/get-connection db))
    (def val-to-insert
      (.createArrayOf con "int" (into-array Integer [])))
    (sql/insert! db
                 :sentences {:text text :addedby 1234 :lovers val-to-insert :haters val-to-insert})
    (rr/response {:addedBy 1234 :text text}))
  (PUT "/love-sentence" req
    ;(def addedBy (get (get req :body) "addedBy"))
    ;(def bodyText (get (get req :body) "bodyText"))
    ;(rr/response {:addedBy addedBy :bodyText bodyText})
    )
  (PUT "/hate-sentence" req
    ;(def addedBy (get (get req :body) "addedBy"))
    ;(def bodyText (get (get req :body) "bodyText"))
    ;(rr/response {:addedBy addedBy :bodyText bodyText})
    )
  (POST "/signup" req
    (def nickname (get (get req :body) "nickname"))
    (def email (get (get req :body) "email"))
    (sql/insert! db
                 :users {:email email :nickname nickname})
    (rr/response {:email email :nickname nickname}))
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
      (ring-json/wrap-json-body)
      (ring-json/wrap-json-response)
      (wrap-defaults api-defaults)
      (wrap-cors :access-control-allow-origin [#".*"] :access-control-allow-methods [:get :post])))
