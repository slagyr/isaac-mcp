Feature: MCP stdio lifecycle
  Configured MCP servers start over stdio. Discovered tools register
  as ns__name (server id + MCP tool name) and run through the existing
  tool registry. A dead command does not fail Isaac boot. Crew allow
  in a turn is isaac-6b5z.

  Background:
    Given default Grover setup
    And config:
      | log.output | memory |

  Scenario: a live lens server registers prefixed tools that execute
    Given config:
      | mcp.lens.command | bb |
      | mcp.lens.args    | ["test-resources/marigold/lens_mcp.bb"] |
    When the Isaac system is started
    And tool "lens__catalog" is executed with:
      | query | marigold |
    Then the tool result is not an error
    And the tool result contains "marigold"
    And the log has entries matching:
      | level | event          | server |
      | :info | :mcp/connected | lens   |

  Scenario: MCP name catalog is not registered without the server prefix
    Given config:
      | mcp.lens.command | bb |
      | mcp.lens.args    | ["test-resources/marigold/lens_mcp.bb"] |
    When the Isaac system is started
    And tool "catalog" is executed with:
      | query | marigold |
    Then the tool result is an error

  Scenario: a dead command does not fail boot and leaves no tools
    Given config:
      | mcp.lens.command | /no/such/mcp-server |
    When the Isaac system is started
    Then the log has entries matching:
      | level  | event               |
      | :error | :mcp/connect-failed |
    When tool "lens__catalog" is executed with:
      | query | marigold |
    Then the tool result is an error

  Scenario: two servers with the same MCP tool name stay distinct
    Given config:
      | mcp.lens.command    | bb |
      | mcp.lens.args       | ["test-resources/marigold/lens_mcp.bb"] |
      | mcp.skybeam.command | bb |
      | mcp.skybeam.args    | ["test-resources/marigold/lens_mcp.bb"] |
    When the Isaac system is started
    And tool "lens__catalog" is executed with:
      | query | marigold |
    Then the tool result is not an error
    And the tool result contains "marigold"
    When tool "skybeam__catalog" is executed with:
      | query | marigold |
    Then the tool result is not an error
    And the tool result contains "marigold"

  Scenario: a hung MCP call is a tool error
    Given config:
      | mcp.lens.command    | bb |
      | mcp.lens.args       | ["test-resources/marigold/lens_mcp.bb"] |
      | mcp.lens.timeout-ms | 50 |
    When the Isaac system is started
    And tool "lens__catalog" is executed with:
      | query | stare |
    Then the tool result is an error
    And the tool result contains "timeout"
    And the log has entries matching:
      | level  | event                | tool          |
      | :error | :tool/execute-failed | lens__catalog |
