/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.util.HashSet;
import java.util.Set;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.blocks.BlockRegistry;
import de.haumacher.ehome.blocks.BlockType;
import de.haumacher.ehome.blocks.Manifest;
import de.haumacher.ehome.blocks.Manifest.Choice;
import de.haumacher.ehome.blocks.Manifest.Param;
import de.haumacher.ehome.blocks.Manifest.Port;
import de.haumacher.ehome.runtime.BlockTexts;
import de.haumacher.ehome.runtime.HeatingCurveTexts;
import de.haumacher.ehome.runtime.HeatingDemandTexts;
import de.haumacher.ehome.runtime.HouseModeTexts;
import de.haumacher.ehome.runtime.PvSurplusTexts;
import de.haumacher.ehome.runtime.RoomControllerTexts;
import de.haumacher.ehome.runtime.SunShadingTexts;

/**
 * The help texts of the built-in block types (haumacher/ehome #101): every port and every parameter
 * of each of them has one in German and in English, so a member cannot be added without its help;
 * an enumeration explains each of its choices in a paragraph of its own that starts with the
 * choice's label; and no help names a member the type does not have.
 */
@SuppressWarnings("javadoc")
public class TestBlockHelp extends TestCase {

	private static final String[] LANGUAGES = { "de", "en" };

	public void testSixTypes() {
		assertEquals(6, BlockRegistry.builtins().types().size());
	}

	public void testEveryMember() {
		for (BlockType type : BlockRegistry.builtins().types()) {
			Manifest m = type.manifest();
			for (String language : LANGUAGES) {
				for (Port port : m.ports()) {
					assertHelp(m.type() + " port " + port.id() + " " + language,
						BlockTexts.help(m.type(), port.id(), language));
				}
				for (Param param : m.params()) {
					assertHelp(m.type() + " parameter " + param.id() + " " + language,
						BlockTexts.help(m.type(), param.id(), language));
				}
			}
		}
	}

	public void testChoiceParagraphs() {
		for (BlockType type : BlockRegistry.builtins().types()) {
			Manifest m = type.manifest();
			for (Param param : m.params()) {
				if (param.choices().isEmpty()) {
					continue;
				}
				for (String language : LANGUAGES) {
					String help = BlockTexts.help(m.type(), param.id(), language);
					String[] paragraphs = help.split("\n\n");
					for (Choice choice : param.choices()) {
						String label = BlockTexts.label(m.type(), param.id() + "." + choice.id(), choice.label(), language);
						boolean found = false;
						for (String paragraph : paragraphs) {
							if (paragraph.startsWith(label + ":")) {
								found = true;
							}
						}
						assertTrue(m.type() + " parameter " + param.id() + " " + language + ": no paragraph \"" + label
							+ ":\" in: " + help, found);
					}
				}
			}
		}
	}

	public void testNoStrayHelp() {
		assertMembers(RoomControllerTexts.TYPE, RoomControllerTexts.HELP_DE.keySet(), RoomControllerTexts.HELP_EN.keySet());
		assertMembers(HeatingDemandTexts.TYPE, HeatingDemandTexts.HELP_DE.keySet(), HeatingDemandTexts.HELP_EN.keySet());
		assertMembers(HeatingCurveTexts.TYPE, HeatingCurveTexts.HELP_DE.keySet(), HeatingCurveTexts.HELP_EN.keySet());
		assertMembers(SunShadingTexts.TYPE, SunShadingTexts.HELP_DE.keySet(), SunShadingTexts.HELP_EN.keySet());
		assertMembers(HouseModeTexts.TYPE, HouseModeTexts.HELP_DE.keySet(), HouseModeTexts.HELP_EN.keySet());
		assertMembers(PvSurplusTexts.TYPE, PvSurplusTexts.HELP_DE.keySet(), PvSurplusTexts.HELP_EN.keySet());
	}

	public void testUnknownType() {
		assertNull(BlockTexts.help("org.example.unknown", "temperature", "de"));
		assertNull(BlockTexts.help(RoomControllerTexts.TYPE, "noSuchMember", "en"));
	}

	private static void assertMembers(String typeId, Set<String> de, Set<String> en) {
		Manifest m = BlockRegistry.builtins().findAny(typeId).manifest();
		Set<String> ids = new HashSet<>();
		for (Port port : m.ports()) {
			ids.add(port.id());
		}
		for (Param param : m.params()) {
			ids.add(param.id());
		}
		assertEquals(typeId + " de", ids, de);
		assertEquals(typeId + " en", ids, en);
	}

	private static void assertHelp(String what, String help) {
		assertNotNull(what, help);
		assertFalse(what, help.isBlank());
		assertFalse(what + " has a literal escape", help.contains("\\u"));
		assertEquals(what + " surrounding space", help.strip(), help);
	}

	public static Test suite() {
		return new TestSuite(TestBlockHelp.class);
	}

}
