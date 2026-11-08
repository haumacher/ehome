/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.TLContext;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.rules.DatapointId;
import de.haumacher.ehome.rules.RuleLoader;
import de.haumacher.ehome.rules.ValueType;
import de.haumacher.ehome.runtime.DatapointNames;
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The refusals of the image loaders name a datapoint as <code>Name (id)</code> where they are shown
 * for a project (haumacher/ehome #118): the loaders put the id into a message as a
 * {@link DatapointId}, which the form, the image's findings (deployment, simulation) name through
 * {@link DatapointNames}; a message shown without a project shows the id.
 */
@SuppressWarnings("javadoc")
public class TestRefusalNames extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

	private TLObject _project;

	private TLObject _write;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_project = create("ehome.project:Project");
			_project.tUpdateByName("name", "Refusal names");
			// A group address without a datapoint type is no datapoint of the image.
			TLObject light = create("ehome.logical:GroupAddress");
			light.tUpdateByName("name", "Licht Flur");
			light.tUpdateByName("address", GA.parse("11/0/4", GroupAddressStyle.ThreeLevel));
			_project.tUpdateByName("groupAddresses", List.of(light));
			TLObject rule = RuleFunctions.create(_project, "rules", runtime("Rule"));
			rule.tUpdateByName("identifier", "licht");
			TLObject reaction = RuleFunctions.create(rule, "reactions", runtime("Reaction"));
			RuleFunctions.create(reaction, "triggers", runtime("StartTrigger"));
			_write = RuleFunctions.create(reaction, "actions", runtime("WriteAction"));
			_write.tUpdateByName("datapoint", light);
			_write.tUpdateByName("value", "true");
			tx.commit();
		}
	}

	@Override
	protected void tearDown() throws Exception {
		SharedData.deleteProjects(List.of(_project));
		super.tearDown();
	}

	/** The form of the action names the group address by its name. */
	public void testForm() {
		RuleForm form = new RuleForm(_write);
		List<String> warnings = de(() -> form.warnings("datapoint"));
		assertEquals(List.of("Der Controller würde das ablehnen: Es gibt keinen Datenpunkt „Licht Flur (11/0/4)“."),
			warnings);
	}

	/** The image's findings (the deployment, the simulation of the project) name it the same way. */
	public void testImage() {
		ImageReport report = de(() -> RuntimeImage.build(_project, 1, NOW).validate());
		assertEquals(1, report.getRefusals().size());
		ImageReport.Refusal refusal = report.getRefusals().get(0);
		assertEquals("Regel „licht“: Es gibt keinen Datenpunkt „Licht Flur (11/0/4)“.",
			Resources.getInstance(Locale.GERMAN).getString(refusal.message()));
		// The controller's reason stays as it is.
		assertTrue(refusal.reason(), refusal.reason().endsWith("no datapoint '11/0/4'"));
	}

	/** The same refusal of the loader the simulation runs, without a project: the id. */
	public void testWithoutProject() {
		List<Object> rules = List.of(Map.of("id", "licht", "name", "Licht", "reactions", List.of(Map.of(
			"triggers", List.of(Map.of("kind", "start")),
			"actions", List.of(Map.of("kind", "write", "datapoint", "11/0/4", "value", Map.of("kind", "literal", "value",
				Boolean.TRUE)))))));
		try {
			RuleLoader.load(rules, new RuleLoader.DatapointDirectory() {
				@Override
				public ValueType type(String name) {
					return null;
				}

				@Override
				public boolean writable(String name) {
					return false;
				}
			}, null);
			fail("The datapoint is unknown.");
		} catch (RuleLoader.LoadException ex) {
			assertEquals("Es gibt keinen Datenpunkt „11/0/4“.",
				Resources.getInstance(Locale.GERMAN).getString(ex.getRefusal().message()));
		}
	}

	/** A datapoint inside a message given as an argument (the biggest histories of a budget). */
	public void testNested() {
		ResKey list = I18NConstants.IMAGE_BIGGEST_MORE__LIST_ITEM.fill(
			I18NConstants.IMAGE_BIGGEST__DATAPOINT_BYTES.fill(DatapointId.of("11/0/4"), "1200"),
			I18NConstants.IMAGE_BIGGEST__DATAPOINT_BYTES.fill(DatapointId.of("11/0/9"), "800"));
		ResKey message = I18NConstants.IMAGE_HISTORY_RAM__NEED_CAP_BIGGEST.fill("70000", "65536", list);
		assertEquals("Die Verläufe brauchen 70000 Bytes RAM, das Gerät erlaubt höchstens 65536; die größten, in Bytes: "
			+ "11/0/4: 1200, 11/0/9: 800.", Resources.getInstance(Locale.GERMAN).getString(message));
		ResKey named = de(() -> DatapointNames.of(_project).names(message));
		assertEquals("Die Verläufe brauchen 70000 Bytes RAM, das Gerät erlaubt höchstens 65536; die größten, in Bytes: "
			+ "Licht Flur (11/0/4): 1200, 11/0/9: 800.", Resources.getInstance(Locale.GERMAN).getString(named));
		// A message without datapoints stays the same.
		ResKey plain = I18NConstants.REFUSAL_AT__PATH_MESSAGE.fill("a", "b");
		assertSame(plain, DatapointNames.of(_project).names(plain));
	}

	// ---- helpers ------------------------------------------------------------------------------

	private static <T> T de(Supplier<T> value) {
		TLContext context = TLContext.getContext();
		Person before = context.getPerson();
		String contextId = context.getContextId();
		context.setPerson(PersonManager.getManager().getRoot());
		Locale localeBefore = context.getCurrentLocale();
		context.setCurrentLocale(Locale.GERMAN);
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

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRefusalNames.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
