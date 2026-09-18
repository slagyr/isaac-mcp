(ns isaac.mcp.runtime
  "Start/stop configured MCP servers and register prefixed tools.
   Reconfigurable so a later hot-reload bean is not a rewrite."
  (:require
    [isaac.config.loader :as loader]
    [isaac.logger :as log]
    [isaac.mcp.client :as client]
    [isaac.reconfigurable :as reconfigurable]
    [isaac.tool.registry :as registry]))

(def RETRY-HOLD-MS
  "A server whose command failed is not retried before this many ms have
   passed (isaac-vadd): the long-lived server must recover from a fixed
   command without a restart, but a turn must not pay a spawn timeout on
   every prompt while it stays broken."
  60000)

(defonce ^:private state* (atom {:clients {} :tools [] :failed {}}))

(defn- timeout-ms [server]
  (or (:timeout-ms server) client/DEFAULT-TIMEOUT-MS))

(defn- mcp-arguments
  "The model's arguments only: drops the keys the turn injects for
   Isaac's own tools (session_key, state_dir) and any callable the drive
   attaches (progress!) — none of it is JSON and none of it is the
   server's business."
  [args]
  (reduce-kv
    (fn [m k v]
      (let [sk (if (keyword? k) (name k) (str k))]
        (if (or (#{"session_key" "state_dir"} sk) (fn? v))
          m
          (assoc m sk v))))
    {}
    (or args {})))

(defn- unregister-tools! [names]
  (doseq [name names]
    (registry/unregister! name)))

(declare ensure-server!)

(defn- live-client [server-id]
  (let [c (get-in @state* [:clients server-id :client])]
    (when (and c (client/alive? c))
      c)))

(defn- call-server!
  "Route a registered tool to the server's *current* client. A registration
   can outlive the process it was made against (a stopped or crashed
   server, a harness that reset the runtime); rather than closing over a
   dead client, look it up by id and reconnect on demand."
  [server-id mcp-name arguments timeout]
  (if-let [c (or (live-client server-id)
                 (do (ensure-server! (name server-id))
                     (live-client server-id)))]
    (client/call-tool c mcp-name arguments timeout)
    {:isError true :error (str "MCP server " (name server-id) " is not connected")}))

(defn- register-tool! [server-id client-state mcp-tool]
  (let [mcp-name (or (:name mcp-tool) (get mcp-tool "name"))
        reg-name (client/registry-name server-id mcp-name)
        params   (or (:inputSchema mcp-tool)
                     (get mcp-tool "inputSchema")
                     {:type "object"})
        timeout  (timeout-ms client-state)]
    (registry/register!
      {:name        reg-name
       :description (or (:description mcp-tool) (get mcp-tool "description") "")
       :parameters  params
       :handler     (fn [args]
                      (call-server! server-id mcp-name (mcp-arguments args) timeout))})
    reg-name))

(defn- connect-server! [server-id server]
  (let [result (client/connect! server)]
    (if (:error result)
      (do (log/error :mcp/connect-failed :server server-id :error (:error result))
          nil)
      (let [listed (client/list-tools result (timeout-ms server))]
        (if (:error listed)
          (do (client/stop! result)
              (log/error :mcp/connect-failed :server server-id :error (:error listed))
              nil)
          (let [tool-names (mapv #(register-tool! server-id
                                                  (assoc server :client result)
                                                  %)
                                 (:ok listed))]
            (log/info :mcp/connected :server server-id)
            {:id     server-id
             :client result
             :tools  tool-names}))))))

(defn stop!
  []
  (let [{:keys [clients tools]} @state*]
    (unregister-tools! tools)
    (doseq [entry (vals clients)]
      (try (client/stop! (:client entry)) (catch Exception _ nil)))
    (reset! state* {:clients {} :tools [] :failed {}})))

(defn- server-config
  "The configured server under `ns-str`; the committed table may key slots
   by keyword or string."
  [servers ns-str]
  (or (get servers (keyword ns-str)) (get servers ns-str)))

(defn- held? [id now-ms]
  (when-let [failed-at (get-in @state* [:failed id])]
    (< (- now-ms failed-at) RETRY-HOLD-MS)))

(defn- remember-started! [id started]
  (swap! state* (fn [s]
                  (-> s
                      (assoc-in [:clients id] started)
                      (update :tools into (:tools started))
                      (update :failed dissoc id)))))

(defn ensure-server!
  "Tool-provider entry for the :isaac.agent/tool-providers berth. Makes the
   MCP server configured under `ns-str` available to the tool registry:
   connects on first use, reuses the live client after that, and declines
   (nil) for an unconfigured id, a dead command, or a command that failed
   within RETRY-HOLD-MS. Returns the registered wire names."
  ([ns-str] (ensure-server! ns-str nil))
  ([ns-str _module-index]
   (let [id (keyword ns-str)]
     (or (when (live-client id)
           (get-in @state* [:clients id :tools]))
         (let [servers (:mcp (or (loader/snapshot "mcp ensure-server") {}))
               server  (server-config servers ns-str)
               now-ms  (System/currentTimeMillis)]
           (when (and server (not (held? id now-ms)))
             (if-let [started (connect-server! id server)]
               (do (remember-started! id started)
                   (:tools started))
               (do (swap! state* assoc-in [:failed id] now-ms)
                   nil))))))))

(defn start!
  "Boot MCP servers from an explicit servers map, or from the committed
   config snapshot's :mcp table. Never throws on a dead command."
  ([]
   (start! (:mcp (or (loader/snapshot "mcp start") {}))))
  ([servers]
   (stop!)
   (let [started (keep (fn [[id server]]
                         (connect-server! id (or server {})))
                       (or servers {}))
         tools   (vec (mapcat :tools started))]
     (reset! state* {:clients (into {} (map (juxt :id identity) started))
                     :tools   tools
                     :failed  {}})
     nil)))

(deftype McpRuntime []
  reconfigurable/Reconfigurable
  (on-load [_ slice]
    (start! slice))
  (on-config-change! [_ old-slice new-slice]
    (when (not= old-slice new-slice)
      (start! (or new-slice {}))))
  (on-unload [_ _slice]
    (stop!)))

(defn make
  "Schema-factory for the :mcp table (one node for the whole map, not per server)."
  [_path _slice]
  (McpRuntime.))
