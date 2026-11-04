/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.Map;

import de.haumacher.ehome.blocks.PvSurplus;

/**
 * The German texts of the PV surplus control's manifest (homecontrol <code>spec/blocks.md</code>
 * 20.1), by the id of the port, parameter or choice (see {@link RoomControllerTexts}).
 */
public final class PvSurplusTexts {

	/** The type id. */
	public static final String TYPE = PvSurplus.TYPE;

	/** The German label of the type. */
	public static final String LABEL_DE = "PV-Überschussregelung";

	/** The German labels of the ports and parameters by id, the choices by <code>parameter.choice</code>. */
	public static final Map<String, String> DE = Map.ofEntries(
		Map.entry("gridPower", "Netzleistung"),
		Map.entry("pvPower", "PV-Leistung"),
		Map.entry("housePower", "Hausverbrauch"),
		Map.entry("batteryPower", "Batterieleistung"),
		Map.entry("batterySoc", "Ladezustand der Batterie"),
		Map.entry("lowerPower", "Leistung der nachrangigen Verbraucher"),
		Map.entry("enable", "Überschussregelung ein"),
		Map.entry("ready1", "Verbraucher 1 bereit"),
		Map.entry("ready2", "Verbraucher 2 bereit"),
		Map.entry("switch1", "Verbraucher 1 ein"),
		Map.entry("setpoint1", "Verbraucher 1 Sollwert"),
		Map.entry("state1", "Verbraucher 1 Zustand"),
		Map.entry("energy1", "Verbraucher 1 Energie heute"),
		Map.entry("switch2", "Verbraucher 2 ein"),
		Map.entry("setpoint2", "Verbraucher 2 Sollwert"),
		Map.entry("state2", "Verbraucher 2 Zustand"),
		Map.entry("energy2", "Verbraucher 2 Energie heute"),
		Map.entry("loadPower", "Leistung der Verbraucher"),
		Map.entry("surplus", "Verbleibender Überschuss"),
		Map.entry("gridSign", "Vorzeichen des Netzzählers"),
		Map.entry("gridSign.importPositive", "Bezug positiv"),
		Map.entry("gridSign.exportPositive", "Einspeisung positiv"),
		Map.entry("batterySign", "Vorzeichen der Batterie"),
		Map.entry("batterySign.chargePositive", "Laden positiv"),
		Map.entry("batterySign.dischargePositive", "Entladen positiv"),
		Map.entry("surplusFilter", "Glättung des Überschusses"),
		Map.entry("onMargin", "Einschaltreserve"),
		Map.entry("allowedImport", "Zulässiger Netzbezug"),
		Map.entry("batteryPriority", "Batterie zuerst bis Ladezustand"),
		Map.entry("meterTimeout", "Zählerausfall nach"),
		Map.entry("setpointUnit", "Einheit der Sollwerte"),
		Map.entry("setpointUnit.watt", "W"),
		Map.entry("setpointUnit.percent", "% der Leistung"),
		Map.entry("power1", "Verbraucher 1 Leistung"),
		Map.entry("minPower1", "Verbraucher 1 kleinste Leistung"),
		Map.entry("step1", "Verbraucher 1 Leistungsstufe"),
		Map.entry("minOn1", "Verbraucher 1 Mindestlaufzeit"),
		Map.entry("minOff1", "Verbraucher 1 Mindestpause"),
		Map.entry("maxPerDay1", "Verbraucher 1 höchste Laufzeit pro Tag"),
		Map.entry("mustRunBy1", "Verbraucher 1 Pflichtlaufzeit bis"),
		Map.entry("mustRunFor1", "Verbraucher 1 Pflichtlaufzeit"),
		Map.entry("power2", "Verbraucher 2 Leistung"),
		Map.entry("minPower2", "Verbraucher 2 kleinste Leistung"),
		Map.entry("step2", "Verbraucher 2 Leistungsstufe"),
		Map.entry("minOn2", "Verbraucher 2 Mindestlaufzeit"),
		Map.entry("minOff2", "Verbraucher 2 Mindestpause"),
		Map.entry("maxPerDay2", "Verbraucher 2 höchste Laufzeit pro Tag"),
		Map.entry("mustRunBy2", "Verbraucher 2 Pflichtlaufzeit bis"),
		Map.entry("mustRunFor2", "Verbraucher 2 Pflichtlaufzeit"));

	private PvSurplusTexts() {
		// Constants only.
	}

}
