(ns isaac.tool.mcp.module-spec
  (:require
    [clojure.edn :as edn]
    [isaac.foundation.module.protocol]
    [isaac.tool.mcp.module :as sut]
    [speclj.core :refer [context describe it should should-be-nil should=]]))

(def manifest
  (edn/read-string (slurp "resources/isaac-manifest.edn")))

(describe "isaac.tool.mcp.module"

  (it "returns a module"
    (should (satisfies? isaac.foundation.module.protocol/Module (sut/create-module))))

  (it "contributes ensure-server! to the :isaac.agent/tool-providers berth"
    (should= 'isaac.tool.mcp.runtime/ensure-server!
             (get-in manifest [:isaac.agent/tool-providers :mcp :ensure!])))

  (context "config schema"

    (it "requires command on each server"
      (should= :string (get-in manifest [:isaac.config/schema :mcp :schema :value-spec :schema :command :type]))
      (should= [:present?] (get-in manifest [:isaac.config/schema :mcp :schema :value-spec :schema :command :validations])))

    (it "accepts optional timeout-ms as int"
      (should= :int (get-in manifest [:isaac.config/schema :mcp :schema :value-spec :schema :timeout-ms :type])))

    (it "does not put a factory on the table or value-spec (dotted error keys, not slot-bracket)"
      (should-be-nil (get-in manifest [:isaac.config/schema :mcp :schema :factory]))
      (should-be-nil (get-in manifest [:isaac.config/schema :mcp :schema :value-spec :factory])))
    )
  )
