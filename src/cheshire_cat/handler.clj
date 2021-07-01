(ns cheshire-cat.handler
  (:require [compojure.core :refer [defroutes GET POST PUT DELETE]]
            [compojure.route :as route]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults api-defaults]]
            [ring.middleware.json :as ring-json]
            [ring.util.response :as rr]
            [ring.middleware.cors :refer [wrap-cors]]
            [clojure.java.jdbc :as sql]))

(def db "postgresql://localhost:5432/testdb")

(extend-protocol clojure.java.jdbc/ISQLParameter
  clojure.lang.IPersistentVector
  (set-parameter [v ^java.sql.PreparedStatement stmt ^long i]
    (let [conn (.getConnection stmt)
          meta (.getParameterMetaData stmt)
          type-name (.getParameterTypeName meta i)]
      (if-let [elem-type (when (= (first type-name) \_) (apply str (rest type-name)))]
        (.setObject stmt i (.createArrayOf conn elem-type (to-array v)))
        (.setObject stmt i v)))))

(extend-protocol clojure.java.jdbc/IResultSetReadColumn
  java.sql.Array
  (result-set-read-column [val _ _]
    (into [] (.getArray val))))

(defroutes app-routes
  (GET "/" [] (do (println "someone comes in!")
                  (rr/response {:name "home" :status "good"})))
  (GET "/cat/:name/profile" [name]
    (rr/response
     {:name name
      :status :grinning}))
  (GET "/db" []
    (do
      (str "wow" "<h1>hello!</h1>" "data in DB: " (:data (first (sql/query "postgresql://localhost:5432/testdb"
                                                                           ["select * from sentences"]))))))
  (POST "/get-sentence" req
    (def result
      (sql/query db ["select id, content, created_by from posts"]))
    (rr/response {:result result}))

  (POST "/add-sentence" req
    (def content
      (get-in req [:body "content"]))
    (def userId
      (Integer/parseInt (get-in req [:body "userId"])))
    (def post_id (get-in (first (sql/insert! db
                                             :posts {:content content :created_by userId} {:return-keys ["id"]})) [:id]))
    (sql/insert! db :loves {:user_id userId :post_id post_id})
    (rr/response {:content content :userId userId}))

  (POST "/love-sentence" req
    (def post-id
      (Integer/parseInt (get-in req [:body "postId"])))
    (def user-id
      (Integer/parseInt (get-in req [:body "userId"])))
    (sql/insert! db :loves {:user_id user-id :post_id post-id})
    (rr/response {:userId user-id :postId post-id}))

  (POST "/hate-sentence" req
    (def post-id
      (Integer/parseInt (get-in req [:body "postId"])))
    (def user-id
      (Integer/parseInt (get-in req [:body "userId"])))
    (sql/insert! db :hates {:user_id user-id :post_id post-id})
    (rr/response {:userId user-id :postId post-id}))

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
