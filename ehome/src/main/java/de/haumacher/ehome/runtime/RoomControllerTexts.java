/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.Map;

import de.haumacher.ehome.blocks.RoomController;

/**
 * The German texts of the room controller's manifest (homecontrol <code>spec/blocks.md</code> 14.1):
 * the manifest carries its labels in English, as the firmware does; these are their translations for
 * E-Home's views, by the id of the port, parameter or choice.
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

	private RoomControllerTexts() {
		// Constants.
	}

	/**
	 * The label of a port, parameter or choice of a block type in the language: the German one where
	 * E-Home has it, else the manifest's English.
	 */
	public static String label(String type, String id, String english, String language) {
		if ("de".equals(language) && TYPE.equals(type)) {
			String de = DE.get(id);
			if (de != null) {
				return de;
			}
		}
		return english;
	}

}
