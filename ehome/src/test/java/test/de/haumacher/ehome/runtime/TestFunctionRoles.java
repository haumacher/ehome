/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.derived;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.address;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.rules.DatapointId;
import de.haumacher.ehome.runtime.DatapointNames;
import de.haumacher.ehome.runtime.Deployer;
import de.haumacher.ehome.runtime.FunctionKinds;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.FunctionRoleCheck;
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.Pages;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The roles of schema 1.6 (homecontrol <code>spec/runtime-image.md</code> 3.5) on
 * <code>src/test/fixtures/TestFunctionRoles.knxproj</code> (see <code>make-fixtures.py</code>).
 *
 * <ul>
 * <li>A blind's end positions (haumacher/ehome #136): the proposal fills <code>atTop</code> and
 * <code>atBottom</code> from the 1-bit outputs of the channel named for the upper and the lower end
 * position, beside a position where the channel has one; not from the direction's status (1.008) nor
 * from an object naming both ends. The image takes them as booleans and refuses a percentage there,
 * with a reason naming the role.</li>
 * <li>A climate's heating level (haumacher/ehome #138): the proposal fills <code>heatingLevel</code>
 * from the actuating value (5.001) of a heating actuator with its own controller, whose channel takes
 * the received room temperature as its temperature; <code>heating</code> from its truth value where
 * it has one. The image takes a percentage as <code>heating</code> as well, and refuses a truth value
 * as <code>heatingLevel</code>.</li>
 * <li>A dimmer's main role is its switching command (haumacher/ehome #134): it needs it or its level
 * command; with neither it is unused (also the derived attribute). With its command alone it is in the
 * image, which then needs a controller reading schema 1.6, as a heating of a number does.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestFunctionRoles extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestFunctionRoles.knxproj";

	private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");

	private static final List<String> EXPECTED = List.of(
		// "Position oben/unten" names both ends: no role.
		"Jalousie Gast | blind | move=6/3/1 | - | used",
		// The end positions without a position; not the direction's status 1.008.
		"Markise Terrasse | blind | move=6/1/1, stop=6/1/2, atTop=6/1/3, atBottom=6/1/4 | - | used",
		// The end positions beside a position.
		"Rollladen Büro | blind | move=6/2/1, position=6/2/2, atTop=6/2/3, atBottom=6/2/4 | - | used",
		// The heating actuator's controller: the received temperature, the actuating value, heating.
		"Temperatur Bad | climate | temperature=6/4/1, setpoint=6/4/3, setpointCommand=6/4/2, modeCommand=6/4/4, "
			+ "heating=6/4/6, heatingLevel=6/4/5 | Wohnzimmer | used",
		// The actuating value without a truth value of heating.
		"Temperatur Kind | climate | temperature=6/5/1, setpointCommand=6/5/2, heatingLevel=6/5/5 | Küche | used");

	/**
	 * One import of the fixture for both parts (an import of its three programs costs a few seconds the
	 * first time).
	 */
	public void testRoles() throws Exception {
		assertEquals("command", FunctionKinds.mainRole("dimmer"));
		assertEquals(List.of("command", "levelCommand"), FunctionKinds.requiredRoles("dimmer"));
		assertEquals(List.of("move"), FunctionKinds.requiredRoles("blind"));
		assertEquals(List.of(), FunctionKinds.requiredRoles("toaster"));

		List<TLObject> before = instances("ehome.project:Project");
		importSample(FIXTURE);
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);
			proposal(project);
			dimmer(project);
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private void proposal(TLObject project) {
		run(project);
		assertEquals(EXPECTED, TestFunctionLinks.table(project));
		for (TLObject function : FunctionProposal.functions(project)) {
			// Every role is served by an object in effect (haumacher/ehome #137).
			assertEquals(TestFunctionProposal.name(function), List.of(), FunctionRoleCheck.problems(function));
		}

		RuntimeImage image = RuntimeImage.build(project, 1, NOW);
		ImageReport report = image.validate();
		assertTrue(report.toString(), report.isValid());
		assertEquals(RuntimeImage.SCHEMA_VERSION, ((Map<?, ?>) image.getJson().get("image")).get("schemaVersion"));
		Map<?, ?> awning = roles(image, "Markise Terrasse");
		assertEquals(List.of("move", "stop", "atTop", "atBottom"), List.copyOf(awning.keySet()));
		Map<?, ?> child = roles(image, "Temperatur Kind");
		assertEquals(List.of("temperature", "setpointCommand", "heatingLevel"), List.copyOf(child.keySet()));
		// A climate whose heating is a boolean, its level beside it, needs no 1.6.
		assertNull(Deployer.schema16Refusal(image.getJson(), "1.5", "Fake"));

		// A percentage as the end position: refused with the role named.
		TLObject awningFunction = function(project, "Markise Terrasse");
		TestFunctionProposal.update(awningFunction, "atTop", address(project, "6/2/2"));
		assertRefused(project, ".datapoints.atTop",
			I18NConstants.IMAGE_ROLE_TYPE__NAME_TYPE_ROLE_KIND_TYPES.fill(DatapointId.of(id(project, "6/2/2")), "real",
				"atTop", "blind", "boolean"));
		TestFunctionProposal.update(awningFunction, "atTop", address(project, "6/1/3"));

		// A truth value as the heating level: refused; a percentage as heating: taken, the image needs 1.6.
		TLObject childFunction = function(project, "Temperatur Kind");
		TestFunctionProposal.update(childFunction, "heatingLevel", address(project, "6/4/6"));
		assertRefused(project, ".datapoints.heatingLevel",
			I18NConstants.IMAGE_ROLE_TYPE__NAME_TYPE_ROLE_KIND_TYPES.fill(DatapointId.of(id(project, "6/4/6")),
				"boolean", "heatingLevel", "climate", "unsigned, signed, real, enumeration"));
		TestFunctionProposal.update(childFunction, "heatingLevel", address(project, "6/5/5"));
		TestFunctionProposal.update(childFunction, "heating", address(project, "6/5/5"));
		image = RuntimeImage.build(project, 1, NOW);
		assertTrue(image.validate().toString(), image.validate().isValid());
		assertEquals(roles(image, "Temperatur Kind").get("heatingLevel"), roles(image, "Temperatur Kind").get("heating"));
		String functionId = String.valueOf(childFunction.tValueByName("identifier"));
		assertEquals(I18NConstants.DEPLOY_REFUSED_HEATING_SCHEMA__CONTROLLER_SCHEMA_FUNCTION.fill("Fake", "1.5",
			functionId), Deployer.schema16Refusal(image.getJson(), "1.5", "Fake"));
		assertEquals(I18NConstants.DEPLOY_REFUSED_HEATING_NO_SCHEMA__CONTROLLER_FUNCTION.fill("Fake", functionId),
			Deployer.schema16Refusal(image.getJson(), null, "Fake"));
		assertNull(Deployer.schema16Refusal(image.getJson(), "1.6", "Fake"));
		assertNull(Deployer.schema16Refusal(image.getJson(), "2.0", "Fake"));
		TestFunctionProposal.update(childFunction, "heating", null);

		// Running the proposal again changes nothing.
		assertEquals(List.of(), run(project).added());
	}

	private void dimmer(TLObject project) {
		TLObject dimmer;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			dimmer = TestFunctionProposal.create("ehome.runtime:Function");
			dimmer.tUpdateByName("name", ResKey.text("Dimmer Flur"));
			dimmer.tUpdateByName("identifier", "dimmer_flur");
			dimmer.tUpdateByName("kind", FunctionProposal.classifier("dimmer"));
			List<TLObject> functions = new ArrayList<>(FunctionProposal.functions(project));
			functions.add(dimmer);
			project.tUpdateByName("functions", functions);
			tx.commit();
		}
		// Neither its switching nor its level command: unused, also as the derived attribute.
		assertTrue(FunctionProposal.unused(dimmer));
		assertEquals(Boolean.TRUE, derived(dimmer, "unused"));

		// Its switching command alone: in use, in the image, which needs schema 1.6.
		TestFunctionProposal.update(dimmer, "command", address(project, "6/1/1"));
		assertFalse(FunctionProposal.unused(dimmer));
		assertEquals(Boolean.FALSE, derived(dimmer, "unused"));
		RuntimeImage image = RuntimeImage.build(project, 1, NOW);
		assertTrue(image.validate().toString(), image.validate().isValid());
		assertEquals(Map.of("command", id(project, "6/1/1")), roles(image, "Dimmer Flur"));
		assertEquals(I18NConstants.DEPLOY_REFUSED_DIMMER_SCHEMA__CONTROLLER_SCHEMA_FUNCTION.fill("Fake", "1.5",
			"dimmer_flur"), Deployer.schema16Refusal(image.getJson(), "1.5", "Fake"));
		assertEquals(I18NConstants.DEPLOY_REFUSED_DIMMER_NO_SCHEMA__CONTROLLER_FUNCTION.fill("Fake", "dimmer_flur"),
			Deployer.schema16Refusal(image.getJson(), "", "Fake"));
		assertNull(Deployer.schema16Refusal(image.getJson(), "1.6", "Fake"));

		// Its level command alone, as an image of 1.5 had it: in use, an older controller takes it.
		TestFunctionProposal.update(dimmer, "command", null);
		TestFunctionProposal.update(dimmer, "levelCommand", address(project, "6/2/2"));
		assertFalse(FunctionProposal.unused(dimmer));
		assertEquals(Boolean.FALSE, derived(dimmer, "unused"));
		image = RuntimeImage.build(project, 1, NOW);
		assertNull(Deployer.schema16Refusal(image.getJson(), "1.5", "Fake"));
		// The level command needs a writable datapoint: the status of a position is not one.
		assertRefused(project, ".datapoints.levelCommand",
			I18NConstants.IMAGE_ROLE_WRITABLE__ROLE_NAME.fill("levelCommand",
				DatapointId.of(id(project, "6/2/2"))));
	}

	/**
	 * The image of the project is refused at the path ending in the given suffix, with the given reason
	 * (whose datapoint the user reads as Name (id)).
	 */
	private static void assertRefused(TLObject project, String pathSuffix, ResKey reason) {
		RuntimeImage image = RuntimeImage.build(project, 1, NOW);
		ImageReport report = image.validate();
		assertFalse(report.isValid());
		for (ImageReport.Refusal refusal : report.getRefusals()) {
			if (refusal.path().endsWith(pathSuffix)) {
				Resources resources = Resources.getInstance();
				assertEquals(resources.getString(DatapointNames.of(project).names(reason)),
					resources.getString(refusal.message()));
				return;
			}
		}
		fail("No refusal at " + pathSuffix + ": " + report);
	}

	/** The roles of the function of the image with the given label. */
	private static Map<?, ?> roles(RuntimeImage image, String label) {
		for (Object item : (List<?>) image.getJson().get("functions")) {
			Map<?, ?> function = (Map<?, ?>) item;
			Object labels = function.get("label");
			if (label.equals(labels) || labels instanceof Map<?, ?> map && map.containsValue(label)) {
				return (Map<?, ?>) function.get("datapoints");
			}
		}
		throw new AssertionError("No function " + label + " in the image.");
	}

	/** The function of the project with the given name. */
	private static TLObject function(TLObject project, String name) {
		for (TLObject function : FunctionProposal.functions(project)) {
			if (name.equals(TestFunctionProposal.name(function))) {
				return function;
			}
		}
		throw new AssertionError("No function " + name);
	}

	/** The id of the group address in the image. */
	private static String id(TLObject project, String threeLevel) {
		return Pages.imageId(address(project, threeLevel));
	}

	private static FunctionProposal.Run run(TLObject project) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			FunctionProposal.Run result = FunctionProposal.run(project);
			tx.commit();
			return result;
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestFunctionRoles.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
