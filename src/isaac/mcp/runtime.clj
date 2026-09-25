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
  "The first hold after a failed connect (isaac-vadd). Each consecutive
   failure doubles it (isaac-aswr) up to MAX-HOLD-MS; a success resets it.
   The long-lived server must recover from a fixed command without a
   restart, but a broken server must not be respawned on every turn."
  60000)

(def MAX-HOLD-MS
  "Cap on the failure hold: a dead server is retried at least this often."
  900000)

(def AWAIT-MS
  "Upper bound await-connects! waits for in-flight background connects."
  60000)

(defn- fresh-state []
  {:clients {} :tools [] :failed {} :pending {} :generation 0})

(defonce ^:private state* (atom (fresh-state)))

(def ^:private finish-lock (Object.))

(defn- now-ms [] (System/currentTimeMillis))

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

(declare ensure-server! recatalog!)

(defn- await-connect!
  "Inside a tool call only: wait up to `timeout` ms for the in-flight
   connect of `server-id` to land."
  [server-id timeout]
  (when-let [task (get-in @state* [:pending server-id])]
    (deref task timeout nil)))

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
                     (await-connect! server-id timeout)
                     (live-client server-id)))]
    (let [result (client/call-tool c mcp-name arguments timeout)]
      ;; A list_changed notification is only ever seen while a reply is
      ;; being read, so this is the earliest the catalog can move; doing it
      ;; here makes the new tools part of the next turn's prompt.
      (when (client/dirty? c)
        (recatalog! server-id))
      result)
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

(defn- open-server
  "Spawn, initialize and list tools — everything that waits on the server.
   Registers nothing. Returns {:client c :tools [mcp-tool…]} or {:error msg}."
  [server]
  (let [result (client/connect! server)]
    (if (:error result)
      result
      (let [listed (client/list-tools result (timeout-ms server))]
        (if (:error listed)
          (do (client/stop! result) listed)
          {:client result :tools (:ok listed)})))))

(defn- recatalog!
  "Ask a dirty server for its catalog again (isaac-0szr): register what is
   new or changed, unregister what vanished, clear the flag. On a failed
   tools/list the old catalog stands and the flag clears so the next
   notification can try again."
  [server-id]
  (let [{:keys [client server tools]} (get-in @state* [:clients server-id])
        listed (client/list-tools client (timeout-ms server))]
    (client/clear-dirty! client)
    (if (:error listed)
      (do (log/error :mcp/recatalog-failed :server server-id :error (:error listed))
          tools)
      (let [new-names (mapv #(register-tool! server-id (assoc server :client client) %)
                            (:ok listed))
            vanished  (remove (set new-names) tools)]
        (unregister-tools! vanished)
        (swap! state* (fn [s]
                        (-> s
                            (assoc-in [:clients server-id :tools] new-names)
                            (update :tools #(into (vec (remove (set tools) %)) new-names)))))
        (log/info :mcp/recatalogued :server server-id :count (count new-names))
        new-names))))

(defn- hold-ms [failures]
  (min MAX-HOLD-MS (* RETRY-HOLD-MS (bit-shift-left 1 (min 20 (dec failures))))))

(defn- held? [id at-ms]
  (when-let [{:keys [until]} (get-in @state* [:failed id])]
    (< at-ms until)))

(defn- record-connected! [id server {:keys [client tools]}]
  (let [names (mapv #(register-tool! id (assoc server :client client) %) tools)]
    (swap! state* (fn [s]
                    (-> s
                        (assoc-in [:clients id] {:id id :server server :client client :tools names})
                        (update :tools into names)
                        (update :failed dissoc id))))
    (log/info :mcp/connected :server id)
    names))

(defn- record-failed! [id error]
  (log/error :mcp/connect-failed :server id :error error)
  (let [failures (inc (get-in @state* [:failed id :failures] 0))
        hold     (hold-ms failures)]
    (swap! state* assoc-in [:failed id] {:failures failures :until (+ (now-ms) hold)})
    (log/warn :mcp/connect-held :server id :failures failures :hold-ms hold)))

(defn- finish-connect!
  "Land a background connect. A connect that outlived its generation (stop!
   or a reload ran meanwhile) is abandoned: its client is stopped and
   nothing registers."
  [id server generation result]
  (locking finish-lock
    (if (= generation (:generation @state*))
      (do (swap! state* update :pending dissoc id)
          (if (:error result)
            (record-failed! id (:error result))
            (record-connected! id server result)))
      (when-let [c (:client result)]
        (try (client/stop! c) (catch Exception _ nil))))))

(defn- connect-async!
  "Start connecting `id` on a background thread unless a connect is already
   in flight. Never waits on the server."
  [id server]
  (locking finish-lock
    (when-not (get-in @state* [:pending id])
      (let [generation (:generation @state*)
            task       (future
                         (finish-connect! id server generation
                                          (try (open-server server)
                                               (catch Throwable e
                                                 {:error (or (.getMessage e) (str (class e)))}))))]
        (swap! state* assoc-in [:pending id] task))))
  nil)

(defn await-connects!
  "Block until every in-flight background connect has landed (bounded by
   AWAIT-MS). For hosts and tests that need a settled catalog; the turn
   path never calls it."
  []
  (doseq [task (vals (:pending @state*))]
    (deref task AWAIT-MS nil)))

(defn stop!
  "Stop every client and unregister every tool. An in-flight connect is
   abandoned, not waited on: when it lands it finds a newer generation,
   stops its own client and registers nothing."
  []
  (locking finish-lock
    (let [{:keys [clients tools generation]} @state*]
      (unregister-tools! tools)
      (doseq [entry (vals clients)]
        (try (client/stop! (:client entry)) (catch Exception _ nil)))
      (reset! state* (assoc (fresh-state) :generation (inc generation)))))
  nil)

(defn- server-config
  "The configured server under `ns-str`; the committed table may key slots
   by keyword or string."
  [servers ns-str]
  (or (get servers (keyword ns-str)) (get servers ns-str)))

(defn ensure-server!
  "Tool-provider entry for the :isaac.agent/tool-providers berth. Never
   waits on the server (isaac-aswr): returns the wire names already
   registered for the server configured under `ns-str`, or nil. With no
   live client, no connect in flight and no failure hold, it starts a
   background connect; the tools join the registry when it lands and are
   on the next turn's prompt. Declines (nil) an unconfigured id."
  ([ns-str] (ensure-server! ns-str nil))
  ([ns-str _module-index]
   (let [id (keyword ns-str)]
     (if-let [c (live-client id)]
       (if (client/dirty? c)
         (recatalog! id)
         (get-in @state* [:clients id :tools]))
       (let [servers (:mcp (or (loader/snapshot "mcp ensure-server") {}))
             server  (server-config servers ns-str)]
         (when (and server (not (held? id (now-ms))))
           (connect-async! id server))
         nil)))))

(defn start!
  "Boot MCP servers from an explicit servers map, or from the committed
   config snapshot's :mcp table. Kicks off one background connect per
   server and returns without waiting; never throws on a dead command."
  ([]
   (start! (:mcp (or (loader/snapshot "mcp start") {}))))
  ([servers]
   (stop!)
   (doseq [[id server] (or servers {})]
     (connect-async! (keyword id) (or server {})))
   nil))

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
