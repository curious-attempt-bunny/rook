(ns rook-stress-test.core
  (:require [io.aviso.rook.dispatcher :as dispatcher]
            [ring.adapter.jetty :as jetty]))

(defn make-dispatch-table [& syms]
  (reduce into []
    (map #(dispatcher/namespace-dispatch-table
            [[(str %)]
             (symbol (str "rook-stress-test.resources." %))
             identity])
      syms)))

(def syms (mapv #(str "example" %) (range 64)))

(doseq [sym syms]
  (let [sym (symbol (str "rook-stress-test.resources." sym))
        ns  (do (remove-ns sym) (create-ns sym))]
    (binding [*ns* ns]
      (eval
        `(do
           (clojure.core/refer-clojure)
           ~'(require '[ring.util.response :as resp])
           (defn ~'index []
             (~'resp/response (str "(" '~sym ") Hello!")))
           (defn ~'show [~'id]
             (~'resp/response (str "(" '~sym ") Interesting id: " ~'id)))
           (defn ~'create [~'x]
             (~'resp/response (str "(" '~sym ") Attempted to create " ~'x))))))))

(def dispatch-table
  (apply make-dispatch-table 'foo 'bar 'baz 'quux syms))

(def rook-handler
  (dispatcher/compile-dispatch-table dispatch-table))

(defonce js (atom #{}))

(defn start []
  (locking js
    (swap! js conj
      (jetty/run-jetty rook-handler
        {:port 6001 :host "localhost" :join? false}))))

(defn stop []
  (locking js
    (doseq [j @js]
      (.stop j))
    (reset! js #{})))

(defn restart []
  (stop)
  (start))
