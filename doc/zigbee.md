# Zigbee devices

The HomeControl controller is the Zigbee coordinator: it pairs and interviews the devices in the
field and keeps their database (keys, addresses). E-Home stores what the controller's export carries
(homecontrol `spec/zigbee.md`, "Export") and what the installer adds: a name, the room, and the
bindings of the devices' functions to datapoints of the project, which the runtime image carries as
the datapoints' `zigbee` member. Package `de.haumacher.ehome.zigbee`, model
`WEB-INF/model/ehome.zigbee.model.xml`, view `WEB-INF/views/projects/zigbee.view.xml` (tab "Zigbee"
of a project).

## Getting the devices in (round one, haumacher/ehome #40)

All ways run the same import (`ZigbeeImport`), idempotent by the IEEE address: a device, its
endpoints and functions are updated in place; the user's name, room and bindings survive. A function
the export no longer lists is removed, unless a datapoint is bound to it: then it stays, marked
`absent`. A device the export no longer lists stays (it may belong to another controller).

- **Import Zigbee devices**: the file the controller's Zigbee page downloads.
- **Read Zigbee devices**: the same export read from the project's controller, all pages of
  `GET /api/zigbee/export?from=n` (over the E-Home connection when the controller is connected,
  else over HTTP with host and token; `ZigbeeController`).
- **Open network for pairing** / **Close network**: `POST /api/zigbee/permit` (254 s / 0).

## Live import (round two, haumacher/ehome #64)

While a controller is connected to E-Home (`doc/controllers.md`), its `zigbee` messages
(homecontrol `spec/api.md`, "WebSocket /ws") reach `ZigbeeLive`, a listener of the controller
connections registered at startup (`ZigbeeLiveRegistration` in `WEB-INF/web.xml`):

- `joined`, `interviewed`, `interview_failed`, `left`, `removed`, `changed`, `network` schedule a
  read of the export and the same import as "Read" into every project whose `controller` the
  controller is; the network facts are stored with it (below). The reads of a controller are
  debounced: the first runs 300 ms after the event, the next no earlier than 2 s after the start of
  the previous one, and every event until then is part of it; an event during a read asks for one
  more afterwards.
- `permit` updates only the live state (the countdown).
- Events of a controller no project deploys to are ignored (logged at debug level).
- A failure (the controller refuses, the export cannot be read, the commit fails) is logged with the
  controller's name and the reason and shown in the tab until the next successful read; the devices
  stay as they were.

Threads: the socket thread only updates the live state and schedules. The read runs on the single
thread of `ZigbeeLive` (a request blocks until the socket thread delivers the response, so it must
not run there; on the connections' writer thread it would stall the last-contact updates of every
controller). The import, a write of the knowledge base, runs on the connections' writer thread
(`ControllerConnections.executeAndWait`), the one thread writing what the connections learn.

A controller that connects is not read on its own: the first event reads it, or "Read Zigbee devices".

## What the tab shows

- Above the device table: the network of the project's controller ("Network: PAN 0x1234, channel 15,
  joining open for 83 s more" / "joining closed" / "not formed" / "not read yet"), when the devices
  were last read and whether live or on request, and in red why the last live import failed.
- The column "Status": what the events tell (Interviewing after `joined`/`interviewing`, Interview
  failed, Left the network; `interviewed` and `removed` clear it, `changed` clears a leave), else
  whether the last export said the interview finished (Paired, Not interviewed).
- The functions by their names in the user's language (`ZigbeeStatus.functionLabel`, also in the
  function's `displayName`): on_off, level, color_temperature, temperature, humidity, illuminance,
  occupancy, battery, multistate, action; an IAS zone by the datapoint its zone type gives (contact,
  motion, smoke, water, alarm). A key E-Home does not know (a newer controller's) shows as it is;
  the import never refuses it.
- **Remove absent functions** deletes the selected device's absent functions no datapoint is bound
  to and names the bound ones it keeps; it is disabled, with the reason, when nothing is absent.

Stored and held: the network facts of the last read are attributes of the controller
(`ehome.runtime:Controller#zigbeeNetworkUp`, `zigbeePanId`, `zigbeeChannel`, `zigbeeExtendedPanId`,
`zigbeePermitJoin`, `zigbeeExported` — the export's time, `zigbeeReadLive`); a file import does not
touch them. The live state (the last `permit` with its arrival time, the devices' states, the last
read, the failure) is held in memory per controller, like its online state, and cleared at a
disconnect; the countdown is computed when it is shown.

The tab updates by itself: `<zigbee-updates>` (`ZigbeeUpdatesElement`, built on
`view.ProjectUpdatesElement` like the simulation's `<simulation-updates>`) writes the channel
`refresh` on every change of the live state, at most once a second, and once a second while the
network is open for joining; the texts and the device table are computed again from it, after a
refetch of the knowledge base (the import commits on another thread). The view layer has no
declared way for this (TL #29814, `doc/tl-todo.md`, `view.WindowUpdates`).

## Reporting (haumacher/ehome #64)

How often a device reports a function is the controller's business, with the defaults of its
function table; the installer tunes it per function in E-Home, and the runtime image carries the
override as the binding's `reporting` (homecontrol `spec/zigbee.md` "Reporting overrides").

- **Two sets of attributes.** The installer's override is `ZigbeeFunction#reportingMin`,
  `reportingMax` (seconds) and `reportingChange` (in the datapoint's unit, °C or %), each optional:
  an empty one keeps the controller's default. What the controller does is mirrored from its export
  into `reportedMin`, `reportedMax`, `reportedChange` (read-only; empty for `action`, `multistate`,
  `ias_zone`, and when an older controller exports no `reporting`). The import writes only the
  mirror, never the override; an export without `reporting` empties the mirror.
- **The image.** `ZigbeeBindings.json` writes `reporting` with exactly the members the installer set
  (`ZigbeeReporting.json`), and no member at all without an override: the image of a project
  without overrides is the same as before.
- **One check.** `ZigbeeReporting.check` is the controller's `resolve_reporting`, in its order: the
  function takes no override at all (`action`, `multistate`, `ias_zone`); `min` 0..65534; `max`
  0..65535; the effective `max` (an empty member is the function's default) not below the effective
  `min`, except 0 (reports on a change only) and 65535 (no reports, the value only from reads); no
  `change` for `on_off`, `occupancy` (discrete), `illuminance`, `color_temperature` (no fixed step in
  lux or kelvin); `change` ≥ 0 and, rounded to the attribute's step as the controller rounds it, at
  most 327.67 °C (temperature) or 100 % (level, humidity, battery). Each refusal carries the
  controller's text and a `ResKey` whose English text is the same (the doclet capitalizes its first
  letter).
  - `ImageValidator` refuses a binding's `reporting` with it, at the controller's path
    (`datapoints[i].zigbee.reporting`, `.min`, `.max`, `.change`) after the member types (`reporting
    must be an object`, `min must be an integer`, `change must be a number`); the controller's
    corpus `refuse-zigbee-reporting.json` / `accept-zigbee-reporting.json` runs in
    `TestImageConformance`.
  - `ReportingConstraint`, the constraint of the three override attributes, refuses the edit when it
    is saved, at the field of the member (a refusal of the whole at every field with a value), in the
    user's language; the three depend on each other, so each is checked again when another changes.
    The model checks the constraints at every commit as well, so no override the controller refuses
    can be stored, from the UI or from code.
- **The defaults** are in `ZigbeeFunctionTable` (`Info#reporting`), from homecontrol `doc/zigbee.md`:
  on_off 0..300 s; level 1..300 s, 0.4 %; color_temperature 1..300 s (10 mireds, no change in K);
  temperature 30..600 s, 0.1 °C; humidity 30..600 s, 1 %; illuminance 10..600 s (attribute step 500,
  no change in lux); occupancy 0..600 s; battery 3600..21600 s, 1 %; none for ias_zone, multistate,
  action.
- **What the tab shows.** The function table's column "Reports" and the form below it
  (`ZigbeeFunction#reportingSummary`, `ZigbeeReporting.summary`): "Reports: every 30..600 s, change
  0.1 °C (default)" — the export's effective values ("as the controller reports") where present, else
  the defaults; "on a change only, at most every 0 s" for a maximum of 0, "none, the value only from
  reads" for 65535, "change as the controller's default" where the change has no fixed step in the
  unit. The form edits the override (the unit of the function beside it) and shows the mirror.

The controller configures the override when the image is applied (homecontrol "Reporting
overrides"); the simulation ignores reporting.

## Rooms

The buildings view (`projects/buildings.view.xml`) lists the Zigbee devices placed in a part after
its KNX devices: in the tree, in the tab "Zigbee devices" of the part, and selected with a form and
"Go to the Zigbee device". The part reaches them through the backwards reference
`ehome.building:BuildingDefinition#zigbeeDevices` (the inverse of `ZigbeeDevice#location`).

## Simulation

The rules simulation (`de.haumacher.ehome.simulation`, the project's tab "Simulation") makes every
Zigbee function bound to a datapoint of the image a function of a **virtual Zigbee device**
(`VirtualZigbee`), which behaves towards the rules as the controller's adapter sees a real device
(homecontrol `doc/zigbee.md` "Datapoints", `core/src/zigbee/adapter.cpp`). Its state is held in
memory only.

- **Reports.** The tab "Zigbee devices" of the simulation lists one row per bound function (the
  device's name and IEEE address, the endpoint, the function by `ZigbeeStatus.functionLabel`, the
  datapoint, its value, the time of the last report). "Report from the device" (the same
  `simulationSend` as "Send from the installation" for a KNX datapoint, which also works for a
  Zigbee datapoint in the tab "Datapoints") makes the device report the value: an update of the
  datapoint (origin report) at the simulated time, a timeline entry `zigbee report` (`zigbee
  action` for a button), source `zigbee.<ieee>.<endpoint>`, like the monitor events `report` and
  `action`. The value is coded as the device codes the attribute and decoded as the controller does
  (a level in steps of 1/254, a temperature in hundredths, a battery in halves of a percent, ...)
  and refused outside the range, with the reason in the timeline and the message: level, humidity
  and battery 0..100 %, colour temperature 16..1000000 K, temperature -327.67..327.67 °C,
  illuminance 0 lux up to the largest coded value, multistate 0..65535, action 0 off, 1 on, 2
  toggle, 3 dim up, 4 dim down, 5 dim stop.
- **Every press is an event.** Each report is an update of the datapoint, also with the value it
  has already, as the controller reports every command a button sends. A rule's datapoint trigger
  with `on: update` fires for each press; the default `on: change` only when the value changes
  (homecontrol `spec/rules.md` 2.3) — on the controller exactly the same.
- **Commands.** A command (a rule's write, the simulation's "Command") to a function with a command
  cluster (`on_off`, `level`, `color_temperature` bound writable) is checked against the controller's
  range, logged as `zigbee command` and reported back by the device after the cascade (a commanded
  level of 33 % comes back as 33.1 %, the device's level 84 of 254). A datapoint that is not writable
  is refused by the model (`not writable`); a writable one whose binding has no command cluster by
  the device (`read-only`, as the controller says); both show as problems in the timeline.
  Every function keeps a value of its own: switching a light does not change its level.
- **Reads.** A rule's `read` (`spec/rules.md` 7.12) makes the device report its value after the
  cascade (`zigbee report ... (read, <datapoint>)`); a device without a value yet leaves the
  datapoint unknown and says so as a problem. An `action` or a `multistate` input refuses the read
  (no attribute value), as the controller does.
- **Start.** Every Zigbee datapoint is unknown at the start. The controller reads the server
  functions but the multistate input at its start; the virtual devices keep their values from the
  last simulation of the project (in memory, until the application stops, as real devices keep
  their state while the controller restarts) and report them at the start. The first simulation of
  a project starts with devices without values.
- **Not simulated.** Functions bound to no datapoint are not in the image and so not in the
  simulation; nor are joins, leaves, the stale rule, reporting intervals and the interplay of
  functions of one device (a level command that switches the light on).

## Not done yet

The device catalog and quirks.
