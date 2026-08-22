(require '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(defn- write! [message]
  (println (json/generate-string message))
  (flush))

(def tools
  [{:name        "catalog"
    :description "Marigold catalog lookup"
    :inputSchema {:type       "object"
                  :properties {:query {:type "string"}}
                  :required   ["query"]}}
   {:name        "read"
    :description "Read a marigold page"
    :inputSchema {:type       "object"
                  :properties {:query {:type "string"}}
                  :required   ["query"]}}])

(defn- arg [params k]
  (let [arguments (or (:arguments params) (get params "arguments"))]
    (or (get arguments k)
        (get arguments (name k)))))

(defn- handle [message]
  (let [method (:method message)
        id     (:id message)
        params (:params message)]
    (case method
      "initialize"
      (write! {:jsonrpc "2.0"
               :id      id
               :result  {:protocolVersion "2024-11-05"
                         :capabilities    {:tools {}}
                         :serverInfo      {:name "lens" :version "0.1.0"}}})

      ("initialized" "notifications/initialized")
      nil

      "tools/list"
      (write! {:jsonrpc "2.0" :id id :result {:tools tools}})

      "tools/call"
      (let [tool-name (or (:name params) (get params "name"))
            query     (arg params :query)]
        (when (and (= "catalog" tool-name) (= "stare" query))
          (Thread/sleep 10000))
        (write! {:jsonrpc "2.0"
                 :id      id
                 :result  {:content [{:type "text"
                                      :text (str "marigold " tool-name " " query)}]}}))

      (when id
        (write! {:jsonrpc "2.0"
                 :id      id
                 :error   {:code -32601 :message "Method not found"}})))))

(doseq [line (line-seq (io/reader *in*))]
  (when-not (str/blank? line)
    (handle (json/parse-string line true))))
