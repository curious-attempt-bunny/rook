(ns rook.dispatcher-spec
  (:use speclj.core
        clojure.pprint
        [clojure.template :only [do-template]])
  (:require [io.aviso.rook.dispatcher :as dispatcher]
            [io.aviso.rook.client :as client]
            [io.aviso.rook.utils :as utils]
            [io.aviso.rook.async :as rook-async]
            [io.aviso.rook :as rook]
            [io.aviso.rook.jetty-async-adapter :as jetty]
            [clj-http.client :as http]
            [clj-http.cookies :as cookies]
            [ring.mock.request :as mock]
            [clojure.core.async :as async]
            ring.middleware.params
            ring.middleware.keyword-params
            [clojure.pprint :as pp]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint])
  (:import (javax.servlet.http HttpServletResponse)))

(defn wrap-with-pprint-request [handler]
  (fn [request]
    (utils/pprint-code request)
    (handler request)))

(defn wrap-with-pprint-response [handler]
  (fn [request]
    (let [resp (handler request)]
      (if (satisfies? clojure.core.async.impl.protocols/ReadPort resp)
        (let [v (async/<!! resp)]
          (async/>!! resp v)
          (utils/pprint-code v))
        (utils/pprint-code resp))
      (prn)
      resp)))

(defn wrap-with-incrementer [handler atom]
  (fn [request]
    (swap! atom inc)
    (handler request)))


(defn generate-huge-resource-namespace [ns-name size]
  (if-let [ns (find-ns ns-name)]
    (throw
      (ex-info
        (str
          "It so happens that we already have a namespace named "
          ns-name
          "; I probably shouldn't touch it.")
        {:ns-name ns-name :ns ns})))
  (let [ns (create-ns ns-name)]
    (doseq [i (range size)
            :let [foo (str "foo" i)]]
      (intern ns
              (with-meta (symbol foo)
                         {:route-spec [:get [foo :x]]
                          :arglists   (list '[request x])})
              (fn [request x]
                {:status  200
                 :headers {}
                 :body    (str foo "/" x)})))))


(create-ns 'example.foo)

(binding [*ns* (the-ns 'example.foo)]
  (eval '(do
           (clojure.core/refer-clojure)
           (require '[ring.util.response :as resp])
           (defn index []
             (resp/response "Hello!"))
           (defn show [id]
             (resp/response (str "Interesting id: " id)))
           (defn create [^:param x]
             (resp/response (str "Created " x))))))

(create-ns 'example.bar)

(binding [*ns* (the-ns 'example.bar)]
  (eval '(do
           (clojure.core/refer-clojure)
           (require '[ring.util.response :as resp])
           (defn index []
             (resp/response "Hello!"))
           (defn show [id]
             (resp/response (str "Interesting id: " id)))
           (defn create [^:param x]
             (resp/response (str "Created " x))))))


(defn default-middleware
  "Pretty much same as the one in dispatcher, but seperate to faciliate some of the tests."
  [handler middleware]
  handler)

(def simple-dispatch-table
  [[:get ["foo"] 'example.foo/index default-middleware]
   [:post ["foo"] 'example.foo/create default-middleware]
   [:get ["foo" :id] 'example.foo/show default-middleware]])

(describe "io.aviso.rook.dispatcher"

  (describe "path-spec->route-spec"

    (it "should correctly convert path specs to route specs"
        (should= [:get ["foo" :id]]
                 (dispatcher/path-spec->route-spec [:get "/foo/:id"]))))

  (describe "pathvec->path"

    (it "should correctly convert pathvecs to paths"
      (should= "/"        (dispatcher/pathvec->path []))
      (should= "/foo"     (dispatcher/pathvec->path ["foo"]))
      (should= "/foo/bar" (dispatcher/pathvec->path ["foo" "bar"]))
      (should= "/foo/:id" (dispatcher/pathvec->path ["foo" :id]))))

  (describe "unnest-dispatch-table"

    (it "should leave tables with no nesting unchanged"

        (should= simple-dispatch-table
                 (dispatcher/unnest-dispatch-table simple-dispatch-table)))

    (it "should correctly unnest DTs WITHOUT default middleware"

        (let [dt [(into [["api"]] simple-dispatch-table)]]
          (should= [[:get ["api" "foo"] 'example.foo/index default-middleware]
                    [:post ["api" "foo"] 'example.foo/create default-middleware]
                    [:get ["api" "foo" :id] 'example.foo/show default-middleware]]
                   (dispatcher/unnest-dispatch-table dt))))

    (it "should correctly unnest DTs WITH default middleware and empty context pathvec"

        (let [dt [(into [[] default-middleware]
                        (mapv pop simple-dispatch-table))]]
          (should= simple-dispatch-table
                   (dispatcher/unnest-dispatch-table dt))))

    (it "should correctly unnest DTs WITH default middleware and non-empty context pathvec"

        (let [dt [(into [["api"] default-middleware]
                        (mapv pop simple-dispatch-table))]]
          (should= [[:get ["api" "foo"] 'example.foo/index default-middleware]
                    [:post ["api" "foo"] 'example.foo/create default-middleware]
                    [:get ["api" "foo" :id] 'example.foo/show default-middleware]]
                   (dispatcher/unnest-dispatch-table dt)))))


  (describe "compile-dispatch-table"

    (it "should produce a handler returning valid response maps"

        (let [handler (dispatcher/compile-dispatch-table simple-dispatch-table)
              index-response (handler (mock/request :get "/foo"))
              show-response (handler (mock/request :get "/foo/1"))
              create-response (handler (merge (mock/request :post "/foo")
                                              {:params {:x 123}}))]
          (should= {:status 200 :headers {} :body "Hello!"}
                   index-response)
          (should= {:status 200 :headers {} :body "Interesting id: 1"}
                   show-response)
          (should= {:status 200 :headers {} :body "Created 123"}
                   create-response)))

    (it "should inject the default middleware"

        (let [a (atom 0)]
          (with-redefs [default-middleware (fn [handler metadata]
                                             (fn [request]
                                               (swap! a inc)
                                               (handler request)))]
                       (let [local-dispatch-table [[:get ["foo"] 'example.foo/index default-middleware]]
                             handler (dispatcher/compile-dispatch-table local-dispatch-table)]
                         (handler (mock/request :get "/foo"))
                         (should= 1 @a))))))

  (describe "namespace-dispatch-table"

    (it "should return a DT reflecting the state of the namespace"

        (let [dt (set (dispatcher/unnest-dispatch-table
                        (dispatcher/namespace-dispatch-table
                          [["foo"] 'example.foo default-middleware])))]
          (should= (set simple-dispatch-table) dt)))

    (it "should respect the :context-pathvec option"

        (let [dt (set (dispatcher/unnest-dispatch-table
                        (dispatcher/namespace-dispatch-table
                          {:context-pathvec ["api"]}
                          [["foo"] 'example.foo default-middleware])))]
          (should= (set (map (fn [[_ pathvec :as entry]]
                               (update-in entry [1] #(into ["api"] %)))
                             simple-dispatch-table))
                   dt)))

    (it "should respect the :default-middleware option"

        (let [dt (dispatcher/unnest-dispatch-table
                   (dispatcher/namespace-dispatch-table
                     {:default-middleware 'very-strange-middleware}
                     [["foo"] 'example.foo]))]
          (should (every? #{'very-strange-middleware} (map peek dt)))))

    (it "should only use :default-middleware in absence of explicit middleware"

        (let [dt (dispatcher/unnest-dispatch-table
                   (dispatcher/namespace-dispatch-table
                     {:default-middleware 'very-strange-middleware}
                     [["foo"] 'example.foo]
                     [["bar"] 'example.bar 'completely-regular-middleware]))
              foos (filter (fn [[_ [seg] & _]] (= seg "foo")) dt)
              bars (filter (fn [[_ [seg] & _]] (= seg "bar")) dt)]
          (should= 3 (count foos))
          (should= 3 (count bars))
          (should= #{'very-strange-middleware 'completely-regular-middleware}
                   (set (map peek dt)))
          (should= #{'very-strange-middleware}
                   (set (map peek foos)))
          (should= #{'completely-regular-middleware}
                   (set (map peek bars)))))

    (it "should support ns-specs consisting of the ns symbol alone"

        (let [dt (dispatcher/unnest-dispatch-table
                   (dispatcher/namespace-dispatch-table
                     {:context-pathvec    ["foo"]
                      :default-middleware default-middleware}
                     ['example.foo]))]
          (should= (set simple-dispatch-table)
                   (set dt))))


    (it "should report invalid namespace specs well"
        (try
          (dispatcher/namespace-dispatch-table {}
                                               ;; A simple, comment error: example.foo is nested incorrectly.
                                               [[:post "/foo" 'example.foo]])
          (should-fail)
          (catch Throwable t
            (should-contain "Parsing namespace specification `[[:post \"/foo\" example.foo]]'."
                            (-> t .getData :operation-trace))))))

  (describe "compiled handlers using map traversal"

    (it "should return the expected responses"
        (do-template [method path namespace-name extra-params expected-value]
                     (do
                       (should= expected-value
                                (let [mw (fn [handler metadata]
                                           (-> handler
                                               ring.middleware.keyword-params/wrap-keyword-params
                                               ring.middleware.params/wrap-params))
                                      dt (dispatcher/namespace-dispatch-table
                                           [[] namespace-name mw])
                                      handler (dispatcher/compile-dispatch-table
                                                {:build-handler-fn dispatcher/build-map-traversal-handler}
                                                dt)
                                      body #(:body % %)]
                                  (-> (mock/request method path)
                                      (update-in [:params] merge extra-params)
                                      handler
                                      ;; TODO: fix rook-spec and rook-test/activate (the
                                      ;; latter should return a response map rather than a
                                      ;; string) and switch back to :body
                                      body))))

                     :get "/?limit=100" 'rook-test {} "limit=100"
                     :get "/" 'rook-test {} "limit="
                     :get "/123" 'rook-test {} "id=123"
                     :get "/123/activate" 'rook-test {} nil
                     :put "/" 'rook-test {} nil
                     :put "/123" 'rook-test {} nil

                     :post "/123/activate" 'rook-test
                     {:test1 "foo" :test2 "bar"}
                     "test1=foo,id=123,test2=bar,test3=TEST#,test4=test$/123/activate,meth=:post")))

  (describe "argument resolution"

    (it "supports overriding default arg resolvers"

        (let [override ^:replace-resolvers {'magic-value (constantly "**magic**")}
              handler (rook/namespace-handler {:arg-resolvers override}
                                              [["magic"] 'magic])]
          (-> (mock/request :get "/magic")
              handler
              (should= "**magic**"))))

    (it "supports overriding default arg resolver factories"
        (let [override ^:replace-factories {:magic (fn [sym]
                                                     (constantly (str "**presto[" sym "]**")))}
              handler (rook/namespace-handler {:arg-resolvers override}
                                              [["presto"] 'presto])]
          (-> (mock/request :get "/presto/42")
              handler
              (should= "42 -- **presto[extra]**")))))

  (describe "async handlers"

    (it "should return a channel with the correct response"

        (let [handler (rook/namespace-handler {:async? true}
                                              [[] 'barney default-middleware])]
          (should= {:message "ribs!"}
                   (-> (mock/request :get "/") handler async/<!! :body))))

    (it "should expose the request's :params key as an argument"
        (let [handler (rook/namespace-handler {:async? true}
                                              [[] 'echo-params])
              params {:foo :bar}]
          (should-be-same params
                          (-> (mock/request :get "/")
                              (assoc :params params)
                              handler
                              async/<!!
                              :body
                              :params-arg))))


    (it "should return a 500 response if a sync handler throws an exception"
        (let [handler (-> (rook/namespace-handler
                            [["fail"] 'failing])
                          rook-async/wrap-restful-format
                          rook-async/wrap-with-loopback
                          rook-async/async-handler->ring-handler)]
          (should= HttpServletResponse/SC_INTERNAL_SERVER_ERROR
                   (-> (mock/request :get "/fail") handler :status)))))


  (describe "loopback-handler"

    (it "should allow two resources to collaborate"
        (let [handler (rook-async/async-handler->ring-handler
                        (rook-async/wrap-with-loopback
                          (rook/namespace-handler {:async? true}
                                                  [["fred"] 'fred]
                                                  [["barney"] 'barney])))]
          (should= ":barney says `ribs!'"
                   (-> (mock/request :get "/fred")
                       handler
                       :body
                       :message))))

    (it "should allow three resources to collaborate"
        (let [handler (rook-async/async-handler->ring-handler
                        (rook-async/wrap-with-loopback
                          (rook/namespace-handler {:async? true}
                                                  [["fred"] 'fred]
                                                  [["barney"] 'barney]
                                                  [["betty"] 'betty])))]
          (should= ":barney says `:betty says `123 is a very fine id!''"
                   (-> (mock/request :get "/fred/123") handler :body :message)))))


  (describe "handlers with schema attached"

    (it "should respond appropriately given a valid request"
        (let [handler (->> (dispatcher/namespace-dispatch-table
                             [["validating"] 'validating rook-async/wrap-with-schema-validation])
                           (dispatcher/compile-dispatch-table {:async? true})
                           rook-async/wrap-with-loopback
                           rook-async/async-handler->ring-handler)
              response (-> (mock/request :post "/validating")
                           (merge {:params {:name "Vincent"}})
                           handler)]
          (should= HttpServletResponse/SC_OK (:status response))
          (should= [:name] (:body response))))

    (it "should send schema validation failures"
        (let [handler (->> (dispatcher/namespace-dispatch-table
                             [["validating"] 'validating rook-async/wrap-with-schema-validation])
                           (dispatcher/compile-dispatch-table {:async? true})
                           rook-async/wrap-with-loopback
                           rook-async/async-handler->ring-handler
                           ring.middleware.keyword-params/wrap-keyword-params
                           ring.middleware.params/wrap-params)
              response (-> (mock/request :post "/validating")
                           handler)]
          (should= HttpServletResponse/SC_BAD_REQUEST (:status response))
          (should= "validation-error" (-> response :body :error))
          ;; TODO: Not sure that's the exact format I want sent back to the client!
          (should= "{:name missing-required-key}" (-> response :body :failures)))))


  (describe "handlers with a large number of endpoints"

    (it "should compile and handle requests as expected using map traversal"
        (should= {:status 200 :headers {} :body "foo0/123"}
                 ((let [size 500]
                    (remove-ns 'rook.example.huge)
                    (generate-huge-resource-namespace 'rook.example.huge size)
                    (dispatcher/compile-dispatch-table
                      (dispatcher/namespace-dispatch-table [[] 'rook.example.huge])))
                  {:request-method :get
                   :uri            "/foo0/123"
                   :server-name    "127.0.0.1"
                   :port           8080
                   :remote-addr    "127.0.0.1"
                   :scheme         :http
                   :headers        {}}))))

  (describe "running inside jetty-async-adapter"

    (with-all server
              (let [handler (->
                              (rook/namespace-handler
                                {:async?             true
                                 :default-middleware rook-async/wrap-with-schema-validation
                                 :arg-resolvers      {'strange-injection :injection}
                                 ;; just to make sure Swagger support doesn't break things
                                 :swagger            true}
                                [["fred"] 'fred]
                                [["barney"] 'barney]
                                [["betty"] 'betty]
                                [["slow"] 'slow]
                                [["sessions"] 'sessions]
                                [["creator"] 'creator]
                                [["creator-loopback"] 'creator-loopback]
                                [["static"] 'static]
                                [["static2" :foo "asdf"] 'static2 dispatcher/default-namespace-middleware]
                                [["catch-all"] 'catch-all dispatcher/default-namespace-middleware]
                                [["surprise"] 'surprise dispatcher/default-namespace-middleware
                                 [[:id "foo"] 'surprise-foo]]
                                [["foobar"] 'foobar])
                              rook-async/wrap-with-loopback
                              rook-async/wrap-session
                              rook-async/wrap-with-standard-middleware
                              (rook/wrap-with-injection :strange-injection "really surprising"))]
                (jetty/run-async-jetty handler
                                       {:host "localhost" :port 9988 :join? false :async-timeout 100})))

    (it "initializes the server successfully"
        (should-not-be-nil @server))

    (it "can process requests and return responses"
        (let [response (http/get "http://localhost:9988/fred" {:accept :json})]
          (should= HttpServletResponse/SC_OK
                   (:status response))
          (should= "application/json; charset=utf-8"
                   (-> response :headers (get "Content-Type")))
          (should= "{\"message\":\":barney says `ribs!'\"}" (:body response))))

    (it "will respond with a failure if the content is not valid"
        (let [response (http/post "http://localhost:9988/fred"
                                  {:accept           :edn
                                   :content-type     :edn
                                   :body             "{not valid edn"
                                   :as               :clojure
                                   :throw-exceptions false})]
          ;; this is actually client error, but we don't guard against it
          (should= 500 (:status response))
          (should= {:exception "EOF while reading"} (:body response))))

    (it "can manage server-side session state"
      (let [k (utils/new-uuid)
            v (utils/new-uuid)
            uri "http://localhost:9988/sessions/"
            store (cookies/cookie-store)

            response (http/post (str uri k "/" v)
                       {:accept       :edn
                        :cookie-store store})
            response' (http/get (str uri k)
                        {:accept           :edn
                         :cookie-store     store
                         :throw-exceptions false})]
        (should= 200 (:status response))
        (should= (pr-str {:result :ok}) (:body response))
        (should= 200 (:status response'))
        (should= v (-> response' :body edn/read-string :result))))

    (it "handles a slow handler timeout"
        (let [response (http/get "http://localhost:9988/slow"
                                 {:accept           :json
                                  :throw-exceptions false})]
          (should= HttpServletResponse/SC_GATEWAY_TIMEOUT (:status response))))

    (it "responds with 404 if no handler can be found"
        (let [response (http/get "http://localhost:9988/wilma"
                                 {:throw-exceptions false})]
          (should= HttpServletResponse/SC_NOT_FOUND (:status response))))

    ;; TODO: this passes, but context handling needs more thought
    (it "can calculate :resource-uri after loopback"
        (let [response (http/post "http://localhost:9988/creator-loopback"
                                  {:throw-exceptions false})]
          (should= "http://localhost:9988/creator/<ID>"
                   (get-in response [:headers "Location"]))))

    (it "should allow three resources to collaborate"
        (let [response (http/get "http://localhost:9988/fred/123"
                                 {:accept           :edn
                                  :throw-exceptions true})]
          (should= ":barney says `:betty says `123 is a very fine id!''"
                   (-> response :body edn/read-string :message))))

    (it "should resolve arguments statically given appropriate metadata"
        (let [response (http/get "http://localhost:9988/static"
                                 {:accept :edn})]
          (should= "Server localhost has received a request for some application/edn."
                   (:body response))))

    (it "should correctly handle route params specified in context vectors"
        (let [response (http/get "http://localhost:9988/static2/123/asdf/foo")]
          (should= "Here's the foo param for this request: 123"
                   (:body response))))

    (it "should correctly handle catch-all routes (:all)"
        (let [response1 (http/get "http://localhost:9988/catch-all")
              response2 (http/put "http://localhost:9988/catch-all")]
          (should= "Caught you!" (:body response1))
          (should= "Caught you!" (:body response2))))

    (it "should support injections and default argument resolvers"
        (let [response (http/get "http://localhost:9988/surprise")]
          (should= "This is really surprising!" (:body response))))

    (it "should support nested ns-specs in namespace-handler calls with context route params"
        (let [response (http/get "http://localhost:9988/surprise/123/foo")]
          (should= "Surprise at id 123!" (:body response))))

    (it "should support routes using different route param names in the same position"
      (let [foo-response (http/get "http://localhost:9988/foobar/123/foo")
            bar-response (http/get "http://localhost:9988/foobar/456/bar")]
        (should= "foo-id is 123" (:body foo-response))
        (should= "bar-id is 456" (:body bar-response))))

    (after-all
      (.stop @server))))
