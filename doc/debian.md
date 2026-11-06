# The Debian package

E-Home installs on Debian 13 (trixie) or newer, and on Ubuntu 24.04, as the package `ehome`. It runs
the war in the distribution's Tomcat 10 (`tomcat10`, Jakarta EE 10, the WebSocket of the controllers
included) on Java 21 (`java21-runtime-headless`, which TopLogic 8 requires; Debian 12 has
no Java 21 of its own) and keeps its data in an H2 database under `/var/lib/ehome`.

## Building

The CI workflow `.github/workflows/debian-package.yml` builds the package on every push to `master`
(an artifact `ehome-deb` of the run, and the suite `snapshot` of the apt repository) and publishes it
as a GitHub release and in the suite `stable` for a tag `vX.Y.Z` (see "The apt repository" below). By
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

GitHub Pages serves a signed apt repository at <https://haumacher.github.io/ehome/>, with two suites:
`stable` (the releases, tags `vX.Y.Z`) and `snapshot` (every push to `master`). Each holds the newest
two packages (a package is about 130 MB, a Pages site at most 1 GB).

```bash
sudo curl -fsSLo /usr/share/keyrings/ehome-archive-keyring.gpg https://haumacher.github.io/ehome/ehome-archive-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/ehome-archive-keyring.gpg] https://haumacher.github.io/ehome stable main" \
  | sudo tee /etc/apt/sources.list.d/ehome.list
sudo apt update && sudo apt install ehome
```

(`snapshot` in place of `stable` follows `master`.) The job `apt` of the workflow builds the whole
site with `bin/apt-repo.sh`: it takes the packages of `stable` from the newest GitHub releases (the
suite's source of truth) and those of `snapshot` from the live site (checked against their SHA-256 in
the published index), adds the new one to its suite, drops the older ones,
writes the indexes with `apt-ftparchive` (`binary-all` and, identical, `binary-amd64` and
`binary-arm64`) and signs `Release` (`InRelease`, `Release.gpg`) with the key of the repository
secrets `APT_SIGNING_KEY` (the secret key, armored or base64) and `APT_SIGNING_PASSPHRASE`; the public
key is published beside it (`ehome-archive-keyring.gpg`, `.asc`), with an `index.html` naming its
fingerprint. The site is deployed with `actions/deploy-pages` (Pages source "GitHub Actions"; the
environment `github-pages` admits `master` and the tags `v*`), never as a branch: a package is above
the 100 MB of a file in git. Runs deploy one after the other (concurrency group `apt-repository`).
Pages takes only the first deployment of a commit: a second one reports success and changes nothing.
So the run of a push to `master` does not deploy a commit that carries a release tag, and the tag's
run does (push the release commit and its tag together, `git push origin master vX.Y.Z`). A tag set
later on a commit `master` already deployed is published by the next push to `master`, whose site
takes `stable` from the releases. With 1.0.0 the master run of the release commit deployed first and
left `stable` empty; its tag run's deployment did not take effect.

## Installing

```bash
sudo apt install ehome                       # from the apt repository, or a downloaded package:
sudo apt install ./ehome_<version>_all.deb
sudo grep tl_initial_password /etc/ehome/ehome.env
```

The app is at `http://<host>:8080/ehome/view/`; log in as `root` with the password of
`/etc/ehome/ehome.env`, generated at the first installation and applied only when the database is
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

`tomcat10.service` runs with `ProtectSystem=strict`: the drop-in grants `/var/lib/ehome` from
Tomcat's next start, and a Tomcat that runs already gets the directory bound into its namespace with
`systemctl bind tomcat10 /var/lib/ehome` (systemd 248 or newer; Debian 13 has 257). Where that is not
possible, the package installs without deploying and says what to do. E-Home shares the heap with the
other applications of the Tomcat; sizing it is the administrator's choice (`JAVA_OPTS` below).

| Path | What |
| --- | --- |
| `/usr/share/ehome/ehome.war` | The app. |
| `/etc/tomcat10/Catalina/localhost/ehome.xml` | Tomcat's context `/ehome` with the settings as `<Environment name="tl/...">` entries, written by `postinst` from the template `/usr/share/ehome/tomcat/ehome.xml` and `ehome.env` (mode 0640, `root:tomcat`: it holds the passwords). Writing it deploys the app in the running Tomcat. |
| `/usr/lib/systemd/system/tomcat10.service.d/ehome.conf` | Write access to the data from Tomcat's next start (`ReadWritePaths`); nothing else. |
| `/etc/ehome/ehome.env` | The settings (lines `name=value`): `tl_storage_path`, `h2_name`, `h2_passwd` (the password of the database, generated with the file), `tl_initial_password`; created once, owned by the administrator afterwards. Further TopLogic settings go there too; `dpkg-reconfigure ehome` after a change (deploys the app again, Tomcat keeps running). Tomcat never reads this file. |
| `/var/lib/ehome` | The data: `h2.mv.db`, documents (`var/`), logs (`logs/top-logic.main.log`). |

## Upgrading

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

Verified in a Debian 13 container with systemd and a running `tomcat10` (2026-11-04, 1.0.1): a first
installation and an upgrade from 1.0.0 left Tomcat's process (same PID) and its ROOT application
running, the first installation bound `/var/lib/ehome` into it, and the Tomcat process has no `tl_` or
`h2_` variable. Earlier, in a Debian 13 container with systemd and `tomcat10` (2026-11-03): the package of 32788e6
installed, a project created, upgraded twice (from a file, then from a local copy of the repository
with `apt-get install --only-upgrade`): Tomcat kept its process, each upgrade left a backup, the
project was there after both, and the model upgrade only added the module `tl.agent`.

## Settings and data

The upgrade keeps the settings and the data. The engine has no default for the database password since TopLogic
8.0.0-alpha12 (named secrets): an upgrade from an earlier package adds `h2_passwd` with the former
default `theautomatedappengine`, which the existing database was created with, to settings that lack
it. `apt remove` undeploys the app; `apt purge` also deletes the settings. As an upgrade does,
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
`RemoteIpValve`, which takes the client's address and scheme from the forwarded headers of a proxy
connecting from the loopback interface only, so that the enrollment's rate limit counts the clients;
a proxy on another host needs its address in the valve's `internalProxies` (the context is rewritten
by every upgrade of the package).
