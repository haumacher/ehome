# Waiting for TopLogic

Gaps of the React UI (`ehome/src/main/webapp/WEB-INF/views/`; paths below are relative to the app module `ehome/` unless they name `ehome-virtual/`) and
workarounds in the ETS import, that the TopLogic version in use (8.0.0-alpha12) cannot close yet.
Check each item when upgrading TopLogic (an upgrade happens only when the user asks for one). Every
entry names its TL ticket and what to remove once the engine covers it; a gap whose workaround would
not be minimal is left visible instead.

State of the upgrade to 8.0.0-alpha12 (2026-10-30): the fixes of TL #29798, #29812, #29814, #29869,
#29872 to #29874, #29876, #29877, #29880 to #29883, #29886 to #29896, #29898, #29973 to #29983 and
#29990 shipped; their workarounds are removed, except where an entry below says why one stays.
TL #29884 and #29885 are open; TL #29899 is closed as wontfix. TL #30086 to #30088 are new.
`parameterValueType` (TL #29798) stays: the options, constraints and visibility of a parameter value
call it because it reads the values entered in a form, which the derived `valueType` does not.

## ETS import: workarounds for the XML import (TL #29797, #30086, #30087)

The ETS import (`de.haumacher.ehome.importer`, `etsImporter.xml`) has handlers of its own for what
the engine's XML import (`com.top_logic.xio`) cannot express without restating the model. Since
alpha12 it uses `<set-translation>` for the texts, merged `<switch>` cases, warnings on unknown
elements (every construct deliberately not modeled is declared with `<skip-deep/>` at its place) and
adds to set references directly. What stays:

- TL #29974 shipped `<unless-known><update condition=…>`, but giving a known application program the
  texts of the languages it lacks (haumacher/ehome #38) would mean restating the program's whole
  text-carrying structure in `<update>` (about ten element types at several levels, each with
  `<object-ref>` and `<set-translation>`). Workaround: `<unless-known-program>` (`UnlessKnownProgram`,
  `ProgramLanguages`, `TranslationsImport#importBaseTexts`). TL #30086: an update by ID of every
  known descendant without restating the nesting. **When available:** replace `<unless-known-program>`.
- TL #29975 shipped the `partition` of `<resolve-existing>`, but the `objects` of a partition (all
  parts of a program, reached through 24 composite references of `ehome.operational`) cannot be
  computed in TL-Script without naming every composite reference. Workaround: `SharedObjects`, an
  `IdLookup` that `Importer` installs with `ImportContext#withIdLookup`. TL #30087: a TL-Script walk of
  the composition of an object. **When available:** declare the partition in `etsImporter.xml`.
- `<versioned-master-data>` (`VersionedMasterData`, `MasterDataUpdates`, `ObjectMerge`) imports a known
  mask version as a transient candidate (`JoiningModelBinding#setTransient`) and, at the end of the
  import, updates the known one in place from it (newer master data) or compares and warns (older or
  the same). The engine has no import of an element into a transient candidate of a known object
  (TL #29797, shipped in part).
- `<translations>` (`TranslationsImport`) reads the `Languages` sections: the attribute a translation
  goes to depends on the type of the object (`Text` is the description of a parameter, the name of a
  variant), and a translation of a known program sets only the languages it lacks (#38).
- `<set-translation>` names the fallback language as `default-language="en"` instead of reading the
  application's fallback language.
- An unset boolean reads `false` on stored and transient objects alike since TL #29883; the Zigbee
  import (`ZigbeeImport#set`, `ZigbeeCatalogJson`) still treats a missing flag of an export (`null`)
  as the stored `false`, which is the format's meaning, not a workaround.
- Deleting the parts the import drops one at a time was slow (the referrer scans of TL #29876, since
  fixed); `MasterDataUpdates` deletes them in one `deleteAll`, which stays the cheaper way. The tests
  still avoid deleting shared data (`TestRoundTrip` runs first, `SharedData.resetMaskVersions` updates
  in place).

## Single branch (TL #29876, #29877)

E-Home runs without branches: `top-logic.singleBranch.config.xml` (from tl-core) in
`WEB-INF/conf/metaConf.txt`. Since alpha12 it only sets `multiple-branches="false"`, and H2 databases
switch to the key layout `branch-last` automatically; a database created with branches has its
branch columns removed at the start when it holds only the trunk. The developer article of TL #29877
describes the persistency configuration.

**On the next upgrade:** nothing to undo.

## Live sources of a view (TL #30088)

The group monitor (`projects/monitor.view.xml`, `de.haumacher.ehome.monitor.MonitorSource`), the
simulation (`projects/simulation.view.xml`, `SimulationSource`) and the Zigbee tab
(`projects/zigbee.view.xml`, `ZigbeeSource`) are fed by `<source-channel>`s. A displayed monitor
detaches with the end of its subscription. A monitor hidden in a tab has already ended its
subscription, and the engine tells the source nothing when that window is torn down later (TL #29869
tears it down), so `MonitorSource` checks again at the next bus change, or every 60 s on a quiet bus,
whether the window is still registered. TL #30088: a lifecycle signal of the view instance
independent of the subscription. **When available:** detach on that signal and drop the check.

The virtual device (`ehome-virtual/src/main/webapp/WEB-INF/views/virtual/virtual-device.view.xml`):
its status and the number of services recorded change while a programming tool downloads into it,
but the recorder tells nobody of a change. The view recomputes them when it is displayed and on its
"Refresh" command. A change notification of the recorder would let a source channel follow it (an
app change, no engine gap).

## Access coverage of engine modules (TL #29884)

The engine ships model modules whose types have no complete access definition: at the start of
E-Home, `SecurityCoverageCheck` reports engine types of 15 modules (`Contacts`,
`SearchExpressionStructure`, `SecurityStructure`, `Tag`, `mandatorStructure`, `orgStructure`,
`tl.accounts`, `tl.comments`, `tl.contact.ui`, `tl.core`, `tl.folder`, `tl.model`, `tl.reporting`,
`tl.search`, `tl.units`; e.g. `tl.accounts:Group`, `tl.model:TLClass`: no role source and no read
grant). An application that switches `TestSecurityCoverage` on fails on them although it does not
grant them to its users.

Workaround in `WEB-INF/autoconf/ehome.security.config.xml`: `SecurityCoverageCheck` excludes these
15 modules.

**When fixed** (the engine covers or marks its own modules, or the check can be limited to the
application's modules): remove `excluded-modules`.

## A refused command on a composition part (TL #29885)

Under alpha11 a refused check on an object whose type delegates to its access parent (a device, a
group address, a catalog entry) hid the command instead of disabling it. With the consolidated access
checks of alpha12 (TL #29856) a viewer sees these commands disabled with the reason ("Sie dürfen
dieses Objekt nicht ändern"); four import commands (rules, Zigbee devices, product data, Zigbee
catalog) are disabled with only their own label as the reason. The ticket is still open.

**On the next upgrade:** check the ticket and the import commands' reasons; nothing to remove.

## A refused submit leaves its input editable (TL #30105)

The `<access>` check of a command decides the state of its button (TL #29856); the `<on-submit>`
command of a `<value-input>` has no button, so a refused check leaves the input editable and reports
the refusal only after the submit. Workaround in `projects/device-item.view.xml`: a device's parameter
inputs switch to their read-only form with `!canWrite($device)`, repeating the `<access>` check of
their `<on-submit>` (haumacher/ehome #65).

**When fixed** (the input follows the state of its submit command): remove the `!canWrite($device)`
case of the input kind in `device-item.view.xml`.

## No at-rest encryption out of the box (TL #29899, wontfix)

The secret of an enrolled controller (haumacher/ehome #54) must be readable by E-Home to check the
controller's HMAC, so it cannot be hashed. TopLogic's `EncryptionService` needs a key set up per
installation, and the engine will not provide an attribute storage that encrypts with a key of its
own (TL #29899, wontfix). The secret is stored as is in the hidden attribute `Controller#secret`
with a read grant without roles (only administrators read it through the model), like the controller
token.

## Upstream tickets to follow

- TL #29797: see above (XML import into a transient candidate; `<versioned-master-data>`).
- TL #29884, #29885: see above (access coverage of engine modules, the reason of refused commands).
- TL #30086, #30087: see above (XML import: updating known descendants, the composition of a partition).
- TL #30088: see above (a source is not told of the teardown of a hidden view's window).
- TL #30105: see above (a refused submit leaves its `<value-input>` editable).
