# 🍏 Isaac MCP

Module endowing Isaac with MCP tool calling powers.

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation) and
[isaac-agent](https://github.com/slagyr/isaac-agent). Contributes config-declared
MCP servers and (planned) tool discovery into the agent tool loop.

[![MCP](https://github.com/slagyr/isaac-mcp/actions/workflows/ci-tests.yml/badge.svg)](https://github.com/slagyr/isaac-mcp/actions/workflows/ci-tests.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Clojure](https://img.shields.io/badge/Clojure-1.11%2B-blue?logo=clojure)](https://clojure.org)
[![Babashka](https://img.shields.io/badge/Babashka-1.3%2B-red?logo=clojure)](https://babashka.org)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk)](https://openjdk.org/)

## What's here

- Module skeleton (`isaac.mcp.module/create-module`).
- Config schema stub for MCP servers in `config/mcp/` (`:command` required; optional `:args`, `:env`, `:cwd`).
- Tool discovery and registration into the agent tool loop — planned.

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
