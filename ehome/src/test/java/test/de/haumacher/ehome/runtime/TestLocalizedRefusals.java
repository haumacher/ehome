/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

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
import de.haumacher.ehome.runtime.RuntimeFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The findings of E-Home's check of the runtime image in the language of the user (haumacher/ehome
 * #95, #96): a refusal keeps the controller's English reason and carries a message, a whole sentence
 * in every language of the application, which a stored refusal keeps in both languages.
 *
 * <p>
 * The completeness check runs the conformance cases and the image fixtures of the specification
 * through the validator: every refusal, of the validator itself and of the loaders of rules,
 * schedules and blocks, has a message with a German text of its own.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestLocalizedRefusals extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

	/** A key of the application's messages that did not resolve. */
	private static final Pattern UNRESOLVED = Pattern.compile("\\b(IMAGE|RULE|SCHEDULE|BLOCK|OWNER)_[A-Z_]+\\b|\\[class\\.");

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
				String where = file.getName() + " " + refusal;
				assertNotNull("No message: " + where, refusal.message());
				String de = text(refusal, Locale.GERMAN);
				String en = text(refusal, Locale.ENGLISH);
				assertFalse("No German text: " + where + ": " + de, de.equals(en));
				assertFalse("Unresolved key: " + where + ": " + de, UNRESOLVED.matcher(de).find());
				keys.add(refusal.message().plain());
				checked++;
			}
		}
		assertTrue("The cases refuse something: " + checked, checked > 50);
		assertTrue("The cases reach many messages: " + keys.size(), keys.size() > 40);
	}

	/**
	 * A refusal of the rule loader (haumacher/ehome #96): the rule's id frames the loader's sentence.
	 */
	public void testRuleLoader() {
		Refusal refusal = single(image(
			"\"datapoints\": [{\"id\": \"light\", \"type\": \"boolean\", \"virtual\": {}, \"writable\": true}],"
				+ " \"rules\": [{\"id\": \"r\", \"name\": \"R\", \"reactions\": [{\"triggers\": [{\"kind\": \"timer\", \"timer\": \"t\"}],"
				+ " \"actions\": []}]}]"));
		assertEquals("rules[0].reactions[0].triggers[0].timer", refusal.path());
		assertEquals("rule 'r': unknownReference: no timer 't' in the rule", refusal.reason());
		assertEquals("Regel „r“: Die Regel hat keinen Timer „t“.", text(refusal, Locale.GERMAN));
		assertEquals("Rule \"r\": The rule has no timer \"t\".", text(refusal, Locale.ENGLISH));
	}

	/**
	 * Refusals of the schedule loader: a value out of its range, an output another component owns
	 * (named in the user's language).
	 */
	public void testScheduleLoader() throws Exception {
		ImageReport report = ImageValidator.validate(Files.readString(
			new File(TestImageConformance.IMAGES, "cases/refuse-schedules.json").toPath(), StandardCharsets.UTF_8));
		Refusal owned = refusal(report, "schedules[0].output");
		assertEquals("outputConflict: output 'knxbool' is owned by the KNX adapter", owned.reason());
		assertEquals("Der KNX-Adapter belegt den Ausgang „knxbool“ schon.", text(owned, Locale.GERMAN));
		assertEquals("The KNX adapter owns the output \"knxbool\" already.", text(owned, Locale.ENGLISH));

		Refusal hour = refusal(report, "schedules[1].time");
		assertEquals("malformed: hour must be 0..23", hour.reason());
		assertEquals("„hour“ muss zwischen 0 und 23 liegen.", text(hour, Locale.GERMAN));
		assertEquals("\"hour\" must be 0 to 23.", text(hour, Locale.ENGLISH));

		// What a deployment stores: both languages, without the keys.
		ResKey stored = owned.translations();
		assertEquals(text(owned, Locale.GERMAN), Resources.getInstance(Locale.GERMAN).getString(stored));
		assertEquals(text(owned, Locale.ENGLISH), Resources.getInstance(Locale.ENGLISH).getString(stored));
	}

	/**
	 * A refusal of the block loader: the virtual device's id frames the loader's sentence.
	 */
	public void testBlockLoader() {
		Refusal refusal = single(image(
			"\"blocks\": [{\"id\": \"b1\", \"type\": \"nonsense\", \"version\": \"1.0\", \"label\": \"B\"}]"));
		assertEquals("blocks[0].type", refusal.path());
		assertEquals("block 'b1': unknownType: nonsense 1.0 (this firmware has no such block type)", refusal.reason());
		assertEquals("Virtuelles Gerät „b1“: Diese Firmware kennt den Typ nonsense 1.0 virtueller Geräte nicht.",
			text(refusal, Locale.GERMAN));
		assertEquals("Virtual device \"b1\": This firmware has no type nonsense 1.0 of virtual devices.",
			text(refusal, Locale.ENGLISH));
	}

	/**
	 * An unknown member of a rule in an image of a newer minor version: the message adds that the
	 * firmware is older than the image, in the user's language.
	 */
	public void testNewerMinorRule() throws Exception {
		ImageReport report = ImageValidator.validate(Files.readString(
			new File(TestImageConformance.IMAGES, "cases/refuse-newer-minor-rule.json").toPath(), StandardCharsets.UTF_8));
		Refusal refusal = refusal(report, "rules[0]");
		String supported = ImageValidator.SCHEMA_MAJOR + "." + ImageValidator.SCHEMA_MINOR;
		assertEquals("Regel „r“: Unbekannter Eintrag „priority“. Das Schema 1.99 des Abbilds ist neuer, als diese Firmware"
			+ " liest (bis " + supported + "); aktualisieren Sie die Firmware.", text(refusal, Locale.GERMAN));
		assertEquals("Rule \"r\": Unknown member \"priority\". The schema 1.99 of the image is newer than this firmware"
			+ " reads (up to " + supported + "); update the firmware.", text(refusal, Locale.ENGLISH));
	}

	/**
	 * The messages of the settings form name the problem in the user's language, without the
	 * controller's English (haumacher/ehome #96).
	 */
	public void testSettingsMessages() {
		ResKey zone = RuntimeFunctions.timeZoneProblem("Europe/Berlin");
		assertEquals("Die Zeitzone „Europe/Berlin“ ist keine POSIX-TZ-Regel: Die Abweichung der Normalzeit fehlt oder ist"
			+ " ungültig ([+-]hh[:mm[:ss]], hh höchstens 24). Ein Beispiel einer POSIX-TZ-Regel: CET-1CEST,M3.5.0,M10.5.0/3.",
			Resources.getInstance(Locale.GERMAN).getString(zone));
		assertEquals("The time zone \"Europe/Berlin\" is not a POSIX TZ rule: the offset of the standard zone is missing or"
			+ " invalid ([+-]hh[:mm[:ss]], hh at most 24). An example of a POSIX TZ rule: CET-1CEST,M3.5.0,M10.5.0/3.",
			Resources.getInstance(Locale.ENGLISH).getString(zone));
		assertEquals("Die Zeitzone ist länger als 127 Zeichen.",
			Resources.getInstance(Locale.GERMAN).getString(RuntimeFunctions.timeZoneProblem("x".repeat(128))));

		ResKey host = RuntimeFunctions.hostNameProblem("pool..ntp.org");
		assertEquals("Der Server „pool..ntp.org“ ist kein Hostname: Er hat einen leeren Namensteil zwischen Punkten.",
			Resources.getInstance(Locale.GERMAN).getString(host));
		assertEquals("The server \"pool..ntp.org\" is not a host name: it has an empty part between dots.",
			Resources.getInstance(Locale.ENGLISH).getString(host));
		assertEquals("Der Hostname ist länger als 127 Zeichen.",
			Resources.getInstance(Locale.GERMAN).getString(RuntimeFunctions.hostNameProblem("x".repeat(128))));
	}

	/**
	 * Every message of the loaders of rules, schedules and blocks has a German text of its own and an
	 * English one, also those the conformance cases do not reach.
	 */
	public void testEveryLoaderMessageTranslated() throws Exception {
		int checked = 0;
		for (Class<?> constants : List.of(de.haumacher.ehome.rules.I18NConstants.class,
			de.haumacher.ehome.blocks.I18NConstants.class)) {
			for (Field field : constants.getFields()) {
				if (!Modifier.isStatic(field.getModifiers())) {
					continue;
				}
				ResKey key = filled(field.get(null));
				if (key == null) {
					continue;
				}
				String de = Resources.getInstance(Locale.GERMAN).getString(key);
				String en = Resources.getInstance(Locale.ENGLISH).getString(key);
				String where = constants.getPackageName() + " " + field.getName();
				assertFalse("No German text: " + where + ": " + de, de.equals(en));
				assertFalse("Unresolved: " + where + ": " + de, de.startsWith("[") || UNRESOLVED.matcher(de).find());
				assertFalse("Unresolved: " + where + ": " + en, en.startsWith("[") || UNRESOLVED.matcher(en).find());
				checked++;
			}
		}
		assertTrue("The loaders have their messages: " + checked, checked > 250);
	}

	/** The key with placeholder arguments, <code>null</code> for a field that is no key. */
	private static ResKey filled(Object constant) throws Exception {
		if (constant instanceof ResKey key) {
			return key;
		}
		if (constant == null) {
			return null;
		}
		for (Method fill : constant.getClass().getMethods()) {
			if (fill.getName().equals("fill") && ResKey.class.isAssignableFrom(fill.getReturnType())
				&& Arrays.stream(fill.getParameterTypes()).allMatch(type -> type == Object.class)) {
				Object[] arguments = new Object[fill.getParameterCount()];
				Arrays.fill(arguments, "x");
				return (ResKey) fill.invoke(constant, arguments);
			}
		}
		return null;
	}

	private static ImageReport image(String members) {
		return ImageValidator.validate("{\"image\": {\"schemaVersion\": \"1.0\", \"projectId\": \"case\", \"revision\": 1}, "
			+ members + "}");
	}

	private static Refusal single(ImageReport report) {
		assertEquals(report.toString(), 1, report.getRefusals().size());
		return report.getRefusals().get(0);
	}

	private static Refusal refusal(ImageReport report, String path) {
		for (Refusal refusal : report.getRefusals()) {
			if (refusal.path().equals(path)) {
				return refusal;
			}
		}
		fail("No refusal at " + path + ": " + report);
		return null;
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
