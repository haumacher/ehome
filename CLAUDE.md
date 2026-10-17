# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

E-Home: a TopLogic (8.0.0-alpha10, Java 17) web app that imports KNX ETS projects (`.knxproj`) and discovers KNX gateways (Calimero). Single Maven module at the repository root. The app has its own version (`1.0.0-SNAPSHOT`), independent of the TopLogic version.

## Build, run, verify

- Maven 3.9.16: the wrapper `./mvnw`, or `mvn` from `~/tools/maven` (first on the PATH via `~/.bashrc` and `~/.profile`). TopLogic 8 requires Maven >= 3.8.9; the distribution's `/usr/bin/mvn` is older and must not come first on the PATH.
- Build: `./mvnw install`. The parent `com.top-logic:tl-parent-app` comes from the TopLogic Nexus repositories declared in `pom.xml`.
- Lint: `./mvnw checkstyle:check` (rules in `checkstyle.xml`: copyright header, tab indentation, imports, braces). It is not bound to the build, so run it after Java edits.
- Run: `./mvnw exec:java` starts the app with Jetty on port 8080 (`-Dtl.port=...` to change); `pom.xml` sets `tl_operation_mode=development` for it (without it, TopLogic 8 runs in production mode). Local runs use H2 (`deploy/local`, data in `tmp/`). The knowledge base runs without branch support (`top-logic.singleBranch.config.xml` in `WEB-INF/conf/metaConf.txt`; see `doc/tl-todo.md`, TL #29876): a database created with branches does not fit and needs a fresh `tmp/`. The launch config `bin/launch/Start E-Home.launch` sets `-Dtl_initial_password=root1234` for the initial `root` login. The context path is `ehome` (`tl.contextPath` in `pom.xml`, also the name of the deployable war `ehome.war`); the UI is at `/ehome/view/` (the classic `/servlet/LayoutServlet` only shows the engine's administration); log in through the in-app dialog (there is no `login.jsp` any more).
- Model access rights are deny-by-default in TopLogic 8 and there is no `<security-config>` yet: only administrators (`root`) see data. Add grants for the `ehome.*` modules before creating non-admin users.
- Tests: `./mvnw test -DskipTests=false` (the TopLogic parent skips tests by default). The generic `test.TestAll` from tl-basic collects every test under `src/test/java` and adds TopLogic conformance checks (copyright comments, normalized layouts, JSPs, I18N). Test classes go in package `test.<package>` and provide `suite()`; for KB/model access, wrap them like `TestEtsImport` (`KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(..., ModelService.Module.INSTANCE))`). Test-only config lives in `src/test/webapp/WEB-INF/conf/`.
- `test.de.haumacher.ehome.roundtrip.TestRoundTrip` (the `.knxproj` export, `doc/knx/export.md`) empties the E-Home data of the shared test database before each sample; it must remain the last test using that data (tests sort by package). What the round trip may lose is listed in `doc/knx/not-modeled.txt`; its exports and differences land in `target/roundtrip/`.
- Derived attributes (`<storage-algorithm><query>`) are computed with the reading user's access rights. A test runs without a person and reads them as `null`; read them as `root` (see `TestEtsImport.derived`).
- Verify behavior changes by running the tests, then starting the app and checking it in the browser. For ETS import, use the sample projects in `samples/` (`Test.knxproj` has a single device).
- Commit to `master` and push to `origin/master` after every verified change (standing directive). Feature branches and pull requests are for external contributors. The working method (agent briefs, probe review, gate, run loop) is the skill `ehome-workflow` in `.claude/skills/`.

## Generated code

- The Java model bindings in `src/main/java/de/haumacher/ehome/model/{core,operational}/` are generated from `src/main/webapp/WEB-INF/model/ehome.{core,operational}.model.xml` by `tl:generate-java` (configured in `pom.xml`, not bound to the build). Never hand-edit `internal/*Base.java`, `*Constants.java` (deprecated stubs), or the `*Factory.java` files.
- The generator reads the model and the engine's web fragments from the artifacts installed in `~/.m2`, not from the sources. After a model change, run `./mvnw install -DskipTests`, then `./mvnw tl:generate-java`, then build again. If the existing wrappers do not compile against the changed model, add `-Dmaven.main.skip` to the first install.
- The plain `X.java` / `internal/XImpl.java` wrappers are generated only once and are meant for hand-written code.
- Views (`WEB-INF/views/**`), `WEB-INF/autoconf/*.config.xml`, model XML, and `.properties` resources can be edited directly as text.

## UI (TL views)

- The UI lives in `src/main/webapp/WEB-INF/views/*.view.xml` (module `tl-layout-view`); the app has no classic layouts (`WEB-INF/layouts/`) any more. Read the `view-layer` chapter of the developer docs (tl-mcp `list_docs`/`read_doc`) before editing views; `tl-demo-react` in the engine sources is the reference application.
- `app.view.xml` is an overlay of the engine's shell (sidebar items). Structure trees create nodes through `create-node.view.xml` and the `new` command clique (configured in `WEB-INF/conf/ehomeConf.config.xml`, as is the TL-Script function `knxNetworkInterfaces()`).
- App-specific view actions are Java classes with a `@TagName` config (`import-ets-project`, `discover-knx-gateways`).
- A changed `.view.xml` is not picked up by a running app; restart it.
- `doc/knx/schema-coverage.md` tracks which parts of the KNX project schema the model covers; extend the model there in its own terms, not as a copy of the XML.
- `doc/tl-todo.md` lists the gaps that wait for a newer TopLogic version (tree tables, Excel export, TL #29791). Engine gaps and defects get a TL ticket, a minimal workaround (or none, leaving the problem visible) and an entry there. A TopLogic upgrade happens only when the user asks for one; then check every entry and undo the workarounds whose fix has shipped. Do not look for newer engine versions or poll the tickets on your own.

## Stale files (ignore)

- `bin/build.xml` (old Ant build), `bin/launch/Generate ehome.operational.launch` (old layout, references a nonexistent `ehome.model.xml`), and `.factorypath` (wrong user and version).

## Code style

- Java sources and `.properties` resource bundles are UTF-8 (TopLogic 8); write umlauts directly, not as `\uXXXX` escapes.
- Indent with tabs. Every Java file starts with:
  ```
  /*
   * Copyright (c) <year the file was created> Bernhard Haumacher
   */
  ```
- Put UI texts in `I18NConstants` classes as `ResKey` fields, with the English text in an `@en` javadoc tag (TopLogic convention).
