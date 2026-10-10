# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

E-Home: a TopLogic (7.10.2, Java 17) web app that imports KNX ETS projects (`.knxproj`) and discovers KNX gateways (Calimero). Single Maven module at the repository root.

## Build, run, verify

- Build: `mvn install`. The parent `com.top-logic:tl-parent-app` comes from the TopLogic Nexus configured in `~/.m2/settings.xml`.
- Lint: `mvn checkstyle:check` (rules in `checkstyle.xml`: copyright header, ASCII-only, tab indentation, imports, braces). It is not bound to the build, so run it after Java edits.
- Run: `mvn exec:java` starts the app with Jetty on port 8080 (`-Dtl.port=...` to change). Local runs use H2 (`deploy/local`). The launch config `bin/launch/Start E-Home.launch` sets `-Dtl_initial_password=root1234` for the initial `root` login.
- Tests: `mvn test -DskipTests=false` (the TopLogic parent skips tests by default). The generic `test.TestAll` from tl-basic collects every test under `src/test/java` and adds TopLogic conformance checks (copyright comments, normalized layouts, JSPs, I18N). Test classes go in package `test.<package>` and provide `suite()`; for KB/model access, wrap them like `TestEtsImport` (`KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(..., ModelService.Module.INSTANCE))`). Test-only config lives in `src/test/webapp/WEB-INF/conf/`.
- After editing layouts, normalize them with `mvn exec:java@normalize-layouts -DskipNormalizeLayouts=false`, otherwise `TestLayoutsNormalized` fails.
- Verify behavior changes by running the tests, then starting the app and checking it in the browser. For ETS import, use the sample projects in `samples/` (`Test.knxproj` has a single device).

## Generated code

- The Java model bindings in `src/main/java/de/haumacher/ehome/model/{core,operational}/` are generated from `src/main/webapp/WEB-INF/model/ehome.{core,operational}.model.xml` by `tl:generate-java`, which is bound to `generate-sources` (configured in `pom.xml`). Never hand-edit `internal/*Base.java`, `*Constants.java` (deprecated stubs), or the `*Factory.java` files.
- The generator reads the model from the project artifact installed in `~/.m2`, not from the sources. After a model change, run `mvn install` twice (the first installs the new XML, the second regenerates). If the stale wrappers no longer compile, install once with `-Dmaven.main.skip`, then build normally.
- The plain `X.java` / `internal/XImpl.java` wrappers are generated only once and are meant for hand-written code.
- Layouts (`WEB-INF/layouts/**`), `WEB-INF/autoconf/*.config.xml`, model XML, and `.properties` resources can be edited directly as text.

## Stale files (ignore)

- `bin/build.xml` (old Ant build), `bin/launch/Generate ehome.operational.launch` (old layout, references a nonexistent `ehome.model.xml`), and `.factorypath` (wrong user and version).

## Code style

- Java source encoding is ISO-8859-1 (Eclipse project setting). Keep Java files ASCII-only and use `\uXXXX` escapes for non-ASCII characters.
- Indent with tabs. Every Java file starts with:
  ```
  /*
   * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
   */
  ```
- Put UI texts in `I18NConstants` classes as `ResKey` fields, with the English text in an `@en` javadoc tag (TopLogic convention).
