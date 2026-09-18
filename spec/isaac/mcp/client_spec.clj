(ns isaac.mcp.client-spec
  (:require
    [isaac.mcp.client :as sut]
    [speclj.core :refer [after context describe it should should-be-nil should-contain should-not should=]]))

(def lens-args ["test-resources/marigold/lens_mcp.bb"])

(def ^:private live* (atom nil))

(defn- connect-lens
  ([] (connect-lens {}))
  ([opts]
   (let [client (sut/connect! (merge {:command "bb" :args lens-args} opts))]
     (reset! live* client)
     client)))

(describe "isaac.mcp.client"

  (after (do (when-let [client @live*]
               (when-not (:error client)
                 (sut/stop! client)))
             (reset! live* nil)))

  (it "prefixes the MCP tool name with the config server id"
    (should= "lens__catalog" (sut/registry-name :lens "catalog"))
    (should= "skybeam__catalog" (sut/registry-name "skybeam" "catalog")))

  (context "lens fixture"

    (it "completes initialize and lists exactly catalog and read"
      (let [client (connect-lens)
            listed (sut/list-tools client 3000)]
        (should-be-nil (:error client))
        (should-be-nil (:error listed))
        (should= ["catalog" "read"] (mapv :name (:ok listed)))))

    (it "returns marigold text from catalog"
      (let [client (connect-lens)
            result (sut/call-tool client "catalog" {"query" "marigold"} 3000)]
        (should-not (:isError result))
        (should-contain "marigold" (:result result))))

    (it "returns a timeout error when catalog query is stare"
      (let [client (connect-lens {:timeout-ms 50})
            result (sut/call-tool client "catalog" {"query" "stare"} 50)]
        (should (:isError result))
        (should-contain "timeout" (:error result))))

    (it "keeps the server's capabilities and is clean after connect"
      (let [client (sut/connect! {:command "bb" :args ["test-resources/marigold/lens_mcp.bb" "--grow" "--list-changed"]})]
        (try
          (should (sut/list-changed? client))
          (should-not (sut/dirty? client))
          (finally (sut/stop! client)))))

    (it "marks a listChanged server dirty when list_changed follows a reply"
      (let [client (sut/connect! {:command "bb" :args ["test-resources/marigold/lens_mcp.bb" "--grow" "--list-changed"]})]
        (try
          (sut/call-tool client "grow" {} 5000)
          (should (sut/dirty? client))
          (sut/clear-dirty! client)
          (should-not (sut/dirty? client))
          (finally (sut/stop! client)))))

    (it "never marks a server dirty that did not declare listChanged"
      (let [client (sut/connect! {:command "bb" :args ["test-resources/marigold/lens_mcp.bb" "--grow"]})]
        (try
          (should-not (sut/list-changed? client))
          (sut/call-tool client "grow" {} 5000)
          (sut/call-tool client "catalog" {"query" "marigold"} 5000)
          (should-not (sut/dirty? client))
          (finally (sut/stop! client)))))

    (it "stop! destroys the process"
      (let [client (connect-lens)]
        (should (sut/alive? client))
        (sut/stop! client)
        (reset! live* nil)
        (should-not (sut/alive? client))))
    )
  )
