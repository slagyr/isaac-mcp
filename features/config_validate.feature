Feature: Config Validate (MCP servers)
  `isaac config validate` checks MCP entity files under config/mcp/
  against the :mcp schema. :command is required. Crew allow/deny is
  not an MCP concern.

  Background:
    Given an Isaac root at "isaac-state"

  Scenario: missing command is refused
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :local}
       :crew      {:main {}}
       :models    {:local {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    And config file "mcp/lens.edn" containing:
      """
      {:args ["--help"]}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern            |
      | mcp\.lens\.command |
      | required           |
    And the exit code is 1

  Scenario: a server with :command loads
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :local}
       :crew      {:main {}}
       :models    {:local {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    And config file "mcp/lens.edn" containing:
      """
      {:command "bb" :args ["test-resources/marigold/lens_mcp.bb"]}
      """
    When the config is loaded
    Then the loaded config has:
      | key              | value |
      | mcp.lens.command | bb    |
