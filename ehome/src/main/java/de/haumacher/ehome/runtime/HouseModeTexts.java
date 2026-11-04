/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.Map;

import de.haumacher.ehome.blocks.HouseMode;

/**
 * The German texts of the house mode's manifest (homecontrol <code>spec/blocks.md</code> 19.1), by
 * the id of the port, parameter or choice (see {@link RoomControllerTexts}).
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

	private HouseModeTexts() {
		// Constants only.
	}

}
