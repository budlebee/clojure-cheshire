
(ns cheshire-cat.handler
  (:gen-class)
  (:require [config.core :refer [env]]
            [compojure.core :refer [defroutes GET POST PUT DELETE]]
            [compojure.route :as route]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults api-defaults]]
            [ring.middleware.json :as ring-json]
            [ring.util.response :as rr]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.cookies :as cookies]
            [clojure.java.jdbc :as sql]
            [honey.sql :as honey]
            [buddy.hashers :as hashers]
            [buddy.sign.jwt :as jwt]))


(def runtime (:runtime env))
(def db-env (:db runtime))
(def dbtype (:dbtype db-env))
(def host (:host db-env))
(def port (:port db-env))
(def dbname (:dbname db-env))
(def jwt-secret
  (:jwt-secret runtime))

(def db (format "%s://%s:%s/%s" dbtype host port dbname))
;(def db "postgresql://localhost:5432/testdb")

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

  (POST "/guest-get-sentence" req
    (def result
      (sql/query db ["select * from posts left outer join (select post_id, count (*) from loves group by post_id) as ranking on posts.id=ranking.post_id order by ranking.count asc"]))
    (rr/response {:result result}))

  (POST "/get-sentence" req
    (def user-id
      (Integer/parseInt (get-in req [:body "userId"])))
    ;(def result
     ; (sql/query db [(format "select * from posts where id not in ((select post_id from loves where user_id=%s) union (select post_id from hates where user_id=%s))" user-id user-id)]))
    (def result
      (sql/query db [(format "select * from posts left outer join (select post_id, count (*) from loves group by post_id) as ranking on posts.id=ranking.post_id where id not in ((select post_id from loves where user_id=%s) union (select post_id from hates where user_id=%s)) order by ranking.count asc" user-id user-id)]))
    ; 해당 사용자의 nickname 도 가져오면 좋겠는걸. 근데 그러려면 users 테이블도 참고해야 되니 join 을 한번 더 해야되네.
    ; 그럼 get-sentence 에선 하지말자. 하지만 my-feed 에서는 할 수 있게 하자.
    (rr/response {:result result}))

  (POST "/add-sentence" req
    (def content
      (get-in req [:body "content"]))
    (def user-id
      (Integer/parseInt (get-in req [:body "userId"])))
    (def post-id (get-in (first (sql/insert! db
                                             :posts {:content content :created_by user-id} {:return-keys ["id"]})) [:id]))
    (sql/insert! db :loves {:user_id user-id :post_id post-id})
    (rr/response {:content content :userId user-id}))

  (POST "/love-sentence" req
    (def post-id
      (Integer/parseInt (get-in req [:body "postId"])))
    (def user-id
      (Integer/parseInt (get-in req [:body "userId"])))
    (sql/insert! db :loves {:user_id user-id :post_id post-id})
    ; 여기서 lambda api 를 호출한다.
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
    (def my-id
      (Integer/parseInt (get-in req [:body "myId"])))
    (def result
      (sql/query db [(format "select posts.content, posts.timestamp, posts.created_by, posts.id as post_id from loves left outer join posts on loves.post_id = posts.id where loves.user_id=%s and loves.deleted=false order by loves.added_at desc" user-id)]))
    ;(def is-follow
    ;  (sql/query db [""]))
    ; users 에서 해당 posts 의 id 랑 join 해서, 해당 문장을 추가한 사람의 닉네임도 가져오자.
    ; 또한 어차피 users 테이블을 훑어야 되는게, 이 유저의 닉네임도 가져와야지 화면에 표시해줄게 있네. 프로필사진같은것은 따로 하지말고, 몇가지 동물 아이콘중 고르라고 하자.
    ; 또한 해당유저와 내가 팔로우 관계인지 보여줘야된다. followers 도 훑어야함.
    (rr/response {:result result}))

  (POST "/gurus-feed" req
    (def user-id
      (Integer/parseInt (get-in req [:body "userId"])))
    (def result
      (sql/query db [(format "select posts.content, posts.timestamp, posts.created_by, posts.id as post_id, users.nickname from posts left outer join users on posts.created_by=users.id where created_by in (select guru_id from followers where follower_id=%s) order by timestamp desc" user-id)]))
    (rr/response {:result result}))

  (POST "/follow" req
    (def guru-id
      (Integer/parseInt (get-in req [:body "guruId"])))
    (def follower-id
      (Integer/parseInt (get-in req [:body "userId"])))
    (sql/insert! db :followers {:guru_id guru-id :follower_id follower-id})
    (rr/response {:guru_id guru-id :follower_id follower-id}))

  (POST "/login" req
    (def email
      (get-in req [:body "email"]))
    (def pwd
      (get-in req [:body "pwd"]))
    (def query-vector
      (honey/format {:select [:id :hashed_pwd]
                     :from   [:users]
                     :where  [:= :users.email email]} {:inline true}))
    (def query-result
      (first (sql/query db query-vector)))
    (def hashed-pwd
      (:hashed_pwd query-result))
    (def user-id
      (:id query-result))
    (def result
      (get (hashers/verify pwd hashed-pwd) :valid))
    ; 헤더에 set cookie 로 refresh token .
    (if result
      (let [claims {:aud user-id
                    :exp (+ (quot (System/currentTimeMillis) 1000) 3600)}
            token (jwt/sign claims jwt-secret {:alg :hs512})]
        (rr/set-cookie
         (rr/response
          {:result true :token token :userId user-id})
         "cookie name"
         "value"
         {:max-age (* 30 24 60 60 1000) :path "/"}))
      (rr/response {:result result})))




  (POST "/email-verification" req
    (def email
      (get-in req [:body "email"]))
 ; email 주소로 메일 보내는 lambda 함수 호출.
    (rr/response {:result "good"}))

  (POST "/signup" req
    (def nickname
      (get-in req [:body "nickname"]))
    (def email
      (get-in req [:body "email"]))
    (def hashed-pwd
      (hashers/derive (get-in req [:body "pwd"])))
    ; pwd 를 salt 랑 섞어서 해쉬화 해가지고 저장할 것.
    (def user-id (get-in (first (sql/insert! db
                                             :users {:email email :nickname nickname :hashed_pwd hashed-pwd} {:return-keys ["id"]})) [:id]))
    (rr/response {:email email :nickname nickname :userId user-id})
    (let [claims {:aud user-id
                  :exp (+ (quot (System/currentTimeMillis) 1000) 3600)}
          token (jwt/sign claims jwt-secret {:alg :hs512})]
      (rr/response {:result true :token token :userId user-id})))

  (PUT "/put" []
    (rr/response {:name "put" :status "good"}))

  (DELETE "/delete" []
    (rr/response {:name "delete" :status "good"}))

  (route/not-found "Not Found"))



(def app
  (-> app-routes
      (ring-json/wrap-json-body)
      (ring-json/wrap-json-response)
      (cookies/wrap-cookies)
      (wrap-defaults api-defaults)
      (wrap-cors :access-control-allow-origin [#"http://localhost:3000"]
                 :access-control-allow-methods [:get :post]
                 :access-control-allow-credentials "true"
                 :access-control-allow-headers #{"accept" "accept-encoding" "accept-language" "authorization" "content-type" "origin"})))

