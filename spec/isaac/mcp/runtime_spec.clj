(ns isaac.mcp.runtime-spec
  (:require
    [isaac.logger :as log]
    [isaac.mcp.client :as client]
    [isaac.mcp.runtime :as sut]
    [isaac.nexus :as nexus]
    [isaac.reconfigurable :as reconfigurable]
    [isaac.spec-helper :as helper]
    [isaac.tool.registry :as registry]
    [speclj.core :refer [after around context describe it should should-be-nil should-contain should-not should=]]))

(def lens-server
  {:command "bb" :args ["test-resources/marigold/lens_mcp.bb"]})

(describe "isaac.mcp.runtime"

  (helper/with-captured-logs)

  (around [it]
    (nexus/-with-nexus {}
      (it)))

  (after (sut/stop!))

  (it "make returns a Reconfigurable"
    (should (satisfies? reconfigurable/Reconfigurable (sut/make [:mcp] {}))))

  (it "start! does not throw on a dead command"
    (sut/start! {:lens {:command "/no/such/mcp-server"}})
    (should-be-nil (registry/lookup "lens__catalog")))

  (it "logs :mcp/connect-failed for a dead command"
    (sut/start! {:lens {:command "/no/such/mcp-server"}})
    (should (some #(= :mcp/connect-failed (:event %)) @log/captured-logs)))

  (context "ensure-server! (tool provider)"

    (it "connects a configured server on first use and returns its wire names"
      (helper/with-config {:mcp {:lens lens-server}}
        (should= ["lens__catalog" "lens__read"] (sort (sut/ensure-server! "lens")))
        (should (registry/lookup "lens__catalog"))
        (should (some #(= :mcp/connected (:event %)) @log/captured-logs))))

    (it "reuses the live client on later calls"
      (helper/with-config {:mcp {:lens lens-server}}
        (sut/ensure-server! "lens")
        (should= ["lens__catalog" "lens__read"] (sort (sut/ensure-server! "lens" {})))
        (should= 1 (count (filter #(= :mcp/connected (:event %)) @log/captured-logs)))))

    (it "finds a server keyed by string in the committed table"
      (helper/with-config {:mcp {"lens" lens-server}}
        (should= ["lens__catalog" "lens__read"] (sort (sut/ensure-server! "lens")))))

    (it "declines an id that is not configured"
      (helper/with-config {:mcp {:lens lens-server}}
        (should-be-nil (sut/ensure-server! "skybeam"))
        (should-be-nil (registry/lookup "skybeam__catalog"))))

    (it "declines a dead command and holds the retry"
      (helper/with-config {:mcp {:lens {:command "/no/such/mcp-server"}}}
        (should-be-nil (sut/ensure-server! "lens"))
        (should-be-nil (sut/ensure-server! "lens"))
        (should= 1 (count (filter #(= :mcp/connect-failed (:event %)) @log/captured-logs)))))

    (it "a registration that outlived its server reconnects on the next call"
      (helper/with-config {:mcp {:lens lens-server}}
        (sut/start! {:lens lens-server})
        (client/stop! (get-in @@#'sut/state* [:clients :lens :client]))
        (should (registry/lookup "lens__catalog"))
        (let [result (registry/execute "lens__catalog" {"query" "marigold"})]
          (should-not (:isError result))
          (should-contain "marigold" (:result result)))
        (should= 2 (count (filter #(= :mcp/connected (:event %)) @log/captured-logs)))))

    (it "a call with no server configured reports not connected"
      (helper/with-config {:mcp {}}
        (sut/start! {:lens lens-server})
        (client/stop! (get-in @@#'sut/state* [:clients :lens :client]))
        (let [result (registry/execute "lens__catalog" {"query" "marigold"})]
          (should (:isError result))
          (should-contain "not connected" (:error result)))))

    (it "stop! clears the retry hold"
      (helper/with-config {:mcp {:lens {:command "/no/such/mcp-server"}}}
        (sut/ensure-server! "lens")
        (sut/stop!)
        (sut/ensure-server! "lens")
        (should= 2 (count (filter #(= :mcp/connect-failed (:event %)) @log/captured-logs))))))

  (context "live lens"

    (it "registers prefixed tools and logs :mcp/connected"
      (sut/start! {:lens lens-server})
      (should (registry/lookup "lens__catalog"))
      (should (registry/lookup "lens__read"))
      (should-be-nil (registry/lookup "catalog"))
      (should (some #(and (= :mcp/connected (:event %))
                          (= :lens (:server %)))
                    @log/captured-logs)))

    (it "executes catalog through the tool registry"
      (sut/start! {:lens lens-server})
      (let [result (registry/execute "lens__catalog" {"query" "marigold"})]
        (should-not (:isError result))
        (should-contain "marigold" (:result result))))

    (it "strips injected keys and callables before calling the server"
      (sut/start! {:lens lens-server})
      (let [result (registry/execute "lens__catalog" {"query"      "marigold"
                                                       "session_key" "s1"
                                                       "state_dir"   "/tmp"
                                                       :progress!    (fn [_] nil)})]
        (should-not (:isError result))
        (should-contain "marigold" (:result result))))

    (it "keeps two servers with the same MCP tool name distinct"
      (sut/start! {:lens lens-server :skybeam lens-server})
      (let [lens    (registry/execute "lens__catalog" {"query" "marigold"})
            skybeam (registry/execute "skybeam__catalog" {"query" "marigold"})]
        (should-not (:isError lens))
        (should-not (:isError skybeam))
        (should-contain "marigold" (:result lens))
        (should-contain "marigold" (:result skybeam))))

    (it "on-unload stops servers and unregisters tools"
      (let [runtime (sut/make [:mcp] {})]
        (reconfigurable/on-load runtime {:lens lens-server})
        (should (registry/lookup "lens__catalog"))
        (reconfigurable/on-unload runtime {:lens lens-server})
        (should-be-nil (registry/lookup "lens__catalog"))))
    )
  )
