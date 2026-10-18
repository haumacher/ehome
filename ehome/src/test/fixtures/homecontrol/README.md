# HomeControl fixtures

Verbatim copies of `spec/fixtures/images/` and `spec/fixtures/rules/` of the HomeControl
repository (haumacher/homecontrol, private), commit `cf700148aa4a788ce8dd0439cfaccf5ded2794fc`
(2026-10-18). The image fixtures last changed in `90b7b747d694734a0283192a8a0ec03118eb94f0`
(2026-10-17, "Runtime image: the deployment descriptor, its loader and the push API"), the rule
corpus with the spec decisions of round two (`8d901491e7c3ded8d0880830b7ebc0be6013ddc8`,
2026-10-18, "Rule spec decisions, round two: the read action, the silence trigger, parameters as
datapoints").
The formats are `spec/runtime-image.md` (schema 1.0), `spec/rules.md` and `spec/schedules.md` of
that commit.

- `images/example.json`, `images/minimal.json`: complete and minimal images.
- `images/test-system.template.json`: the hand-written template for the KNX test system
  (`samples/KNX-Testsystem.knxproj`), compared with E-Home's export in
  `test.de.haumacher.ehome.runtime.TestRuntimeImage`.
- `images/cases/*.json`: the conformance cases E-Home's validator
  (`de.haumacher.ehome.runtime.ImageValidator`) passes, run by
  `test.de.haumacher.ehome.runtime.TestImageConformance`. A case expects a valid image or the kind
  of refusal and each refusal by its path and a part of its reason. The rules and schedules are
  checked by the loaders of `de.haumacher.ehome.rules`.
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
