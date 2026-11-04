/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.Map;

import de.haumacher.ehome.blocks.SunShading;

/**
 * The German texts of the sun shading's manifest (homecontrol <code>spec/blocks.md</code> 18.1), by
 * the id of the port, parameter or choice (see {@link RoomControllerTexts}).
 */
public final class SunShadingTexts {

	/** The type id. */
	public static final String TYPE = SunShading.TYPE;

	/** The German label of the type. */
	public static final String LABEL_DE = "Beschattung";

	/** The German labels of the ports and parameters by id, the choices by <code>parameter.choice</code>. */
	public static final Map<String, String> DE = Map.ofEntries(
		Map.entry("brightness", "Helligkeit"),
		Map.entry("wind", "Windgeschwindigkeit"),
		Map.entry("rain", "Regen"),
		Map.entry("frost", "Frostalarm"),
		Map.entry("outside", "Außentemperatur"),
		Map.entry("manual", "Handbedienung"),
		Map.entry("presence", "Anwesenheit"),
		Map.entry("summer", "Sommer"),
		Map.entry("roomTemperature", "Raumtemperatur"),
		Map.entry("enable", "Automatik ein"),
		Map.entry("position", "Behangposition"),
		Map.entry("slat", "Lamellenposition"),
		Map.entry("upDown", "Auf/Ab"),
		Map.entry("state", "Zustand"),
		Map.entry("sunAzimuth", "Sonnenazimut"),
		Map.entry("sunElevation", "Sonnenhöhe"),
		Map.entry("facadeAzimuth", "Ausrichtung der Fassade"),
		Map.entry("openingAngle", "Öffnungswinkel"),
		Map.entry("minElevation", "Kleinste Sonnenhöhe"),
		Map.entry("overhangAngle", "Winkel des Vordachs"),
		Map.entry("horizonPoints", "Anzahl der Horizontpunkte"),
		Map.entry("horizonAngle1", "Horizontrichtung 1"),
		Map.entry("horizonElevation1", "Horizonthöhe 1"),
		Map.entry("horizonAngle2", "Horizontrichtung 2"),
		Map.entry("horizonElevation2", "Horizonthöhe 2"),
		Map.entry("horizonAngle3", "Horizontrichtung 3"),
		Map.entry("horizonElevation3", "Horizonthöhe 3"),
		Map.entry("horizonAngle4", "Horizontrichtung 4"),
		Map.entry("horizonElevation4", "Horizonthöhe 4"),
		Map.entry("brightnessOn", "Hell ab"),
		Map.entry("brightnessOff", "Dunkel unter"),
		Map.entry("brightnessOnDelay", "Verzögerung hell"),
		Map.entry("brightnessOffDelay", "Verzögerung dunkel"),
		Map.entry("shadePosition", "Beschattungsposition"),
		Map.entry("slatMode", "Lamellen beim Beschatten"),
		Map.entry("slatMode.fixed", "Fest"),
		Map.entry("slatMode.tracking", "Dem Sonnenstand nachgeführt"),
		Map.entry("shadeSlat", "Lamellenposition beim Beschatten"),
		Map.entry("slatRatio", "Lamellenabstand zu Lamellenbreite"),
		Map.entry("slatOpen", "Lamellenposition waagerecht"),
		Map.entry("slatStep", "Lamellenschritt"),
		Map.entry("heatLimit", "Hitzeschutz oberhalb"),
		Map.entry("windLimit", "Windalarm ab"),
		Map.entry("windTimeout", "Überwachungszeit des Windsensors"),
		Map.entry("frostLimit", "Frostalarm unterhalb"),
		Map.entry("alarmRelease", "Rückfallzeit nach Alarm"),
		Map.entry("manualLock", "Nach Handbedienung"),
		Map.entry("manualLock.duration", "Für eine feste Dauer"),
		Map.entry("manualLock.sunChange", "Bis sich die Sonne ändert"),
		Map.entry("manualLock.midnight", "Bis Mitternacht"),
		Map.entry("manualDuration", "Sperrzeit nach Handbedienung"),
		Map.entry("minMoveInterval", "Kürzeste Zeit zwischen zwei Fahrten"));

	private SunShadingTexts() {
		// Constants only.
	}

}
