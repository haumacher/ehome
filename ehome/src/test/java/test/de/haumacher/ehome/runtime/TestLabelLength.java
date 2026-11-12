/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
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
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.Deployer;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.LabelLimit;
import de.haumacher.ehome.runtime.RuleDrafts;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.simulation.ProjectSimulation;

/**
 * The labels the controller shows have at most 63 bytes per language (haumacher/ehome #116): a
 * longer text is an error of the input of a rule's name, a parameter's or choice's title, an
 * application datapoint's or schedule entry's label; a label stored before is refused by the
 * simulation and the deployment with a message in the reader's language that names the object, the
 * language and the length, not the path of the image.
 */
@SuppressWarnings("javadoc")
public class TestLabelLength extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

	/** 64 bytes. */
	private static final String LONG = "Maximale Helligkeit der Hofbeleuchtung nachts am Wochenende, Hof";

	/** 62 characters, 64 bytes: the umlauts count twice. */
	private static final String UMLAUTS = "Größte Helligkeit der Hofbeleuchtung nachts am Wochenende, Hof";

	/** 63 bytes. */
	private static final String LIMIT = "Maximale Helligkeit der Hofbeleuchtung nachts am Wochenende Hof";

	private static final String TAIL = " ist zu lang: 64 Bytes, höchstens 63 (Umlaute zählen doppelt).";

	private TLObject _project;

	private TLObject _rule;

	private TLObject _parameter;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		assertEquals(64, LabelLimit.bytes(LONG));
		assertEquals(62, UMLAUTS.length());
		assertEquals(64, LabelLimit.bytes(UMLAUTS));
		assertEquals(63, LabelLimit.bytes(LIMIT));
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_project = create("ehome.project:Project");
			_project.tUpdateByName("name", "Label length");
			_rule = RuleFunctions.create(_project, "rules", runtime("Rule"));
			_rule.tUpdateByName("identifier", "hoflicht");
			_rule.tUpdateByName("name", i18n("Yard light", "Hoflicht"));
			_parameter = RuleFunctions.create(_rule, "parameters", runtime("RuleParameter"));
			_parameter.tUpdateByName("identifier", "maxHelligkeit");
			_parameter.tUpdateByName("title", i18n("Max. brightness", "Max. Helligkeit"));
			tx.commit();
		}
	}

	@Override
	protected void tearDown() throws Exception {
		SharedData.deleteProjects(List.of(_project));
		super.tearDown();
	}

	/**
	 * The edit form of a parameter: a German title of 64 bytes (also of 62 characters with umlauts)
	 * is an error with the issue's message, and not a warning besides; 63 bytes are accepted.
	 */
	public void testParameter() {
		RuleForm form = new RuleForm(_parameter);
		de(() -> {
			form.set("title", i18n("Max. brightness", LONG));
			assertEquals(List.of("Die deutsche Bezeichnung des Parameters „" + LONG + "“ der Regel „Hoflicht“" + TAIL),
				form.errors("title"));
			assertEquals(List.of(), form.warnings("title"));
			form.set("title", i18n("Max. brightness", UMLAUTS));
			assertEquals(List.of("Die deutsche Bezeichnung des Parameters „" + UMLAUTS + "“ der Regel „Hoflicht“" + TAIL),
				form.errors("title"));
			form.set("title", i18n("Max. brightness", LIMIT));
			assertEquals(List.of(), form.errors("title"));
			assertEquals(List.of(), form.warnings("title"));
			return null;
		});
		// The English text in English, read by an English reader.
		en(() -> {
			form.set("title", i18n(LONG, "Max. Helligkeit"));
			assertEquals(List.of("The English label of the parameter \"" + LONG
				+ "\" of the rule \"Yard light\" is too long: 64 bytes, at most 63 (umlauts count twice)."),
				form.errors("title"));
			return null;
		});
	}

	/** The create dialog of a parameter: its draft is checked the same way. */
	public void testParameterDraft() {
		TLObject draft = RuleDrafts.draft(_rule, "parameters", runtime("RuleParameter"));
		RuleForm form = new RuleForm(draft);
		de(() -> {
			form.set("identifier", "grenze");
			form.set("title", i18n("Limit", LONG));
			assertEquals(List.of("Die deutsche Bezeichnung des Parameters „" + LONG + "“ der Regel „Hoflicht“" + TAIL),
				form.errors("title"));
			form.set("title", i18n("Limit", LIMIT));
			assertEquals(List.of(), form.errors("title"));
			return null;
		});
	}

	public void testChoice() {
		TLObject choice;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			choice = RuleFunctions.create(_parameter, "choices", runtime("RuleChoice"));
			choice.tUpdateByName("value", "1");
			choice.tUpdateByName("title", i18n("Dim", "Gedimmt"));
			tx.commit();
		}
		RuleForm form = new RuleForm(choice);
		de(() -> {
			form.set("title", i18n("Dim", UMLAUTS));
			assertEquals(List.of("Die deutsche Bezeichnung der Auswahl „" + UMLAUTS
				+ "“ des Parameters „Max. Helligkeit“ der Regel „Hoflicht“" + TAIL), form.errors("title"));
			assertEquals(List.of(), form.warnings("title"));
			form.set("title", i18n("Dim", LIMIT));
			assertEquals(List.of(), form.errors("title"));
			return null;
		});
	}

	public void testRuleName() {
		RuleForm form = new RuleForm(_rule);
		de(() -> {
			form.set("name", i18n("Yard light", LONG));
			assertEquals(List.of("Die deutsche Bezeichnung der Regel „" + LONG + "“" + TAIL), form.errors("name"));
			assertEquals(List.of(), form.warnings("name"));
			form.set("name", i18n("Yard light", LIMIT));
			assertEquals(List.of(), form.errors("name"));
			return null;
		});
	}

	public void testApplicationDatapoint() {
		TLObject datapoint;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			datapoint = RuleFunctions.create(_project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			datapoint.tUpdateByName("identifier", "hof.max");
			tx.commit();
		}
		RuleForm form = new RuleForm(datapoint);
		de(() -> {
			form.set("label", i18n("Yard", LONG));
			assertEquals(List.of("Die deutsche Bezeichnung des Anwendungsdatenpunkts „" + LONG + " (hof.max)“" + TAIL),
				form.errors("label"));
			form.set("label", i18n("Yard", LIMIT));
			assertEquals(List.of(), form.errors("label"));
			return null;
		});
	}

	public void testScheduleEntry() {
		TLObject entry;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			entry = RuleFunctions.create(_project, "schedules", runtime("WeeklySchedule"));
			entry.tUpdateByName("identifier", "morgens");
			entry.tUpdateByName("label", i18n("Morning", "Morgens"));
			tx.commit();
		}
		RuleForm form = new RuleForm(entry);
		de(() -> {
			form.set("label", i18n("Morning", LONG));
			assertEquals(List.of("Die deutsche Bezeichnung des Zeitplaneintrags „" + LONG + "“" + TAIL),
				form.errors("label"));
			assertEquals(List.of(), form.warnings("label"));
			form.set("label", i18n("Morning", LIMIT));
			assertEquals(List.of(), form.errors("label"));
			return null;
		});
	}

	/**
	 * A title stored before the check: the simulation refuses to start with the message of the input
	 * check in the reader's language, without the path of the image; the deployment records the
	 * same message in every language, at the parameter.
	 */
	public void testStoredTitle() {
		// Refused as input, also by the commit.
		try {
			update(_parameter, "title", i18n("Max. brightness", LONG));
			fail("A title of 64 bytes is not stored.");
		} catch (TopLogicException ex) {
			// Expected.
		}

		for (String title : List.of(LONG, UMLAUTS)) {
			storedBefore(_parameter, "title", i18n("Max. brightness", title));
			String expected = "Die deutsche Bezeichnung des Parameters „" + title + "“ der Regel „Hoflicht“" + TAIL;

			// The image's finding.
			ImageReport report = de(() -> RuntimeImage.build(_project, 1, NOW).validate());
			assertEquals(1, report.getRefusals().size());
			ImageReport.Refusal refusal = report.getRefusals().get(0);
			assertEquals("rules[0].parameters[0].label.de", refusal.path());
			assertEquals(expected, Resources.getInstance(Locale.GERMAN).getString(refusal.message()));

			// The simulation.
			String error = de(() -> {
				try {
					ProjectSimulation.start(_project, NOW.getEpochSecond(), new File("target/label-length"));
					fail("The image is invalid.");
					return null;
				} catch (TopLogicException ex) {
					return Resources.getInstance(Locale.GERMAN).getString(ex.getErrorKey());
				}
			});
			assertEquals("Das Laufzeit-Image des Projekts ist ungültig, daher kann die Simulation nicht gestartet werden: "
				+ expected, error);

			// The deployment.
			List<TLObject> controllers = new ArrayList<>();
			try {
				TLObject controller;
				try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
					controller = create("ehome.runtime:Controller");
					controller.tUpdateByName("name", "Label length");
					controller.tUpdateByName("host", "127.0.0.1");
					controller.tUpdateByName("port", Integer.valueOf(1));
					controller.tUpdateByName("token", "hct_00000000000000000000000000000000");
					tx.commit();
				}
				controllers.add(controller);
				ControllerFixture.deployTo(_project, controller);
				TLObject deployment = de(() -> Deployer.deploy(_project, NOW));
				assertEquals(Deployer.CODE_INVALID, deployment.tValueByName("code"));
				List<?> findings = (List<?>) deployment.tValueByName("refusals");
				assertEquals(1, findings.size());
				TLObject finding = (TLObject) findings.get(0);
				assertSame(_parameter, finding.tValueByName("element"));
				ResKey message = (ResKey) finding.tValueByName("message");
				assertEquals(expected, Resources.getInstance(Locale.GERMAN).getString(message));
				assertEquals("The German label of the parameter \"" + title
					+ "\" of the rule \"Hoflicht\" is too long: 64 bytes, at most 63 (umlauts count twice).",
					Resources.getInstance(Locale.ENGLISH).getString(message));
			} finally {
				update(_project, "controller", null);
				SharedData.deleteProjects(controllers);
			}
		}
	}

	/**
	 * Another refusal of the simulation: named by the object of the project it concerns, in the
	 * reader's language, not by its path.
	 */
	public void testOtherRefusal() {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_parameter.tUpdateByName("default", "200");
			_parameter.tUpdateByName("max", "100");
			tx.commit();
		}
		String error = de(() -> {
			try {
				ProjectSimulation.start(_project, NOW.getEpochSecond(), new File("target/label-length"));
				fail("The image is invalid.");
				return null;
			} catch (TopLogicException ex) {
				return Resources.getInstance(Locale.GERMAN).getString(ex.getErrorKey());
			}
		});
		assertEquals("Das Laufzeit-Image des Projekts ist ungültig, daher kann die Simulation nicht gestartet werden: "
			+ "Max. Helligkeit (maxHelligkeit): Regel „hoflicht“: Die Vorgabe liegt außerhalb von „min“ bis „max“.", error);
	}

	// ---- helpers ------------------------------------------------------------------------------

	/**
	 * Stores a value as it was stored before the check of the input: the commit check of the model's
	 * constraints refuses it now.
	 */
	private static void storedBefore(TLObject obj, String attribute, Object value) {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		kb.removeCommitChecker(ModelService.getInstance());
		try {
			update(obj, attribute, value);
		} finally {
			kb.addCommitChecker(ModelService.getInstance());
		}
	}

	private static void update(TLObject obj, String attribute, Object value) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			obj.tUpdateByName(attribute, value);
			tx.commit();
		}
	}

	private static ResKey i18n(String en, String de) {
		return ResKey.builder().add(Locale.ENGLISH, en).add(Locale.GERMAN, de).build();
	}

	private static <T> T en(Supplier<T> value) {
		return inLocale(Locale.ENGLISH, value);
	}

	private static <T> T de(Supplier<T> value) {
		return inLocale(Locale.GERMAN, value);
	}

	/** The value as a person reads it in the given language. */
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

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestLabelLength.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
