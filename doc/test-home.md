# The test home of the KNX test system

An integration test project for the controller and the test system on the desk: the KNX test system
(`ehome/samples/KNX-Testsystem.knxproj`, project P-0563: the MDT switch actuator AKK-04 1.3.5 with
channels A to D, the Gira Tastsensor 3 1.3.50 with keys, LEDs, alarm, night setback and room
temperature, the IP router) plus the Zigbee Hue motion sensor "Flur Bewegung" (Philips SML002, IEEE
`0017880106498a49`) paired with the controller, and a rule set that exercises the rule engine, the
scheduler, the Zigbee adapter and the KNX tunnel together.

The keys and the actuator share no group address: the controller is the only logic between them.
Every effect the checklist below names passes through a rule.

The sample `ehome/samples/KNX-Testsystem.home.json` is a fragment of a runtime image
(homecontrol `spec/runtime-image.md`) with the members

- `settings`: the location (49.45 N, 11.08 E, Nürnberg area, for dawn and dusk) and the time zone
  `CET-1CEST,M3.5.0,M10.5.0/3`;
- `datapoints`: the application datapoints `flur.presence`, `flur.lux`, `flur.temperature`,
  `flur.battery`, bound to the sensor's functions occupancy, illuminance, temperature and battery
  (endpoint 2; the illuminance with the reporting override 10..300 s, so that "dark" is seen within
  five minutes);
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
| `dark-light` "Licht bei Dunkelheit (Aktor 1)" | A on when `flur.lux` falls below `darkBelow` (20 lx), off when it rises above `brightAbove` (60 lx). Edge-triggered (`match`), so a key that switches A in between is not overruled at the next report; at the start (image swap) A is set by `hysteresis` when the illuminance is known. Writes `onlyOnChange`. |
| `dusk-dawn` "Von der Abend- bis zur Morgendämmerung (Aktor 2)" | B on at the schedule entry `dusk`, off at `dawn` (astro, civil twilight); at the start `B := not inWindow(time(), dawn(), dusk())` when the clock and the location give a value. |
| `frost` "Frostschutz (Aktor 3)" | C on when `flur.temperature` falls below `below` (17 °C), off from `below + band` (0.5 K); at the start by `hysteresis`. |
| `presence-light` "Licht bei Bewegung (Aktor 4)" | D on at motion when it is dark (`flur.lux < darkBelow`, 20 lx) or D is on already; off `delay` (2 min) after the last motion (the pattern of `spec/rules.md` 11.1). |
| `keys` | Keys 1 to 4 (`0/0/1`..`0/0/4`) switch A to D with the key's value (`on: update`: every press counts); key 5 switches all four off. |
| `leds` | LED 1 to 3 (`0/0/7`..`0/0/9`) show the status objects of A to C, LED 4 (`0/0/10`) the switching value of D. |
| `night` "Nachtruhe" | At 22:00 every day (entry `night`) A to D off and the night setback (`0/0/32`) on; at 06:30 on workdays (`morning-workday`) and 08:00 at the weekend (`morning-weekend`) the night setback off. |
| `sensor-watch` "Flur-Sensor überwachen" | A warning in the log and the alarm (`0/0/29`) on when the sensor reports no temperature for `silentAfter` (2 h, a `silence` trigger), or its battery falls below `batteryBelow` (20 %); the alarm acknowledgement (`0/0/30`) clears the alarm. |
| `temp-compare` "Temperaturen vergleichen" | A log entry with both values when the Tastsensor's room temperature (`0/0/28`) and `flur.temperature` differ by more than `maxDiff` (3 K). |
| `presence-sim` "Anwesenheitssimulation (Aktor 2)" | Toggles B every 30 min from 18:00 to 23:00 (interval entry `presence-sim`). Disabled in the image; switch it on on the controller to test it. |

Schedule labels are plain strings (the specification allows no language map there), so they are
German: "Abenddämmerung", "Morgendämmerung", "Nachtruhe", "Morgen (Werktag)", "Morgen
(Wochenende)", "Anwesenheitssimulation".

## What the project gives, and the one setting it lacks

The import of the test system leaves exactly one group address without a datapoint type:
`0/0/28 Raumtemperatur` (neither the address nor the Tastsensor's 2-byte object names one, and two
bytes do not decide the main type). The image leaves it out with a note, and the rule
`temp-compare`, which reads it, would be refused as naming an unknown datapoint. **Set its type to
9.001** (temperature, °C) as below. The keys, LEDs, alarm and night setback have no type in the
project either, but their 1-bit objects decide the main type 1 (`1.000` in the image, value type
boolean), which is all the rules need.

Every address the rules write is writable as the project's objects give it (the actuator's switching
objects, the LED, alarm and night setback objects of the Tastsensor receive): nothing to change in
the datapoint settings.

## Setting it up in the app

1. Projects: import `ehome/samples/KNX-Testsystem.knxproj`.
2. Tab "Gruppenadressen" (Group addresses): select `0/0/28 Raumtemperatur`, "Datenpunkttyp setzen"
   (Set the datapoint type), choose 9.001.
3. Tab "Controller": "Controller hinzufügen" (Add controller) and enroll the controller on its
   Settings page with the code (`doc/controllers.md`), or enter its host and token for the LAN.
4. Tab "Zigbee": "Zigbee-Geräte lesen" (Read Zigbee devices). The sensor appears as "Flur Bewegung"
   with its functions. (Or "Import Zigbee devices" with the file the controller's Zigbee page
   downloads.)
5. Tab "Regeln" (Rules): "Regeln importieren" (Import rules) with
   `ehome/samples/KNX-Testsystem.home.json`. The import creates the ten rules, six schedule entries
   and the four `flur.*` datapoints bound to the sensor's functions, and sets the project's location
   and time zone where it has none. Its message names everything it decided besides: a datapoint
   the project has already is kept; without the sensor (step 4 skipped) the datapoints are created
   unbound and can be bound in the tab "Zigbee" later; a project with a location of its own keeps
   it. Imported again, the sample adds nothing: every rule, schedule entry and datapoint whose id the
   project has is kept as it is (the message names them); to take a changed sample, delete the
   rules, entries and datapoints first.
6. Tab "Funktionen" (Functions), optional: "Funktionen vorschlagen" (Propose functions) gives the
   controller's UI the switches, keys and the temperature.
7. Tab "Simulation", optional: start it and try the scenarios below without hardware.
8. Tab "Bereitstellung" (Deployment): "Bereitstellen" (Deploy).

## Hardware checklist

With the image deployed, the controller connected to the router of the test system and the sensor
joined. Values are seen on the actuator's channel LEDs, the Tastsensor's LEDs, and in the
controller's log and diagnostics.

| Action | Expected result |
|---|---|
| Cover the motion sensor (illuminance below 20 lx; it reports within 5 min) | Channel A on, LED 1 on. |
| Uncover it in daylight (above 60 lx) | Channel A off, LED 1 off. |
| Walk past the sensor in the dark | Channel D on, LED 4 on; 2 min after the last motion D off, LED 4 off. |
| Press key 1 / 2 / 3 / 4 | Channel A / B / C / D follows the key's value; LED 1 to 3 follow the status of A to C, LED 4 follows D. |
| Press key 5 | All four channels off, LEDs off. |
| Cool the sensor below 17 °C (or set `frost`'s parameter `below` above the room temperature on the controller) | Channel C on, LED 3 on; at 17.5 °C (or the parameter back) off. |
| Raise `sensor-watch`'s `batteryBelow` above the battery level on the controller | Warning "Die Batterie des Flur-Sensors ist bei … %." in the log, the alarm of the Tastsensor on. |
| Acknowledge the alarm on the Tastsensor | Log "Alarm quittiert.", the alarm off. |
| Take the battery out of the sensor (or set `silentAfter` to 10 min) | After `silentAfter` without a temperature: warning "Der Flur-Sensor meldet seit … keine Temperatur.", the alarm on. |
| Room temperature of the Tastsensor and the sensor more than 3 K apart (or `maxDiff` lowered) | Log "Raumtemperatur … °C, Flur … °C: Abweichung über … K." once per divergence. |
| Dusk (or the controller's time zone / location changed) | Channel B on; off at dawn. |
| 22:00 | All channels off, the night setback of the Tastsensor on; off at 06:30 on workdays, 08:00 at the weekend. |
| Enable `presence-sim` on the controller, 18:00 to 23:00 | Channel B toggles every 30 min. |
