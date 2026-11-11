/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.importer.TestEtsImport;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.ImageCache;
import de.haumacher.ehome.runtime.RuleFunctions;

/**
 * The checks of the fields of a schedule entry and a rule build the project's image once per
 * revision of the knowledge base (haumacher/ehome #89, {@link ImageCache}): checking every field of a
 * form and changing one of them builds it at most once; after a commit the next check sees the new
 * state (a changed datapoint makes an output or a write accepted); another reader gets an image of its own.
 *
 * <p>
 * An empty project serves; no ETS import.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestImageCache extends BasicTestCase {

	public void testSchedule() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			TLObject entry;
			TLObject light;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				// An output that is no truth value: refused.
				light = RuleFunctions.create(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
				light.tUpdateByName("identifier", "light");
				light.tUpdateByName("type", valueType("unsigned"));
				entry = RuleFunctions.create(project, "schedules", runtime("AstroSchedule"));
				entry.tUpdateByName("identifier", "dusk");
				entry.tUpdateByName("output", light);
				tx.commit();
			}

			long before = ImageCache.builds();
			RuleForm form = new RuleForm(entry);
			checkAll(form, entry);
			assertRefused(form, "output", "not boolean");
			form.set("enabledAdjustable", Boolean.FALSE);
			checkAll(form, entry);
			form.set("enabledAdjustable", Boolean.TRUE);
			checkAll(form, entry);
			RuleFunctions.datapoints(project);
			assertEquals("One build for every check and the datapoint list.", 1, ImageCache.builds() - before);

			// A truth value makes the output accepted.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				light.tUpdateByName("type", valueType("boolean"));
				tx.commit();
			}
			before = ImageCache.builds();
			form = new RuleForm(entry);
			checkAll(form, entry);
			assertEquals(List.of(), form.warnings("output"));
			assertTrue(RuleFunctions.datapoints(project).stream()
				.anyMatch(datapoint -> "boolean".equals(((Map<?, ?>) datapoint).get("type"))));
			assertEquals(1, ImageCache.builds() - before);
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	public void testRule() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			TLObject write;
			TLObject light;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				// A datapoint of another type: refused (that it is not writable does not matter: rules write
				// every application datapoint, haumacher/ehome #138).
				light = RuleFunctions.create(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
				light.tUpdateByName("identifier", "light");
				light.tUpdateByName("type", valueType("unsigned"));
				TLObject rule = RuleFunctions.create(project, "rules", runtime("Rule"));
				TLObject reaction = RuleFunctions.create(rule, "reactions", runtime("Reaction"));
				RuleFunctions.create(reaction, "triggers", runtime("StartTrigger"));
				write = RuleFunctions.create(reaction, "actions", runtime("WriteAction"));
				write.tUpdateByName("datapoint", light);
				write.tUpdateByName("value", "true");
				tx.commit();
			}

			long before = ImageCache.builds();
			RuleForm form = new RuleForm(write);
			checkAll(form, write);
			assertRefused(form, "value", "cannot be written");
			form.set("value", "false");
			checkAll(form, write);
			assertEquals(1, ImageCache.builds() - before);

			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				light.tUpdateByName("type", valueType("boolean"));
				tx.commit();
			}
			form = new RuleForm(write);
			checkAll(form, write);
			assertEquals(List.of(), form.warnings("value"));
			assertEquals(List.of(), form.warnings("datapoint"));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * Another reader (here a person instead of the system context) and an uncommitted change are not
	 * answered from the entry of the system context.
	 */
	public void testKey() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			ImageCache.Entry system = ImageCache.get(project);
			assertSame(system, ImageCache.get(project));
			ImageCache.Entry root = TestEtsImport.asRoot(() -> ImageCache.get(project));
			assertNotSame(system, root);
			assertSame(root, TestEtsImport.asRoot(() -> ImageCache.get(project)));

			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				RuleFunctions.create(project, "applicationDatapoints", runtime("ApplicationDatapoint"))
					.tUpdateByName("identifier", "pending");
				ImageCache.Entry uncommitted = ImageCache.get(project);
				assertNotSame(system, uncommitted);
				assertNotNull(uncommitted.directory().type("pending"));
				// Not committed: rolled back.
			}
			assertSame(system, ImageCache.get(project));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	/** Reads the errors and warnings of every field, as a form shows them. */
	private static void checkAll(RuleForm form, TLObject obj) {
		for (TLStructuredTypePart part : obj.tType().getAllParts()) {
			form.errors(part.getName());
			form.warnings(part.getName());
		}
	}

	private static void assertRefused(RuleForm form, String attribute, String part) {
		List<String> warnings = form.warnings(attribute);
		assertEquals(attribute + ": " + warnings, 1, warnings.size());
		assertTrue(attribute + ": " + warnings, warnings.get(0).contains(part));
	}

	private static Object valueType(String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType")).getClassifier(name);
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestImageCache.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
