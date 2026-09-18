(ns isaac.mcp.client
  "Stdio JSON-RPC NDJSON client for one MCP server process.
   Reads are polled on the caller thread — no future, no non-daemon reader."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.util.jsonrpc :as jrpc])
  (:import
    (java.io BufferedReader InputStreamReader OutputStreamWriter Reader Writer)
    (java.lang Process ProcessBuilder ProcessHandle StringBuilder)
    (java.nio.charset StandardCharsets)
    (java.util.concurrent TimeUnit)))

(def PROTOCOL-VERSION "2024-11-05")
(def DEFAULT-TIMEOUT-MS 30000)
(def DRAIN-GRACE-MS
  "After a reply, keep reading this long for lines the server sent right
   behind it — a tools/list_changed that follows a mutating call."
  10)

(def LIST-CHANGED "notifications/tools/list_changed")

(defn registry-name [server-id tool-name]
  (str (name server-id) "__" tool-name))

(defn list-changed?
  "True when the server declared tools.listChanged at initialize."
  [client]
  (let [tools (or (get-in client [:capabilities :tools])
                  (get-in client [:capabilities "tools"]))]
    (true? (or (:listChanged tools) (get tools "listChanged")))))

(defn dirty?
  "True once a tools/list_changed notification was seen from a server that
   declared listChanged. Cleared by clear-dirty! after a re-catalog."
  [client]
  (boolean (some-> (:dirty* client) deref)))

(defn clear-dirty! [client]
  (some-> (:dirty* client) (reset! false)))

(defn- note-notification! [client msg]
  (when (and (= LIST-CHANGED (:method msg)) (list-changed? client))
    (some-> (:dirty* client) (reset! true))))

(defn- drain-stderr! [^java.io.InputStream err]
  (when err
    (doto (Thread. ^Runnable
            (fn []
              (try
                (let [reader (BufferedReader. (InputStreamReader. err StandardCharsets/UTF_8))]
                  (loop []
                    (when (.readLine reader)
                      (recur))))
                (catch Exception _ nil)))
            "mcp-stderr")
      (.setDaemon true)
      (.start))))

(defn- spawn [{:keys [command args env cwd]}]
  (let [argv (into [(str command)] (map str (or args [])))
        pb   (ProcessBuilder. ^java.util.List argv)]
    (.redirectErrorStream pb false)
    (when (seq cwd)
      (.directory pb (io/file cwd)))
    (when (seq env)
      (let [pb-env (.environment pb)]
        (doseq [[k v] env]
          (.put pb-env (str k) (str v)))))
    (let [proc (.start pb)]
      (drain-stderr! (.getErrorStream proc))
      proc)))

(defn- poll-line [^Reader reader ^StringBuilder buf deadline-ms]
  (loop []
    (cond
      (> (System/currentTimeMillis) deadline-ms)
      nil

      (.ready reader)
      (let [c (.read reader)]
        (cond
          (neg? c) nil
          (= (int \newline) c)
          (let [line (str buf)]
            (.setLength buf 0)
            line)
          (= (int \return) c)
          (recur)
          :else
          (do (.append buf (char c))
              (recur))))

      :else
      (do (Thread/sleep 1)
          (recur)))))

(defn- request-id [{:keys [next-id]}]
  (swap! next-id inc))

(defn- drain-trailing! [{:keys [reader buf] :as client}]
  (loop []
    (when-let [line (poll-line reader buf (+ (System/currentTimeMillis) DRAIN-GRACE-MS))]
      (let [msg (jrpc/parse-message line)]
        (when-not (jrpc/parse-error? msg)
          (note-notification! client msg)))
      (recur))))

(defn request!
  ([client method]
   (request! client method nil DEFAULT-TIMEOUT-MS))
  ([client method params]
   (request! client method params DEFAULT-TIMEOUT-MS))
  ([{:keys [writer reader buf] :as client} method params timeout-ms]
   (let [id       (request-id client)
         deadline (+ (System/currentTimeMillis) (or timeout-ms DEFAULT-TIMEOUT-MS))
         _        (jrpc/write-message! writer (jrpc/request id method params))
         response (loop []
                    (if-let [line (poll-line reader buf deadline)]
                      (let [msg (jrpc/parse-message line)]
                        (cond
                          (jrpc/parse-error? msg) (recur)
                          (and (jrpc/result? msg) (= id (:id msg))) {:ok (:result msg)}
                          (and (jrpc/error? msg) (= id (:id msg)))
                          {:error (or (get-in msg [:error :message]) "JSON-RPC error")}
                          :else (do (note-notification! client msg)
                                    (recur))))
                      {:error "timeout"}))]
     (when (list-changed? client)
       (drain-trailing! client))
     response)))

(defn notify! [{:keys [writer]} method params]
  (jrpc/write-message! writer (jrpc/notification method params)))

(defn- destroy-tree! [^Process proc]
  (when proc
    (try
      (let [handle (.toHandle proc)]
        (doseq [^ProcessHandle child (.toList (.descendants handle))]
          (.destroy child))
        (.destroy handle)
        (when-not (.waitFor proc 200 TimeUnit/MILLISECONDS)
          (doseq [^ProcessHandle child (.toList (.descendants handle))]
            (.destroyForcibly child))
          (.destroyForcibly proc)))
      (catch Exception _
        (try (.destroyForcibly proc) (catch Exception _ nil))))))

(defn stop! [{:keys [proc writer reader]}]
  (when writer
    (try (.close ^Writer writer) (catch Exception _ nil)))
  (destroy-tree! proc)
  (when reader
    (try (.close ^Reader reader) (catch Exception _ nil))))

(defn alive? [{:keys [proc]}]
  (boolean (and proc (.isAlive ^Process proc))))

(defn connect!
  "Spawn the server, complete initialize, and send notifications/initialized.
   Returns a client map, or {:error message} when spawn/handshake fails."
  [opts]
  (try
    (let [timeout-ms (or (:timeout-ms opts) DEFAULT-TIMEOUT-MS)
          proc       (spawn opts)
          writer     (OutputStreamWriter. (.getOutputStream proc) StandardCharsets/UTF_8)
          reader     (BufferedReader. (InputStreamReader. (.getInputStream proc) StandardCharsets/UTF_8))
          client     {:proc    proc
                      :writer  writer
                      :reader  reader
                      :buf     (StringBuilder.)
                      :next-id (atom 0)
                      :dirty*  (atom false)}
          init       (request! client
                               "initialize"
                               {:protocolVersion PROTOCOL-VERSION
                                :capabilities    {}
                                :clientInfo      {:name "isaac" :version "0.1.0"}}
                               timeout-ms)]
      (cond
        (:error init)
        (do (stop! client)
            {:error (str "MCP initialize failed: " (:error init))})

        :else
        (do (notify! client "notifications/initialized" {})
            (assoc client :capabilities (or (get-in init [:ok :capabilities])
                                            (get-in init [:ok "capabilities"])
                                            {})))))
    (catch Exception e
      {:error (or (.getMessage e) (str (class e)))})))

(defn list-tools [client timeout-ms]
  (let [response (request! client "tools/list" {} timeout-ms)]
    (if (:error response)
      response
      {:ok (vec (or (get-in response [:ok :tools]) []))})))

(defn- text-content [result]
  (->> (:content result)
       (map #(or (:text %) (get % "text")))
       (remove str/blank?)
       (str/join "\n")))

(defn call-tool [client mcp-name arguments timeout-ms]
  (let [response (request! client
                           "tools/call"
                           {:name      mcp-name
                            :arguments arguments}
                           timeout-ms)]
    (cond
      (:error response)
      {:isError true :error (:error response)}

      (or (true? (get-in response [:ok :isError]))
          (true? (get-in response [:ok "isError"])))
      {:isError true :error (or (text-content (:ok response)) "MCP tool error")}

      :else
      {:result (text-content (:ok response))})))
