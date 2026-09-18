@wip
Feature: MCP tools reach every host
  Config-declared MCP servers are offered and invoked through the same
  registry seam in every host. Nothing in `prompt` or `acp` knows about
  MCP; the tool registry asks the MCP module for an allowed namespace
  the first time a turn needs it. Fixture is qgtn's marigold lens server.

  Background:
    Given default Grover setup
    And config:
      | log.output       | memory |
      | mcp.lens.command | bb |
      | mcp.lens.args    | ["test-resources/marigold/lens_mcp.bb"] |
    And the crew "main" allows tools: "lens/*"
    And the following model responses are queued:
      | model | tool_call     | arguments              |
      | echo  | lens__catalog | {"query": "marigold"}  |
      | model | type          | content                |
      | echo  | text          | Catalogued.            |

  Scenario: the prompt command offers and invokes an MCP tool
    When isaac is run with "prompt --crew main --session lens-run -m 'find marigold'"
    Then the exit code is 0
    And session "lens-run" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | find marigold   |
      | message | toolResult   | #".*marigold.*" |
      | message | assistant    | Catalogued.     |

  Scenario: an acp session invokes an MCP tool
    Given the following sessions exist:
      | name     |
      | lens-acp |
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/prompt","params":{"sessionId":"lens-acp","prompt":[{"type":"text","text":"find marigold"}]}}
      """
    When isaac is run with "acp --session lens-acp"
    Then the stdout contains "\"stopReason\":\"end_turn\""
    And the exit code is 0
    And session "lens-acp" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | find marigold   |
      | message | toolResult   | #".*marigold.*" |
      | message | assistant    | Catalogued.     |
