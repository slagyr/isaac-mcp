(ns isaac.mcp.runtime-spec
  (:require
    [isaac.logger :as log]
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
