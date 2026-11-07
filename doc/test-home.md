# The test home of the KNX test system

An integration test project for the controller and the test system on the desk: the KNX test system
(`ehome/samples/KNX-Testsystem.knxproj`, project P-0563: the MDT switch actuator AKK-04 1.3.5 with
channels A to D, the Gira Tastsensor 3 1.3.50 with keys, LEDs, alarm, night setback and room
temperature, the IP router) plus the Zigbee Hue smart plug "Hue Steckdose" (Signify LOM007, IEEE
`001788010f069c8e`, mains powered) paired with the controller, and a rule set that exercises the rule
engine, the scheduler, the Zigbee adapter and the KNX tunnel together. Everything it needs stays on
the desk; it uses no battery-powered sensor (the Hue motion sensor of earlier versions is gone, its
datapoints and the rules reading them with it).

The keys and the actuator share no group address: the controller is the only logic between them.
Every effect the checklist below names passes through a rule.

The sample `ehome/samples/KNX-Testsystem.home.json` is a fragment of a runtime image
(homecontrol `spec/runtime-image.md`) with the members

- `settings`: the location (49.45 N, 11.08 E, Nürnberg area, for dawn and dusk) and the time zone
  `CET-1CEST,M3.5.0,M10.5.0/3`;
- `datapoints`: the application datapoint `steckdose.on`, writable, bound to the plug's on/off
  (endpoint 11, commands to the On/Off cluster);
- `schedules` and `rules` in the JSON form of homecontrol `spec/rules.md` and `spec/schedules.md`.

`test.de.haumacher.ehome.runtime.TestTestHome` imports it into the test system as described below,
checks that the controller's checks accept the image with every rule, and runs the scenarios of the
checklist in the simulation.

## The rules

The datapoints of the group addresses have the address in three levels as their id (`0/0/34`).
Actuator channels: A `0/0/34` (status `0/0/37`), B `0/0/40` (`0/0/43`), C `0/0/46` (`0/0/47`), D
`0/0/48` (no status object). Rule names are given in German and English; the parameters are adjustable on
the controller.

| Rule | What it does |
|---|---|
| `dusk-dawn` "Von der Abend- bis zur Morgendämmerung (Aktor 2)" | B on at the schedule entry `dusk`, off at `dawn` (astro, civil twilight); at the start `B := not inWindow(time(), dawn(), dusk())` when the clock and the location give a value. |
| `frost` "Frostschutz (Aktor 3)" | C on when the Tastsensor's room temperature (`0/0/28`) falls below `below` (17 °C), off from `below + band` (0.5 K); at the start by `hysteresis`. |
| `keys` | Keys 1 to 4 (`0/0/1`..`0/0/4`) switch A to D with the key's value (`on: update`: every press counts); key 5 switches all four off. |
| `leds` "LEDs und Tasten folgen den Aktoren" | LED 1 to 3 (`0/0/7`..`0/0/9`) show the status objects of A to C, LED 4 (`0/0/10`) the switching value of D; the same value goes to the switching object of key 1 to 4 (`0/0/1`..`0/0/4`), so a toggling key knows the channel's state also after key 5, the night or the actuator itself switched it, and its next press sends the opposite. |
| `night` "Nachtruhe" | At 22:00 every day (entry `night`) A to D off and the night setback (`0/0/32`) on; at 06:30 on workdays (`morning-workday`) and 08:00 at the weekend (`morning-weekend`) the night setback off. |
| `plug-watch` "Steckdose überwachen" | A warning in the log and the alarm (`0/0/29`: every LED and the backlight of the Tastsensor flash) on when the plug reports nothing for `silentAfter` (15 min, a `silence` trigger; the plug reports its state at least every 5 min). The plug's next report clears the alarm ("Die Steckdose meldet sich wieder."), as does the acknowledgement (`0/0/30`, "Alarm quittiert."), so the alarm never outlives its cause. |
| `presence-sim` "Anwesenheitssimulation (Aktor 2)" | Toggles B every 30 min from 18:00 to 23:00 (interval entry `presence-sim`). Disabled in the image; switch it on on the controller to test it. |
| `plug` "Taste 6 schaltet die Steckdose" | Key 6 (`0/0/6`) switches the plug (`steckdose.on`) with the key's value; LED 5 (`0/0/11`) and the key's own switching object show the plug's state, also when it is switched at its own button, so the next press of the toggling key sends the opposite. |

Schedule labels are plain strings (the specification allows no language map there), so they are
German: "Abenddämmerung", "Morgendämmerung", "Nachtruhe", "Morgen (Werktag)", "Morgen
(Wochenende)", "Anwesenheitssimulation".

## What the project gives, and the one setting it lacks

The import of the test system leaves exactly one group address without a datapoint type:
`0/0/28 Raumtemperatur` (neither the address nor the Tastsensor's 2-byte object names one, and two
bytes do not decide the main type). The image leaves it out with a note, and the rule `frost`, which
reads it, would be refused as naming an unknown datapoint. **Set its type to 9.001** (temperature,
°C) as below. The keys, LEDs, alarm and night setback have no type in the project either, but their
1-bit objects decide the main type 1 (`1.000` in the image, value type boolean), which is all the
rules need.

The keys' switching objects transmit on their addresses, so the datapoint settings do not derive
them as writable (an object that transmits on an address is a sensor there, and in an installation
an actuator listens to it): **set `0/0/1` to `0/0/4` and `0/0/6` writable**, so the rules may feed the
switched state back to the keys. Every other address the rules write is writable as the project's
objects give it (the actuator's switching objects, the LED, alarm and night setback objects of the
Tastsensor receive).

## Setting it up in the app

1. Projects: import `ehome/samples/KNX-Testsystem.knxproj`.
2. Tab "Gruppenadressen" (Group addresses): select `0/0/28 Raumtemperatur`, "Datenpunkttyp setzen"
   (Set the datapoint type), choose 9.001; for `0/0/1 Taste1` to `0/0/4 Taste4` and `0/0/6 Taste6`
   set "Schreibbar" (Writable) in their datapoint settings.
3. Tab "Controller": "Controller hinzufügen" (Add controller) and enroll the controller on its
   Settings page with the code (`doc/controllers.md`), or enter its host and token for the LAN.
4. Tab "Zigbee": "Zigbee-Geräte lesen" (Read Zigbee devices). The plug appears as "Hue Steckdose",
   of the type "Hue Steckdose" of the Zigbee catalog, with its function "Schalten". (Or "Import
   Zigbee devices" with the file the controller's Zigbee page downloads.)
5. Tab "Regeln" (Rules): "Regeln importieren" (Import rules) with
   `ehome/samples/KNX-Testsystem.home.json`. The import creates the eight rules, six schedule entries
   and `steckdose.on` bound to the plug, and sets the project's location and time zone where it has
   none. Its message names everything it decided besides: a datapoint the project has already is
   kept; without the plug (step 4 skipped) the datapoint is created unbound and can be bound in the
   tab "Zigbee" later; a project with a location of its own keeps it. Imported again, the sample adds
   nothing: every rule, schedule entry and datapoint whose id the project has is kept as it is (the
   message names them); to take a changed sample, delete the rules, entries and datapoints first.
6. Tab "Funktionen" (Functions), optional: "Funktionen vorschlagen" (Propose functions) gives the
   controller's UI the switches, keys and the temperature of the KNX devices, and a switch for the
   plug's datapoint (haumacher/ehome #68).
7. Tab "Simulation", optional: start it and try the scenarios below without hardware.
8. Tab "Bereitstellung" (Deployment): "Bereitstellen" (Deploy).

## Hardware checklist

With the image deployed, the controller connected to the router of the test system and the plug
joined. Values are seen on the actuator's channel LEDs, the Tastsensor's LEDs, and in the
controller's log and diagnostics.

| Action | Expected result |
|---|---|
| Press key 1 / 2 / 3 / 4 | Channel A / B / C / D follows the key's value; LED 1 to 3 follow the status of A to C, LED 4 follows D. |
| Press key 5 | All four channels off, LEDs off. |
| Press key 6 | The plug on / off, LED 5 follows. |
| Press the plug's own button, then key 6 | The plug toggles, LED 5 follows (the plug reports its state); the first press of key 6 switches it back (the key learned the state). |
| Press key 5, then key 1 | All channels off; the first press of key 1 switches A on (the key learned that A is off). |
| Set `frost`'s parameter `below` above the room temperature on the controller | Channel C on, LED 3 on; with the parameter back below the room temperature (by more than `band`) off. |
| Unplug the plug (or set `silentAfter` to 10 min and wait) | After `silentAfter` without a report: warning "Die Steckdose meldet seit … nichts.", the alarm of the Tastsensor on. |
| Plug it in again | Within 5 min its report clears the alarm: log "Die Steckdose meldet sich wieder.", the alarm off. |
| Acknowledge the alarm on the Tastsensor | Log "Alarm quittiert.", the alarm off. |
| Dusk (or the controller's time zone / location changed) | Channel B on; off at dawn. |
| 22:00 | All channels off, the night setback of the Tastsensor on; off at 06:30 on workdays, 08:00 at the weekend. |
| Enable `presence-sim` on the controller, 18:00 to 23:00 | Channel B toggles every 30 min. |
