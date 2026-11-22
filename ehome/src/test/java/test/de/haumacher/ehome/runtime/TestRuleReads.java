/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.address;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.createAddress;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.TLContext;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.runtime.Reaction;
import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.programming.DeviceChanges;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.runtime.DatapointSettings;
import de.haumacher.ehome.runtime.GroupAddressIds;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuleReads;
import de.haumacher.ehome.runtime.RuntimeFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The datapoints a rule reads that nobody can be asked for after a restart (haumacher/ehome #216),
 * on the KNX test system.
 *
 * <p>
 * A single sender: the actuator's object 0 is alone on 0/0/34 (its sending address); its device's
 * own flags are set to each case. A rule whose condition reads 0/0/34 is reported with what is wrong
 * (the only sender without the read flag, an object with the read flag that does not send there, an
 * object connected without sending, no object at all), in the rule's check and in the notes of the
 * image; the fix sets the read flag of the only sender, after which the address is read on start,
 * the report is gone and the device has a change for its next download.
 * </p>
 *
 * <p>
 * Several senders: key 2 of the push button sensor sends on the address of key 1, none with the read
 * flag. The report names both, nothing is set without the user's choice, and the choice sets exactly
 * one read flag. On no address of the project does more than one object have the read flag.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestRuleReads extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");

	public void testReads() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);
			TLObject channelA = address(project, "0/0/34");
			List<TLObject> connections = GroupAddressConnections.connections(channelA);
			assertEquals(1, connections.size());
			TLObject connection = connections.get(0);
			TLObject connector = connection.tContainer();
			TLObject device = GroupAddressConnections.device(connection);
			assertOneReaderAtMost(project);

			Rule rule;
			Reaction reaction;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				rule = (Rule) RuleFunctions.create(project, "rules", runtime("Rule"));
				rule.setIdentifier("rollladen");
				rule.setNameKey(ResKey.builder().add(Locale.ENGLISH, "Shutters day/night")
					.add(Locale.GERMAN, "Rollläden Tag/Nacht").build());
				reaction = (Reaction) RuleFunctions.create(rule, "reactions", runtime("Reaction"));
				reaction.setCondition("@\"" + GroupAddressIds.datapointId(channelA) + "\"");
				tx.commit();
			}

			// A state output (C R T): read on start, nothing reported.
			flags(connector, "CRT");
			assertEquals(List.of(), RuleReads.ofRule(rule));
			assertNull(en(() -> RuleReads.text(rule)));
			int notes = RuntimeImage.build(project, 1, NOW).getNotes().size();

			// A sender without the read flag (C W T): nobody answers a read.
			flags(connector, "CWT");
			List<RuleReads.Problem> problems = RuleReads.ofRule(rule);
			assertEquals(1, problems.size());
			assertEquals(RuleReads.Cause.NO_READ_FLAG, problems.get(0).cause());
			assertEquals(List.of(connection), problems.get(0).connections());
			assertEquals(
				"Rule \"Shutters day/night\" reads \"KanalA (0/0/34)\", which nobody can be asked for after a restart: the object \"Channel A\" of \"AKK-0416.03 Switch Actuator 4fold, 2SU, 16A (1.3.5)\" sends on it without the read flag.",
				en(() -> RuleReads.text(rule)));
			assertEquals(
				"Regel „Rollläden Tag/Nacht“ liest den Datenpunkt „KanalA (0/0/34)“, den nach einem Neustart niemand abfragen kann: Das Objekt „Kanal A“ von „AKK-0416.03 Schaltaktor 4fach, 2TE, 16A (1.3.5)“ sendet darauf ohne das Lesen-Flag.",
				de(() -> RuleReads.text(rule)));
			assertTrue(RuleReads.fixable(rule));
			// Also a note of the image.
			List<String> imageNotes = en(() -> RuntimeImage.build(project, 1, NOW).getNotes());
			assertEquals(notes + 1, imageNotes.size());
			assertTrue(imageNotes.toString(), imageNotes.contains(en(() -> RuleReads.text(rule))));

			// The fix sets the read flag: read on start, nothing reported, a change of the device.
			assertFalse(readFlagChanged(device, connection));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				assertEquals(1, RuleReads.fix(rule));
				tx.commit();
			}
			assertTrue(DatapointSettings.derive(channelA).readOnStart());
			assertEquals(List.of(), RuleReads.ofRule(rule));
			assertFalse(RuleReads.fixable(rule));
			assertTrue(readFlagChanged(device, connection));
			assertEquals(notes, RuntimeImage.build(project, 1, NOW).getNotes().size());

			assertOneReaderAtMost(project);
			// The fix adds no second read flag.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				assertEquals(0, RuleReads.choose(connection));
				tx.commit();
			}

			// An object with the read flag that does not send on the address (C R W): reported, no
			// second read flag is set.
			flags(connector, "CRW");
			problems = RuleReads.ofRule(rule);
			assertEquals(1, problems.size());
			assertEquals(RuleReads.Cause.READ_FLAG_NOT_SENDING, problems.get(0).cause());
			assertFalse(RuleReads.fixable(rule));
			assertEquals(List.of(), RuleReads.choices(rule));
			assertEquals(
				"Rule \"Shutters day/night\" reads \"KanalA (0/0/34)\", which nobody can be asked for after a restart: the object \"Channel A\" of \"AKK-0416.03 Switch Actuator 4fold, 2SU, 16A (1.3.5)\" has the read flag but does not send on it.",
				en(() -> RuleReads.text(rule)));
			assertEquals(
				"Regel „Rollläden Tag/Nacht“ liest den Datenpunkt „KanalA (0/0/34)“, den nach einem Neustart niemand abfragen kann: Das Objekt „Kanal A“ von „AKK-0416.03 Schaltaktor 4fach, 2TE, 16A (1.3.5)“ hat das Lesen-Flag, sendet aber nicht darauf.",
				de(() -> RuleReads.text(rule)));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				assertEquals(0, RuleReads.fix(rule));
				tx.commit();
			}

			// An input (C W): connected, but it does not send on the address.
			flags(connector, "CW");
			problems = RuleReads.ofRule(rule);
			assertEquals(1, problems.size());
			assertEquals(RuleReads.Cause.NOT_SENDING, problems.get(0).cause());
			assertFalse(RuleReads.fixable(rule));
			assertEquals(
				"Rule \"Shutters day/night\" reads \"KanalA (0/0/34)\", which nobody can be asked for after a restart: the object \"Channel A\" of \"AKK-0416.03 Switch Actuator 4fold, 2SU, 16A (1.3.5)\" is connected to it but does not send on it.",
				en(() -> RuleReads.text(rule)));
			assertEquals(
				"Regel „Rollläden Tag/Nacht“ liest den Datenpunkt „KanalA (0/0/34)“, den nach einem Neustart niemand abfragen kann: Das Objekt „Kanal A“ von „AKK-0416.03 Schaltaktor 4fach, 2TE, 16A (1.3.5)“ ist mit ihm verbunden, sendet aber nicht darauf.",
				de(() -> RuleReads.text(rule)));

			// The user's setting decides: read on start set, or the value kept across restarts.
			update(() -> ((de.haumacher.ehome.model.logical.GroupAddress) channelA).setRuntimePersist(true));
			assertEquals(List.of(), RuleReads.ofRule(rule));
			update(() -> ((de.haumacher.ehome.model.logical.GroupAddress) channelA).setRuntimePersist(false));

			// No object at all.
			TLObject lock = createAddress(project, "0/0/62", "Tür verriegelt", "DPST-1-1");
			update(() -> reaction.setCondition("@\"" + GroupAddressIds.datapointId(lock) + "\""));
			problems = RuleReads.ofRule(rule);
			assertEquals(1, problems.size());
			assertEquals(RuleReads.Cause.NO_OBJECT, problems.get(0).cause());
			assertEquals(List.of(), problems.get(0).connections());
			assertEquals(
				"Rule \"Shutters day/night\" reads \"Tür verriegelt (0/0/62)\", which nobody can be asked for after a restart: no object of the project sends on it.",
				en(() -> RuleReads.text(rule)));
			assertEquals(
				"Regel „Rollläden Tag/Nacht“ liest den Datenpunkt „Tür verriegelt (0/0/62)“, den nach einem Neustart niemand abfragen kann: Kein Objekt des Projekts sendet darauf.",
				de(() -> RuleReads.text(rule)));
			assertEquals(0, RuleReads.fix(rule));
			assertOneReaderAtMost(project);
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	/**
	 * Two keys of the push button sensor sending on 0/0/1, none with the read flag: the user picks the
	 * one that answers reads; nothing is set without the choice, and the choice sets exactly one read
	 * flag.
	 */
	public void testSeveralSenders() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			TLObject project = projects.get(0);
			assertOneReaderAtMost(project);
			TLObject key1 = address(project, "0/0/1");
			TLObject key2 = address(project, "0/0/2");
			TLObject first = sender(key1);
			TLObject second = sender(key2);
			// Key 2 sends on the address of key 1 instead of its own.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				TLObject device = GroupAddressConnections.device(second);
				de.haumacher.ehome.model.operational.Interface placed = GroupAddressConnections.object(second);
				DeviceSettings.disconnect(second);
				second = DeviceSettings.connect(device, placed, key1,
					((TLEnumeration) TLModelUtil.findType("ehome.physical:ConnectionType")).getClassifier("send"));
				tx.commit();
			}
			assertEquals(2, GroupAddressConnections.senders(key1));

			Rule rule;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				rule = (Rule) RuleFunctions.create(project, "rules", runtime("Rule"));
				rule.setIdentifier("flur");
				rule.setNameKey(ResKey.builder().add(Locale.ENGLISH, "Hall").add(Locale.GERMAN, "Flur").build());
				Reaction reaction = (Reaction) RuleFunctions.create(rule, "reactions", runtime("Reaction"));
				reaction.setCondition("@\"" + GroupAddressIds.datapointId(key1) + "\"");
				tx.commit();
			}

			List<RuleReads.Problem> problems = RuleReads.ofRule(rule);
			assertEquals(1, problems.size());
			assertEquals(RuleReads.Cause.SEVERAL_SENDERS, problems.get(0).cause());
			assertEquals(List.of(first, second), problems.get(0).connections());
			// The program texts of the push button sensor depend on the languages the shared catalog
			// was imported in: the names of the objects as the helpers give them.
			TLObject one = first;
			TLObject two = second;
			assertEquals("Rule \"Hall\" reads \"Taste1 (0/0/1)\", which nobody can be asked for after a restart: the objects "
				+ en(() -> "\"" + objectText(one) + "\" of \"" + deviceText(one) + "\", \"" + objectText(two) + "\" of \""
					+ deviceText(two) + "\"")
				+ " send on it, none with the read flag; one of them must answer reads.",
				en(() -> RuleReads.text(rule)));
			assertEquals("Regel „Flur“ liest den Datenpunkt „Taste1 (0/0/1)“, den nach einem Neustart niemand abfragen kann: Die Objekte "
				+ de(() -> "„" + objectText(one) + "“ von „" + deviceText(one) + "“, „" + objectText(two) + "“ von „"
					+ deviceText(two) + "“")
				+ " senden darauf, keines mit dem Lesen-Flag; eines von ihnen muss Leseanfragen beantworten.",
				de(() -> RuleReads.text(rule)));

			// Nothing is fixed without the user's choice.
			assertFalse(RuleReads.fixable(rule));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				assertEquals(0, RuleReads.fix(rule));
				tx.commit();
			}
			assertEquals(List.of(), RuleReads.readers(key1));
			assertEquals(List.of(first, second), RuleReads.choices(rule));

			// The user's choice: exactly one read flag; then read on start, no report.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				assertEquals(1, RuleReads.choose(second));
				tx.commit();
			}
			assertEquals(List.of(second), RuleReads.readers(key1));
			assertTrue(DatapointSettings.derive(key1).readOnStart());
			assertEquals(List.of(), RuleReads.ofRule(rule));
			assertEquals(List.of(), RuleReads.choices(rule));

			// One sender with the read flag, a second without: no report, and no second flag.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				assertEquals(0, RuleReads.choose(first));
				tx.commit();
			}
			assertEquals(List.of(second), RuleReads.readers(key1));
			assertOneReaderAtMost(project);
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static String objectText(TLObject connection) {
		return GroupAddressConnections.objectText(connection);
	}

	private static String deviceText(TLObject connection) {
		return GroupAddressConnections.deviceLabel(GroupAddressConnections.device(connection));
	}

	/** The connection of the object sending on the address. */
	private static TLObject sender(TLObject address) {
		for (TLObject connection : GroupAddressConnections.connections(address)) {
			if (DatapointSettings.transmits(connection)) {
				return connection;
			}
		}
		throw new AssertionError("No sender on " + GroupAddressIds.datapointId(address));
	}

	/** On no group address of the project more than one object has the read flag. */
	private static void assertOneReaderAtMost(TLObject project) {
		for (TLObject address : RuntimeFunctions.groupAddresses(project)) {
			assertTrue(GroupAddressIds.datapointId(address), RuleReads.readers(address).size() <= 1);
		}
	}

	/**
	 * Whether the device has a change of its object of the connection to be downloaded that sets the
	 * read flag.
	 */
	private static boolean readFlagChanged(TLObject device, TLObject connection) {
		DeviceChanges.Entry entry = DeviceChanges.of(device, null).getEntry(GroupAddressConnections.object(connection));
		return entry != null && entry.flagsChanged() && entry.currentFlags().enabled("canRead")
			&& !entry.referenceFlags().enabled("canRead");
	}

	/**
	 * Sets the device's own flags of the connector: those named by their letters (C, R, W, T, U) are
	 * enabled, the others disabled.
	 */
	private static void flags(TLObject connector, String letters) {
		TLEnumeration enablement = (TLEnumeration) TLModelUtil.findType("ehome.common:Enablement");
		String[][] flags = { { "communication", "C" }, { "canRead", "R" }, { "canWrite", "W" },
			{ "transmission", "T" }, { "update", "U" } };
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			for (String[] flag : flags) {
				connector.tUpdateByName(flag[0],
					enablement.getClassifier(letters.contains(flag[1]) ? "Enabled" : "Disabled"));
			}
			tx.commit();
		}
	}

	private static void update(Runnable change) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			change.run();
			tx.commit();
		}
	}

	private static <T> T en(Supplier<T> value) {
		return inLocale(Locale.ENGLISH, value);
	}

	private static <T> T de(Supplier<T> value) {
		return inLocale(Locale.GERMAN, value);
	}

	/** The value as the root reads it in the given language: labels and messages are resolved in it. */
	private static <T> T inLocale(Locale locale, Supplier<T> value) {
		TLContext context = TLContext.getContext();
		Person before = context.getPerson();
		String contextId = context.getContextId();
		context.setPerson(PersonManager.getManager().getRoot());
		Locale localeBefore = context.getCurrentLocale();
		context.setCurrentLocale(locale);
		try {
			return value.get();
		} finally {
			context.setCurrentLocale(localeBefore);
			context.setPerson(before);
			if (before == null) {
				context.setContextId(contextId);
			}
		}
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRuleReads.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
