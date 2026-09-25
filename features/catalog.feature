Feature: MCP catalog honors tools/list_changed
  A server's catalog is fetched once at connect and cached. A server that
  declared tools.listChanged may announce a change with
  notifications/tools/list_changed; Isaac re-catalogs it before the next
  turn's prompt. A server without the flag is never asked again.
  Fixture: `--grow` adds a grow tool that appends `extra` and emits the
  notification; `--list-changed` advertises the capability.

  Background:
    Given default Grover setup
    And config:
      | log.output | memory |
    And the crew "main" allows tools: "lens/*"
    And the following sessions exist:
      | name       |
      | tools-test |
    And the following model responses are queued:
      | model | tool_call  | arguments |
      | echo  | lens__grow | {}        |
      | model | type       | content   |
      | echo  | text       | Grown.    |
      | model | type       | content   |
      | echo  | text       | Again.    |

  Scenario: a server that announces list_changed is re-catalogued for the next turn
    Given config:
      | mcp.lens.command | bb |
      | mcp.lens.args    | ["test-resources/marigold/lens_mcp.bb", "--grow", "--list-changed"] |
    When the Isaac system is started
    And the user sends "grow" on session "tools-test"
    And the user sends "what now" on session "tools-test"
    Then the prompt has tools:
      | name          |
      | lens__catalog |
      | lens__read    |
      | lens__grow    |
      | lens__extra   |
    And the log has entries matching:
      | level | event             | server |
      | :info | :mcp/recatalogued | lens   |

  Scenario: a server without listChanged keeps its catalog
    Given config:
      | mcp.lens.command | bb |
      | mcp.lens.args    | ["test-resources/marigold/lens_mcp.bb", "--grow"] |
    When the Isaac system is started
    And the user sends "grow" on session "tools-test"
    And the user sends "what now" on session "tools-test"
    Then the prompt has tools:
      | name          |
      | lens__catalog |
      | lens__read    |
      | lens__grow    |
    And the prompt does not have tools:
      | name        |
      | lens__extra |
