# Ideas

Open points of E-Home and of its controller HomeControl (`haumacher/homecontrol`): proposals that
were discussed but not decided, or deliberately put off. This file is the master list for both
repositories. An entry is no ticket: an implementing agent does not work on it. When the user
agrees on its design, it becomes a ticket (one per repository side) and leaves this list.

Each entry: what it is about, the user's words, what exists today (with references), the open
questions, and why it waits.

## Zigbee: a datapoint per function without the extra step

The user: "dort muss man explizit für jede Funktion einen Datenpunkt anlegen oder auswählen. Wenn
das ohnehin nur genau einer pro Gerät sein kann, dann ergibt das einfach keinen Sinn."

Today:
- A Zigbee function has at most one datapoint (`ZigbeeFunction#datapoints`,
  `ehome/src/main/webapp/WEB-INF/model/ehome.zigbee.model.xml:309`; the image refuses a function
  bound twice, homecontrol `spec/zigbee.md:55`).
- The tab "Zigbee" creates the datapoints per device ("Create datapoints", with name, type and
  reporting from the Zigbee catalog) or per function ("Create datapoint", "Bind datapoint",
  "Unbind") (`ehome/src/main/webapp/WEB-INF/views/projects/zigbee.view.xml:229`, `:433`, `:462`,
  `:490`).
- The controller gives a device without any binding generic datapoints
  `zigbee.<ieee>.<endpoint>.<function>` (homecontrol `spec/zigbee.md:19`, `:26`).
- What the datapoint of its own is good for: a stable name for rules, schedules and control pages;
  replacing a broken device by binding its datapoints to the new one; a function the export no
  longer lists stays marked `absent` while a datapoint is bound to it (`ZigbeeImport.java:41`).

Discussed: create the datapoints with the import, show and edit them in the function table, turn
"Bind" into "Replace device". Against it, the user: creating them directly gives the datapoints
unreadable technical names. "Wir lassen das erst mal so wie es ist."

Open: how the installer gets readable names without a step per function.

## Zigbee: switches bound directly to actuators

The user: "Man kann aber Zigbee Geräte im Zigbee Netzwerk direkt miteinander verbinden, indem man
dort dieselben Datenpunkte verwendet - z.B. Schalter und Steckdose?"

Today:
- In Zigbee itself a switch's client cluster On/Off can be bound to a socket's server cluster (ZDO
  Bind) or to a Zigbee group the socket is a member of (close to a KNX group address); the switch
  then commands the socket without the coordinator, also while the controller is down.
- The firmware binds every mapped cluster to the coordinator only (homecontrol
  `doc/zigbee.md:194`); groups and scenes are listed under "Not done" (`doc/zigbee.md:389`).
- In E-Home a datapoint's `zigbee` member names one function of one device (homecontrol
  `spec/zigbee.md:30`), and a datapoint is either `knx` or `zigbee` (a datapoint has one owning
  technology, homecontrol `core/include/hc/technology.h:11`). Switch and socket get two datapoints;
  a rule on the controller couples them, as it couples Zigbee and KNX.

Open (proposals, not decided):
1. Zigbee groups or single bindings (proposed: groups; one entry in the switch's binding table for
   several sockets, and the concept of a KNX group address).
2. Whether the controller still sees the commands (proposed: keep the switch's binding to the
   coordinator, so its datapoint, rules and history stay).
3. Which clusters (proposed: buttons only, client clusters On/Off 6 and Level Control 8, the
   `action` function).
4. A factory-reset device loses its bindings; the firmware already detects it and interviews again
   (homecontrol `doc/zigbee.md:199`), the binding again must hook in there. Further: the
   simulation in E-Home, the binding table limits of the devices, an older firmware under I4/I5 of
   homecontrol `spec/runtime-image.md`.

Would be two tickets: homecontrol (bindings/groups, image format) and ehome (model, editor, image
writer, simulation).

## Modules: compare E-Home's download with an ETS download

Put off in the round of haumacher/ehome #119 (dynamic modules); the user: "for 6 we start with b"
(unit tests on the computed object numbers and offsets only).

Today:
- No device with modules on the test system: the MDT actuator's program `M-0083_A-000C-21` has
  no `ModuleDefs` (`ehome/samples/KNX-Testsystem.knxproj`).
- The tooling records ETS downloads to the virtual device (`ehome-virtual`, package
  `de.haumacher.ehome.virtual.tooling`, traces in `ehome-virtual/src/test/fixtures/ets-traces/`).

Proposal: record an ETS 6 download of a device with modules (e.g. an MDT switching actuator
12-fold, program `M-0083_A-0014-32-1A2C`) to the virtual device and compare E-Home's download of
the same device against the trace. Open: which device and project to record from (the
verification project of #119 must not be committed, so the trace would come from a project made for
it).
