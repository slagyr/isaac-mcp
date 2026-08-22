@wip
Feature: MCP tools in a turn
  A crew `:allow` of `:lens/*` (or `:lens/catalog`) offers the prefixed
  wire names on the prompt and a turn can invoke them. Unavailable
  servers are not offered. Needs isaac-ek0r (namespaced allow + globs).

  Background:
    Given default Grover setup
    And config:
      | log.output | memory |

  Scenario: crew glob lens/* offers prefixed MCP tools
    Given config:
      | mcp.lens.command | bb |
      | mcp.lens.args    | ["test-resources/marigold/lens_mcp.bb"] |
    And the crew "main" allows tools: "lens/*"
    And the following sessions exist:
      | name       |
      | tools-test |
    When the Isaac system is started
    And the user sends "hello" on session "tools-test"
    Then the prompt has tools:
      | name          |
      | lens__catalog |
      | lens__read    |
    And the prompt does not have tools:
      | name    |
      | catalog |
      | read    |

  Scenario: a turn invokes lens__catalog
    Given config:
      | mcp.lens.command | bb |
      | mcp.lens.args    | ["test-resources/marigold/lens_mcp.bb"] |
    And the crew "main" allows tools: "lens/catalog"
    And the following sessions exist:
      | name       |
      | tools-test |
    And the following model responses are queued:
      | model | tool_call     | arguments              |
      | echo  | lens__catalog | {"query": "marigold"}  |
      | model | type          | content                |
      | echo  | text          | Catalogued.            |
    When the Isaac system is started
    And the user sends "catalog marigold" on session "tools-test"
    Then session "tools-test" has transcript matching:
      | type    | message.role | message.content |
      | message | toolResult   | #".*marigold.*" |
      | message | assistant    | Catalogued.     |

  Scenario: crew without lens on the allow list does not see MCP tools
    Given config:
      | mcp.lens.command | bb |
      | mcp.lens.args    | ["test-resources/marigold/lens_mcp.bb"] |
    And the following sessions exist:
      | name       |
      | tools-test |
    When the Isaac system is started
    And the user sends "hello" on session "tools-test"
    Then the prompt does not have tools:
      | name          |
      | lens__catalog |
      | lens__read    |

  Scenario: a dead lens server is not offered even when allowed
    Given config:
      | mcp.lens.command | /no/such/mcp-server |
    And the crew "main" allows tools: "lens/*"
    And the following sessions exist:
      | name       |
      | tools-test |
    When the Isaac system is started
    And the user sends "hello" on session "tools-test"
    Then the prompt does not have tools:
      | name          |
      | lens__catalog |
