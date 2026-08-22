(ns isaac.mcp.module-spec
  (:require
    [isaac.module.protocol]
    [isaac.mcp.module :as sut]
    [speclj.core :refer [describe it should]]))

(describe "isaac.mcp.module"

  (describe "create-module"

    (it "returns a module"
      (should (satisfies? isaac.module.protocol/Module (sut/create-module))))))
