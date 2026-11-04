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
The formats are `spec/runtime-image.md` (schema 1.0), `spec/rules.md` and `spec/schedules.md` of
that commit.

- `images/example.json`, `images/minimal.json`: complete and minimal images.
- `images/example.json.sig`: the detached signature of `images/example.json` with the test
  installation key (homecontrol `spec/api.md`, "Signed runtime images"), and
  `signatures/vectors.json`: the test keys (TEST KEYS ONLY: their seeds are public), their
  fingerprints, the digest, message and signatures of `example.json` and RFC 8032 section 7.1 test 1;
  E-Home's signing reproduces them byte for byte in
  `test.de.haumacher.ehome.connection.TestImageSignatures`. The update manifest and its signature
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
