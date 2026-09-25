(ns isaac.mcp.runtime-spec
  (:require
    [isaac.logger :as log]
    [isaac.mcp.client :as client]
    [isaac.mcp.runtime :as sut]
    [isaac.nexus :as nexus]
    [isaac.reconfigurable :as reconfigurable]
    [isaac.spec-helper :as helper]
    [isaac.tool.registry :as registry]
    [speclj.core :refer [after around context describe it should should-be-nil should-contain should-not should-not-contain should=]]))

(def lens-server
  {:command "bb" :args ["test-resources/marigold/lens_mcp.bb"]})

(def lens-list-changed
  {:command "bb" :args ["test-resources/marigold/lens_mcp.bb" "--grow" "--list-changed"]})

(def lens-grow
  {:command "bb" :args ["test-resources/marigold/lens_mcp.bb" "--grow"]})

(def hung-server
  "Spawns and never answers initialize."
  {:command "sleep" :args ["30"] :timeout-ms 1500})

(def dead-server {:command "/no/such/mcp-server"})

(defn- events [event]
  (filter #(= event (:event %)) @log/captured-logs))

(defn- elapsed-ms [f]
  (let [t0 (System/currentTimeMillis)]
    (f)
    (- (System/currentTimeMillis) t0)))

(defn- ensure-settled!
  "First call kicks off the background connect; the second, after it
   lands, returns what registered."
  [ns-str]
  (sut/ensure-server! ns-str)
  (sut/await-connects!)
  (sut/ensure-server! ns-str))

(defmacro ^:private with-clock [clock & body]
  `(with-redefs-fn {#'sut/now-ms (fn [] @~clock)} (fn [] ~@body)))

(defn- counting-connect [spawns]
  (let [real client/connect!]
    (fn [server] (swap! spawns inc) (real server))))

(describe "isaac.mcp.runtime"

  (helper/with-captured-logs)

  (around [it]
    (nexus/-with-nexus {}
      (it)))

  (after (sut/stop!))

  (it "make returns a Reconfigurable"
    (should (satisfies? reconfigurable/Reconfigurable (sut/make [:mcp] {}))))

  (it "start! does not throw on a dead command"
    (sut/start! {:lens dead-server})
    (sut/await-connects!)
    (should-be-nil (registry/lookup "lens__catalog")))

  (it "logs :mcp/connect-failed for a dead command"
    (sut/start! {:lens dead-server})
    (sut/await-connects!)
    (should (some #(= :mcp/connect-failed (:event %)) @log/captured-logs)))

  (context "ensure-server! (tool provider)"

    (it "connects a configured server on first use and returns its wire names"
      (helper/with-config {:mcp {:lens lens-server}}
        (should= ["lens__catalog" "lens__read"] (sort (ensure-settled! "lens")))
        (should (registry/lookup "lens__catalog"))
        (should (some #(= :mcp/connected (:event %)) @log/captured-logs))))

    (it "reuses the live client on later calls"
      (helper/with-config {:mcp {:lens lens-server}}
        (ensure-settled! "lens")
        (should= ["lens__catalog" "lens__read"] (sort (sut/ensure-server! "lens" {})))
        (should= 1 (count (filter #(= :mcp/connected (:event %)) @log/captured-logs)))))

    (it "finds a server keyed by string in the committed table"
      (helper/with-config {:mcp {"lens" lens-server}}
        (should= ["lens__catalog" "lens__read"] (sort (ensure-settled! "lens")))))

    (it "declines an id that is not configured"
      (helper/with-config {:mcp {:lens lens-server}}
        (should-be-nil (ensure-settled! "skybeam"))
        (should-be-nil (registry/lookup "skybeam__catalog"))))

    (it "declines a dead command and holds the retry"
      (helper/with-config {:mcp {:lens dead-server}}
        (should-be-nil (ensure-settled! "lens"))
        (should-be-nil (ensure-settled! "lens"))
        (should= 1 (count (filter #(= :mcp/connect-failed (:event %)) @log/captured-logs)))))

    (it "a registration that outlived its server reconnects on the next call"
      (helper/with-config {:mcp {:lens lens-server}}
        (sut/start! {:lens lens-server})
        (sut/await-connects!)
        (client/stop! (get-in @@#'sut/state* [:clients :lens :client]))
        (should (registry/lookup "lens__catalog"))
        (let [result (registry/execute "lens__catalog" {"query" "marigold"})]
          (should-not (:isError result))
          (should-contain "marigold" (:result result)))
        (should= 2 (count (filter #(= :mcp/connected (:event %)) @log/captured-logs)))))

    (it "a call with no server configured reports not connected"
      (helper/with-config {:mcp {}}
        (sut/start! {:lens lens-server})
        (sut/await-connects!)
        (client/stop! (get-in @@#'sut/state* [:clients :lens :client]))
        (let [result (registry/execute "lens__catalog" {"query" "marigold"})]
          (should (:isError result))
          (should-contain "not connected" (:error result)))))

    (it "stop! clears the retry hold"
      (helper/with-config {:mcp {:lens dead-server}}
        (ensure-settled! "lens")
        (sut/stop!)
        (ensure-settled! "lens")
        (should= 2 (count (filter #(= :mcp/connect-failed (:event %)) @log/captured-logs))))))

  (context "a turn never waits on an MCP server (isaac-aswr)"

    (it "ensure-server! returns at once on a hung server and spawns it once"
      (let [spawns (atom 0)]
        (with-redefs [client/connect! (counting-connect spawns)]
          (helper/with-config {:mcp {:lens hung-server}}
            (should (> 100 (elapsed-ms #(should-be-nil (sut/ensure-server! "lens")))))
            (dotimes [_ 5]
              (should (> 100 (elapsed-ms #(should-be-nil (sut/ensure-server! "lens"))))))
            (should= 1 @spawns)))))

    (it "the next call after a background connect lands returns its tools"
      (helper/with-config {:mcp {:lens lens-server}}
        (should-be-nil (sut/ensure-server! "lens"))
        (sut/await-connects!)
        (should= ["lens__catalog" "lens__read"] (sort (sut/ensure-server! "lens")))
        (should (registry/lookup "lens__catalog"))
        (should-not (:isError (registry/execute "lens__catalog" {"query" "marigold"})))))

    (it "a failure holds the server; a call during the hold spawns nothing"
      (let [spawns (atom 0)]
        (with-redefs [client/connect! (counting-connect spawns)]
          (helper/with-config {:mcp {:lens dead-server}}
            (ensure-settled! "lens")
            (should-be-nil (sut/ensure-server! "lens"))
            (sut/await-connects!)
            (should= 1 @spawns)
            (should= 1 (count (events :mcp/connect-failed)))
            (should= 1 (count (events :mcp/connect-held)))))))

    (it "the hold doubles per consecutive failure up to the cap"
      (let [clock (atom 1000000)]
        (with-clock clock
          (helper/with-config {:mcp {:lens dead-server}}
            (doseq [expected [60000 120000 240000 480000 900000 900000]]
              (ensure-settled! "lens")
              (should= expected (:hold-ms (last (events :mcp/connect-held))))
              (swap! clock + expected -1)
              (ensure-settled! "lens")
              (should= expected (:hold-ms (last (events :mcp/connect-held))))
              (swap! clock inc))))))

    (it "a success resets the hold"
      (let [clock (atom 1000000)]
        (with-clock clock
          (helper/with-config {:mcp {:lens dead-server}}
            (ensure-settled! "lens")
            (swap! clock + 60000)
            (ensure-settled! "lens")
            (should= 120000 (:hold-ms (last (events :mcp/connect-held)))))
          (swap! clock + 120000)
          (helper/with-config {:mcp {:lens lens-server}}
            (should= ["lens__catalog" "lens__read"] (sort (ensure-settled! "lens")))
            (client/stop! (get-in @@#'sut/state* [:clients :lens :client])))
          (helper/with-config {:mcp {:lens dead-server}}
            (ensure-settled! "lens")
            (should= 60000 (:hold-ms (last (events :mcp/connect-held))))))))

    (it "start! returns before a dead server's timeout and a live server registers"
      (should (> 1000 (elapsed-ms #(sut/start! {:hung hung-server :lens lens-server}))))
      (sut/await-connects!)
      (should (registry/lookup "lens__catalog"))
      (should= 1 (count (events :mcp/connected)))
      (should= 1 (count (events :mcp/connect-failed))))

    (it "stop! abandons an in-flight connect and leaves no tools behind"
      (let [gate (promise)
            real client/connect!]
        (with-redefs [client/connect! (fn [server] @gate (real server))]
          (helper/with-config {:mcp {:lens lens-server}}
            (sut/ensure-server! "lens")
            (let [task (get-in @@#'sut/state* [:pending :lens])]
              (sut/stop!)
              (deliver gate true)
              (deref task 10000 nil))
            (should-be-nil (registry/lookup "lens__catalog"))
            (should= [] (events :mcp/connected))
            (should= {} (:clients @@#'sut/state*))))))

    (it "logs connect-failed and connect-held once per failure, connected on success"
      (helper/with-config {:mcp {:lens dead-server :skybeam lens-server}}
        (ensure-settled! "lens")
        (ensure-settled! "lens")
        (ensure-settled! "skybeam")
        (should= [:lens] (map :server (events :mcp/connect-failed)))
        (should= [:lens] (map :server (events :mcp/connect-held)))
        (should= [:skybeam] (map :server (events :mcp/connected))))))

  (context "tools/list_changed (isaac-0szr)"

    (it "re-catalogs a listChanged server after grow, before the next turn"
      (helper/with-config {:mcp {:lens lens-list-changed}}
        (ensure-settled! "lens")
        (should-be-nil (registry/lookup "lens__extra"))
        (should-not (:isError (registry/execute "lens__grow" {})))
        (should (registry/lookup "lens__extra"))
        (should= 1 (count (events :mcp/recatalogued)))
        (should-contain "lens__extra" (sut/ensure-server! "lens"))
        (should= 1 (count (events :mcp/recatalogued)))))

    (it "keeps the catalog of a server that did not declare listChanged"
      (helper/with-config {:mcp {:lens lens-grow}}
        (ensure-settled! "lens")
        (should-not (:isError (registry/execute "lens__grow" {})))
        (should-be-nil (registry/lookup "lens__extra"))
        (sut/ensure-server! "lens")
        (should-be-nil (registry/lookup "lens__extra"))
        (should= [] (events :mcp/recatalogued))))

    (it "a stop! and reconnect starts clean"
      (helper/with-config {:mcp {:lens lens-list-changed}}
        (ensure-settled! "lens")
        (registry/execute "lens__grow" {})
        (sut/stop!)
        (should-be-nil (registry/lookup "lens__extra"))
        (should-not-contain "lens__extra" (ensure-settled! "lens")))))

  (context "live lens"

    (it "registers prefixed tools and logs :mcp/connected"
      (sut/start! {:lens lens-server})
      (sut/await-connects!)
      (should (registry/lookup "lens__catalog"))
      (should (registry/lookup "lens__read"))
      (should-be-nil (registry/lookup "catalog"))
      (should (some #(and (= :mcp/connected (:event %))
                          (= :lens (:server %)))
                    @log/captured-logs)))

    (it "executes catalog through the tool registry"
      (sut/start! {:lens lens-server})
      (sut/await-connects!)
      (let [result (registry/execute "lens__catalog" {"query" "marigold"})]
        (should-not (:isError result))
        (should-contain "marigold" (:result result))))

    (it "strips injected keys and callables before calling the server"
      (sut/start! {:lens lens-server})
      (sut/await-connects!)
      (let [result (registry/execute "lens__catalog" {"query"      "marigold"
                                                       "session_key" "s1"
                                                       "state_dir"   "/tmp"
                                                       :progress!    (fn [_] nil)})]
        (should-not (:isError result))
        (should-contain "marigold" (:result result))))

    (it "keeps two servers with the same MCP tool name distinct"
      (sut/start! {:lens lens-server :skybeam lens-server})
      (sut/await-connects!)
      (let [lens    (registry/execute "lens__catalog" {"query" "marigold"})
            skybeam (registry/execute "skybeam__catalog" {"query" "marigold"})]
        (should-not (:isError lens))
        (should-not (:isError skybeam))
        (should-contain "marigold" (:result lens))
        (should-contain "marigold" (:result skybeam))))

    (it "on-unload stops servers and unregisters tools"
      (let [runtime (sut/make [:mcp] {})]
        (reconfigurable/on-load runtime {:lens lens-server})
        (sut/await-connects!)
        (should (registry/lookup "lens__catalog"))
        (reconfigurable/on-unload runtime {:lens lens-server})
        (should-be-nil (registry/lookup "lens__catalog"))))
    )
  )
