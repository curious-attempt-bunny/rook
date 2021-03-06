(ns barney
  {:arg-resolvers {:partner (fn [_] (constantly :betty))}}
  (:require [io.aviso.rook.utils :as utils]
            [io.aviso.rook.client :as client]
            [clojure.core.async :as async]))

(defn index
  {:sync true}
  []
  (utils/response {:message "ribs!"}))

(defn show
  [id ^:injection loopback-handler ^:partner partner]
  (async/go
    (->
      (client/new-request loopback-handler)
      (client/to :get partner id)
      client/send
      (client/then
        :pass-failure
        :success [response (utils/response {:message (format "%s says `%s'" partner (-> response :body :message))})]))))
