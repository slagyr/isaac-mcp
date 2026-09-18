Feature: MCP stdio lifecycle
  Configured MCP servers start over stdio the first time a turn allows
  their namespace (isaac-vadd: the tool registry asks the MCP module
  through the :isaac.agent/tool-providers berth; nothing starts servers
  by hand). Discovered tools register as ns__name (server id + MCP tool
  name) and run through the existing tool registry. A dead command does
  not fail the turn. Offering and invoking in a turn is turn.feature.

  Background:
    Given default Grover setup
    And config:
      | log.output | memory |
    And the following sessions exist:
      | name       |
      | tools-test |

  Scenario: a dead command does not fail the turn and is logged once
    Given config:
      | mcp.lens.command | /no/such/mcp-server |
    And the crew "main" allows tools: "lens/*"
    And the following model responses are queued:
      | model | type | content    |
      | echo  | text | Still here |
    When the Isaac system is started
    And the user sends "hello" on session "tools-test"
    Then the log has entries matching:
      | level  | event               | server |
      | :error | :mcp/connect-failed | lens   |
    And the prompt does not have tools:
      | name          |
      | lens__catalog |
    And session "tools-test" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | Still here      |

  Scenario: two servers with the same MCP tool name stay distinct
    Given config:
      | mcp.lens.command    | bb |
      | mcp.lens.args       | ["test-resources/marigold/lens_mcp.bb"] |
      | mcp.skybeam.command | bb |
      | mcp.skybeam.args    | ["test-resources/marigold/lens_mcp.bb"] |
    And the crew "main" allows tools: "lens/*, skybeam/*"
    And the following model responses are queued:
      | model | tool_call        | arguments              |
      | echo  | lens__catalog    | {"query": "marigold"}  |
      | model | tool_call        | arguments              |
      | echo  | skybeam__catalog | {"query": "marigold"}  |
      | model | type             | content                |
      | echo  | text             | Both catalogued.       |
    When the Isaac system is started
    And the user sends "catalog marigold twice" on session "tools-test"
    Then the prompt has tools:
      | name             |
      | lens__catalog    |
      | lens__read       |
      | skybeam__catalog |
      | skybeam__read    |
    And session "tools-test" has transcript matching:
      | type    | message.role | message.content |
      | message | toolResult   | #".*marigold.*" |
      | message | toolResult   | #".*marigold.*" |
      | message | assistant    | Both catalogued. |
    And the log has entries matching:
      | level | event          | server  |
      | :info | :mcp/connected | lens    |
      | :info | :mcp/connected | skybeam |

  Scenario: a hung MCP call is a tool error
    Given config:
      | mcp.lens.command    | bb |
      | mcp.lens.args       | ["test-resources/marigold/lens_mcp.bb"] |
      | mcp.lens.timeout-ms | 2000 |
    And the crew "main" allows tools: "lens/*"
    And the following model responses are queued:
      | model | tool_call     | arguments          |
      | echo  | lens__catalog | {"query": "stare"} |
      | model | type          | content            |
      | echo  | text          | It hung.           |
    When the Isaac system is started
    And the user sends "stare at the lens" on session "tools-test"
    Then session "tools-test" has transcript matching:
      | type    | message.role | message.content |
      | message | toolResult   | #".*timeout.*"  |
      | message | assistant    | It hung.        |
    And the log has entries matching:
      | level  | event                | tool          |
      | :error | :tool/execute-failed | lens__catalog |
