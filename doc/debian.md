# The Debian package

E-Home installs on Debian 12 (bookworm) or newer, and on Ubuntu 24.04, as the package `ehome`. It runs
the war in the distribution's Tomcat 10 (`tomcat10`, Jakarta EE 10, the WebSocket of the controllers
included) and keeps its data in an H2 database under `/var/lib/ehome`.

## Building

The CI workflow `.github/workflows/debian-package.yml` builds the package on every push to `master`
(an artifact `ehome-deb` of the run) and publishes it as a GitHub release for a tag `vX.Y.Z`. By hand,
in the repository root:

```bash
./mvnw install -Pproduction,debian -DskipTests=false   # the app alone, tested; the war of the profile debian
bin/build-deb.sh                                       # ehome/target/ehome_<version>_all.deb
```

The profile `debian` of `ehome/pom.xml` builds a war of its own: the deploy aspect `deploy/debian`
selects the H2 connection pool (`db-h2.xml`) instead of the container's JNDI data source that the
engine adds to every war (`tl.app.metaConfAddons`), leaves out the debug settings of `deploy/local`,
and puts the H2 driver into `WEB-INF/lib`. Without a tag on `HEAD` the package version names the
commit: `1.0.0~git20261025.91d27ec` for the app version `1.0.0-SNAPSHOT`, older than a later release
`1.0.0`. The tooling (`ehome-virtual`) is never part of it.

## Installing

```bash
sudo apt install ./ehome_<version>_all.deb
sudo grep tl_initial_password /etc/ehome/ehome.env
```

The app is at `http://<host>:8080/ehome/view/`; log in as `root` with the password of
`/etc/ehome/ehome.env`, generated at the first installation and applied only when the database is
created at the first start (about a minute). Change it in the application afterwards.

| Path | What |
| --- | --- |
| `/usr/share/ehome/ehome.war` | The app. |
| `/etc/tomcat10/Catalina/localhost/ehome.xml` | Tomcat's context `/ehome`, written by `postinst` while Tomcat is stopped (a running Tomcat would deploy it at once, without the settings). |
| `/usr/lib/systemd/system/tomcat10.service.d/ehome.conf` | Gives Tomcat the settings (`EnvironmentFile`) and write access to the data (`ReadWritePaths`; `tomcat10.service` runs with `ProtectSystem=strict`). |
| `/etc/ehome/ehome.env` | The settings: `tl_storage_path`, `h2_name`, `tl_initial_password`; created once, owned by the administrator afterwards. Further TopLogic variables (`tl_*`) go there too; `systemctl restart tomcat10` after a change. |
| `/var/lib/ehome` | The data: `h2.mv.db`, documents (`var/`), logs (`logs/top-logic.main.log`). |

An upgrade replaces the war, restarts Tomcat and keeps the settings and the data (the database is
migrated at the start). `apt remove` undeploys the app; `apt purge` also deletes the settings. The data
in `/var/lib/ehome` are never deleted by the package.

Tomcat's heap is the JVM's default (a quarter of the memory); set `JAVA_OPTS` in
`/etc/default/tomcat10` for more. TLS and a public host name (the controllers connect to
`wss://<host>/ehome/api/controllers/<id>/socket`, `doc/controllers.md`) belong to a reverse proxy in
front of Tomcat.
