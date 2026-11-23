# HomeControl fixtures

Verbatim copies of `spec/fixtures/images/`, `spec/fixtures/rules/` and of
`spec/fixtures/signatures/vectors.json` of the HomeControl repository (haumacher/homecontrol,
private), commit `0b2bb2997a790163b1b67b14ffffd4c4472d8322` (2026-10-19, "Ed25519 signatures: the
installation key paired at enrollment, signed runtime images, signed update manifests", which added
`images/example.json.sig` and `signatures/vectors.json` and changed nothing else of the copies). Before that, the image fixtures last changed in `1a317bece91857c6aad9160d50c021095a1781ab`
(2026-10-18, "Zigbee bindings carry reporting overrides": `images/zigbee-bindings.json` and the
cases `accept-zigbee-reporting.json`, `refuse-zigbee-reporting.json`), the rule
corpus with the spec decisions of round two (`8d901491e7c3ded8d0880830b7ebc0be6013ddc8`,
2026-10-18, "Rule spec decisions, round two: the read action, the silence trigger, parameters as
datapoints").
Synced again at `08fa4138537a44aad9c016084011cd94b350d13d` (2026-11-04, "Blocks (#60): virtual
devices on the controller, schema 1.3, the room controller"), which added `blocks/` (the block
conformance corpus, 25 cases) and brought `images/pages.json` and `images/ehome-72-pages.json`
along; and at `36bbcd10a5ab457812692d72dc9aa5d6d5c872b3` (2026-11-04, "Blocks (#60): the runtime
follows spec 6.1 and B13 where E-Home's port found it did not"), which added the case
`26-fault-disabled-swap`.
Synced again at `6ccddb4538bf4a775503973ee876c4b7317b2d38` (2026-11-04, "Blocks: heating demand
(#61) and heating curve (#62)", `spec/blocks.md` 16, 17 and B20), which added the cases
`27-demand-hysteresis-min-times` to `41-demand-image-swap-keeps-hold` and changed the corpus `README.md`; the
image and rule fixtures did not change.
Synced again at `bdaf0bcda6d4aab19801cbce24f55c0e259c84c2` (2026-11-04, "Blocks: sun shading (#63)",
`spec/blocks.md` 18, B21, B22, 13.1 and 13.2), which added the cases `42-shading-sun-on-facade` to
`54-shading-sensors-one-by-one` (with the case member `location`) and changed the corpus
`README.md`; the image and rule fixtures did not change.
Synced again at `83781d5d51fd16e56e40558eee615526bdd92311` (2026-11-04, "Blocks: house mode
(#64)", `spec/blocks.md` 19), which added the cases `55-house-away-return` to
`61-house-room-chain` and changed the corpus `README.md`; the image and rule fixtures did not change.
Synced again at `85c2d7fd00db880f26a8be763db7ed6a3a04dbf6` (2026-11-04, "Blocks: PV surplus control (#65)", `spec/blocks.md` 20), which added the cases
`62-pv-priority-shedding` to `74-load-refused-pv-must-run` and changed the corpus `README.md`; the
image and rule fixtures did not change.
Synced again at `b70be27cf2357c1ea747da616d3156ba373b0107` (2026-11-04, the Zigbee remotes as
buttons, `spec/runtime-image.md` 3.5, schema 1.4), which added the cases
`images/cases/accept-button-presses.json` and `images/cases/refuse-button-presses.json`; the rule
and block fixtures did not change.
Synced again at `c4de11b5ea1d9522f3cc5b8ea954f85a6ea480bb` (2026-11-04, the rule functions
`sunAzimuth()` and `sunElevation()`, `spec/rules.md` 4.7), which added the cases `59-sun-position` to
`64-sun-follow-timer` (with the case member `location`) and changed the rule corpus `README.md`; the
image and block fixtures did not change.
Synced again at `9c3a1f147d0f2c113e687afc38478f685c63badb` (2026-11-08, "Rules: parameter ids of
the rule-id characters (A-Z a-z 0-9 _ -), referenced as $"…", schema 1.5", haumacher/homecontrol#81),
which added the rule cases `65-parameter-id-quoted` to
`71-load-refused-parameter-id-keyword`, the image cases `images/cases/accept-parameter-ids.json` and
`images/cases/refuse-parameter-id.json` and changed the rule corpus `README.md`; the block fixtures
did not change.
Synced again with the schema 1.6 round of haumacher/homecontrol (haumacher/ehome #134, #136, #138, on
top of `a3f51b988c3dd581278c54fadae0b1813711b3cb`; fb80455), which added the image cases
`images/cases/accept-function-roles-1-6.json` and `images/cases/refuse-function-roles-1-6.json`, changed
`images/cases/refuse-rules.json` (the datapoint a rule may not write is a KNX status now: an application
datapoint is the rules' to write), added the rule cases `72-application-write-not-writable` (with the
datapoint member `application`) and `73-load-refused-not-writable-status` and changed the rule corpus
`README.md`; the block fixtures did not change.
Synced again with the schema 1.7 round of haumacher/homecontrol (#84, haumacher/ehome #140: time
programs, the timelines of rules with states and the state datapoints; from the working tree on top of
`ef0b105609ac8efccc42ed82157ff2b165704c75`), which added the rule cases `74-timeline-heating-week` to
`101-timeline-enable-override-watch` (with the steps `clockStep` and `setTimeline`, `final.timelines`,
the persisted `<rule>/.timeline` and the refusal's `path` and `text`), the image
`images/heating-timeline.json` (E-Home's export of the heating program equals it,
`test.de.haumacher.ehome.runtime.TestTimelines`) and the image case
`images/cases/refuse-rule-timeline.json`, and changed the rule corpus `README.md`; the block fixtures
did not change.
Synced again with the schema 1.8 round of haumacher/homecontrol (#91, haumacher/ehome #184: the
minimum role of a control page, binary format 1.1 with the sections of the anonymous pages; from the
working tree on top of `87a965ad55e3ff873d19f4f9383590f3cf9d9711`), which added the image
`images/anonymous-pages.json` alone (its `.bin` is compared by
`test.de.haumacher.ehome.runtime.binary.TestImageCorpus`); the rule and block fixtures did not change.
Synced again at `02a4b7d17fe0cc21a58d199835bfcffb959c1277` (haumacher/homecontrol#97, haumacher/ehome #204: a rule's last error
names the expression and its first datapoint without a value, `spec/rules.md` 9.1), which changed the
rule cases `13-null-condition`, `14-null-stale-known-lastknown`, `15-eval-error-once` and
`61-sun-position-no-clock` (the log entries `conditionNoValue`, `valueNoValue` and `evalError` with the
member `datapoint`, `null` for none) and the rule corpus `README.md` (which brought along its section
"The compiled images" of the binary image round, homecontrol #87); the image and block fixtures did not
change.
Synced again at `50da03de90441bed100db2dbb28ab3b0c5ce6869` (2026-11-20, haumacher/homecontrol#100: a
restored persisted value is good after a reboot, for every technology, `spec/rules.md` 3.3 and 7.10),
which added the rule case `102-persisted-value-reboot` (with the datapoint member `persist`) and changed
the rule corpus `README.md` (the member `persist` and the step `reboot`); the image and block fixtures
did not change.
Synced again at `dd11feab5aa0f1455fea1560154984d5fdec9f49` (2026-11-20, haumacher/homecontrol#102,
haumacher/ehome #210: only managed block state is persisted, written 10 s after it changes, no
`persistEvery`, `spec/blocks.md` B4, B13, 8.1; the copied files are those of
`c1d835b22fe5cc89664058edc42dcbff08e7a6d5`, dd11fea added only the compiled image of case 75), which
changed the block cases `09-fault-failsafe-restart`, `10-disable-enable-failsafe`,
`12-image-swap-drops-state`, `15-persistence-reboot`, `25-room-integral-reboot`,
`30-demand-offset-persist`, `35-curve-filter-persist`, `36-curve-summer-switch`,
`50-shading-min-interval-reboot`, `59-house-reboot-since` and `71-pv-failsafe-swap-reboot`, added
`75-house-reboot-managed-state` and changed the block corpus `README.md` (records holding exactly the
listed fields, the check member `stored`, `records` `{}` as no record; it brought along its section
"The compiled images" of the binary image round, homecontrol #87); the image and rule fixtures did not
change.
Synced again at `69d31f5a059d793fd1285d2cb0eb53d5f7742337` (2026-11-22, haumacher/homecontrol#104),
which changed `75-house-reboot-managed-state` (checks of the stored records 9 s after each change, before
the 10 s delay is over) and its row in the block corpus `README.md`; its rules and the compiled image did
not change.
Synced again with the schema 1.9 round of haumacher/homecontrol (#107, haumacher/ehome #217: the initial
value of an application datapoint, the member `initial`, binary format 1.2; from the working tree on top
of `e4617cdec954b87c39d5444da29b752c6084e72c`, commit `236fc8b3787edbf1ee9d499abceb5df98f4eb445`), which added the image cases
`images/cases/accept-initial-values.json` (its `.bin` is compared by
`test.de.haumacher.ehome.runtime.binary.TestImageCorpus`) and `images/cases/refuse-initial-values.json`;
the rule and block fixtures did not change.
Synced again at `4b729b1f059b187e7a0747fef4f0f53f78c7c78d` (2026-11-23, haumacher/homecontrol#116: the `final` and `check` member
`lastError`, a rule's last error and whether it is resolved, `spec/rules.md` 9.1), which added the rule
cases `103-last-error-condition-resolved`, `104-last-error-reaction-site` and
`105-last-error-silence-armed` and changed the rule corpus `README.md`; the image and block fixtures did
not change.
The formats are `spec/runtime-image.md` (schema 1.0), `spec/rules.md` and `spec/schedules.md` of
that commit.

- `images/example.json`, `images/minimal.json`: complete and minimal images.
- `images/example.json.sig`: the detached signature (context `v1`) of `images/example.json`, history
  since the binary image (homecontrol #87) carries its signature inside, and
  `signatures/vectors.json`: the test keys (TEST KEYS ONLY: their seeds are public), their
  fingerprints and RFC 8032 section 7.1 test 1, checked in
  `test.de.haumacher.ehome.connection.TestImageSignatures`; the signed worked example
  `minimal.signed.bin` of homecontrol `spec/runtime-image.md` 13 is reproduced byte for byte in
  `test.de.haumacher.ehome.runtime.binary.TestImageCompiler`.
- The compiled corpora (homecontrol `spec/runtime-image.md` 13.1: the `.bin` files and the mutation
  corpus `images/binary/`) are not copied: `test.de.haumacher.ehome.runtime.binary.TestImageCorpus`
  compiles the sources of the homecontrol checkout beside this repository and compares them with its
  committed files, or writes them there (`-Dhomecontrol.regenerate=true`). The update manifest and its signature
  (`signatures/manifest.*`, the release key's) are HomeControl's release process and not copied.
- `images/zigbee-bindings.json`: Zigbee bindings without reporting overrides (an image written
  before the member existed), valid in `test.de.haumacher.ehome.runtime.TestImageFixtures`.
- `images/test-system.template.json`: the hand-written template for the KNX test system
  (`samples/KNX-Testsystem.knxproj`), compared with E-Home's export in
  `test.de.haumacher.ehome.runtime.TestRuntimeImage`.
- `images/cases/*.json`: the conformance cases E-Home's validator
  (`de.haumacher.ehome.runtime.ImageValidator`) passes, run by
  `test.de.haumacher.ehome.runtime.TestImageConformance`. A case expects a valid image or the kind
  of refusal and each refusal by its path and a part of its reason. The rules and schedules are
  checked by the loaders of `de.haumacher.ehome.rules`.
- `blocks/*.json`: the block conformance corpus (format in `blocks/README.md`, homecontrol
  `spec/blocks.md`), which E-Home's block runtime (`de.haumacher.ehome.blocks`) passes inside the
  reference interpreter, run by `test.de.haumacher.ehome.blocks.TestBlockCorpus` (one test per case,
  with the runner of the rule corpus).
- `rules/*.json`: the rule conformance corpus (format in `rules/README.md`), which E-Home's reference
  interpreter (`de.haumacher.ehome.rules`) passes as the controller's does, run by
  `test.de.haumacher.ehome.rules.TestRuleCorpus` (one test per case).

Do not edit the files: a case that looks wrong is an issue at haumacher/homecontrol. To refresh
them, from the root of this repository:

```sh
gh repo clone haumacher/homecontrol <dir>   # or git -C <dir> pull
bin/sync-homecontrol-fixtures.sh <dir>
```

The script replaces the copies and prints the commit; write it into this file. The DPT corpus has
its own copy and script (`src/test/fixtures/dpt-corpus/`, `bin/sync-dpt-corpus.sh`).
