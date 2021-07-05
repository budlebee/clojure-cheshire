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
    (do
      (str "wow" "<h1>hello!</h1>" "data in DB: " (:data (first (sql/query "postgresql://localhost:5432/testdb"
                                                                           ["select * from sentences"]))))))
  (POST "/get-sentence" req
    (def user-id
      (Integer/parseInt (get-in req [:body "userId"])))
    ;(def result
     ; (sql/query db [(format "select * from posts where id not in ((select post_id from loves where user_id=%s) union (select post_id from hates where user_id=%s))" user-id user-id)]))
    (def result
      (sql/query db [(format "select * from posts left outer join (select post_id, count (*) from loves group by post_id) as ranking on posts.id=ranking.post_id where id not in ((select post_id from loves where user_id=%s) union (select post_id from hates where user_id=%s)) order by ranking.count asc" user-id user-id)]))
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

  (POST "/user-feed" req
    (def user-id
      (Integer/parseInt (get-in req [:body "userId"])))
    (def result
      (sql/query db [(format "select * from loves left outer join posts on loves.post_id = posts.id where loves.user_id=%s and loves.deleted=false order by loves.id desc" user-id)]))
    (rr/response {:result result}))

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



(def app
  (-> app-routes
      (ring-json/wrap-json-body)
      (ring-json/wrap-json-response)
      (wrap-defaults api-defaults)
      (wrap-cors :access-control-allow-origin [#".*"] :access-control-allow-methods [:get :post])))
