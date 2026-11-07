/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.Map;

import de.haumacher.ehome.blocks.HouseMode;

/**
 * The German texts of the house mode's manifest (homecontrol <code>spec/blocks.md</code> 19.1), by
 * the id of the port, parameter or choice (see {@link RoomControllerTexts}), and the help texts of the ports and parameters in German and
 * English.
 */
public final class HouseModeTexts {

	/** The type id. */
	public static final String TYPE = HouseMode.TYPE;

	/** The German label of the type. */
	public static final String LABEL_DE = "Hausmodus";

	/** The German labels of the ports and parameters by id, the choices by <code>parameter.choice</code>. */
	public static final Map<String, String> DE = Map.ofEntries(
		Map.entry("presence", "Anwesenheit"),
		Map.entry("awayButton", "Taster „Abwesend“"),
		Map.entry("nightButton", "Taster „Gute Nacht“"),
		Map.entry("homeButton", "Taster „Zuhause“"),
		Map.entry("vacation", "Urlaub"),
		Map.entry("morning", "Morgen"),
		Map.entry("evening", "Abend"),
		Map.entry("mode", "Hausmodus"),
		Map.entry("hvacMode", "Betriebsart (HVAC)"),
		Map.entry("home", "Zuhause"),
		Map.entry("away", "Abwesend"),
		Map.entry("night", "Nacht"),
		Map.entry("onVacation", "Im Urlaub"),
		Map.entry("occupied", "Bewohnt"),
		Map.entry("since", "Seit"),
		Map.entry("awayDelay", "Abwesend nach"),
		Map.entry("returnDetection", "Rückkehrerkennung"),
		Map.entry("hvacHome", "Betriebsart zuhause"),
		Map.entry("hvacHome.auto", "Automatisch"),
		Map.entry("hvacHome.comfort", "Komfort"),
		Map.entry("hvacHome.standby", "Standby"),
		Map.entry("hvacHome.night", "Nacht"),
		Map.entry("hvacHome.protection", "Frost-/Gebäudeschutz"),
		Map.entry("hvacAway", "Betriebsart abwesend"),
		Map.entry("hvacAway.auto", "Automatisch"),
		Map.entry("hvacAway.comfort", "Komfort"),
		Map.entry("hvacAway.standby", "Standby"),
		Map.entry("hvacAway.night", "Nacht"),
		Map.entry("hvacAway.protection", "Frost-/Gebäudeschutz"),
		Map.entry("hvacNight", "Betriebsart nachts"),
		Map.entry("hvacNight.auto", "Automatisch"),
		Map.entry("hvacNight.comfort", "Komfort"),
		Map.entry("hvacNight.standby", "Standby"),
		Map.entry("hvacNight.night", "Nacht"),
		Map.entry("hvacNight.protection", "Frost-/Gebäudeschutz"),
		Map.entry("hvacVacation", "Betriebsart im Urlaub"),
		Map.entry("hvacVacation.auto", "Automatisch"),
		Map.entry("hvacVacation.comfort", "Komfort"),
		Map.entry("hvacVacation.standby", "Standby"),
		Map.entry("hvacVacation.night", "Nacht"),
		Map.entry("hvacVacation.protection", "Frost-/Gebäudeschutz"));

	/** The paragraphs of the HVAC modes the four <code>hvac</code> parameters choose from, in German. */
	private static final String HVAC_CHOICES_DE = "\n\n"
		+ "Automatisch: sendet 0; die Raumregler wählen ihren Sollwert dann selbst nach ihrer „Heizzeit“ und „Anwesenheit“.\n\n"
		+ "Komfort: sendet 1; die Raumregler heizen auf ihren Sollwert „Komfort“, z. B. 21 °C.\n\n"
		+ "Standby: sendet 2; die Raumregler senken auf „Standby“ ab, z. B. 19 °C, und sind schnell wieder warm.\n\n"
		+ "Nacht: sendet 3; die Raumregler senken auf „Nacht“ ab, z. B. 17 °C.\n\n"
		+ "Frost-/Gebäudeschutz: sendet 4; die Raumregler halten nur „Frostschutz“, z. B. 7 °C, etwa für eine lange Abwesenheit.";

	/** The paragraphs of the HVAC modes in English, as {@link #HVAC_CHOICES_DE}. */
	private static final String HVAC_CHOICES_EN = "\n\n"
		+ "Auto: sends 0; the room controllers then choose their setpoint themselves by their \"Heating period\" and \"Presence\".\n\n"
		+ "Comfort: sends 1; the room controllers heat to their setpoint \"Comfort\", 21 °C, say.\n\n"
		+ "Standby: sends 2; the room controllers set back to \"Standby\", 19 °C, say, and are warm again quickly.\n\n"
		+ "Night: sends 3; the room controllers set back to \"Night\", 17 °C, say.\n\n"
		+ "Frost/building protection: sends 4; the room controllers only keep \"Frost protection\", 7 °C, say, for a long absence, for instance.";

	/**
	 * The German help texts of the ports and parameters by id (haumacher/ehome #101): what the member
	 * does, for the installer. The choices of an enumeration each in a paragraph of their own.
	 */
	public static final Map<String, String> HELP_DE = Map.ofEntries(
		Map.entry("presence", "Präsenz- und Bewegungsmelder des Hauses, bis zu 16; anwesend ist, wenn einer von ihnen Anwesenheit meldet. "
			+ "Meldet keiner mehr Anwesenheit, wird aus Zuhause nach „Abwesend nach“ Abwesend; "
			+ "aus Abwesend wird mit „Rückkehrerkennung“ wieder Zuhause, sobald jemand erkannt wird. Nachts und im Urlaub ändert sie nichts."),
		Map.entry("awayButton", "Taster für „Ich gehe“, z. B. an der Haustür, bis zu 4: Jedes Telegramm mit 1 (auch wiederholt) schaltet sofort auf Abwesend. "
			+ "Die „Rückkehrerkennung“ greift danach erst, wenn die Melder einmal abwesend gemeldet haben, "
			+ "damit man beim Hinausgehen nicht gleich wieder als zurückgekehrt gilt."),
		Map.entry("nightButton", "Taster für „Gute Nacht“, z. B. am Bett, bis zu 4: Jedes Telegramm mit 1 schaltet auf Nacht, aus Zuhause wie aus Abwesend."),
		Map.entry("homeButton", "Taster für „Ich bin da“, bis zu 4: Jedes Telegramm mit 1 schaltet auf Zuhause, aus Abwesend wie aus Nacht, "
			+ "und beginnt die Wartezeit „Abwesend nach“ von vorn."),
		Map.entry("vacation", "Ein Urlaubsschalter, z. B. aus einer Visualisierung: Ein (1) schaltet auf Urlaub, vor allem anderen; "
			+ "Taster, Melder, Morgen und Abend wirken dann nicht. Aus (0) beendet den Urlaub mit Zuhause."),
		Map.entry("morning", "Der Morgen, z. B. von einem Zeitprogramm um 6:30: Der Wechsel von 0 auf 1 beendet die Nacht mit Zuhause. "
			+ "In den anderen Modi ändert er nichts."),
		Map.entry("evening", "Der Abend, z. B. von einem Zeitprogramm um 23:00: Der Wechsel von 0 auf 1 schaltet aus Zuhause auf Nacht, "
			+ "auch wenn niemand „Gute Nacht“ drückt. In den anderen Modi ändert er nichts."),
		Map.entry("mode", "Der Hausmodus als Zahl: 0 Zuhause, 1 Abwesend, 2 Nacht, 3 Urlaub; z. B. für eine Visualisierung oder Regeln. "
			+ "Er übersteht einen Neustart des Controllers. Hält das virtuelle Gerät an, geht er auf Zuhause."),
		Map.entry("hvacMode", "Die KNX-Betriebsart (DPT 20.102) zum Hausmodus, für die Eingänge „Betriebsart (HVAC)“ der Raumregler: "
			+ "je Modus nach „Betriebsart zuhause“, „Betriebsart abwesend“, „Betriebsart nachts“ und „Betriebsart im Urlaub“ "
			+ "(Vorgabe: Komfort, Standby, Nacht, Frost-/Gebäudeschutz). Hält das virtuelle Gerät an, geht sie auf Automatisch."),
		Map.entry("home", "Ein, solange der Hausmodus Zuhause ist, z. B. für Licht- oder Musikszenen. Hält das virtuelle Gerät an, schaltet er ein."),
		Map.entry("away", "Ein, solange der Hausmodus Abwesend ist, z. B. zum Scharfschalten einer Alarmanlage oder für eine Anwesenheitssimulation. "
			+ "Hält das virtuelle Gerät an, schaltet er aus."),
		Map.entry("night", "Ein, solange der Hausmodus Nacht ist, z. B. für ein Nachtlicht oder zum Absenken der Rollläden. Hält das virtuelle Gerät an, schaltet er aus."),
		Map.entry("onVacation", "Ein, solange der Hausmodus Urlaub ist. Hält das virtuelle Gerät an, schaltet er aus."),
		Map.entry("occupied", "Ein, solange jemand im Haus ist: bei Zuhause und Nacht, aus bei Abwesend und Urlaub; "
			+ "z. B. für den Eingang „Anwesenheit“ einer Beschattung (Hitzeschutz). Hält das virtuelle Gerät an, schaltet er ein."),
		Map.entry("since", "Wann der Hausmodus zuletzt gewechselt hat, als Minuten seit dem 1. 1. 1970 in Ortszeit; erst, wenn der Controller die Uhrzeit kennt. "
			+ "Zur Fehlersuche."),
		Map.entry("awayDelay", "Wie lange die Melder ununterbrochen abwesend melden müssen, bevor aus Zuhause Abwesend wird, z. B. 30 min; "
			+ "kürzere Ruhe (Lesen, Fernsehen) ändert nichts. Gilt nicht nachts. Die Bewohner dürfen sie ändern."),
		Map.entry("returnDetection", "Ein: Meldet ein Melder im Modus Abwesend Anwesenheit, wird wieder Zuhause, nach dem Taster „Abwesend“ aber erst, "
			+ "wenn zwischendurch einmal abwesend gemeldet wurde. Aus: Zurück nach Zuhause nur mit dem Taster „Zuhause“, "
			+ "z. B. wenn Haustiere die Melder auslösen. Die Bewohner dürfen sie ändern."),
		Map.entry("hvacHome", "Die Betriebsart, die „Betriebsart (HVAC)“ sendet, solange der Hausmodus Zuhause ist (Vorgabe Komfort)." + HVAC_CHOICES_DE),
		Map.entry("hvacAway", "Die Betriebsart, die „Betriebsart (HVAC)“ sendet, solange der Hausmodus Abwesend ist (Vorgabe Standby)." + HVAC_CHOICES_DE),
		Map.entry("hvacNight", "Die Betriebsart, die „Betriebsart (HVAC)“ sendet, solange der Hausmodus Nacht ist (Vorgabe Nacht)." + HVAC_CHOICES_DE),
		Map.entry("hvacVacation", "Die Betriebsart, die „Betriebsart (HVAC)“ sendet, solange der Hausmodus Urlaub ist (Vorgabe Frost-/Gebäudeschutz)." + HVAC_CHOICES_DE));

	/** The English help texts of the ports and parameters by id, as {@link #HELP_DE}. */
	public static final Map<String, String> HELP_EN = Map.ofEntries(
		Map.entry("presence", "The house's presence and motion detectors, up to 16; someone is present when any of them reports presence. "
			+ "When none reports presence any more, home turns into away after \"Away after\"; "
			+ "with \"Return detection\", away turns into home again as soon as someone is detected. At night and on vacation it changes nothing."),
		Map.entry("awayButton", "Push-buttons for \"I'm leaving\", at the front door, say, up to 4: every telegram with 1 (also a repeated one) switches to away at once. "
			+ "The \"Return detection\" only takes effect after the detectors have reported absent once, "
			+ "so walking out does not count as coming back."),
		Map.entry("nightButton", "Push-buttons for \"Good night\", at the bed, say, up to 4: every telegram with 1 switches to night, from home as from away."),
		Map.entry("homeButton", "Push-buttons for \"I'm home\", up to 4: every telegram with 1 switches to home, from away as from night, "
			+ "and starts the wait \"Away after\" afresh."),
		Map.entry("vacation", "A vacation switch, from a visualization, say: on (1) switches to vacation, ahead of everything else; "
			+ "buttons, detectors, morning and evening then have no effect. Off (0) ends the vacation with home."),
		Map.entry("morning", "The morning, from a schedule at 6:30, say: the change from 0 to 1 ends the night with home. "
			+ "In the other modes it changes nothing."),
		Map.entry("evening", "The evening, from a schedule at 23:00, say: the change from 0 to 1 switches from home to night, "
			+ "even if nobody presses \"Good night\". In the other modes it changes nothing."),
		Map.entry("mode", "The house mode as a number: 0 home, 1 away, 2 night, 3 vacation; for a visualization or rules, say. "
			+ "It survives a restart of the controller. When the virtual device stops, it goes to home."),
		Map.entry("hvacMode", "The KNX HVAC mode (DPT 20.102) of the house mode, for the inputs \"HVAC mode\" of the room controllers: "
			+ "per mode as \"HVAC mode at home\", \"HVAC mode away\", \"HVAC mode at night\" and \"HVAC mode on vacation\" set it "
			+ "(by default comfort, standby, night, frost/building protection). When the virtual device stops, it goes to auto."),
		Map.entry("home", "On while the house mode is home, for lighting or music scenes, say. When the virtual device stops, it switches on."),
		Map.entry("away", "On while the house mode is away, to arm an alarm system or for a presence simulation, say. "
			+ "When the virtual device stops, it switches off."),
		Map.entry("night", "On while the house mode is night, for a night light or to lower the shutters, say. When the virtual device stops, it switches off."),
		Map.entry("onVacation", "On while the house mode is vacation. When the virtual device stops, it switches off."),
		Map.entry("occupied", "On while someone is in the house: at home and at night, off when away and on vacation; "
			+ "for the input \"Presence\" of a sun shading (heat protection), say. When the virtual device stops, it switches on."),
		Map.entry("since", "When the house mode last changed, as minutes since 1 January 1970 in local time; only once the controller knows the time. "
			+ "For troubleshooting."),
		Map.entry("awayDelay", "How long the detectors have to report absent without a break before home turns into away, 30 min, say; "
			+ "shorter quiet (reading, watching TV) changes nothing. Not at night. The residents may change it."),
		Map.entry("returnDetection", "On: when a detector reports presence in the mode away, it turns into home again, after the \"Away button\", however, "
			+ "only once absent has been reported in between. Off: back to home only with the \"Home button\", "
			+ "for instance when pets set the detectors off. The residents may change it."),
		Map.entry("hvacHome", "The HVAC mode \"HVAC mode\" sends while the house mode is home (comfort by default)." + HVAC_CHOICES_EN),
		Map.entry("hvacAway", "The HVAC mode \"HVAC mode\" sends while the house mode is away (standby by default)." + HVAC_CHOICES_EN),
		Map.entry("hvacNight", "The HVAC mode \"HVAC mode\" sends while the house mode is night (night by default)." + HVAC_CHOICES_EN),
		Map.entry("hvacVacation", "The HVAC mode \"HVAC mode\" sends while the house mode is vacation (frost/building protection by default)." + HVAC_CHOICES_EN));

	private HouseModeTexts() {
		// Constants only.
	}

}
