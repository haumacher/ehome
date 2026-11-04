/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.Map;

import de.haumacher.ehome.blocks.HeatingCurve;

/**
 * The German texts of the heating curve's manifest (homecontrol <code>spec/blocks.md</code> 17.1),
 * by the id of the port, parameter or choice (see {@link RoomControllerTexts}).
 */
public final class HeatingCurveTexts {

	/** The type id. */
	public static final String TYPE = HeatingCurve.TYPE;

	/** The German label of the type. */
	public static final String LABEL_DE = "Heizkurve";

	/** The German labels of the ports and parameters by id, the choices by <code>parameter.choice</code>. */
	public static final Map<String, String> DE = Map.ofEntries(
		Map.entry("outside", "Außentemperatur"),
		Map.entry("roomSetpoint", "Raumsollwert"),
		Map.entry("offset", "Vorlaufanhebung"),
		Map.entry("enable", "Heizung ein"),
		Map.entry("summerSwitch", "Sommer-/Winterschalter"),
		Map.entry("flowSetpoint", "Vorlauf-Sollwert"),
		Map.entry("summer", "Sommer"),
		Map.entry("outsideFiltered", "Gefilterte Außentemperatur"),
		Map.entry("state", "Zustand"),
		Map.entry("curve", "Kurvenform"),
		Map.entry("curve.slope", "Neigung und Niveau"),
		Map.entry("curve.design", "Auslegungspunkt"),
		Map.entry("curve.points", "Stützpunkte"),
		Map.entry("slope", "Neigung"),
		Map.entry("level", "Niveau"),
		Map.entry("roomTemperature", "Raumtemperatur der Kurve"),
		Map.entry("designOutside", "Auslegungs-Außentemperatur"),
		Map.entry("designFlow", "Auslegungs-Vorlauftemperatur"),
		Map.entry("pointCount", "Anzahl der Stützpunkte"),
		Map.entry("outside1", "Außentemperatur 1"),
		Map.entry("flow1", "Vorlauf 1"),
		Map.entry("outside2", "Außentemperatur 2"),
		Map.entry("flow2", "Vorlauf 2"),
		Map.entry("outside3", "Außentemperatur 3"),
		Map.entry("flow3", "Vorlauf 3"),
		Map.entry("outside4", "Außentemperatur 4"),
		Map.entry("flow4", "Vorlauf 4"),
		Map.entry("outside5", "Außentemperatur 5"),
		Map.entry("flow5", "Vorlauf 5"),
		Map.entry("outside6", "Außentemperatur 6"),
		Map.entry("flow6", "Vorlauf 6"),
		Map.entry("minFlow", "Kleinster Vorlauf"),
		Map.entry("maxFlow", "Größter Vorlauf"),
		Map.entry("minChange", "Kleinste gesendete Änderung"),
		Map.entry("outsideFilter", "Filterzeitkonstante der Außentemperatur"),
		Map.entry("summerOn", "Sommer oberhalb"),
		Map.entry("summerOff", "Winter unterhalb"),
		Map.entry("summerDays", "Tage bis zum Sommer"),
		Map.entry("frostOutside", "Frostschutz unterhalb"),
		Map.entry("offFlow", "Vorlauf ausgeschaltet"),
		Map.entry("sensorTimeout", "Sensor-Zeitüberschreitung"));

	private HeatingCurveTexts() {
		// Constants.
	}

}
