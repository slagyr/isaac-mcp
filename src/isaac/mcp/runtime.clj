(ns isaac.mcp.runtime
  "Start/stop configured MCP servers and register prefixed tools.
   Reconfigurable so a later hot-reload bean is not a rewrite."
  (:require
    [isaac.config.loader :as loader]
    [isaac.logger :as log]
    [isaac.mcp.client :as client]
    [isaac.reconfigurable :as reconfigurable]
    [isaac.tool.registry :as registry]))

(defonce ^:private state* (atom {:clients {} :tools []}))

(defn- timeout-ms [server]
  (or (:timeout-ms server) client/DEFAULT-TIMEOUT-MS))

(defn- mcp-arguments [args]
  (reduce-kv
    (fn [m k v]
      (let [sk (if (keyword? k) (name k) (str k))]
        (if (#{"session_key" "state_dir"} sk)
          m
          (assoc m sk v))))
    {}
    (or args {})))

(defn- unregister-tools! [names]
  (doseq [name names]
    (registry/unregister! name)))

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
                      (client/call-tool (:client client-state)
                                        mcp-name
                                        (mcp-arguments args)
                                        timeout))})
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
    (reset! state* {:clients {} :tools []})))

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
                     :tools   tools})
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
