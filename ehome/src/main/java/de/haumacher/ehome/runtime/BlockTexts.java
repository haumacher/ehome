/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.Map;

/**
 * The translations of the built-in block types' manifests for E-Home's views: the manifests carry
 * their labels in English, as the firmware does; {@link RoomControllerTexts},
 * {@link HeatingDemandTexts}, {@link HeatingCurveTexts}, {@link SunShadingTexts} and
 * {@link HouseModeTexts} hold the German ones.
 */
public final class BlockTexts {

	/** The German label of each type by its id. */
	private static final Map<String, String> TYPE_DE = Map.of(
		RoomControllerTexts.TYPE, RoomControllerTexts.LABEL_DE,
		HeatingDemandTexts.TYPE, HeatingDemandTexts.LABEL_DE,
		HeatingCurveTexts.TYPE, HeatingCurveTexts.LABEL_DE,
		SunShadingTexts.TYPE, SunShadingTexts.LABEL_DE,
		HouseModeTexts.TYPE, HouseModeTexts.LABEL_DE);

	/** The German labels of each type's ports, parameters and choices by the type's id. */
	private static final Map<String, Map<String, String>> DE = Map.of(
		RoomControllerTexts.TYPE, RoomControllerTexts.DE,
		HeatingDemandTexts.TYPE, HeatingDemandTexts.DE,
		HeatingCurveTexts.TYPE, HeatingCurveTexts.DE,
		SunShadingTexts.TYPE, SunShadingTexts.DE,
		HouseModeTexts.TYPE, HouseModeTexts.DE);

	private BlockTexts() {
		// Static helpers.
	}

	/** The German label of a type, <code>null</code> when E-Home has none. */
	public static String typeLabelDe(String type) {
		return type == null ? null : TYPE_DE.get(type);
	}

	/**
	 * The label of a port, parameter or choice (<code>parameter.choice</code>) of a block type in the
	 * language: the German one where E-Home has it, else the manifest's English.
	 */
	public static String label(String type, String id, String english, String language) {
		if ("de".equals(language) && type != null) {
			Map<String, String> texts = DE.get(type);
			String de = texts == null ? null : texts.get(id);
			if (de != null) {
				return de;
			}
		}
		return english;
	}

}
