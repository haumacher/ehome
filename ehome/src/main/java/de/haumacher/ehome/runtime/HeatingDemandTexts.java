/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.Map;

import de.haumacher.ehome.blocks.HeatingDemand;

/**
 * The German texts of the heating demand's manifest (homecontrol <code>spec/blocks.md</code> 16.1),
 * by the id of the port, parameter or choice (see {@link RoomControllerTexts}).
 */
public final class HeatingDemandTexts {

	/** The type id. */
	public static final String TYPE = HeatingDemand.TYPE;

	/** The German label of the type. */
	public static final String LABEL_DE = "Wärmebedarf";

	/** The German labels of the ports and parameters by id, the choices by <code>parameter.choice</code>. */
	public static final Map<String, String> DE = Map.ofEntries(
		Map.entry("valves", "Ventilstellungen"),
		Map.entry("demands", "Wärmeanforderungen"),
		Map.entry("enable", "Heizung ein"),
		Map.entry("summer", "Sommer"),
		Map.entry("request", "Anforderung Wärmeerzeuger"),
		Map.entry("level", "Bedarfsgrad"),
		Map.entry("pump", "Umwälzpumpe"),
		Map.entry("flowSetpointOffset", "Vorlaufanhebung"),
		Map.entry("state", "Zustand"),
		Map.entry("staleInputs", "Eingänge ohne gültigen Wert"),
		Map.entry("mode", "Bedarfsermittlung"),
		Map.entry("mode.max", "Höchstes Ventil"),
		Map.entry("mode.topMean", "Mittel der höchsten"),
		Map.entry("topCount", "Anzahl gemittelter Ventile"),
		Map.entry("onThreshold", "Einschaltschwelle"),
		Map.entry("offThreshold", "Ausschaltschwelle"),
		Map.entry("minOn", "Mindestlaufzeit"),
		Map.entry("minOff", "Mindestpause"),
		Map.entry("pumpRunOn", "Pumpennachlauf"),
		Map.entry("targetLevel", "Bedarfsgrad für die Anhebung"),
		Map.entry("offsetGain", "Anhebungsrate"),
		Map.entry("maxOffset", "Größte Anhebung"),
		Map.entry("staleInput", "Eingang ohne gültigen Wert"),
		Map.entry("staleInput.ignore", "Ignorieren"),
		Map.entry("staleInput.full", "Als voll geöffnet werten"));

	private HeatingDemandTexts() {
		// Constants.
	}

}
