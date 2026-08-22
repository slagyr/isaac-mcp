(ns isaac.mcp.module-spec
  (:require
    [clojure.edn :as edn]
    [isaac.module.protocol]
    [isaac.mcp.module :as sut]
    [speclj.core :refer [context describe it should should-be-nil should=]]))

(def manifest
  (edn/read-string (slurp "resources/isaac-manifest.edn")))

(describe "isaac.mcp.module"

  (it "returns a module"
    (should (satisfies? isaac.module.protocol/Module (sut/create-module))))

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
