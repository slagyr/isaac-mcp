(require '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(defn- write! [message]
  (println (json/generate-string message))
  (flush))

(def list-changed?
  "With --list-changed the server advertises tools.listChanged (isaac-0szr)."
  (boolean (some #{"--list-changed"} *command-line-args*)))

(def grow?
  "With --grow the catalog carries a `grow` tool that adds `extra` and then
   emits notifications/tools/list_changed — with or without the capability."
  (boolean (some #{"--grow"} *command-line-args*)))

(def query-schema
  {:type       "object"
   :properties {:query {:type "string"}}
   :required   ["query"]})

(def tools
  (atom (cond-> [{:name "catalog" :description "Marigold catalog lookup" :inputSchema query-schema}
                 {:name "read" :description "Read a marigold page" :inputSchema query-schema}]
          grow? (conj {:name "grow" :description "Add the extra tool to this catalog" :inputSchema {:type "object" :properties {}}}))))

(defn- grow! []
  (swap! tools (fn [ts]
                 (if (some #(= "extra" (:name %)) ts)
                   ts
                   (conj ts {:name "extra" :description "Grown marigold tool" :inputSchema query-schema})))))

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
                         :capabilities    {:tools (if list-changed? {:listChanged true} {})}
                         :serverInfo      {:name "lens" :version "0.1.0"}}})

      ("initialized" "notifications/initialized")
      nil

      "tools/list"
      (write! {:jsonrpc "2.0" :id id :result {:tools @tools}})

      "tools/call"
      (let [tool-name (or (:name params) (get params "name"))
            query     (arg params :query)]
        (when (and (= "catalog" tool-name) (= "stare" query))
          (Thread/sleep 10000))
        (write! {:jsonrpc "2.0"
                 :id      id
                 :result  {:content [{:type "text"
                                      :text (str "marigold " tool-name " " query)}]}})
        (when (= "grow" tool-name)
          (grow!)
          (write! {:jsonrpc "2.0" :method "notifications/tools/list_changed"})))

      (when id
        (write! {:jsonrpc "2.0"
                 :id      id
                 :error   {:code -32601 :message "Method not found"}})))))

(doseq [line (line-seq (io/reader *in*))]
  (when-not (str/blank? line)
    (handle (json/parse-string line true))))
