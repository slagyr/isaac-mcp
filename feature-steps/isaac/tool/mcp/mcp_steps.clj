(ns isaac.tool.mcp.mcp-steps
  "The Isaac system step commits feature config and reconciles the MCP
   component through the same boot hook used by the production host.
   Turns still reach already-connected tools through the provider berth."
  (:require
    [clojure.string :as str]
    [gherclj.core :as g :refer [defwhen after-all after-scenario helper!]]
    [isaac.comm.acp.cli :as acp-cli]
    [isaac.foundation.cli.registry :as cli-registry]
    [isaac.foundation.cli-steps :as cli-steps]
    [isaac.foundation.config.configurator :as configurator]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.config.runtime :as config-runtime]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]))

(helper! isaac.tool.mcp.mcp-steps)

;; hosts.feature runs `isaac acp` through main/run. The ACP module is not
;; :builtin?, so with no on-disk isaac.edn declaring it the command is not
;; discovered; register it at step-ns load time exactly as isaac-acp's own
;; acp_steps does, with the same state-dir/home preflight.
(cli-registry/register! (acp-cli/make-command))

(defn- acp-isaac-run-preflight! []
  (let [root      (g/get :root)
        root-home (when (and root (str/ends-with? root "/.isaac"))
                    (fs/parent root))]
    (g/update! :main-extra-opts
               (fn [opts]
                 (cond-> (or opts {})
                   root      (assoc :state-dir root)
                   root-home (assoc :home root-home))))))

(cli-steps/register-isaac-run-preflight! acp-isaac-run-preflight!)

(defn isaac-system-started []
  (let [fs*  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs))
        root (or (g/get :runtime-root-dir) (g/get :root))]
    (let [config      (loader/load-config! root fs* "mcp feature start")
          registries  (configurator/declared-registries (:module-index config))]
      (config-runtime/install! {:config config :registries registries
                                :host {:module-index (:module-index config)}}))))

(defn isaac-system-stopped []
  (try
    (when-let [runtime (nexus/get-in [:mcp])]
      ((requiring-resolve 'isaac.foundation.reconfigurable/on-unload) runtime nil)
      (nexus/deregister! [:mcp]))
    (catch Exception _)))

(after-scenario isaac-system-stopped)

(after-all shutdown-agents)

(defwhen "the Isaac system is started" isaac.tool.mcp.mcp-steps/isaac-system-started)

(defn mcp-servers-have-connected
  "A turn never waits on an MCP server (isaac-aswr). Wait for the boot
   background connects to land before a scenario that needs tools. The
   already-settled servers are checked in id order for stable log order."
  []
  (let [ensure! (requiring-resolve 'isaac.tool.mcp.runtime/ensure-server!)
        await!  (requiring-resolve 'isaac.tool.mcp.runtime/await-connects!)
        servers (:mcp (or (loader/snapshot "mcp feature connect") {}))]
    (doseq [id (sort (map name (keys servers)))]
      (ensure! id)
      (await!))))

(defwhen "the MCP servers have connected" isaac.tool.mcp.mcp-steps/mcp-servers-have-connected)


(defn background-connects-landed
  "Wait for in-flight background connects only. Unlike 'the MCP servers have
   connected', this reaches no server itself, so it shows what boot alone
   connected."
  []
  ((requiring-resolve 'isaac.tool.mcp.runtime/await-connects!)))

(defwhen "the background MCP connects have landed" isaac.tool.mcp.mcp-steps/background-connects-landed)
