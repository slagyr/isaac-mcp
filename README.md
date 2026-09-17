# 🍏 Isaac MCP

Module endowing Isaac with MCP tool calling powers.

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation) and
[isaac-agent](https://github.com/slagyr/isaac-agent). Starts config-declared MCP
servers over stdio, discovers their tools, and registers them into the agent
tool loop under prefixed names.

[![MCP](https://github.com/slagyr/isaac-mcp/actions/workflows/ci-tests.yml/badge.svg)](https://github.com/slagyr/isaac-mcp/actions/workflows/ci-tests.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Clojure](https://img.shields.io/badge/Clojure-1.11%2B-blue?logo=clojure)](https://clojure.org)
[![Babashka](https://img.shields.io/badge/Babashka-1.3%2B-red?logo=clojure)](https://babashka.org)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk)](https://openjdk.org/)

## What's here

- Module `:isaac.tool.mcp` (`isaac.mcp.module/create-module`).
- Config schema for the `:mcp` table — entity files under `config/mcp/`, one per
  server. `:command` is required; `:args`, `:env`, `:cwd` and `:timeout-ms`
  (per-call, default 30000ms) are optional. Transport is stdio only.
- Tool discovery and registration into the agent tool loop. On load,
  `isaac.mcp.runtime` connects each configured server, lists its tools, and
  registers every one with the agent's tool registry — handler, description and
  the MCP `inputSchema` as parameters.
- Registered names are `<server-id>__<tool-name>`, so a server declared as
  `lens` offers `lens__catalog`. Crew allow-lists therefore name the server,
  e.g. `:lens/*` or `:lens/catalog` — there is no `mcp/` prefix.
- Servers reconnect on config change (`McpRuntime` is `Reconfigurable`), and a
  dead command logs `:mcp/connect-failed` without failing Isaac boot.

## Development

Sibling checkouts expected:

```
plan/
  isaac-foundation/
  isaac-agent/
  isaac-mcp/   # this repo
```

```sh
bb spec
bb ci
```

From the JVM, compose `:spec` (shared test deps live on the alias):

```sh
clj -M:spec
```

## Consumer coordinate

```clojure
io.github.slagyr/isaac-mcp {:local/root "../isaac-mcp"}
;; or {:git/url "https://github.com/slagyr/isaac-mcp.git" :git/sha "..."}
```
