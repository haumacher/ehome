/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.Map;

import de.haumacher.ehome.blocks.RoomController;

/**
 * The German texts of the room controller's manifest (homecontrol <code>spec/blocks.md</code> 14.1):
 * the manifest carries its labels in English, as the firmware does; these are their translations for
 * E-Home's views ({@link BlockTexts}), by the id of the port, parameter or choice. Beside them the
 * help texts of the ports and parameters in German and English (haumacher/ehome #101), which exist
 * only in E-Home: written for the installer from the block's code, not taken from the firmware.
 */
public final class RoomControllerTexts {

	/** The type id. */
	public static final String TYPE = RoomController.TYPE;

	/** The German label of the type. */
	public static final String LABEL_DE = "Raumregler";

	/** The German labels of the ports and parameters by id, the choices by <code>parameter.choice</code>. */
	public static final Map<String, String> DE = Map.ofEntries(
		Map.entry("temperature", "Temperatur"),
		Map.entry("setpoint", "Externer Sollwert"),
		Map.entry("mode", "Betriebsart (HVAC)"),
		Map.entry("presence", "Anwesenheit"),
		Map.entry("heatingPeriod", "Heizzeit"),
		Map.entry("window", "Fenster offen"),
		Map.entry("enable", "Heizung ein"),
		Map.entry("summer", "Sommer"),
		Map.entry("forced", "Zwangsstellung"),
		Map.entry("valve", "Ventil"),
		Map.entry("switch", "Schaltausgang"),
		Map.entry("demand", "Wärmeanforderung"),
		Map.entry("activeSetpoint", "Aktiver Sollwert"),
		Map.entry("state", "Zustand"),
		Map.entry("p", "P-Anteil"),
		Map.entry("i", "I-Anteil"),
		Map.entry("d", "D-Anteil"),
		Map.entry("error", "Regelabweichung"),
		Map.entry("filtered", "Gefilterte Temperatur"),
		Map.entry("system", "Heizsystem"),
		Map.entry("system.radiator", "Heizkörper"),
		Map.entry("system.underfloor", "Fußbodenheizung"),
		Map.entry("system.convector", "Konvektor"),
		Map.entry("system.custom", "Benutzerdefiniert"),
		Map.entry("controlType", "Regelung"),
		Map.entry("controlType.continuous", "Stetig"),
		Map.entry("controlType.pwm", "PWM"),
		Map.entry("controlType.twoPoint", "Zweipunkt"),
		Map.entry("xp", "Proportionalbereich"),
		Map.entry("tn", "Nachstellzeit"),
		Map.entry("td", "Vorhaltezeit"),
		Map.entry("filter", "Filterzeitkonstante"),
		Map.entry("cycle", "PWM-Zyklus"),
		Map.entry("hysteresis", "Hysterese"),
		Map.entry("outputMin", "Kleinste Ventilstellung"),
		Map.entry("outputMax", "Größte Ventilstellung"),
		Map.entry("minChange", "Kleinste gesendete Änderung"),
		Map.entry("resendInterval", "Wiederholintervall"),
		Map.entry("comfort", "Komfort"),
		Map.entry("standby", "Standby"),
		Map.entry("night", "Nacht"),
		Map.entry("frost", "Frostschutz"),
		Map.entry("windowDelay", "Fensterverzögerung"),
		Map.entry("boostThreshold", "Boost-Schwelle"),
		Map.entry("boostDuration", "Längster Boost"),
		Map.entry("sensorTimeout", "Sensor-Zeitüberschreitung"),
		Map.entry("fallbackPosition", "Ventil ohne Sensor"),
		Map.entry("forcedPosition", "Ventil in Zwangsstellung"),
		Map.entry("summerPosition", "Ventil im Sommer"),
		Map.entry("protectionDay", "Ventilschutz-Tag"),
		Map.entry("protectionTime", "Ventilschutz ab"),
		Map.entry("protectionDuration", "Ventilschutz-Dauer"));

	/**
	 * The German help texts of the ports and parameters by id (haumacher/ehome #101): what the member
	 * does, for the installer. The choices of an enumeration each in a paragraph of their own.
	 */
	public static final Map<String, String> HELP_DE = Map.ofEntries(
		Map.entry("temperature", "Die Raumtemperatur, nach der der Regler regelt, z. B. vom Temperaturfühler eines Tastsensors. "
			+ "Bis zu 4 Fühler lassen sich binden; die „Zusammenfassung“ macht daraus einen Wert (Mittelwert, kleinster oder größter Wert). "
			+ "Der Regler rechnet einmal pro Minute mit dem zuletzt empfangenen Wert. "
			+ "Fehlt der Wert oder kam länger als „Sensor-Zeitüberschreitung“ keiner, fährt das Ventil auf „Ventil ohne Sensor“."),
		Map.entry("setpoint", "Ein Sollwert von außen, z. B. vom Raumtemperaturregler eines Tastsensors oder aus einer Visualisierung. "
			+ "Solange er einen Wert hat, ersetzt er „Komfort“, „Standby“ und „Nacht“ (z. B. 22,5 °C statt 21 °C). "
			+ "Fenster offen, „Heizung ein“ aus und die Betriebsart Frost-/Gebäudeschutz gehen vor: dann gilt „Frostschutz“. "
			+ "Ungebunden: Der Regler wählt den Sollwert selbst."),
		Map.entry("mode", "Die KNX-Betriebsart (DPT 20.102), z. B. vom Ausgang „Betriebsart (HVAC)“ eines Hausmodus: "
			+ "1 Komfort, 2 Standby, 3 Nacht, 4 Frost-/Gebäudeschutz wählen den Sollwert „Komfort“, „Standby“, „Nacht“ bzw. „Frostschutz“. "
			+ "0 (Automatisch) oder kein Wert: Der Sollwert folgt „Heizzeit“ und „Anwesenheit“. "
			+ "Ein „Externer Sollwert“ ersetzt Komfort, Standby und Nacht, nicht aber den Frostschutz."),
		Map.entry("presence", "Ob jemand im Raum oder im Haus ist, z. B. von einem Präsenzmelder. "
			+ "Abwesend (0) senkt auf „Standby“ ab, anwesend (1) oder kein Wert heizt auf „Komfort“. "
			+ "Wirkt nur, solange weder „Betriebsart (HVAC)“ (1–3) noch „Externer Sollwert“ einen Wert vorgeben und „Heizzeit“ nicht aus ist."),
		Map.entry("heatingPeriod", "Die Zeit, in der geheizt wird, z. B. von einem Zeitprogramm (ein 6:00–22:00). "
			+ "Aus (0) senkt auf „Nacht“ ab und geht dabei „Anwesenheit“ vor; ein (1) oder kein Wert lässt „Anwesenheit“ entscheiden. "
			+ "Wirkt nur, solange weder „Betriebsart (HVAC)“ (1–3) noch „Externer Sollwert“ einen Wert vorgeben."),
		Map.entry("window", "Fensterkontakte des Raums, bis zu 4; offen ist, wenn einer von ihnen offen meldet. "
			+ "Bleibt ein Fenster länger als „Fensterverzögerung“ offen (Vorgabe 1 min), regelt der Regler auf „Frostschutz“ (Zustand Fenster offen); "
			+ "sind alle wieder zu, sofort zurück. Kurzes Öffnen unter der Verzögerung ändert nichts."),
		Map.entry("enable", "Schaltet die Heizung des Raums ein und aus, z. B. über einen Taster oder eine Visualisierung. "
			+ "Aus (0): Der Regler hält nur noch „Frostschutz“ (z. B. 7 °C), das Ventil schließt also nicht in jedem Fall. "
			+ "Ein (1) oder ungebunden: normaler Betrieb."),
		Map.entry("summer", "Sommerbetrieb, z. B. vom Ausgang „Sommer“ einer Heizkurve. "
			+ "Ein (1): Das Ventil steht auf „Ventil im Sommer“ (Vorgabe 0 %), der Regler regelt nicht und meldet keine „Wärmeanforderung“. "
			+ "Der Ventilschutz läuft trotzdem."),
		Map.entry("forced", "Zwangsstellung, z. B. für Estrichtrocknung, Wartung oder Taupunktalarm. "
			+ "Ein (1): Das Ventil steht auf „Ventil in Zwangsstellung“ (Vorgabe 100 %), vor allem anderen, und es gibt keine „Wärmeanforderung“."),
		Map.entry("valve", "Die Ventilstellung 0–100 % für einen Stellantrieb, der Prozentwerte versteht (DPT 5.001); "
			+ "der Ausgang für die Regelung „Stetig“. "
			+ "Gesendet wird eine Änderung ab „Kleinste gesendete Änderung“ sowie jedes Erreichen von 0 %, 100 %, „Kleinste Ventilstellung“ und „Größte Ventilstellung“, "
			+ "wiederholt nach „Wiederholintervall“. Bei PWM und Zweipunkt wird die Stellung auch hier ausgegeben, gebunden wird dort aber „Schaltausgang“. "
			+ "Hält das virtuelle Gerät an, geht das Ventil auf 30 %."),
		Map.entry("switch", "Ein Schaltausgang (ein/aus) für einen Schalt- oder Heizungsaktor. "
			+ "Bei „PWM“ pulst er je „PWM-Zyklus“ (30 % bei 15 min: 4,5 min ein), bei „Zweipunkt“ schaltet er nach Sollwert und „Hysterese“; "
			+ "bei „Stetig“ ist er ein, solange das Ventil offen ist (z. B. für eine Pumpe oder eine Anzeige). "
			+ "Hält das virtuelle Gerät an, schaltet er aus."),
		Map.entry("demand", "Meldet, dass der Raum Wärme braucht: ein, solange das Ventil über 0 % steht, "
			+ "außer in Zwangsstellung, beim Ventilschutz und im Sommer. "
			+ "Gedacht für den Eingang „Wärmeanforderungen“ eines Wärmebedarfs oder direkt für die Anforderung an den Wärmeerzeuger. "
			+ "Hält das virtuelle Gerät an, schaltet sie aus."),
		Map.entry("activeSetpoint", "Der Sollwert, nach dem gerade geregelt wird, z. B. 21 °C bei Komfort oder 7 °C bei offenem Fenster; "
			+ "zur Anzeige oder Fehlersuche."),
		Map.entry("state", "Was der Regler gerade tut: 0 Aus („Heizung ein“ aus, es gilt „Frostschutz“), 1 Heizen, 2 Fenster offen, "
			+ "3 Frost-/Gebäudeschutz, 4 Boost, 5 Störung (kein gültiger Temperaturwert), 6 Ventilschutz, 7 Sommer, 8 Zwangsstellung."),
		Map.entry("p", "Der P-Anteil der Ventilstellung in %: „Regelabweichung“ mal 100 / „Proportionalbereich“ "
			+ "(1 K zu kalt bei 3 K: 33 %). Zur Fehlersuche; 0 bei Zweipunktregelung, im Boost und wenn der Regler nicht regelt."),
		Map.entry("i", "Der I-Anteil der Ventilstellung in %: wächst, solange es zu kalt ist, um so viel wie der P-Anteil je „Nachstellzeit“, "
			+ "und schrumpft, solange es zu warm ist; begrenzt auf „Kleinste Ventilstellung“ bis „Größte Ventilstellung“. "
			+ "Zur Fehlersuche; bleibt bei Zweipunktregelung stehen."),
		Map.entry("d", "Der D-Anteil der Ventilstellung in %: nimmt die Stellung zurück, solange die Temperatur steigt, und gibt zu, solange sie fällt. "
			+ "Nur mit „Vorhaltezeit“ und „Filterzeitkonstante“ über 0; zur Fehlersuche."),
		Map.entry("error", "Die Regelabweichung: aktiver Sollwert minus gefilterte Temperatur, positiv heißt zu kalt "
			+ "(Sollwert 21 °C, Raum 20,2 °C: 0,8 K). Zur Fehlersuche."),
		Map.entry("filtered", "Die Raumtemperatur nach der Glättung durch „Filterzeitkonstante“; mit ihr rechnet der Regler. Zur Fehlersuche."),
		Map.entry("system", "Wie träge die Heizung ist; die Wahl setzt die Regelparameter passend. "
			+ "Wirkt bei „Stetig“ und „PWM“, nicht bei „Zweipunkt“.\n\n"
			+ "Heizkörper: Proportionalbereich 3 K, Nachstellzeit 90 min, Vorhaltezeit 5 min, Filterzeitkonstante 2 min. "
			+ "Für Plattenheizkörper und Röhrenradiatoren: 1 K zu kalt öffnet das Ventil sofort auf 33 %, nach 90 min ist es bei 66 %.\n\n"
			+ "Fußbodenheizung: 3 K, 180 min, 10 min, 2 min. Für Fußboden- und Wandheizungen mit großer Speichermasse: "
			+ "Der I-Anteil wächst halb so schnell, damit der Estrich nicht überheizt.\n\n"
			+ "Konvektor: 2 K, 45 min, 5 min, 2 min. Für schnelle Heizflächen wie Unterflur- oder Gebläsekonvektoren: "
			+ "1 K zu kalt gibt sofort 50 %, nach 45 min 100 %.\n\n"
			+ "Benutzerdefiniert: Es gelten „Proportionalbereich“, „Nachstellzeit“, „Vorhaltezeit“ und „Filterzeitkonstante“; "
			+ "nur bei dieser Wahl wirken sie. Ihre Vorgabe entspricht dem Heizkörper, aber ohne D-Anteil (Vorhaltezeit 0)."),
		Map.entry("controlType", "Wie der Regler den Stellantrieb ansteuert; davon hängt ab, welcher Ausgang gebunden wird.\n\n"
			+ "Stetig: Der Regler berechnet aus der Abweichung vom Sollwert eine Ventilstellung von 0–100 % und sendet sie am Ausgang „Ventil“, "
			+ "eine Änderung ab „Kleinste gesendete Änderung“ (z. B. 2 %). "
			+ "Für Stellantriebe, die Prozentwerte verstehen, etwa einen KNX-Heizungsaktor mit motorischem Stellantrieb (DPT 5.001). "
			+ "Es wirken „Heizsystem“ (bei „Benutzerdefiniert“ „Proportionalbereich“, „Nachstellzeit“, „Vorhaltezeit“, „Filterzeitkonstante“), "
			+ "der Boost, „Kleinste Ventilstellung“ und „Größte Ventilstellung“; „PWM-Zyklus“ und „Hysterese“ wirken nicht.\n\n"
			+ "PWM: Dieselbe berechnete Stellung, aber als Ein/Aus am Ausgang „Schaltausgang“: "
			+ "In jedem „PWM-Zyklus“ ist er so lange ein, wie es der Stellung entspricht (30 % bei 15 min: 4,5 min ein, 10,5 min aus). "
			+ "Für thermische Stellantriebe an einem Schalt- oder Heizungsaktor, die nur auf oder zu kennen. "
			+ "Es wirken dieselben Parameter wie bei „Stetig“ und dazu „PWM-Zyklus“; „Hysterese“ wirkt nicht.\n\n"
			+ "Zweipunkt: Ohne PI-Regler: „Schaltausgang“ schaltet ein, wenn die Temperatur auf Sollwert − halbe „Hysterese“ fällt, "
			+ "und aus, wenn sie Sollwert + halbe Hysterese erreicht (21 °C und 0,5 K: ein bei 20,75 °C, aus bei 21,25 °C). "
			+ "Für Geräte, die nur ein und aus kennen, z. B. einen Elektroheizkörper. "
			+ "Es wirkt „Hysterese“; Boost, „Proportionalbereich“, „Nachstellzeit“, „Vorhaltezeit“ und „PWM-Zyklus“ wirken nicht. "
			+ "„Ventil“ zeigt dabei „Größte Ventilstellung“ (ein) oder „Kleinste Ventilstellung“ (aus)."),
		Map.entry("xp", "Um wie viel Kelvin es zu kalt sein muss, damit der P-Anteil allein das Ventil ganz öffnet. "
			+ "Bei 3 K öffnet 1 K Abweichung das Ventil sofort auf 33 %; kleiner regelt kräftiger, aber unruhiger. "
			+ "Wirkt nur bei „Heizsystem“ „Benutzerdefiniert“ und nicht bei Zweipunktregelung."),
		Map.entry("tn", "Wie schnell der I-Anteil eine bleibende Abweichung ausgleicht: In dieser Zeit wächst er um so viel, wie der P-Anteil beträgt "
			+ "(90 min, 1 K zu kalt bei 3 K: sofort 33 %, nach 90 min 66 %). Kürzer regelt schneller aus, schwingt aber eher über. "
			+ "Wirkt nur bei „Heizsystem“ „Benutzerdefiniert“ und nicht bei Zweipunktregelung."),
		Map.entry("td", "Der D-Anteil: Steigt die Temperatur, nimmt der Regler das Ventil schon vorher zurück, fällt sie, öffnet er früher. "
			+ "Bei 5 min wirkt ein Anstieg um 0,1 K pro Minute wie 0,5 K weniger Abweichung. 0: kein D-Anteil. "
			+ "Braucht „Filterzeitkonstante“ über 0; wirkt nur bei „Heizsystem“ „Benutzerdefiniert“ und nicht bei Zweipunktregelung."),
		Map.entry("filter", "Glättet die gemessene Temperatur, damit Sprünge des Fühlers (z. B. 0,1-K-Stufen oder kurze Zugluft) das Ventil nicht unruhig machen: "
			+ "Nach dieser Zeit hat die gefilterte Temperatur knapp zwei Drittel eines Sprungs mitgemacht. 0: keine Glättung. "
			+ "Wirkt nur bei „Heizsystem“ „Benutzerdefiniert“ (die anderen Heizsysteme glätten mit 2 min), auch bei Zweipunktregelung."),
		Map.entry("cycle", "Die Periode der Pulsweitenmodulation: In jedem Zyklus ist „Schaltausgang“ den Anteil der Ventilstellung lang ein "
			+ "(30 % bei 15 min: 4,5 min ein, 10,5 min aus). Thermische Stellantriebe brauchen einige Minuten zum Öffnen, daher nicht zu kurz. "
			+ "Nur bei Regelung „PWM“."),
		Map.entry("hysteresis", "Der Abstand zwischen Ein- und Ausschalten der Zweipunktregelung, halb unter und halb über dem Sollwert "
			+ "(21 °C und 0,5 K: ein bei 20,75 °C, aus bei 21,25 °C). Größer schaltet seltener, die Temperatur schwankt mehr. "
			+ "Nur bei Regelung „Zweipunkt“."),
		Map.entry("outputMin", "Die Stellung, unter die der Regler das Ventil nicht schließt, z. B. 10 %, damit ein Raum nie ganz auskühlt oder die Pumpe Durchfluss hat. "
			+ "Bei Zweipunktregelung die Stellung für aus. Gilt nicht für Zwangsstellung, Sommer, Ventilschutz und „Ventil ohne Sensor“. "
			+ "Muss unter „Größte Ventilstellung“ liegen."),
		Map.entry("outputMax", "Die Stellung, über die der Regler das Ventil nicht öffnet, z. B. 80 % für einen hydraulisch zu starken Heizkreis; auch der Boost öffnet nur bis hier. "
			+ "Bei Zweipunktregelung die Stellung für ein. Gilt nicht für Zwangsstellung, Sommer, Ventilschutz und „Ventil ohne Sensor“."),
		Map.entry("minChange", "Wie weit sich die Ventilstellung ändern muss, bevor sie erneut gesendet wird; spart Telegramme und bei Funkantrieben Batterie. "
			+ "Bei 2 % wird 41 → 42 % nicht gesendet, 41 → 43 % schon; 0 %, 100 % und die Grenzen „Kleinste Ventilstellung“ und „Größte Ventilstellung“ immer. "
			+ "0: jede Änderung. Betrifft den Ausgang „Ventil“."),
		Map.entry("resendInterval", "Sendet die Ventilstellung nach dieser Zeit erneut, auch wenn sie sich nicht geändert hat, "
			+ "z. B. 30 min für Stellantriebe mit zyklischer Überwachung, die ohne Telegramm in eine Notstellung gehen. 0: keine Wiederholung."),
		Map.entry("comfort", "Der Sollwert, wenn jemand da ist: Betriebsart Komfort oder, ohne Betriebsart, bei „Anwesenheit“ und in der „Heizzeit“, z. B. 21 °C. "
			+ "Die Bewohner dürfen ihn ändern."),
		Map.entry("standby", "Der Sollwert bei kurzer Abwesenheit: Betriebsart Standby oder, ohne Betriebsart, wenn „Anwesenheit“ abwesend meldet, "
			+ "z. B. 19 °C, damit der Raum schnell wieder warm ist. Die Bewohner dürfen ihn ändern."),
		Map.entry("night", "Der Sollwert für die Nacht: Betriebsart Nacht oder, ohne Betriebsart, außerhalb der „Heizzeit“, z. B. 17 °C. "
			+ "Die Bewohner dürfen ihn ändern."),
		Map.entry("frost", "Der Sollwert, unter den der Raum nie fällt: bei Betriebsart Frost-/Gebäudeschutz, bei offenem Fenster und wenn „Heizung ein“ aus ist, "
			+ "z. B. 7 °C. Die Bewohner dürfen ihn ändern."),
		Map.entry("windowDelay", "Wie lange ein Fenster offen sein muss, bevor der Regler auf „Frostschutz“ geht; kurzes Lüften darunter ändert nichts. "
			+ "Vorgabe 1 min; 0: sofort beim Öffnen."),
		Map.entry("boostThreshold", "Steigt der Sollwert und liegt die Raumtemperatur danach um mindestens diesen Wert darunter "
			+ "(z. B. morgens von Nacht 17 °C auf Komfort 21 °C: 4 K), öffnet der Regler das Ventil ganz (bis „Größte Ventilstellung“), "
			+ "statt langsam über den PI-Anteil aufzuheizen. Der Boost endet, wenn die Abweichung unter die halbe Schwelle fällt, "
			+ "spätestens nach „Längster Boost“. 0: kein Boost. Nicht bei Zweipunktregelung."),
		Map.entry("boostDuration", "Wie lange ein Boost höchstens dauert, z. B. 30 min; danach regelt der PI-Regler weiter, auch wenn es noch zu kalt ist. "
			+ "Nur mit „Boost-Schwelle“ über 0 und nicht bei Zweipunktregelung."),
		Map.entry("sensorTimeout", "Wie lange der Regler ohne neuen Temperaturwert auskommt: Kam länger keiner, gilt der Fühler als ausgefallen "
			+ "(Zustand Störung) und das Ventil fährt auf „Ventil ohne Sensor“. Sendet der Fühler alle 15 min, passen 30 min. "
			+ "0: keine Überwachung, nur ein fehlender oder ungültiger Wert ist eine Störung."),
		Map.entry("fallbackPosition", "Die Ventilstellung, solange der Regler keine gültige Raumtemperatur hat (siehe „Sensor-Zeitüberschreitung“), "
			+ "z. B. 30 %, damit der Raum ohne Fühler weder auskühlt noch überheizt."),
		Map.entry("forcedPosition", "Die Ventilstellung, solange „Zwangsstellung“ ein ist, z. B. 100 % für die Estrichtrocknung oder 0 % bei Taupunktalarm."),
		Map.entry("summerPosition", "Die Ventilstellung, solange „Sommer“ ein ist: 0 % schließt das Ventil, "
			+ "ein kleiner Wert hält etwas Durchfluss (bei manchen Systemen gewünscht)."),
		Map.entry("protectionDay", "Der Wochentag, an dem das Ventil gegen Festsitzen (etwa im Sommer) einmal ganz geöffnet wird: "
			+ "1 Montag bis 7 Sonntag; 0: kein Ventilschutz. Ab „Ventilschutz ab“ steht das Ventil dann für „Ventilschutz-Dauer“ auf 100 %. "
			+ "Braucht die Uhrzeit des Controllers; eine Zwangsstellung geht vor."),
		Map.entry("protectionTime", "Die Uhrzeit, ab der am „Ventilschutz-Tag“ der Ventilschutz läuft, z. B. 10:00, wenn niemand eine kalte Heizung vermisst. "
			+ "Nur mit „Ventilschutz-Tag“ über 0."),
		Map.entry("protectionDuration", "Wie lange das Ventil beim Ventilschutz ganz offen steht, z. B. 5 min; danach regelt der Regler weiter. "
			+ "Nur mit „Ventilschutz-Tag“ über 0."));

	/** The English help texts of the ports and parameters by id, as {@link #HELP_DE}. */
	public static final Map<String, String> HELP_EN = Map.ofEntries(
		Map.entry("temperature", "The room temperature the controller regulates by, for example from the sensor of a push-button. "
			+ "Up to 4 sensors can be bound; their \"Aggregation\" makes one value of them (mean, lowest or highest). "
			+ "The controller computes once a minute with the value last received. "
			+ "Without a value, or when none has come for longer than \"Sensor timeout\", the valve goes to \"Valve without sensor\"."),
		Map.entry("setpoint", "A setpoint from outside, for example from the thermostat of a push-button or from a visualization. "
			+ "While it has a value, it takes the place of \"Comfort\", \"Standby\" and \"Night\" (say 22.5 °C instead of 21 °C). "
			+ "An open window, \"Heating on\" off and the mode frost/building protection come first: then \"Frost protection\" applies. "
			+ "Unbound, the controller chooses the setpoint itself."),
		Map.entry("mode", "The KNX HVAC mode (DPT 20.102), for example from the output \"HVAC mode\" of a house mode: "
			+ "1 comfort, 2 standby, 3 night and 4 frost/building protection select the setpoint \"Comfort\", \"Standby\", \"Night\" or \"Frost protection\". "
			+ "0 (auto) or no value: the setpoint follows \"Heating period\" and \"Presence\". "
			+ "An \"External setpoint\" replaces comfort, standby and night, but not frost protection."),
		Map.entry("presence", "Whether someone is in the room or at home, for example from a presence detector. "
			+ "Absent (0) sets back to \"Standby\"; present (1) or no value heats to \"Comfort\". "
			+ "Only takes effect while neither \"HVAC mode\" (1–3) nor \"External setpoint\" has a value and \"Heating period\" is not off."),
		Map.entry("heatingPeriod", "The hours in which the room is heated, for example from a schedule (on 6:00–22:00). "
			+ "Off (0) sets back to \"Night\" and takes precedence over \"Presence\"; on (1) or no value leaves the choice to \"Presence\". "
			+ "Only takes effect while neither \"HVAC mode\" (1–3) nor \"External setpoint\" has a value."),
		Map.entry("window", "The room's window contacts, up to 4; the window counts as open when any of them reports open. "
			+ "When a window stays open longer than \"Window delay\" (1 min by default), the controller regulates to \"Frost protection\" (state window open), "
			+ "and returns at once when all are closed. Opening briefly, within the delay, changes nothing."),
		Map.entry("enable", "Switches the room's heating on and off, for example from a push-button or a visualization. "
			+ "Off (0): the controller only keeps \"Frost protection\" (say 7 °C), so the valve does not necessarily close. "
			+ "On (1) or unbound: normal operation."),
		Map.entry("summer", "Summer operation, for example from the output \"Summer\" of a heating curve. "
			+ "On (1): the valve stands at \"Valve in summer\" (0 % by default), the controller does not regulate and reports no \"Heat demand\". "
			+ "The valve protection still runs."),
		Map.entry("forced", "Forced position, for example for screed drying, maintenance or a dew point alarm. "
			+ "On (1): the valve stands at \"Forced valve position\" (100 % by default), ahead of everything else, and there is no \"Heat demand\"."),
		Map.entry("valve", "The valve position 0–100 % for an actuator that takes a percentage (DPT 5.001); "
			+ "the output for the control \"Continuous\". "
			+ "A change is sent from \"Smallest change sent\" on, and whenever it reaches 0 %, 100 %, \"Lowest valve position\" or \"Highest valve position\", "
			+ "repeated after \"Resend interval\". With PWM and two-point control the position is shown here as well, but there \"Switch\" is the output to bind. "
			+ "When the virtual device stops, the valve goes to 30 %."),
		Map.entry("switch", "An on/off output for a switching or heating actuator. "
			+ "With \"PWM\" it pulses once per \"PWM cycle\" (30 % of 15 min: 4.5 min on), with \"Two-point\" it switches by setpoint and \"Hysteresis\"; "
			+ "with \"Continuous\" it is on while the valve is open (for a pump or an indicator, say). "
			+ "When the virtual device stops, it switches off."),
		Map.entry("demand", "Tells that the room needs heat: on while the valve is above 0 %, "
			+ "except in the forced position, during the valve protection and in summer. "
			+ "Meant for the input \"Heat demands\" of a heating demand, or directly for the request to the heat source. "
			+ "When the virtual device stops, it switches off."),
		Map.entry("activeSetpoint", "The setpoint the controller regulates to now, for example 21 °C in comfort or 7 °C with the window open; "
			+ "for display or troubleshooting."),
		Map.entry("state", "What the controller is doing: 0 off (\"Heating on\" is off, frost protection applies), 1 heating, 2 window open, "
			+ "3 frost/building protection, 4 boost, 5 fault (no valid temperature), 6 valve protection, 7 summer, 8 forced position."),
		Map.entry("p", "The P term of the valve position in %: \"Control error\" times 100 / \"Proportional band\" "
			+ "(1 K too cold with 3 K: 33 %). For troubleshooting; 0 with two-point control, during a boost and while the controller does not regulate."),
		Map.entry("i", "The I term of the valve position in %: grows while the room is too cold, by as much as the P term per \"Reset time\", "
			+ "and shrinks while it is too warm; limited to \"Lowest valve position\" through \"Highest valve position\". "
			+ "For troubleshooting; stands still with two-point control."),
		Map.entry("d", "The D term of the valve position in %: takes the position back while the temperature rises and adds while it falls. "
			+ "Only with \"Derivative time\" and \"Filter time constant\" above 0; for troubleshooting."),
		Map.entry("error", "The control error: active setpoint minus filtered temperature, positive meaning too cold "
			+ "(setpoint 21 °C, room 20.2 °C: 0.8 K). For troubleshooting."),
		Map.entry("filtered", "The room temperature after the smoothing by \"Filter time constant\"; this is the value the controller computes with. For troubleshooting."),
		Map.entry("system", "How sluggish the heating is; the choice sets the control parameters to fit. "
			+ "Takes effect with \"Continuous\" and \"PWM\", not with \"Two-point\".\n\n"
			+ "Radiator: proportional band 3 K, reset time 90 min, derivative time 5 min, filter time constant 2 min. "
			+ "For panel and column radiators: 1 K too cold opens the valve to 33 % at once and to 66 % after 90 min.\n\n"
			+ "Underfloor heating: 3 K, 180 min, 10 min, 2 min. For floor and wall heating with a large thermal mass: "
			+ "the I term grows half as fast, so the screed does not overheat.\n\n"
			+ "Convector: 2 K, 45 min, 5 min, 2 min. For fast heaters such as trench or fan convectors: "
			+ "1 K too cold gives 50 % at once and 100 % after 45 min.\n\n"
			+ "Custom: \"Proportional band\", \"Reset time\", \"Derivative time\" and \"Filter time constant\" apply; "
			+ "only with this choice do they take effect. Their defaults are those of the radiator, but without a D term (derivative time 0)."),
		Map.entry("controlType", "How the controller drives the actuator; it decides which output to bind.\n\n"
			+ "Continuous: the controller computes a valve position of 0–100 % from the deviation from the setpoint and sends it on the output \"Valve\", "
			+ "a change from \"Smallest change sent\" on (2 %, say). "
			+ "For actuators that take a percentage, such as a KNX heating actuator with a motorized valve drive (DPT 5.001). "
			+ "\"Heating system\" applies (with \"Custom\": \"Proportional band\", \"Reset time\", \"Derivative time\", \"Filter time constant\"), "
			+ "as do the boost, \"Lowest valve position\" and \"Highest valve position\"; \"PWM cycle\" and \"Hysteresis\" do not.\n\n"
			+ "PWM: the same computed position, but as on/off on the output \"Switch\": "
			+ "in every \"PWM cycle\" it is on for the share of the position (30 % of 15 min: 4.5 min on, 10.5 min off). "
			+ "For thermal actuators on a switching or heating actuator, which know only open and closed. "
			+ "The same parameters as with \"Continuous\" apply, plus \"PWM cycle\"; \"Hysteresis\" does not.\n\n"
			+ "Two-point: no PI controller: \"Switch\" turns on when the temperature falls to the setpoint minus half the \"Hysteresis\" "
			+ "and off when it reaches the setpoint plus half of it (21 °C and 0.5 K: on at 20.75 °C, off at 21.25 °C). "
			+ "For devices that know only on and off, such as an electric radiator. "
			+ "\"Hysteresis\" applies; the boost, \"Proportional band\", \"Reset time\", \"Derivative time\" and \"PWM cycle\" do not. "
			+ "\"Valve\" then shows \"Highest valve position\" (on) or \"Lowest valve position\" (off)."),
		Map.entry("xp", "How many kelvin too cold it must be for the P term alone to open the valve fully. "
			+ "With 3 K, a deviation of 1 K opens the valve to 33 % at once; smaller is more forceful, but less calm. "
			+ "Only with \"Heating system\" \"Custom\", not with two-point control."),
		Map.entry("tn", "How fast the I term removes a lasting deviation: in this time it grows by as much as the P term "
			+ "(90 min, 1 K too cold with 3 K: 33 % at once, 66 % after 90 min). Shorter settles faster but overshoots more readily. "
			+ "Only with \"Heating system\" \"Custom\", not with two-point control."),
		Map.entry("td", "The D term: when the temperature rises, the controller takes the valve back ahead of time; when it falls, it opens earlier. "
			+ "With 5 min, a rise of 0.1 K per minute counts like 0.5 K less deviation. 0: no D term. "
			+ "Needs \"Filter time constant\" above 0; only with \"Heating system\" \"Custom\", not with two-point control."),
		Map.entry("filter", "Smooths the measured temperature, so the sensor's steps (0.1 K, say, or a brief draught) do not make the valve restless: "
			+ "after this time the filtered temperature has followed almost two thirds of a step. 0: no smoothing. "
			+ "Only with \"Heating system\" \"Custom\" (the other heating systems smooth with 2 min), also with two-point control."),
		Map.entry("cycle", "The period of the pulse width modulation: in every cycle \"Switch\" is on for the share of the valve position "
			+ "(30 % of 15 min: 4.5 min on, 10.5 min off). Thermal actuators take a few minutes to open, so do not make it too short. "
			+ "Only with the control \"PWM\"."),
		Map.entry("hysteresis", "The gap between switching on and off in two-point control, half below and half above the setpoint "
			+ "(21 °C and 0.5 K: on at 20.75 °C, off at 21.25 °C). Larger switches less often, and the temperature swings more. "
			+ "Only with the control \"Two-point\"."),
		Map.entry("outputMin", "The position the controller does not close the valve below, for example 10 %, so a room never cools down completely or the pump keeps a flow. "
			+ "With two-point control the position for off. Does not apply to the forced position, summer, the valve protection and \"Valve without sensor\". "
			+ "Must be below \"Highest valve position\"."),
		Map.entry("outputMax", "The position the controller does not open the valve beyond, for example 80 % for a hydraulically too strong circuit; the boost opens only this far, too. "
			+ "With two-point control the position for on. Does not apply to the forced position, summer, the valve protection and \"Valve without sensor\"."),
		Map.entry("minChange", "How far the valve position has to change before it is sent again; saves telegrams and, with radio actuators, battery. "
			+ "With 2 %, 41 → 42 % is not sent, 41 → 43 % is; 0 %, 100 % and the limits \"Lowest valve position\" and \"Highest valve position\" always are. "
			+ "0: every change. Concerns the output \"Valve\"."),
		Map.entry("resendInterval", "Sends the valve position again after this time even when it has not changed, "
			+ "for example 30 min for actuators with cyclic monitoring that go to an emergency position without telegrams. 0: no repetition."),
		Map.entry("comfort", "The setpoint while someone is there: HVAC mode comfort or, without a mode, with \"Presence\" and within the \"Heating period\", 21 °C, say. "
			+ "The residents may change it."),
		Map.entry("standby", "The setpoint for a short absence: HVAC mode standby or, without a mode, when \"Presence\" reports absent, "
			+ "19 °C, say, so the room is warm again quickly. The residents may change it."),
		Map.entry("night", "The setpoint for the night: HVAC mode night or, without a mode, outside the \"Heating period\", 17 °C, say. "
			+ "The residents may change it."),
		Map.entry("frost", "The setpoint the room never falls below: with HVAC mode frost/building protection, with an open window and while \"Heating on\" is off, "
			+ "7 °C, say. The residents may change it."),
		Map.entry("windowDelay", "How long a window has to be open before the controller goes to \"Frost protection\"; airing for less changes nothing. "
			+ "1 min by default; 0: at once on opening."),
		Map.entry("boostThreshold", "When the setpoint rises and the room temperature is then at least this much below it "
			+ "(in the morning from night 17 °C to comfort 21 °C, say: 4 K), the controller opens the valve fully (up to \"Highest valve position\") "
			+ "instead of heating up slowly through the PI terms. The boost ends when the deviation falls below half the threshold, "
			+ "after \"Longest boost\" at the latest. 0: no boost. Not with two-point control."),
		Map.entry("boostDuration", "The longest a boost lasts, for example 30 min; then the PI controller takes over even if the room is still too cold. "
			+ "Only with \"Boost threshold\" above 0, not with two-point control."),
		Map.entry("sensorTimeout", "How long the controller manages without a new temperature: when none has come for longer, the sensor counts as failed "
			+ "(state fault) and the valve goes to \"Valve without sensor\". For a sensor sending every 15 min, 30 min fits. "
			+ "0: no monitoring; only a missing or invalid value is a fault."),
		Map.entry("fallbackPosition", "The valve position while the controller has no valid room temperature (see \"Sensor timeout\"), "
			+ "for example 30 %, so the room neither cools down nor overheats without its sensor."),
		Map.entry("forcedPosition", "The valve position while \"Forced position\" is on, for example 100 % for screed drying or 0 % on a dew point alarm."),
		Map.entry("summerPosition", "The valve position while \"Summer\" is on: 0 % closes the valve, "
			+ "a small value keeps some flow (which some systems want)."),
		Map.entry("protectionDay", "The weekday on which the valve is opened fully once against seizing up (in summer, say): "
			+ "1 Monday through 7 Sunday; 0: no valve protection. From \"Valve protection from\" on, the valve then stands at 100 % for \"Valve protection duration\". "
			+ "Needs the controller's clock; a forced position comes first."),
		Map.entry("protectionTime", "The time of day from which the valve protection runs on the \"Valve protection day\", for example 10:00, when nobody misses a cold radiator. "
			+ "Only with \"Valve protection day\" above 0."),
		Map.entry("protectionDuration", "How long the valve stands fully open for the valve protection, for example 5 min; then the controller regulates again. "
			+ "Only with \"Valve protection day\" above 0."));

	private RoomControllerTexts() {
		// Constants.
	}

}
