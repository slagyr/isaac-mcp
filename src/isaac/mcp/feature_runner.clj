(ns isaac.mcp.feature-runner
  "gherclj.main only System/exits on failure, so a green run leaves the
   Clojure agent pool holding the JVM and bb features times out at 60s.
   Always exit so isolated and suite selectors both return."
  (:require
    [gherclj.main :as gherclj]))

(defn -main [& args]
  (let [code (try
               (gherclj/run args)
               (catch Throwable t
                 (.printStackTrace t)
                 1))]
    (System/exit (int (or code 0)))))
