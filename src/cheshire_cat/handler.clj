
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
            [buddy.sign.jwt :as jwt]
            [clj-time.core]
            [clj-time.coerce]
            [clj-time.local]))



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
      (sql/query db ["select posts.id, posts.content, posts.created_by, posts.timestamp, coalesce(count, 0) as rank from posts left outer join (select post_id, count (*) from loves group by post_id) as ranking on posts.id=ranking.post_id order by rank desc"]))
    (rr/response {:result result}))

  (POST "/get-sentence" req
    (def user-id
      (Integer/parseInt (get-in req [:body "userId"])))
    ;(def result
     ; (sql/query db [(format "select * from posts where id not in ((select post_id from loves where user_id=%s) union (select post_id from hates where user_id=%s))" user-id user-id)]))
    ;(def result
    ;  (sql/query db [(format "select * from posts left outer join (select post_id, count (*) from loves group by post_id) as ranking on posts.id=ranking.post_id where id not in ((select post_id from loves where user_id=%s) union (select post_id from hates where user_id=%s)) order by ranking.count asc" user-id user-id)]))
    (def result
      (sql/query db [(format "select posts.id, posts.content, posts.created_by, posts.timestamp, 5*coalesce(correlation,0)+coalesce(count, 0) as rank from posts left outer join (select post_id, count (*) from loves group by post_id) as ranking on posts.id=ranking.post_id 
left outer join (select post_id, count(*) as correlation from loves where user_id in (select user_id from loves where post_id in (select post_id from loves where user_id = %s) and user_id != %s) group by post_id order by post_id) as relation on posts.id = relation.post_id
where id not in ((select post_id from loves where user_id = %s) union (select post_id from hates where user_id= %s)) order by rank desc limit 10;" user-id user-id user-id user-id)]))
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
      (sql/query db [(format "select posts.content, posts.timestamp, posts.created_by, posts.id as post_id, users.nickname as nickname, loves.added_at as added_at from loves left outer join posts on loves.post_id = posts.id left outer join users on posts.created_by=users.id where loves.user_id=%s and loves.deleted=false order by loves.added_at desc" user-id)]))
    (def user-nickname
      (:nickname (first (sql/query db [(format "select nickname from users where id=%s limit 1" user-id)]))))
    (def is-guru
      (if-not (= 0 (count (sql/query db [(format "select id from followers where guru_id=%s and follower_id=%s and deleted=false limit 1" user-id my-id)])))
        true
        false))
    (rr/response {:result result :isGuru is-guru :userNickname user-nickname}))

  ;(POST "get-my-followers" req
    ; 나를 팔로우 하고 있는 사람들의 목록을 보여주는 api.
  ;  (rr/response {}))

  (POST "/gurus-feed" req
    (def my-id
      (Integer/parseInt (get-in req [:body "myId"])))
    (def result
      (sql/query db [(format "select posts.content, posts.timestamp, posts.created_by, posts.id as post_id, users.nickname from posts left outer join users on posts.created_by=users.id where created_by in (select guru_id from followers where follower_id=%s and deleted=false) order by timestamp desc" my-id)]))
    (rr/response {:result result}))

  (POST "/follow" req
    (def guru-id
      (Integer/parseInt (get-in req [:body "guruId"])))
    (def follower-id
      (Integer/parseInt (get-in req [:body "userId"])))
    (def was-guru
      (if-not (= 0 (count (sql/query db [(format "select id from followers where guru_id=%s and follower_id=%s and deleted=true limit 1" guru-id follower-id)])))
        true
        false))
    ; if 문으로 예전에 팔로우 한적 있는지 중복 체크를 먼저 하고, 중복있다면 deleted true 를 false. 없다면 (if )
    ; 쿼리를 두번 날리지 않고, sql if then else 쓰면 한번에 될텐데 지금은 잘 모르겠다.
    (if was-guru
      (sql/execute! db [(format "update followers set deleted=false where guru_id=%s and follower_id=%s and deleted=true" guru-id follower-id)])
      (sql/insert! db :followers {:guru_id guru-id :follower_id follower-id}))
    ;(sql/insert! db :followers {:guru_id guru-id :follower_id follower-id})
    (rr/response {:guruId guru-id :followerId follower-id :isGuru true}))

  (POST "/unfollow" req
    (def guru-id
      (Integer/parseInt (get-in req [:body "guruId"])))
    (def follower-id
      (Integer/parseInt (get-in req [:body "userId"])))
    ;(sql/execute! db (honey/format {:update :followers
     ;                               :set {:deleted true}
      ;                              :where [[:= :deleted false] [:= :guru_id guru-id] [:= :follower_id follower-id]]}))
    (sql/execute! db [(format "update followers set deleted=true where guru_id=%s and follower_id=%s and deleted=false" guru-id follower-id)])
    (rr/response {:guruId guru-id :followerId follower-id :isGuru false}))

  (POST "/login" req
    (def email
      (get-in req [:body "email"]))
    (def pwd
      (get-in req [:body "pwd"]))
    (def query-vector
      (honey/format {:select [:id :hashed_pwd]
                     :from   [:users]
                     :where  [:= :users.email email]
                     :limit 1} {:inline true}))

    (def query-result
      (first (sql/query db query-vector)))
    (def hashed-pwd
      (:hashed_pwd query-result))
    (def user-id
      (:id query-result))
    (def result
      (get (hashers/verify pwd hashed-pwd) :valid))
    (if result
      (let [claims {:aud user-id
                    :exp (+ (quot (System/currentTimeMillis) 1000) 3600)}
            token (jwt/sign claims jwt-secret {:alg :hs512})
            rftk-value (java.util.UUID/randomUUID)]
        (sql/insert! db
                     :refresh_tokens {:value rftk-value :user_id user-id})
        (rr/set-cookie
         (rr/response
          {:result true :token token :userId user-id})
         "rftk"
         rftk-value
         {:max-age (* 3 24 60 60) :path "/" :http-only "true"}))
      (rr/response {:result result})))

  (POST "/logout" req
    (println "run logout")
    (rr/set-cookie
     (rr/response
      {:result true})
     "rftk"
     "kill"
     {:max-age (* 1 1) :path "/" :http-only "true"}))

  (POST "/silent-refresh" req
    (println "run silent-refresh")
    (def req-user-id-cookie-value
      (get (get-in req [:cookies "user-id"]) :value))
    ;(if-not req-user-id-cookie-value (rr/bad-request {:message "no user-id cookie"}))
    (def req-rftk-value
      (get (get-in req [:cookies "rftk"]) :value))
    (def user-id (:user_id (first (sql/query db (honey/format {:select [:user_id]
                                                               :from   [:refresh_tokens]
                                                               :where  [:= :value req-rftk-value]
                                                               :limit 1} {:inline true})))))
    (if user-id
      (let [claims {:aud user-id
                    :exp (+ (quot (System/currentTimeMillis) 1000) 3600)}
            token (jwt/sign claims jwt-secret {:alg :hs512})
            rftk-value (java.util.UUID/randomUUID)]
        (sql/insert! db
                     :refresh_tokens {:value rftk-value :user_id user-id})
        (rr/set-cookie
         (rr/response
          {:result true :token token :userId user-id})
         "rftk"
         rftk-value
         {:max-age (* 3 24 60 60) :path "/" :http-only "true"}))
      (rr/set-cookie
       (rr/response
        {:result false})
       "rftk"
       "kill"
       {:max-age (* 1 1) :path "/" :http-only "true"})))


  (POST "/email-verification" req
    (def email
      (get-in req [:body "email"]))
 ; email 주소로 메일 보내는 lambda 함수 호출.
    (rr/response {:result "good"}))

  (POST "/signup" req
    ;(try ()(catch Exception e ))
    (do
      (println (get req :body))
      (def nickname
        (get-in req [:body "nickname"]))
      (def email
        (get-in req [:body "email"]))
      (def hashed-pwd
        (hashers/derive (get-in req [:body "pwd"])))

      (if (> (count (sql/query db (honey/format {:select [:id]
                                                 :from [:users]
                                                 :where [:= :users.email email]} {:inline true}))) 0)
        (do (rr/response {:result false :message "이미 회원가입한 이메일입니다."}))
        ((do (def user-id (get-in (first (sql/insert! db
                                                      :users {:email email :nickname nickname :hashed_pwd hashed-pwd} {:return-keys ["id"]})) [:id]))
             (if (def temp-love
                   (Integer/parseInt (get-in req [:body "tempLove"])))
               (sql/insert! db :loves {:post_id temp-love :user_id user-id}) ())
             (let [claims {:aud user-id
                           :exp (+ (quot (System/currentTimeMillis) 1000) 3600)}
                   token (jwt/sign claims jwt-secret {:alg :hs512})
                   rftk-value (java.util.UUID/randomUUID)]
               (rr/set-cookie
                (rr/response
                 {:result true :token token :userId user-id})
                "rftk"
                rftk-value
                {:max-age (* 3 24 60 60) :path "/" :http-only "true"})))))))

  (PUT "/put" []
    (rr/response {:name "put" :status "good"}))

  (DELETE "/delete" []
    (rr/response {:name "delete" :status "good"}))

  (route/not-found "Not Found"))


(try (/ 1 0) (catch Exception e (str "error occur: " (.getMessage e))))

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

