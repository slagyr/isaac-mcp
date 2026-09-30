# isaac.tool.mcp — Isaac's operating handbook, MCP servers

You are a crew running inside Isaac. This chapter covers what **isaac-mcp**
owns: the `mcp` config table, launching and reconnecting Model Context
Protocol servers, how their tools get names and reach a crew, and what to
check when a configured server's tools never show up. If you haven't read
`isaac.foundation` yet (config mechanics, `handbook__configure` itself),
read that first — this chapter assumes it. Crews, a crew's tool allow/deny
cascade, and the turn loop belong to `isaac.agent`; this chapter names them
once and moves on. This chapter's own topic id is `isaac.tool.mcp`; each
`##` heading below is also addressable on its own, e.g.
`isaac.tool.mcp#connecting`.

**The one thing to know up front:** a turn never waits on an MCP server. A
server is spawned and initialized on a background thread the first time some
crew's turn needs it; a slow, hung, or dead server costs that server's own
tools for the turns that happen to land before it connects — it never delays
the turn itself, and it never fails the turn.

## MCP servers

**What it is.** Every entry under `mcp` (one file per id under
`config/mcp/`, or inline in `isaac.edn`) declares one external process
Isaac talks to over stdio, newline-delimited JSON-RPC — the same protocol
an editor's MCP integration would use. The table's key is the **server
id** — a short name of your choosing, used both as the config path segment
and as the prefix every one of that server's tools gets (see Tool names,
below). Fields, using the fictional Marigold ship's `lens` server (a
research/search tool) as the running example:

| Field | What it does |
|---|---|
| `command` | The executable to launch. Required — a server with no `command` fails `isaac config validate`. |
| `args` | A list of string arguments passed to `command`. |
| `env` | Extra environment variables for the server process, merged over (not replacing) Isaac's own process environment. |
| `cwd` | Working directory the server process starts in. |
| `timeout-ms` | Per-call timeout in milliseconds, including the initial handshake. Default 30000 (30s) when omitted. |

**How to change it.**

```
config set mcp.lens.command bb
config set mcp.lens.args ["scripts/lens_mcp.bb"]
config set mcp.lens.timeout-ms 15000
```

A companion `env` map is usually easiest written as a whole file,
`config/mcp/lens.edn`:

```
{:command "bb"
 :args    ["scripts/lens_mcp.bb"]
 :env     {"LENS_API_KEY" "${LENS_API_KEY}"}}
```

Never write a real key or token into a config file — reference it with
`${VAR}` the same as any other secret (`isaac.foundation`, Secrets and
`${VAR}`) and let the operator's environment or `<root>/.env` supply it.
`${VAR}` substitution runs over every string in the entry, including
values nested inside `env` and entries inside `args`, so either place can
carry a reference.

**How to verify.** `isaac config validate` schema-checks every `mcp` entry
— a missing `command` is a validation error naming the exact path
(`config:mcp.lens.command` … "required"), not a warning. `isaac config get
mcp.lens.command` (or any other field) reads back what's actually
configured, redacting a resolved `${VAR}` the same as everywhere else. None
of this proves the server actually starts — see Connecting, below, for
that.

### Troubleshooting

- **`config validate` fails naming `mcp.<id>.command`.** Every server needs
  a command; there is no way to declare an entry with only `args` or only
  `env`.
- **A secret in `env` prints `<VAR:UNRESOLVED>`.** The referenced
  environment variable isn't set anywhere Isaac can see it — an
  operator-side fix (process env or `<root>/.env`), not a `handbook__configure`
  one.
- **You changed `mcp.lens.args` and nothing seems different.** Config hot
  reload restarts every configured MCP server on any change to the whole
  `mcp` table (see Connecting, below) — give it a moment for the new
  process to spawn and initialize before checking again.

## Tool names and reaching a crew

**What it is.** Once a server connects, isaac-mcp asks it for its tool
catalog (`tools/list`) and registers each one under
`<server-id>__<tool-name>` — the server id, two underscores, the MCP tool's
own name exactly as it declared it. Two servers can both expose a tool
literally named `catalog` and stay distinct: `lens__catalog` and
`skybeam__catalog` are different registry entries. Nothing about the MCP
tool's own name, description, or input schema is altered — they pass
through as the server declared them.

Reaching a crew is the same allow/deny mechanism every other tool uses —
`isaac.agent` owns that cascade in full (global allow/deny, crew
deny-over-global, crew allow-over-everything); this module contributes
nothing to it beyond the names. A namespace glob works the way it does for
any tool family:

```
config set crew.cordelia.tools.allow.lens/*
```

grants every `lens__*` tool; `config set crew.cordelia.tools.allow.lens/catalog`
grants only `lens__catalog`. An MCP server is *never* offered to a crew
whose allow list doesn't name it or its namespace, even if the server is
configured and connected — an unconfigured or unreached server simply
contributes no tools, the same as a dead one (see Connecting, below).

One thing MCP tools do **not** get: `isaac.agent`'s filesystem directory
grants (`tools.directories.allow`) apply only to the built-in `fs/*`
family. An MCP tool's own access to the filesystem, network, or anything
else is entirely up to what the server process itself does once spawned —
Isaac's directory-grant system has no visibility into it. A server's
`cwd` (above) only sets where its *process* starts; it isn't a permission
boundary.

Isaac also strips a few keys before an MCP server ever sees a call's
arguments: `session_key`, `state_dir`, `caller_crew`, and any callable
value (like a progress callback) a driven turn attaches. Those are Isaac's
own per-turn context, not the model's arguments, and a server whose input
schema sets `additionalProperties: false` would otherwise reject the call
outright.

**How to verify.** After granting a tool and confirming the server has
connected (see Connecting), the next turn for that crew should offer it —
check the model's tool list for that turn, or just have the crew call it.
`isaac.agent#tools-and-directories` covers reading back a crew's effective
allow/deny.

### Troubleshooting

- **A tool the crew should have doesn't show up, but the server's other
  tools do.** The server's own catalog may not include it — MCP tool names
  come from the server, not from Isaac; check what the server itself
  reports (its own docs/logs), not the crew's allow list.
- **Two servers' tools of the same underlying name seem to collide.** They
  shouldn't — the `<server-id>__` prefix keeps them distinct. If you see an
  actual collision, the two entries likely share the same server id by
  mistake (check `config get mcp` for duplicate keys).
- **A call seems to be missing an argument named `session_key`,
  `state_dir`, or `caller_crew`.** Expected — those are stripped before the
  server ever sees the arguments (isaac-r5j4); they're Isaac's context, not
  something an MCP server should need.
- **Granting `fs/*` doesn't change what an MCP tool can touch.** Directory
  grants are an `fs/*`-only mechanism (`isaac.agent#tools-and-directories`)
  and never apply to an MCP server's own filesystem or network access.

## Connecting

**What it is.** Nothing about starting or stopping a server is a
`handbook__configure` action in itself — connecting happens automatically,
triggered the first time some crew's turn needs a tool under that server's
namespace. Inside the long-lived server process (`isaac server`), that
lookup (`isaac.agent`'s tool-provider berth) is non-blocking: with no live
client for the server, no connect already in flight, and no active failure
hold, it starts the connect on a background thread and returns immediately
— that turn simply doesn't see the server's tools yet. The tools join the
registry, and the *next* turn's prompt, once the connect actually lands.
This split is a fixed behavior of which process is running — there is no
config path that changes it.

A **one-shot process** — `isaac prompt`, or an `acp`-mode invocation, run
outside the server — gets exactly one turn, so that turn instead waits for the
connect, bounded by the server's own `timeout-ms`; otherwise a
freshly-configured server's tools would never reach a one-shot invocation
at all.

**Reconnecting.** A connect failure is held with exponential backoff: 60
seconds after the first failure, doubling on every consecutive failure, up
to a 15-minute cap, and reset back to 60 seconds on the next success. While
a server is held, nothing re-spawns it — a broken command doesn't get
retried on every single turn, but it is retried, and eventually recovers on
its own once whatever was wrong is fixed. There is no config path to widen
or shrink the hold window; `timeout-ms` only bounds one connection attempt
or one call, not the retry cadence.

**Catalog changes.** A server that declared the `tools.listChanged`
capability at handshake can tell Isaac its tool list changed
(`notifications/tools/list_changed`); isaac-mcp re-asks for the catalog and
updates the registry (adding new tools, removing vanished ones) the next
time that server is touched — either the next turn that needs it, or right
after a call to it that arrived while the notification was pending. A
server that never declared the capability is never asked again after its
first catalog fetch — restart it (a config touch to its own entry is
enough to trigger a reconnect) if its tools change and it doesn't announce
it.

**How to verify.** This module contributes no top-level CLI command of its
own — verification is indirect: `isaac config validate` confirms the entry
is well-formed (Config, above); whether it's actually reachable shows up as its tools
appearing (or not) on a crew's next turn, and in the logs (see Failure
visibility, below). `isaac logs server` (or `cli` for a one-shot
invocation) is the closest thing to a live status check.

### Troubleshooting

- **A dead or misconfigured command never fails the turn.** By design — a
  server with a bad `command` simply contributes no tools; the crew's turn
  proceeds without them rather than erroring out. Check the logs for
  `:mcp/connect-failed` to confirm that's what happened.
- **A newly-configured server's tools take a turn or two to show up.**
  Expected inside the server process — the first turn that reaches the
  namespace only *starts* the connect; the tools land on a later turn.
  There's no way to force a synchronous connect from inside a turn.
- **A server stopped answering and its tools vanished, but you know the
  command works.** It may be in a failure hold — check for
  `:mcp/connect-held` in the logs and how long the hold has left (it caps
  at 15 minutes and resets on the next success).
- **A server's tool list changed but the registry still shows the old
  one.** If the server never declared `tools.listChanged` at handshake,
  isaac-mcp genuinely never asks again — touch its config entry (even a
  no-op rewrite) to force a fresh connect and catalog fetch.

## Reaching a remote MCP server

**What it is.** isaac-mcp speaks one transport directly: stdio
newline-delimited JSON-RPC to a locally-spawned process (Config, above).
A server that actually lives elsewhere — reachable only over HTTP or SSE —
is reached the same way any MCP client without native remote support
reaches one: configure `command`/`args` to run a small local bridge
process that speaks stdio on one side and the remote transport on the
other, with the remote endpoint as one of its `args` (a URL, never a
config value isaac-mcp itself parses or validates — it's just another
string argument to `command`). `[verify: isaac-mcp has no `:url`/`:type`
field and no built-in remote transport; this section describes the
stdio-bridge pattern as the only route today, not a feature this module
implements]`

**The trap to know about.** A common bridge tool accepts a bearer token
for the remote endpoint via an environment variable pattern (something
like `AUTH_HEADER` in its own `env`). Configuring that as
`mcp.<id>.env.AUTH_HEADER "Bearer ${TOKEN}"` is the natural thing to try —
and it can arrive at the bridge process **empty**, defeating the whole
point of the reference. The known-working fix is to put the resolved value
directly in `args` instead of `env` — one of the command's own arguments,
still written as `${VAR}` in config so the literal secret never lands in a
file:

```
{:command "npx"
 :args    ["-y" "some-mcp-bridge" "https://mcp.example.invalid/sse"
           "--header" "Authorization: Bearer ${REMOTE_MCP_TOKEN}"]}
```

`${VAR}` substitution runs the same way over `args` as it does over `env`
(Config, above), so this loses none of the secret-handling discipline —
the config file still only ever holds a reference, never the literal
token. `[verify: the exact CLI flag/env-var name is bridge-tool-specific
and not exercised by isaac-mcp's own tests; confirm the current bridge
tool's own flag before relying on the example verbatim]`

**How to verify.** Same as any other server — `isaac config validate` for
shape, then check the logs and the crew's next turn for whether its tools
actually showed up (Connecting, above). A bridge process that can spawn
but never completes the remote handshake looks exactly like any other slow
or hung server: it eventually shows `:mcp/connect-failed` with whatever
message the bridge itself printed, or the call that reached it times out
per `timeout-ms`.

### Troubleshooting

- **A remote server's tools never appear, and the bridge's own logs (if
  you can see them) show it never got credentials.** Check whether the
  token is riding in `env` — move it into `args` instead (above).
- **You don't want the token visible in `isaac config get mcp.<id>.args`
  output.** It won't be — a resolved `${VAR}` reference redacts the same
  way in `args` as it does in `env` or anywhere else in config
  (`isaac.foundation`, Secrets and `${VAR}`).
- **The bridge process starts but the remote server is unreachable.**
  That's a `:mcp/connect-failed` like any other bad `command` — the crew's
  turn proceeds without that server's tools; see Connecting, above.

## Failure visibility

**What it is.** isaac-mcp logs at `:info`/`:warn`/`:error` through
whichever stream the running process uses (`cli` for a one-shot
invocation, `server` for the long-lived process) — it declares no stream
of its own.

| Event | Level | Means |
|---|---|---|
| `:mcp/connected` | info | A server's connect landed; its tools are now registered. |
| `:mcp/connect-failed` | error | A connect (spawn, handshake, or initial catalog fetch) failed; the error message is whatever the failure reported. |
| `:mcp/connect-held` | warn | Logged once per failure hold (not per turn) — a server is being left alone until its backoff window elapses. |
| `:mcp/recatalogued` | info | A server announced `tools/list_changed` and its catalog was refreshed; the count of tools now registered is included. |
| `:mcp/recatalog-failed` | error | A catalog refresh (after a `list_changed` notification) failed; the previous catalog is kept as-is. |

A per-call failure — a timeout, or the server's own tool erroring out —
surfaces through `isaac.agent`'s generic `:tool/execute-failed` event, not
a separate MCP-specific one; the tool result the model sees names the
timeout or error text directly.

**How to verify.** `isaac logs server` (or `isaac logs cli`) tails the
relevant stream; both are foundation-declared streams
(`isaac.foundation`, Logs). There's no MCP-specific `isaac logs` name to
ask for.

### Troubleshooting

- **You expect a connect/failure log line and see nothing.** Confirm
  you're checking the right stream for how the process ran — `server` for
  the long-lived process, `cli` for a one-shot `prompt`/`acp` invocation.
- **Repeated `:mcp/connect-failed` with no `:mcp/connect-held` in
  between.** Expected on the *first* failure of a run — the hold log only
  fires once the server actually enters a hold, one line per hold period,
  not one per retry.
- **A tool call failed and you don't see any `:mcp/*` event for it.**
  Correct — a per-call error is `isaac.agent`'s `:tool/execute-failed`
  (`isaac.agent`, Sessions and transcripts / Turns and the tool loop), not
  a `:mcp/*` event; those are reserved for the server's connect/catalog
  lifecycle.
