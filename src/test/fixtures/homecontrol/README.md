# HomeControl runtime image fixtures

Verbatim copies of `spec/fixtures/images/` of the HomeControl repository (haumacher/homecontrol,
private), commit `c0f8891ee7499e97e37ba7f3f7dcd52af0fa979a` (2026-10-18, "Roadmap: the decisions of
the first day recorded"). The format is `spec/runtime-image.md` of that commit, schema 1.0.

- `images/example.json`, `images/minimal.json`: complete and minimal images.
- `images/test-system.template.json`: the hand-written template for the KNX test system
  (`samples/KNX-Testsystem.knxproj`), compared with E-Home's export in
  `test.de.haumacher.ehome.runtime.TestRuntimeImage`.
- `images/cases/*.json`: the conformance cases E-Home's validator
  (`de.haumacher.ehome.runtime.ImageValidator`) passes, run by
  `test.de.haumacher.ehome.runtime.TestImageConformance`. A case expects a valid image or the kind
  of refusal and each refusal by its path and a part of its reason.

Do not edit the files; update them by copying the directory again and naming the new commit here.
