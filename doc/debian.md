# The Debian package

E-Home installs on Debian 13 (trixie) or newer, and on Ubuntu 24.04, as the package `ehome`. It runs
the war in the distribution's Tomcat 10 (`tomcat10`, Jakarta EE 10, the WebSocket of the controllers
included) on Java 21 (`java21-runtime-headless`, which TopLogic 8 requires; Debian 12 has
no Java 21 of its own) and keeps its data in an H2 database under `/var/lib/ehome`.

## Building

The CI workflow `.github/workflows/debian-package.yml` builds the package on every push to `master`
(an artifact `ehome-deb` of the run, kept 30 days) and publishes it as a GitHub release and in the apt
repository for a tag `vX.Y.Z` (see "The apt repository" below). By
hand, in the repository root:

```bash
./mvnw install -Pproduction,debian -DskipTests=false   # the app alone, tested; the war of the profile debian
bin/build-deb.sh                                       # ehome/target/ehome_<version>_all.deb
```

The profile `debian` of `ehome/pom.xml` builds a war of its own: the deploy aspect `deploy/debian`
selects the H2 connection pool (`db-h2.xml`) instead of the container's JNDI data source that the
engine adds to every war (`tl.app.metaConfAddons.db`), leaves out the debug settings of `deploy/local`,
and puts the H2 driver into `WEB-INF/lib`. Without a tag on `HEAD` the package version names the
commit with its time in UTC: `1.0.0~git20261025143012.91d27ec` for the app version
`1.0.0-SNAPSHOT`, older than a later release `1.0.0`, and two snapshots of one day ordered by their
time (packages before 2026-11-03 named only the date, `1.0.0~git20261025.91d27ec`, which sorts
below). The tooling (`ehome-virtual`) is never part of it.

## The apt repository

GitHub Pages serves a signed apt repository at <https://haumacher.github.io/ehome/> with one suite,
`stable`: the newest two releases (tags `vX.Y.Z`; a package is about 160 MB, a Pages site at most
1 GB).

```bash
sudo curl -fsSLo /usr/share/keyrings/ehome-archive-keyring.gpg https://haumacher.github.io/ehome/ehome-archive-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/ehome-archive-keyring.gpg] https://haumacher.github.io/ehome stable main" \
  | sudo tee /etc/apt/sources.list.d/ehome.list
sudo apt update && sudo apt install ehome
```

Only a release updates the repository. Pages replaces the whole site with every deployment, so a
suite of the builds of `master` (`snapshot`, until 2026-11-13) cost every push a download and an upload
of all packages (about 630 MB) and ran into the deployment timeouts of Pages. A build of `master` is
the artifact `ehome-deb` of its run (`gh run download <run> -n ehome-deb`), installed with
`sudo apt install ./ehome_<version>_all.deb`. The suite `snapshot` stays on the live site until the
next release is deployed; a system that follows it gets an error from `apt update` after that and
switches to `stable`.

The job `apt` runs for a tag only. It downloads the packages of the newest two GitHub releases (the
source of truth; the release the run has just made is among them) and builds the whole site with
`bin/apt-repo.sh`: it drops older packages, writes the indexes with `apt-ftparchive` (`binary-all`
and, identical, `binary-amd64` and `binary-arm64`) and signs `Release` (`InRelease`, `Release.gpg`)
with the key of the repository secrets `APT_SIGNING_KEY` (the secret key, armored or base64) and
`APT_SIGNING_PASSPHRASE`. The public key is published beside it (`ehome-archive-keyring.gpg`,
`.asc`), with an `index.html` naming its fingerprint. The site is deployed with
`actions/deploy-pages` (Pages source "GitHub Actions"; the environment `github-pages` admits `master`
and the tags `v*`), never as a branch: a package is above the 100 MB of a file in git. Runs deploy one
after the other (concurrency group `apt-repository`). Pages takes only the first deployment of a
commit: a second one reports success and changes nothing; with only the tag's run deploying, the
release commit is deployed once.

## Installing

```bash
sudo apt install ehome                       # from the apt repository, or a downloaded package:
sudo apt install ./ehome_<version>_all.deb
sudo grep tl_initial_password /etc/ehome/secrets.env
```

The app is at `http://<host>:8080/ehome/view/`; log in as `root` with the password of
`/etc/ehome/secrets.env`, generated at the first installation and applied only when the database is
created at the first start (about a minute). Change it in the application afterwards.

**The package never restarts Tomcat**, neither at the installation nor at an upgrade, a change of the
settings or the removal: other applications of the same Tomcat keep running. And **nothing of E-Home
reaches the JVM**: its settings are JNDI entries of its own context (`java:comp/env/tl/<name>`, which
TopLogic reads before system properties and environment variables), never environment variables of
Tomcat, which every other TopLogic application of the same JVM would read as well. (Package 1.0.0 did
both: it restarted Tomcat at the first installation and gave the settings to the whole JVM through
`EnvironmentFile`, so every other TopLogic application of the Tomcat opened E-Home's database. Fixed
in 1.0.1; a Tomcat started under 1.0.0's drop-in keeps those variables until its next start, which
the administrator schedules.)

`tomcat10.service` runs with `ProtectSystem=strict`: the drop-in grants `/var/lib/ehome` and
`/var/log/ehome` from Tomcat's next start (the log directory optional, so that a missing one never keeps
Tomcat from starting), and a Tomcat that runs already gets each directory it cannot write bound into its
namespace with `systemctl bind tomcat10 <directory>` (systemd 248 or newer; Debian 13 has 257); an
upgrade from a package without the log directory binds it the same way. Where that is not possible
for one of the two, the package installs without deploying and says what to do. E-Home shares the heap with the
other applications of the Tomcat; sizing it is the administrator's choice (`JAVA_OPTS` below).

| Path | What |
| --- | --- |
| `/usr/share/ehome/ehome.war` | The app. |
| `/etc/tomcat10/Catalina/localhost/ehome.xml` | Tomcat's context `/ehome` with the settings as `<Environment name="tl/...">` entries, written by `postinst` from the template `/usr/share/ehome/tomcat/ehome.xml`, `ehome.env` and `secrets.env` (mode 0640, `root:tomcat`: it holds the passwords). Its header only points to `ehome.env`; edits in it are lost at the next `dpkg-reconfigure` or upgrade. Writing it deploys the app in the running Tomcat, without restarting Tomcat. |
| `/usr/lib/systemd/system/tomcat10.service.d/ehome.conf` | Write access to the data and the log from Tomcat's next start (`ReadWritePaths`); nothing else. |
| `/etc/ehome/ehome.env` | The settings (lines `name=value`), a conffile of the package (mode 0640, readable by root only: an API key goes there). Active: `tl_storage_path`, `h2_name`; every other setting of E-Home is a commented line with its meaning and default (`tl_host`, `tl_anthropic_api_key`, `tl_anthropic_models`, `tl_ai_default_model`, `tl_ehome_programming_write_enabled`, `tl_ehome_local_network`; `bin/build-deb.sh` refuses to build when a `${env:tl_...}` variable of the app's configuration has no line). Further TopLogic settings go there too; `dpkg-reconfigure ehome` after a change (deploys the app again, Tomcat keeps running). Tomcat never reads this file. |
| `/etc/ehome/secrets.env` | The generated passwords, `h2_passwd` (of the database) and `tl_initial_password` (of `root`), written once by `postinst` at the first installation (mode 0640, `root:tomcat`); not a conffile, as they differ per installation. A name set in `ehome.env` wins. |
| `/var/lib/ehome` | The data: `h2.mv.db`, documents (`var/`). |
| `/var/log/ehome` | The log of the app, `ehome.log` (owner `tomcat`, group `adm`, mode 2750 as Tomcat's own logs). Written by the logging configuration of the deploy aspect `ehome/deploy/debian`: `WEB-INF/conf/logging/default.xml`, which TopLogic's `LoggerAdminBean` loads at startup in place of the engine's file of that name (console and `top-logic.*.log` in the data directory), and `WEB-INF/classes/log4j2.xml` for the first moments before it (it takes precedence over the engine's console configuration in `WEB-INF/lib`); the scheduler's and the memory observer's lines go to `scheduler.log` and `memory.log` beside it. `ehome.log` is rolled at midnight into `ehome-<YYYY-mm-dd>.log.gz`, the newest 14 kept, by log4j itself (no logrotate: log4j keeps the file open, `copytruncate` would lose lines). Nothing goes to the console, so E-Home's lines are not in Tomcat's journal (`journalctl -u tomcat10`), which it shares with the other applications. An upgrade appends to the same file; `apt purge` deletes the directory. |

## Upgrading

`ehome.env` is a conffile: an upgrade whose package changes it and finds it changed by the administrator
asks as dpkg does for every conffile (keep, install the package's version, show the differences); a
kept file gets the package's version beside it as `ehome.env.dpkg-dist`, a replaced one stays as
`ehome.env.dpkg-old`. `apt-get -o Dpkg::Options::=--force-confold` keeps it without asking. The
passwords are not in it, so neither choice touches them. An unchanged file is replaced silently.

Before 1.7.0 `postinst` generated `ehome.env` once, with the passwords and the commented lines of that
version, and it was no conffile (haumacher/ehome #185). The first upgrade from such a version: `preinst`
moves `h2_passwd` and `tl_initial_password` (with the comment directly above each) into `secrets.env`
(a file without `h2_passwd`, older than TopLogic 8.0.0-alpha12, gets the engine's former default
`theautomatedappengine`, which created its database). dpkg then finds a file it did not install and
asks: keeping it keeps the administrator's active settings (and, for a file of 1.0.0, its outdated
header), installing the package's version gives the documented template, the old file stays as
`ehome.env.dpkg-old` to copy settings from. Either way the app starts on its database with the moved
password. A downgrade to a version before 1.7.0 is not supported: its `postinst` would add the
former default password to the `ehome.env` without the moved one.

An upgrade (`apt upgrade`, or `apt install` of a newer package) stops the app, not Tomcat, and backs
up the database first; the settings and the data stay, and the new version migrates the database at
its start:

1. `preinst` takes the context descriptor `/etc/tomcat10/Catalina/localhost/ehome.xml` away (parked in
   `/var/lib/ehome/.upgrade/`). Tomcat (`autoDeploy`, Debian's default) undeploys the app, which
   closes the database; the script waits until no process holds `/var/lib/ehome/h2.mv.db` open (at
   most 5 minutes) and until Tomcat has removed its unpacked copy of the old war.
2. It copies the database, gzip-compressed, to
   `/var/lib/ehome/backup/h2-<old version>-<YYYYmmdd-HHMMSS>.mv.db.gz` (owner `tomcat`, mode 0640) and
   keeps the newest five backups.
3. dpkg unpacks the new war; `postinst` puts the descriptor back, and the running Tomcat deploys the
   new version (on the test container: down for about 20 s plus the start of the app).

Tomcat is never restarted. If the app
does not stop in time, or the backup fails (disk full), the upgrade is aborted with the reason and
the old version is deployed again (`postrm abort-upgrade` restores the descriptor as well). Stopping
Tomcat (`systemctl stop tomcat10`) and repeating the upgrade then backs up the closed database without
waiting. Only the database is backed up, not the documents in `/var/lib/ehome/var`.

To go back to a backup: take the app away (`mv /etc/tomcat10/Catalina/localhost/ehome.xml /root/`),
wait until it is undeployed, restore the file (`gunzip -c <backup> > /var/lib/ehome/h2.mv.db`, owner
`tomcat`), and put the descriptor back. A backup of an older version is opened by that version only
if the newer one already migrated it: install the version named in the backup's name with it
(`apt install ehome=<version>`, as long as the repository still holds it).

Verified in a Debian 13 container with systemd, a running `tomcat10` and a second application
(2026-11-18, haumacher/ehome #185): a fresh installation (`ehome.env` of the package, `secrets.env`
generated, `root` logs in with its password; `tl_ehome_programming_write_enabled=true` and
`dpkg-reconfigure ehome` enable the download commands, commented out again they are disabled with
the reason); upgrades from 1.6.0 and from 1.0.0 (directly and through 1.6.0), each answered with
"keep" and with "install": the prompt, the passwords in `secrets.env`, the app on its old database;
an upgrade between two new packages with a changed `ehome.env` (silent when the package's file is
unchanged, `--force-confold` keeps it, otherwise the prompt); the purge. Tomcat kept its PID
throughout.

Verified in a Debian 13 container with systemd and a running `tomcat10` (2026-11-04, 1.0.1): a first
installation and an upgrade from 1.0.0 left Tomcat's process (same PID) and its ROOT application
running, the first installation bound `/var/lib/ehome` into it, and the Tomcat process has no `tl_` or
`h2_` variable. Earlier, in a Debian 13 container with systemd and `tomcat10` (2026-11-03): the package of 32788e6
installed, a project created, upgraded twice (from a file, then from a local copy of the repository
with `apt-get install --only-upgrade`): Tomcat kept its process, each upgrade left a backup, the
project was there after both, and the model upgrade only added the module `tl.agent`.

## Settings and data

The upgrade keeps the settings and the data. The engine has no default for the database password since TopLogic
8.0.0-alpha12 (named secrets): an upgrade from an earlier package writes `h2_passwd` with the former
default `theautomatedappengine`, which the existing database was created with, into `secrets.env`
("Upgrading"). `apt remove` undeploys the app; `apt purge` also deletes the settings (`ehome.env`, `secrets.env`) and the logs. As an upgrade does,
`prerm` takes the descriptor away and waits (at most 5 minutes) until the app has closed the database
and Tomcat has removed its unpacked copy, before the war and that copy are deleted: packages up to
1.0.2 deleted the unpacked copy at once, the jars disappeared under the running app, its shutdown
failed on a class it could not load any more (`NoSuchFileException` in Tomcat's
`localhost.<date>.log`), and the database stayed open in Tomcat's JVM (haumacher/ehome #81; the next
installation failed with `The file is locked`, until Tomcat's next start). The data
in `/var/lib/ehome` are never deleted by the package.

Tomcat's heap is the JVM's default (a quarter of the memory); set `JAVA_OPTS` in
`/etc/default/tomcat10` for more (effective at Tomcat's next start, which the administrator chooses). Tomcat runs on the system's default Java; where that is not Java 21,
set `JAVA_HOME` there. TLS and a public host name (the controllers connect to
`wss://<host>/ehome/api/controllers/<id>/socket`, `doc/controllers.md`) belong to a reverse proxy in
front of Tomcat on the same host: `/usr/share/doc/ehome/nginx-ehome.conf` is an example for nginx
(WebSocket upgrade, `X-Forwarded-*`, read timeout). The context `ehome.xml` has Tomcat's
`RemoteIpValve`, which takes the client's address and scheme from the `X-Forwarded-For` and
`X-Forwarded-Proto` headers of a proxy connecting from the loopback interface only, so that the
enrollment's rate limit counts the clients, not the proxy; from any other address the headers are
ignored. A proxy on another host needs its address in the valve's `internalProxies` (the context is
rewritten by every upgrade of the package and every `dpkg-reconfigure`).

## Programming and local networks

Two settings of `/etc/ehome/ehome.env` decide what E-Home may reach (`de.haumacher.ehome.settings.OperatorSettings`;
TopLogic configuration variables with the aliases `%EHOME_PROGRAMMING_WRITE_ENABLED%` and
`%EHOME_LOCAL_NETWORK%` of `WEB-INF/conf/ehomeConf.xml`), each a commented line of `ehome.env`. Like every setting they become JNDI entries of
E-Home's own context with `dpkg-reconfigure ehome` (the app is deployed again, Tomcat keeps running),
never variables of Tomcat's JVM.

- `tl_ehome_programming_write_enabled` (default `false`, haumacher/ehome #149): whether E-Home writes
  to the KNX bus - "Herunterladen", "Physikalische Adresse programmieren" and the programming LED of
  a scanned device. Off, these commands are disabled with the reason; every write goes through the one
  class `CalimeroWriter` (`doc/programming.md`), which refuses without the setting.
- `tl_ehome_local_network` (default `true`, haumacher/ehome #150): whether this installation reaches
  the local networks of its server. A local installation finds its gateway without configuration; a
  hosted instance sets `tl_ehome_local_network=false`. Off:
  - the views do not offer the gateway search (the page "Discover" says why instead, see
    `doc/tl-todo.md`; the network interfaces of the server are not listed either), the entry of a
    gateway address ("Gateway wählen"), the search for controllers, and host, port and token of a
    controller in "Connect to a controller";
  - the server refuses the same paths with the reason "This installation does not reach local networks;
    connect through the controller." ("Diese Installation erreicht keine lokalen Netze; bitte über den
    Controller verbinden."), also for a command sent without the view, a TL-Script call or a tool of
    the assistant or the MCP endpoint: the gateway search, every connection to a gateway address
    (group monitor, programming, comparison, scan, read-back, LED), the controller search (mDNS) and a
    controller reached by host and token (status, deployment and the monitor stream over HTTP);
  - a gateway address a project brings (an import, an earlier setting) stays as data; using it is
    refused with the reason;
  - what goes over the connection an enrolled controller opens itself stays: its status, the
    deployment, its Zigbee devices and the group monitor through the controller (`doc/controllers.md`).

## Passkeys

Users sign in without a password with a passkey (the engine module `tl-security-passkey`,
haumacher/ehome #86): each user adds one in the settings (Security, "Sign in with passkey"), the
login page offers "Sign in with passkey", an administrator sees and deletes the passkeys of every
account in the account administration. A passkey is bound to the host of the address the users open
in the browser, which the installation states and nothing derives: set `tl_host` in
`/etc/ehome/ehome.env` to that address, e.g. `tl_host=https://ehome.example.org` (the public `https`
address of the nginx in front of Tomcat; the file has a commented example line), and apply it with
`dpkg-reconfigure ehome`. It becomes a JNDI entry of E-Home's own context, never a setting of
Tomcat's JVM. Changing the host later invalidates every registered passkey. Without `tl_host` the
sign-in works with passwords as before, and the passkey buttons are disabled with the reason
"Passkeys are not set up for this installation"; opened by another address than `tl_host` (e.g. the
LAN address over `http`), they are disabled too.

## Assistant and MCP endpoint

The assistant in the app (`doc/ai.md`) needs the API key of Anthropic: set
`tl_anthropic_api_key=sk-ant-...` in `/etc/ehome/ehome.env` (a commented line of the file) and apply it with `dpkg-reconfigure ehome`. Like
every setting it becomes a JNDI entry of E-Home's own context, never a variable of Tomcat's JVM, and
`ehome.env` keeps it readable by root only (the context descriptor by root and Tomcat). Without a key the chat answers that the key is
missing; nothing is sent anywhere. `tl_anthropic_models` and `tl_ai_default_model` choose other
models. The use is billed to the account of the key.

The MCP endpoint for external agents (`/ehome/mcp`, `doc/ai.md`) is on and admits the OAuth sign-in
with the E-Home account (E-Home is its own authorization server, TL #30274) and personal access
tokens. Behind the nginx of the example, its location `/ehome/mcp` passes the server-sent events
unbuffered; a proxy of its own needs the same (`proxy_buffering off` or its equivalent).

The sign-in needs from the installation:

- **TLS.** OAuth 2.1 refuses the authorization over plain `http` on any host but the loopback
  interface: the sign-in works through the `https` address of the proxy, not over the LAN address of
  Tomcat (there, a client uses a personal access token).
- **The public address.** The issuer and the resource in the metadata are the address of the
  request (the proxy's `Host` and `X-Forwarded-Proto`, which the context's `RemoteIpValve` applies)
  or `tl_host` when it is set; a client requires the issuer to be exactly the address it asked for.
  Set `tl_host` to the `https` address users open (as for the passkeys, above), so that no proxy
  setting can make the documents name another one.
- **The well-known paths at the root of the host.** A client derives the metadata locations by
  inserting the well-known path before the context path (RFC 8414, RFC 9728):
  `https://<host>/.well-known/oauth-authorization-server/ehome`,
  `https://<host>/.well-known/oauth-protected-resource/ehome/mcp`. These requests reach the root of
  the host, not E-Home's context; the proxy forwards them to `/ehome/.well-known/<type>[/rest]`
  (the location `~ ^/\.well-known/([^/]+)/ehome(/.*)?$` of the example). It forwards, never
  redirects, and answers any other path below `/.well-known/` with `404` rather than sending it to
  E-Home, so that a client goes on to its next candidate. (Claude Code takes the protected resource
  metadata from the challenge of the `401` and finds the server's metadata at
  `/ehome/.well-known/openid-configuration` even without the forwarding; another client may not.)
- Nothing else: the consent page is a page of E-Home behind its login, the data are tables of
  E-Home's database (created at the first start of a version with the sign-in), and the engine
  removes expired codes, tokens, unused registrations and authorizations every hour.
