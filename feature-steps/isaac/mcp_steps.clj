(ns isaac.mcp-steps
  "One new helper: reuse the established 'the Isaac system is started' phrase
   (cron/hail) to boot MCP reconfigurable from loaded config."
  (:require
    [gherclj.core :as g :refer [defwhen after-scenario helper!]]))

(helper! isaac.mcp-steps)

(defn isaac-system-started []
  ((requiring-resolve 'isaac.mcp.runtime/start!)))

(defn isaac-system-stopped []
  (try
    ((requiring-resolve 'isaac.mcp.runtime/stop!))
    (catch Exception _)))

(after-scenario isaac-system-stopped)

(defwhen "the Isaac system is started" isaac.mcp-steps/isaac-system-started)
