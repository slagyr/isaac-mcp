(ns isaac.mcp-steps
  "One new helper: reuse the established 'the Isaac system is started' phrase
   (cron/hail) to boot MCP reconfigurable from loaded config."
  (:require
    [gherclj.core :as g :refer [defwhen after-scenario helper!]]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]))

(helper! isaac.mcp-steps)

(defn isaac-system-started []
  (let [fs*  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs))
        root (or (g/get :runtime-root-dir) (g/get :root))]
    (loader/load-config! root fs* "mcp feature start")
    ((requiring-resolve 'isaac.mcp.runtime/start!))))

(defn isaac-system-stopped []
  (try
    ((requiring-resolve 'isaac.mcp.runtime/stop!))
    (catch Exception _)))

(after-scenario isaac-system-stopped)

(defwhen "the Isaac system is started" isaac.mcp-steps/isaac-system-started)
