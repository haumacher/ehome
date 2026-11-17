/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.programming.GroupAddresses;
import de.haumacher.ehome.runtime.GroupAddressIdentifiers;
import de.haumacher.ehome.runtime.GroupAddressIds;
import de.haumacher.ehome.runtime.ProposeIdentifiersAction;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuntimeFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * Every group address has an identifier (haumacher/ehome #178), on the KNX test system
 * (<code>samples/KNX-Testsystem.knxproj</code>): the import gives each one, valid and unique, the
 * image names no datapoint by its address; a new address gets one (<code>ga_…</code> without a
 * name), renaming keeps it, removing it gives it one again with the rules following; "Propose
 * identifiers" with "Overwrite existing" counts and lists the changes and rewrites the rules.
 */
@SuppressWarnings("javadoc")
public class TestGroupAddressIdentifiers extends BasicTestCase {

	private static final Pattern ADDRESS = Pattern.compile("\\d+(/\\d+)*");

	public void testIdentifiers() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);

			// The import: every address has a valid identifier, unique in the project.
			List<TLObject> addresses = RuntimeFunctions.groupAddresses(project);
			assertTrue(addresses.size() > 50);
			assertUnique(project);
			TLObject taste1 = byName(project, "Taste1");
			assertEquals("taste1", GroupAddressIds.identifier(taste1));
			assertNoAddressIds(project);

			// A new address gets one: from its name, from the address without one.
			TLObject range = taste1.tContainer();
			TLObject flur;
			TLObject nameless;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				flur = GroupAddresses.create(range, GroupAddresses.nextFree(range), "Licht Flur", null);
				tx.commit();
			}
			assertEquals("licht_flur", GroupAddressIds.identifier(flur));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				nameless = GroupAddresses.create(range, GroupAddresses.nextFree(range), null, null);
				tx.commit();
			}
			String notation = ((GA) nameless.tValueByName("address")).toString();
			assertEquals("ga_" + notation.replace('/', '_').replace('.', '_'), GroupAddressIds.identifier(nameless));
			assertUnique(project);

			// Renaming keeps it.
			update(flur, "name", "Licht Diele");
			assertEquals("licht_flur", GroupAddressIds.identifier(flur));

			// A rule naming it, by its identifier and by its address.
			TLObject reaction;
			TLObject write;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				TLObject rule = RuleFunctions.create(project, "rules", runtime("Rule"));
				rule.tUpdateByName("identifier", "diele");
				reaction = RuleFunctions.create(rule, "reactions", runtime("Reaction"));
				TLObject trigger = RuleFunctions.create(reaction, "triggers", runtime("DatapointTrigger"));
				trigger.tUpdateByName("datapoint", taste1);
				reaction.tUpdateByName("condition", "@taste1");
				write = RuleFunctions.create(reaction, "actions", runtime("WriteAction"));
				write.tUpdateByName("datapoint", flur);
				write.tUpdateByName("value", "not @\"" + GroupAddressIds.address(flur) + "\"");
				tx.commit();
			}
			// Entered with the address, written with the identifier.
			assertEquals("not @licht_flur", write.tValueByName("value"));

			// Removed other than by the form: proposed again, the rules naming the old one follow.
			update(nameless, "name", "Licht Bad");
			update(nameless, "identifier", null);
			assertEquals("licht_bad", GroupAddressIds.identifier(nameless));
			update(flur, "identifier", null);
			assertEquals("licht_diele", GroupAddressIds.identifier(flur));
			assertEquals("not @licht_diele", write.tValueByName("value"));
			update(flur, "identifier", "flur.licht");
			assertEquals("not @flur.licht", write.tValueByName("value"));
			assertUnique(project);

			// Overwrite: counted, listed "old → new", applied, the rules rewritten.
			assertEquals(Map.of(), GroupAddressIds.propose(project));
			assertTrue(ProposeIdentifiersAction.confirmation(project).contains("nothing to propose"));
			update(taste1, "name", "Taste Küche oben");
			List<ProposeIdentifiersAction.Change> changes = ProposeIdentifiersAction.changes(project, true);
			assertEquals(2, changes.size());
			String confirmation = ProposeIdentifiersAction.confirmation(project, true);
			assertTrue(confirmation, confirmation.startsWith("2 group addresses get a new identifier."));
			assertTrue(confirmation, confirmation.contains("Taste Küche oben: taste1 → taste_kueche_oben"));
			assertTrue(confirmation, confirmation.contains("Licht Diele: flur.licht → licht_diele"));
			String summary = ProposeIdentifiersAction.summary(ProposeIdentifiersAction.run(project, true));
			assertTrue(summary, summary.startsWith("2 group addresses got a new identifier."));
			assertTrue(summary, summary.contains("taste1 → taste_kueche_oben"));
			assertEquals("taste_kueche_oben", GroupAddressIds.identifier(taste1));
			assertEquals("@taste_kueche_oben", reaction.tValueByName("condition"));
			assertEquals("not @licht_diele", write.tValueByName("value"));
			assertUnique(project);
			assertEquals(List.of(), ProposeIdentifiersAction.changes(project, true));
			assertTrue(ProposeIdentifiersAction.confirmation(project, true).contains("nothing changes"));
			assertNoAddressIds(project);
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static void assertUnique(TLObject project) {
		Set<String> ids = new HashSet<>();
		for (TLObject address : RuntimeFunctions.groupAddresses(project)) {
			String identifier = GroupAddressIds.identifier(address);
			assertNotNull(String.valueOf(address.tValueByName("address")), identifier);
			assertNull(identifier, GroupAddressIds.syntaxProblem(identifier));
			assertNull(identifier, GroupAddressIds.problem(identifier, address));
			assertTrue(identifier, ids.add(identifier));
		}
	}

	/** No datapoint of the image has an address as its id. */
	private static void assertNoAddressIds(TLObject project) {
		List<?> datapoints = (List<?>) RuntimeImage.build(project, 1, Instant.parse("2026-11-17T12:00:00Z")).getJson()
			.get("datapoints");
		assertFalse(datapoints.isEmpty());
		for (Object datapoint : datapoints) {
			String id = String.valueOf(((Map<?, ?>) datapoint).get("id"));
			assertFalse(id, ADDRESS.matcher(id).matches());
		}
	}

	private static TLObject byName(TLObject project, String name) {
		for (TLObject address : RuntimeFunctions.groupAddresses(project)) {
			if (name.equals(address.tValueByName("name"))) {
				return address;
			}
		}
		throw new AssertionError("No group address " + name);
	}

	private static void update(TLObject obj, String attribute, Object value) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			obj.tUpdateByName(attribute, value);
			tx.commit();
		}
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestGroupAddressIdentifiers.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			GroupAddressIdentifiers.Module.INSTANCE));
	}

}
