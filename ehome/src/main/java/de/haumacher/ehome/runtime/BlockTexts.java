/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.Map;

/**
 * The translations of the built-in block types' manifests for E-Home's views: the manifests carry
 * their labels in English, as the firmware does; {@link RoomControllerTexts},
 * {@link HeatingDemandTexts}, {@link HeatingCurveTexts}, {@link SunShadingTexts},
 * {@link HouseModeTexts} and {@link PvSurplusTexts} hold the German ones, and the help texts of
 * the ports and parameters in German and English (haumacher/ehome #101).
 */
public final class BlockTexts {

	/** The German label of each type by its id. */
	private static final Map<String, String> TYPE_DE = Map.of(
		RoomControllerTexts.TYPE, RoomControllerTexts.LABEL_DE,
		HeatingDemandTexts.TYPE, HeatingDemandTexts.LABEL_DE,
		HeatingCurveTexts.TYPE, HeatingCurveTexts.LABEL_DE,
		SunShadingTexts.TYPE, SunShadingTexts.LABEL_DE,
		HouseModeTexts.TYPE, HouseModeTexts.LABEL_DE,
		PvSurplusTexts.TYPE, PvSurplusTexts.LABEL_DE);

	/** The German labels of each type's ports, parameters and choices by the type's id. */
	private static final Map<String, Map<String, String>> DE = Map.of(
		RoomControllerTexts.TYPE, RoomControllerTexts.DE,
		HeatingDemandTexts.TYPE, HeatingDemandTexts.DE,
		HeatingCurveTexts.TYPE, HeatingCurveTexts.DE,
		SunShadingTexts.TYPE, SunShadingTexts.DE,
		HouseModeTexts.TYPE, HouseModeTexts.DE,
		PvSurplusTexts.TYPE, PvSurplusTexts.DE);

	/** The German help texts of each type's ports and parameters by the type's id. */
	private static final Map<String, Map<String, String>> HELP_DE = Map.of(
		RoomControllerTexts.TYPE, RoomControllerTexts.HELP_DE,
		HeatingDemandTexts.TYPE, HeatingDemandTexts.HELP_DE,
		HeatingCurveTexts.TYPE, HeatingCurveTexts.HELP_DE,
		SunShadingTexts.TYPE, SunShadingTexts.HELP_DE,
		HouseModeTexts.TYPE, HouseModeTexts.HELP_DE,
		PvSurplusTexts.TYPE, PvSurplusTexts.HELP_DE);

	/** The English help texts of each type's ports and parameters by the type's id. */
	private static final Map<String, Map<String, String>> HELP_EN = Map.of(
		RoomControllerTexts.TYPE, RoomControllerTexts.HELP_EN,
		HeatingDemandTexts.TYPE, HeatingDemandTexts.HELP_EN,
		HeatingCurveTexts.TYPE, HeatingCurveTexts.HELP_EN,
		SunShadingTexts.TYPE, SunShadingTexts.HELP_EN,
		HouseModeTexts.TYPE, HouseModeTexts.HELP_EN,
		PvSurplusTexts.TYPE, PvSurplusTexts.HELP_EN);

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

	/**
	 * The help text of a port or parameter of a block type in the language (haumacher/ehome #101): what
	 * it does, for the installer; an enumeration's choices in paragraphs of their own, separated by an
	 * empty line. German for <code>de</code>, else English; <code>null</code> for a type or member
	 * E-Home has no help for (a type of a loaded module).
	 */
	public static String help(String type, String id, String language) {
		if (type == null || id == null) {
			return null;
		}
		Map<String, String> texts = ("de".equals(language) ? HELP_DE : HELP_EN).get(type);
		return texts == null ? null : texts.get(id);
	}

}
