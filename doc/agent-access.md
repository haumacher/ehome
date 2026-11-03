# Agent access

An agent outside the browser (Claude Code, a script) can work in a running E-Home session: read what
the session shows, open views, run commands. That way someone running E-Home in the cloud lets an
agent help configure or analyze a project without handing over a password. E-Home uses the engine
module `tl-layout-view-agent` (dependency in `ehome/pom.xml`; engine article `view-layer/agent-access`):
the endpoint `<host>/ehome/agent-api` and access tokens a user issues from their session.

## Giving an agent access

1. Log in to E-Home and keep the browser tab open: the agent drives this session, and what it does
   appears in the tab.
2. Development menu of the app bar (*Entwicklung* / *Development*) → *Agentenzugriff* / *Agent access*
   → *Neues Token* / *New token*: a name, the validity in hours (8 by default), and whether the agent
   may act (otherwise it only observes).
3. *Token ausstellen* / *Issue token* shows the **invitation** once, one line
   `<host>/ehome/agent-api tlagt_<id>_<secret>`. Hand it to the agent; it is not shown again (only
   its hash is stored).
4. When the agent is done: the same dialog → select the token → *Zurückziehen* / *Withdraw*. A token
   also ends with its validity, and with the session (logout, timeout, restart of the server).

What the agent can do is exactly what the account can do in the browser: an `ehome.Viewer` account's
agent reads, an `ehome.Installer` account's agent also edits, imports, programs and deploys
(`ehome.security.config.xml`). A token that may only observe is refused every command. Each account
sees and withdraws its own tokens (role `ehome.TokenOwner`); an administrator lists all tokens in
Administration → *Agent access*.

## For the agent: `bin/ehome-agent.py`

The invitation goes into a file of mode 600, by default `~/.config/ehome/agent-invitation` (or the
variable `EHOME_AGENT_INVITATION`); the script never prints the token.

```bash
bin/ehome-agent.py windows                              # the windows (browser tabs) of the session
bin/ehome-agent.py observe --actions                    # the controls of the window and their commands
bin/ehome-agent.py act /appShell/sidebar selectItem '{"itemId": "controllers"}'
bin/ehome-agent.py navigate /ehome/view/projects
```

`observe` without `--actions` returns the whole tree of the window (with the values of tables and
forms); `act` dispatches a command to the control at an address taken from `observe`, and answers
with the observation after it. A route URL reaches only the areas already displayed; an area of the
sidebar is opened with `selectItem` first. A refused or expired token ends with "the token is not
admitted"; the endpoint answers it with a redirect to the login page instead of 401 (TL #30125,
`doc/tl-todo.md`).

## Operation

- Behind the reverse proxy of the Debian package (`/usr/share/doc/ehome/nginx-ehome.conf`) the
  endpoint is reached through the same `location /ehome/`; the invitation names the public address
  because the context's `RemoteIpValve` takes the scheme and host from the proxy's headers. An
  installation that is reached under another name sets `tl_host` (e.g. `https://ehome.example.org`) in
  `/etc/ehome/ehome.env`.
- Every request of an agent runs in the user's session; a command of the agent waits while a command
  of the browser runs, and the other way round.
- Each admitted request records `lastUsed` on the token in a commit of its own (TL #30048): an agent
  polling `observe` in a loop grows the database's history; poll only as often as needed.
- Open points of the module, recorded in `doc/tl-todo.md` (TL #30048): any logged-in account may issue
  a token; a token of an ended session stays listed until it expires; an agent cannot open a session
  of its own.

Verified on the desk (2026-11-03): a token issued as `root` in the local app, `windows` and
`observe --actions` through `bin/ehome-agent.py`, `selectItem` on the sidebar switched the browser tab
to the controllers, a forged token was refused; `TestSecurityCoverage` covers `tl.agent:AccessToken`.
