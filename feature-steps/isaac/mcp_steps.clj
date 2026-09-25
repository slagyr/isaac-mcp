(ns isaac.mcp-steps
  "One new helper: reuse the established 'the Isaac system is started' phrase
   (cron/hail) to commit the feature config. Nothing starts the MCP runtime
   by hand (isaac-vadd): the first turn that allows a server's namespace
   reaches it through the :isaac.agent/tool-providers berth, exactly as a
   production host does."
  (:require
    [clojure.string :as str]
    [gherclj.core :as g :refer [defwhen after-all after-scenario helper!]]
    [isaac.cli.registry :as cli-registry]
    [isaac.comm.acp.cli :as acp-cli]
    [isaac.config.loader :as loader]
    [isaac.foundation.cli-steps :as cli-steps]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]))

(helper! isaac.mcp-steps)

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
    (loader/load-config! root fs* "mcp feature start")))

(defn isaac-system-stopped []
  (try
    ((requiring-resolve 'isaac.mcp.runtime/stop!))
    (catch Exception _)))

(after-scenario isaac-system-stopped)

(after-all shutdown-agents)

(defwhen "the Isaac system is started" isaac.mcp-steps/isaac-system-started)

(defn mcp-servers-have-connected
  "A turn never waits on an MCP server (isaac-aswr), so a scenario that
   needs a server's tools on its first turn settles the catalog first:
   reach every configured server through the production tool-provider
   entry (what an earlier turn would have done — not start!), then wait
   for the background connects to land. One server at a time, by id, so
   the :mcp/connected log order is deterministic."
  []
  (let [ensure! (requiring-resolve 'isaac.mcp.runtime/ensure-server!)
        await!  (requiring-resolve 'isaac.mcp.runtime/await-connects!)
        servers (:mcp (or (loader/snapshot "mcp feature connect") {}))]
    (doseq [id (sort (map name (keys servers)))]
      (ensure! id)
      (await!))))

(defwhen "the MCP servers have connected" isaac.mcp-steps/mcp-servers-have-connected)

