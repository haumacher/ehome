# The assistant and the MCP endpoint

E-Home has an assistant: a chat docked over every page, answered by an agent of the TopLogic AI
modules (haumacher/ehome #126). External agents - Claude Code, Claude Desktop - work with E-Home
through its MCP endpoint with the same tools and rights (haumacher/ehome #129). E-Home's own tools
and skills for buildings, devices, parameters and wiring are those of haumacher/ehome #127 (below);
rules, functions, schedules and pages follow in #128.

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

## E-Home's tools

Classes of `de.haumacher.ehome.ai` (`ehome.ai.config.xml`): on the chat's agent, published over MCP
as `ehome-building__...`, `ehome-devices__...`, `ehome-wiring__...`. Reading tools are in `data.read`
(installers and viewers), changing ones in `data.write` (installers; a dry run needs no approval).

| Tool | Changes | Runs |
|---|---|---|
| `buildingStructure` | - | the building tree of a project |
| `createBuildingParts` | yes, dry run | parts with their parts, in order (`BuildingDevices.insertVeto`) |
| `moveBuildingParts` | yes | `BuildingDevices.moveVeto` / `move` |
| `placeDevice` | yes | `BuildingDevices.place` |
| `searchCatalog` | - | `DevicePlanning.variants`, words in manufacturer, name, order number, catalog section |
| `listDevices` | - | lines with the next free number, devices with address, product, program, room |
| `planDevice` | yes, dry run | `DevicePlanning.addressVeto` / `nextFree` / `create` |
| `duplicateDevice` | yes, dry run | `DevicePlanning.duplicateVeto` / `duplicate` |
| `deviceParameters` | - | `DeviceConfiguration`: the page tree, a page's parameters with value, default, allowed values, edit veto |
| `setDeviceParameter` | yes | `DeviceSettings.setValue` (type, range, manufacturer's validations and calculations) |
| `listGroupAddresses` | - | ranges, and per address (paged) name, identifier, description, type, range, functions, connections with device, room, object, function, channel, direction |
| `createGroupAddress` | yes, dry run | `GroupAddresses.veto` / `nextFree` / `create`, in the project's notation |
| `setGroupAddressType` | yes | `GroupAddressTypes.veto` / `setType` |
| `nameGroupAddresses` | yes, dry run | names, descriptions and identifiers (haumacher/ehome #130) of several addresses of one project in one call; identifiers checked together (`GroupAddressIds.problems`: syntax, uniqueness against the project after the change, application datapoints, reserved ids) |
| `deviceObjects` | - | communication objects with size, type, flags, default direction, connections |
| `connectObject` | yes, dry run | `DeviceSettings.connect`; refuses an object that does not fit the address (`GroupAddressConnections.problemsWith`) unless `despiteProblems` |
| `disconnectObject` | yes | `DeviceSettings.disconnect` |

- **Rights.** A changing tool checks `Write` on the project of its objects before it changes
  anything (E-Home's Java code is not access checked) and refuses without a user account. Objects
  come in as `Type:id` and are resolved with the read rights of the run.
- **Refusals** are the views' own reasons (their veto functions), in the user's language, as the
  shipped refusal object `{"refused": true, "reasons": [...]}`; nothing is changed then.
- **Answers** name what was created or changed by id and display text.
- **The open project:** every tab of a project passes the project in the page selection; the
  agent hands its id to the tools, which take any object of the project as well.
- **Not a tool:** programming a device, deploying, writing to the bus; creating group ranges (the
  view creates a range with its name only and its bounds in the form: there is no rule to run yet,
  the agent names the command).

Skills (`loadSkill`): `EHome_buildingLayout`, `EHome_devicePlanning`, `EHome_wiring`,
`EHome_namingGroupAddresses` (room + function + kind, the project's language, status marked,
nothing invented, a table first, then one `nameGroupAddresses` call after the user agreed; the
identifiers derived from the names, `kueche.licht`, when the user asks for IDs too). The rules
skill (`EHome_rules`) names a group address by its identifier where it has one and suggests one
where a rule uses an address without; the logic tools accept a group address also by its address
where its datapoint has an identifier.

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
  E-Home's own tools (below) run them, and the application context tells the agent to use them
  instead of the object tools.

## Trying it

1. Set the key (above) and start the app (`./mvnw -pl ehome exec:java` from the repository root).
2. Log in as an installer or as `root`. The star in the lower right corner opens the assistant.
3. Open a project, select a device, and ask "Welche Kommunikationsobjekte hat dieses Gerät?" or
   "Which devices of this project are not placed in a room?". Ask it to rename the selected device:
   it shows an approval card first.
4. Log in as a viewer: the same questions are answered, a change is refused, naming the missing
   capability `data.write`.

## Rules, simulation, functions, pages and blocks (#128)

The tools of `de.haumacher.ehome.ai.logic`, configured with their skills in
`WEB-INF/autoconf/ehome.ai.logic.config.xml`. The chat's agent loads a skill when a question needs it:

| Skill | For | Tools (group) |
|---|---|---|
| `EHome_rules` | writing, changing, explaining and trying rules, schedule entries and application datapoints; the rule language with worked patterns | `automation`, `explainRule`, `datapointUsage`, `checkAutomation`, `simulate`, `imageProblems` (`data.read`); `saveAutomation`, `deleteAutomation` (`data.write`) |
| `EHome_functionsAndPages` | functions from the proposal or by hand, control pages | `proposeFunctions`, `dismissFunctions`, `createFunction`, `createPage` (`data.write`) |
| `EHome_blocks` | virtual devices (room controller, sun shading, ...) | `blockTypes`, `blockStatus` (`data.read`); `createBlock`, `bindBlock` (`data.write`) |
| `EHome_diagnose` | "why did X not happen?" | `datapointUsage`, `explainRule`, `datapointRecordings`, `simulate`, `imageProblems`, `blockStatus` (`data.read`) |

- **Definitions.** Rules, schedule entries and application datapoints are written in the JSON of the
  runtime image with every expression, value and log message as text of the rule language. They are read
  by the import of an image (`RuleImport`) and checked field by field as the rule editor checks them; an
  item with the id of an existing one replaces it in place (what refers to it keeps referring to it).
  `saveAutomation` saves nothing when a field is refused; `checkAutomation` and every dry run try the
  change in a transaction that is rolled back.
- **The simulation** (`simulate`) runs the project's image - or definitions not saved yet - in E-Home's
  simulation of the controller, headless: the steps set values, let time pass (`wait`, `until`) and set
  parameters; the answer is the timeline of the rules' writes and logs. Devices are not simulated (an
  actuator does not answer), and nothing has a value before the scenario sets it.
- Viewers read, explain, check and simulate; saving, deleting and creating need `data.write`.
- No tool deploys, programs or writes to the bus; the skills name the installer's commands.

Try it against a running app (an installer's token in `~/.config/ehome/mcp-token`):

```bash
bin/ehome-mcp.py call ehome-rules__automation '{"project": "<project id>"}'
bin/ehome-mcp.py call ehome-rules__checkAutomation '{"project": "<project id>", "definitions": {"rules": [...]}}'
bin/ehome-mcp.py call ehome-simulation__simulate '{"project": "<project id>", "start": "2026-11-10T22:00", "steps": [{"set": "1/0/0", "value": "1.5"}, {"wait": "1min"}]}'
```

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
`data.write` - the groups of the chat, E-Home's own tools included. A call is decided for the token's
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
bin/ehome-mcp.py call ehome-building__buildingStructure '{"project": "EHProject:1234"}'
bin/ehome-mcp.py resources
bin/ehome-mcp.py read toplogic://model/modules/ehome.project/types
```

## Tests

`test.de.haumacher.ehome.ai` runs without a model and without a key: `TestAssistant` on the scripted
provider of `tl-ai-core` (`src/test/webapp/WEB-INF/conf/ehome-testAi.config.xml`, the engine article
`ai/testing-agents`) - an installer's turn reads the devices of a project, a viewer is refused a
writing tool, a run without a key names the missing secret; `TestMcpEndpoint` over HTTP with
personal access tokens - no token is 401, an installer initializes, lists and calls the query tool,
a resource read of a controller leaves out its token, a viewer is refused a writing tool, an
installer creates a building with a floor through `createBuildingParts` and a viewer is refused it.
`TestAssistantTools` runs E-Home's tools on the test system (a floor with rooms, a planned actuator
in the kitchen, a group address connected to channel A; the refusals of an address in use, a type or
object of another size, a building part of another project, a viewer; parameters, duplicate, move,
place), `TestAssistantNaming` the naming of unnamed addresses (`src/test/fixtures/TestNaming.knxproj`).
