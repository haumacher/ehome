# The assistant and the MCP endpoint

E-Home has an assistant: a chat docked over every page, answered by an agent of the TopLogic AI
modules (haumacher/ehome #126). External agents - Claude Code, Claude Desktop - work with E-Home
through its MCP endpoint with the same tools and rights (haumacher/ehome #129). The domain tools and
skills follow in haumacher/ehome #127 (buildings, devices, wiring) and #128 (rules, functions,
schedules, pages); until then the assistant reads and changes data with the engine's data tools.

## What is in the app

- **Modules** (`ehome/pom.xml`): `tl-ai-core` (agents, providers, tools, security), `tl-chat` (the
  chat), `tl-ai-mcp-server` (the MCP endpoint). Not `tl-ai-views` (agents rewriting views have no
  place in an end-user product) and not A2A.
- **The chat** (`WEB-INF/views/app.view.xml`): a launcher in the corner of the shell opens the
  conversation over the page; the rail beside it lists the account's conversations and shows what
  the agent presents (tables, charts, forms). Every turn tells the agent the open page and what is
  selected on it (`<turn-context>`): each page writes its name and its selection into the shell's
  channels `pageView` and `pageSelection` (`<page-selection>`), so "this device" and "this project"
  work.
- **The agent** is the engine's chat agent `TL_SingleAgent` ("Albert") with E-Home's application
  context (`WEB-INF/autoconf/ehome.ai.config.xml`): E-Home's terms (project, line, device,
  communication object, group address and its styles, datapoint type, building part, function, rule,
  controller, runtime image) and what E-Home does differently from ETS. The text is part of the
  system prompt of every run, in German and in English.
- **Rights** (`WEB-INF/autoconf/ehome.security.config.xml`, `AiToolAccessService`), see below.
- **The MCP endpoint** (`WEB-INF/autoconf/ehome.mcp.config.xml`), see below.

## Key and models

The provider is Anthropic. Its key is the named secret `tl_anthropic_api_key`; no key is in the
repository or the package.

- **Development:** an environment variable `export tl_anthropic_api_key=sk-ant-...` before
  `./mvnw -pl ehome exec:java`, or a file `~/.top-logic/secrets/tl_anthropic_api_key` (mode 600)
  holding the key.
- **Debian package:** `tl_anthropic_api_key=sk-ant-...` in `/etc/ehome/ehome.env`, then
  `dpkg-reconfigure ehome` (`doc/debian.md`, "Assistant and MCP endpoint").

Without a key the chat answers that the key is missing and names it; nothing is sent anywhere.

The models (`WEB-INF/conf/ehomeConf.xml`): `claude-sonnet-5-5` (the default), `claude-opus-5-5`
and `claude-haiku-5-5`, selectable in the chat. `tl_anthropic_models` (comma separated, the default
first) and `tl_ai_default_model` override them; a default model that no provider offers stops the
start of the agent runtime. The other providers the engine ships (`openai`, `mistral`, `xai`) stay
configured and are inactive without their keys: an installation that prefers another vendor sets
that vendor's key (`tl_openai_api_key`, ...) and `tl_ai_default_model`, and changes no file. The
metadata of the three Claude models (context window, output limit, prices) is in
`ehome.ai.config.xml`, since the catalog bundled with the engine does not know them yet.

## Who may do what

| Account | Chat | Tools |
|---|---|---|
| `ehome.Installer` (group `ehome.installers`) | the chat agent | read the model and the data (`model.read`, `data.read`); change data with the object tools (`data.write`: `updateObject`, `createObject`, `deleteObject`, `editText`) |
| `ehome.Viewer` (group `ehome.viewers`) | the chat agent | read only |
| administrator (`root`) | every agent | every tool (the checks are bypassed) |
| any other account | no launcher, no agent | none |

- A tool reads and changes only what the account may read and change (`<security-config>`): the
  controllers' tokens and secrets and the projects' installation keys are never read by anybody but
  an administrator.
- **Every writing call asks the user** in the chat (approval card) before it runs.
- **Not granted to anybody:** changing data with a TL-Script (`changeData`, group `script`): E-Home's
  TL-Script functions are not access checked and include writing to the bus; the privileged groups
  (`model.structure`, `agent.authoring`); the web (`egress`).
- **Never the assistant's:** deploying a runtime image, programming a device, writing to the bus.
  These stay the installer's commands in the views; no tool does them, and the application context
  tells the agent so.
- Planning devices, connecting objects and creating group addresses follow rules the views check;
  until E-Home's own tools exist (#127), the agent names the command and the tab instead of
  composing low-level object edits.

## Trying it

1. Set the key (above) and start the app (`./mvnw -pl ehome exec:java` from the repository root).
2. Log in as an installer or as `root`. The star in the lower right corner opens the assistant.
3. Open a project, select a device, and ask "Welche Kommunikationsobjekte hat dieses Gerät?" or
   "Which devices of this project are not placed in a room?". Ask it to rename the selected device:
   it shows an approval card first.
4. Log in as a viewer: the same questions are answered, a change is refused, naming the missing
   capability `data.write`.

## Costs

Every message is a request to Anthropic, billed to the account of the key: the price per million
tokens of input and output of the chosen model (`ehome.ai.config.xml`; Sonnet 5.5 $2 / $10, Opus 5.5
$4 / $20, Haiku 5.5 $0.10 / $0.50). A turn sends the system prompt (agent, application context, the
tools: some ten thousand tokens, cached by the provider after the first turn of a conversation) and
the conversation so far; reading a large project costs more. The chat shows the cost of each run
after its duration; *Administration* → *KI-Modelle* lists the models with their prices. A budget per
account (`<budget>` of `AgentPolicyService`) is not set.

## External agents over MCP

The endpoint is `<host>/ehome/mcp` (in development `http://localhost:8080/ehome/mcp`). It admits
**personal access tokens** only - no anonymous access, no password:

1. In E-Home: account menu → *Settings* → *Access tokens* → *New token*: a name (the client), the
   validity in days. The token (`tlpat_...`) is shown **once**.
2. Keep it in a file of mode 600, never in a repository:
   ```bash
   install -d -m 700 ~/.config/ehome
   (umask 077; cat > ~/.config/ehome/mcp-token)   # paste the token, then Ctrl-D
   ```
3. Connect Claude Code:
   ```bash
   claude mcp add --transport http ehome http://localhost:8080/ehome/mcp \
       --header "Authorization: Bearer $(cat ~/.config/ehome/mcp-token)"
   ```
   For the Debian installation behind its proxy: `https://<host>/ehome/mcp`. The address ends in
   `/mcp`, without a slash: `/ehome/mcp/` is answered with 404 (the transport of the MCP SDK matches
   the end of the path).
4. Revoke a token in the same settings tab; an administrator lists and revokes every token under
   *Administration* → *Access tokens*.

What the endpoint publishes (`<exposed-tools>`): the tool groups `model.read`, `data.read` and
`data.write` - the groups of the chat. E-Home's own tools of #127/#128 are in `data.read` and
`data.write` and are published as soon as they are registered. A call is decided for the token's
account exactly as in the chat: a viewer's client reads and is refused every writing tool. The tool
list itself is the same for everybody (the MCP SDK answers it without the caller), so a viewer's
client lists the writing tools it cannot use.

An MCP call has nobody in E-Home to approve it: it is a background run, capped by the policy's
`<background-allowlist>`, which admits `model.read`, `data.read` and `data.write`. An installer's
client therefore changes data as the installer could in the chat; the approval is the client's own -
Claude Code asks before a tool the list marks destructive (every writing tool). Resources
(`toplogic://...`, the model and the objects) are read with the account's rights, without the
attributes nobody but an administrator reads.

Browsers are refused (`allowed-origins` is empty: a page of another site cannot reach a local
endpoint); command-line and desktop clients send no `Origin`. The host is not checked, as the Debian
package runs behind the installation's proxy.

### Without an MCP client: `bin/ehome-mcp.py`

The same endpoint from a shell, without changing a client configuration: the token from
`~/.config/ehome/mcp-token` (or the variable `EHOME_MCP_TOKEN`), the address from `--url` or
`EHOME_MCP_URL` (default `http://localhost:8080/ehome/mcp`).

```bash
bin/ehome-mcp.py tools
bin/ehome-mcp.py call model-query__query '{"expression": "all(`ehome.project:Project`)"}'
bin/ehome-mcp.py resources
bin/ehome-mcp.py read toplogic://model/modules/ehome.project/types
```

## Tests

`test.de.haumacher.ehome.ai` runs without a model and without a key: `TestAssistant` on the scripted
provider of `tl-ai-core` (`src/test/webapp/WEB-INF/conf/ehome-testAi.config.xml`, the engine article
`ai/testing-agents`) - an installer's turn reads the devices of a project, a viewer is refused a
writing tool, a run without a key names the missing secret; `TestMcpEndpoint` over HTTP with
personal access tokens - no token is 401, an installer initializes, lists and calls the query tool,
a resource read of a controller leaves out its token, a viewer is refused a writing tool.
