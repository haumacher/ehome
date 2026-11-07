/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.ImageReport.Refusal;
import de.haumacher.ehome.runtime.ImageValidator;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The findings of E-Home's check of the runtime image in the language of the user (haumacher/ehome
 * #95): a refusal keeps the controller's English reason and carries a message, a whole sentence in
 * every language of the application, which a stored refusal keeps in both languages.
 *
 * <p>
 * The completeness check runs the conformance cases and the image fixtures of the specification
 * through the validator: every refusal it makes itself has a message with a German text of its
 * own. Only the refusals of the loaders of rules, schedules and blocks have none (their paths begin
 * with <code>rules</code>, <code>schedules</code>, <code>blocks</code>); they show the English
 * reason.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestLocalizedRefusals extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

	/** The prefixes of the paths of the refusals of the loaders, which have no message. */
	private static final List<String> LOADERS = List.of("rules", "schedules", "blocks");

	/**
	 * The issue's case: a sensor without the datapoint of its role, and a role that needs a writable
	 * datapoint, in a project built without an import.
	 */
	public void testFunctionRoles() throws Exception {
		TLObject project;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			project = create("ehome.project:Project");
			project.tUpdateByName("name", "Localized refusals");
			project.tUpdateByName("extId", "P-TEST-95");
			TLObject level = RuleFunctions.create(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			level.tUpdateByName("identifier", "virtual.level");
			level.tUpdateByName("type", valueType("unsigned"));

			TLObject sensor = create("ehome.runtime:Function");
			sensor.tUpdateByName("name", ResKey.builder().add(Locale.GERMAN, "Temperatur").build());
			sensor.tUpdateByName("identifier", "temperature");
			sensor.tUpdateByName("kind", FunctionProposal.classifier("sensor"));
			TLObject dimmer = create("ehome.runtime:Function");
			dimmer.tUpdateByName("name", ResKey.builder().add(Locale.GERMAN, "Dimmer").build());
			dimmer.tUpdateByName("identifier", "dimmer");
			dimmer.tUpdateByName("kind", FunctionProposal.classifier("dimmer"));
			dimmer.tUpdateByName("levelCommand", level);
			project.tUpdateByName("functions", List.of(sensor, dimmer));
			tx.commit();
		}
		try {
			ImageReport report = RuntimeImage.build(project, 1, NOW).validate();
			assertEquals(report.toString(), 2, report.getRefusals().size());

			Refusal role = report.getRefusals().get(0);
			assertEquals("functions[0].datapoints", role.path());
			assertEquals("a sensor needs the role value", role.reason());
			assertEquals("Eine Funktion der Art „sensor“ braucht die Rolle „value“.", text(role, Locale.GERMAN));
			assertEquals("A sensor needs the role value.", text(role, Locale.ENGLISH));

			Refusal writable = report.getRefusals().get(1);
			assertEquals("functions[1].datapoints.levelCommand", writable.path());
			assertEquals("the role levelCommand needs a writable datapoint; 'virtual.level' is not", writable.reason());
			assertEquals("Die Rolle „levelCommand“ braucht einen beschreibbaren Datenpunkt; „virtual.level“ ist es nicht.",
				text(writable, Locale.GERMAN));
			assertEquals("The role levelCommand needs a writable datapoint; \"virtual.level\" is not.",
				text(writable, Locale.ENGLISH));

			// What a deployment stores: the message in both languages, without its key.
			ResKey stored = writable.translations();
			assertEquals(text(writable, Locale.GERMAN), Resources.getInstance(Locale.GERMAN).getString(stored));
			assertEquals(text(writable, Locale.ENGLISH), Resources.getInstance(Locale.ENGLISH).getString(stored));
			assertTrue(stored.isLiteral());
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * A refusal of the controller has no message: it shows the English reason, and a deployment
	 * stores none.
	 */
	public void testControllerRefusal() {
		Refusal refusal = new Refusal("datapoints[0].knx.dpt", "DPT 9.001 carries real values, the datapoint is boolean");
		assertNull(refusal.message());
		assertNull(refusal.translations());
		assertEquals(refusal.reason(), refusal.text());
	}

	/**
	 * Every refusal of the validator itself, in all conformance cases and image fixtures, has a
	 * message whose German text is its own (not the English one a missing translation falls back
	 * to).
	 */
	public void testEveryRefusalHasAMessage() throws Exception {
		List<File> files = new ArrayList<>();
		files.addAll(Arrays.asList(jsonFiles(new File(TestImageConformance.IMAGES, "cases"))));
		files.addAll(Arrays.asList(jsonFiles(TestImageConformance.IMAGES)));
		Set<ResKey> keys = new HashSet<>();
		int checked = 0;
		for (File file : files) {
			ImageReport report = ImageValidator.validate(Files.readString(file.toPath(), StandardCharsets.UTF_8));
			for (Refusal refusal : report.getRefusals()) {
				if (LOADERS.stream().anyMatch(refusal.path()::startsWith)) {
					continue;
				}
				String where = file.getName() + " " + refusal;
				assertNotNull("No message: " + where, refusal.message());
				String de = text(refusal, Locale.GERMAN);
				String en = text(refusal, Locale.ENGLISH);
				assertFalse("No German text: " + where + ": " + de, de.equals(en));
				assertFalse("Unresolved key: " + where + ": " + de, de.contains("IMAGE_"));
				keys.add(refusal.message().plain());
				checked++;
			}
		}
		assertTrue("The cases refuse something: " + checked, checked > 50);
		assertTrue("The cases reach many messages: " + keys.size(), keys.size() > 40);
	}

	private static File[] jsonFiles(File dir) {
		File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
		assertNotNull(dir.getPath(), files);
		Arrays.sort(files);
		return files;
	}

	private static String text(Refusal refusal, Locale locale) {
		assertNotNull(refusal.toString(), refusal.message());
		return Resources.getInstance(locale).getString(refusal.message());
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	private static Object valueType(String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType")).getClassifier(name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestLocalizedRefusals.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
