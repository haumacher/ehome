/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.knx;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.ExtIds.Kind;
import de.haumacher.ehome.model.operational.Channel;
import de.haumacher.ehome.model.operational.EhomeOperationalFactory;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterGroup;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.model.operational.Program;

/**
 * Test of the external IDs the app gives the objects it creates ({@link ExtIds}): the ETS shape,
 * numbers above the imported ones in the same scope, IDs kept on a repeated import, and the private
 * range of manufacturers without KNX ID.
 */
@SuppressWarnings("javadoc")
public class TestExtIds extends BasicTestCase {

	private static final String SAMPLE = "samples/Test.knxproj";

	private static final String PROGRAM = "M-0008_A-10D7-11-A0E5-O000A";

	public void testExtIds() throws Exception {
		// The tests of the application share one database: the project is the one this import creates.
		List<TLObject> before = instances("ehome.project:Project");
		importSample(SAMPLE);
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		Program program = (Program) byExtId("ehome.operational:Program", PROGRAM);
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);

		// The import raised the sequences to the highest imported number of each scope.
		try (Transaction tx = kb.beginTransaction()) {
			assertEquals(maxNumber(program.getParameters(), Kind.PARAMETER), ExtIds.current(program, Kind.PARAMETER));
			assertEquals(maxNumber(program.getPorts(), Kind.PORT), ExtIds.current(program, Kind.PORT));
			assertEquals(10, ExtIds.current(project, Kind.GROUP_ADDRESS));
			assertEquals(1, ExtIds.current(project, Kind.AREA));
			tx.rollback();
		}

		// Imported numbers per scope, before creating anything.
		long parameters = maxNumber(program.getParameters(), Kind.PARAMETER);
		long parameterRefs = maxNumber(ExtIds.members(program, Kind.PARAMETER_REF), Kind.PARAMETER_REF);
		long ports = maxNumber(program.getPorts(), Kind.PORT);
		long blocks = maxNumber(ExtIds.members(program, Kind.BLOCK), Kind.BLOCK);
		long channels = maxNumber(ExtIds.members(program, Kind.CHANNEL), Kind.CHANNEL);
		long areas = maxNumber(ExtIds.members(project, Kind.AREA), Kind.AREA);
		long lines = maxNumber(ExtIds.members(project, Kind.LINE), Kind.LINE);
		long addresses = maxNumber(ExtIds.members(project, Kind.GROUP_ADDRESS), Kind.GROUP_ADDRESS);
		assertTrue(parameters > 3000 && parameterRefs > 3000 && ports > 50 && blocks > 3000);
		assertEquals(0, channels);
		assertEquals(10, addresses);

		Parameter parameter;
		Port port;
		ParameterGroup block;
		Channel channel;
		TLObject area;
		TLObject line;
		TLObject address;
		try (Transaction tx = kb.beginTransaction()) {
			EhomeOperationalFactory factory = EhomeOperationalFactory.getInstance();
			parameter = factory.createParameter();
			parameter.setName("New");
			parameter.setType(program.getParameterTypes().iterator().next());
			program.addParameter(parameter);

			port = factory.createPort();
			port.setName("New object");
			// The next object number, as the dialog proposes it.
			port.setIndex(program.getPorts().stream().mapToInt(Port::getIndex).max().orElse(-1) + 1);
			program.addPort(port);

			channel = factory.createChannel();
			channel.setName("New channel");
			program.addContent(channel);

			block = factory.createParameterGroup();
			block.setName("New block");
			channel.addContent(block);

			area = create("ehome.physical:Area");
			add(project, "areas", area);
			line = create("ehome.physical:Line");
			add(ExtIds.members(project, Kind.AREA).iterator().next(), "lines", line);
			address = create("ehome.logical:GroupAddress");
			add(ExtIds.members(project, Kind.GROUP_RANGE).iterator().next(), "parts", address);

			// The IDs are given when the transaction commits.
			assertNull(parameter.getExtId());
			tx.commit();
		}

		String prefix = PROGRAM + "_";
		assertNumbered(parameter, prefix + "P-(\\d+)", parameters);
		assertNumbered(port, prefix + "O-(\\d+)", ports);
		assertNumbered(channel, prefix + "CH-(\\d+)", channels);
		assertNumbered(block, prefix + "PB-(\\d+)", blocks);
		assertNumbered(area, "P-01A0-0_A-(\\d+)", areas);
		assertNumbered(line, "P-01A0-0_L-(\\d+)", lines);
		assertNumbered(address, "P-01A0-0_GA-(\\d+)", addresses);
		assertEquals(PROGRAM + "_P-" + (parameters + 1), parameter.getExtId());
		assertEquals(PROGRAM + "_CH-1", channel.getExtId());
		assertEquals("P-01A0-0_GA-11", ExtIds.extId(address));

		// A further import of the same project: nothing renumbered, the program is reused.
		Map<String, Integer> counts = Map.of(
			"parameters", program.getParameters().size(),
			"ports", program.getPorts().size(),
			"blocks", ExtIds.members(program, Kind.BLOCK).size());
		importSample(SAMPLE);
		assertEquals(PROGRAM + "_P-" + (parameters + 1), parameter.getExtId());
		assertEquals(PROGRAM + "_CH-1", channel.getExtId());
		assertEquals("P-01A0-0_GA-11", ExtIds.extId(address));
		assertNumbered(port, prefix + "O-(\\d+)", ports);
		assertNumbered(block, prefix + "PB-(\\d+)", blocks);
		assertNumbered(area, "P-01A0-0_A-(\\d+)", areas);
		assertNumbered(line, "P-01A0-0_L-(\\d+)", lines);
		assertEquals(counts, Map.of(
			"parameters", program.getParameters().size(),
			"ports", program.getPorts().size(),
			"blocks", ExtIds.members(program, Kind.BLOCK).size()));
		assertEquals(1, withExtId(PROGRAM + "_P-" + (parameters + 1)).size());

		// A parameter created afterwards continues the numbering.
		Parameter next;
		try (Transaction tx = kb.beginTransaction()) {
			next = EhomeOperationalFactory.getInstance().createParameter();
			next.setType(program.getParameterTypes().iterator().next());
			program.addParameter(next);
			tx.commit();
		}
		assertEquals(PROGRAM + "_P-" + (parameters + 2), next.getExtId());

		// A manufacturer without KNX ID gets a number of the private range, one with its KNX ID.
		TLObject custom;
		TLObject known;
		try (Transaction tx = kb.beginTransaction()) {
			TLObject root = TLModelUtil.findType("ehome.catalog:ManufacturerRoot").getModule()
				.getSingleton("ROOT");
			custom = create("ehome.catalog:Manufacturer");
			custom.tUpdateByName("name", "Custom");
			add(root, "manufacturers", custom);
			known = create("ehome.catalog:Manufacturer");
			known.tUpdateByName("name", "Known");
			known.tUpdateByName("id", 0x7FF2);
			add(root, "manufacturers", known);
			tx.commit();
		}
		assertTrue(ExtIds.extId(custom), ExtIds.extId(custom).matches("M-FF[0-9A-F]{2}"));
		assertTrue(Kind.MANUFACTURER.number(ExtIds.extId(custom)) >= 1);
		assertEquals("M-7FF2", ExtIds.extId(known));
		// The imported manufacturers carry their KNX ID.
		assertEquals(8, byExtId("ehome.catalog:Manufacturer", "M-0008").tValueByName("id"));

		// The tests of the application share one database: remove what this test created.
		try (Transaction tx = kb.beginTransaction()) {
			custom.tDelete();
			known.tDelete();
			for (TLObject created : List.of(parameter, next, port, channel, area, line, address)) {
				created.tDelete();
			}
			tx.commit();
		}

		// Escaping of names and order numbers as ETS does it.
		assertEquals("5133.2000", ExtIds.escape("5133 00"));
		assertEquals("Wert.5FSchrittweite.5F1.C2.B0C", ExtIds.escape("Wert_Schrittweite_1°C"));
	}

	private static void assertNumbered(TLObject obj, String pattern, long importedMax) {
		String id = ExtIds.extId(obj);
		assertNotNull("No ID: " + obj, id);
		assertTrue(id + " does not match " + pattern, id.matches(pattern));
		long number = Long.parseLong(id.replaceAll(pattern, "$1"));
		assertTrue(id + " not above the imported " + importedMax, number > importedMax);
	}

	private static long maxNumber(Collection<? extends TLObject> objects, Kind kind) {
		long result = 0;
		for (TLObject obj : objects) {
			result = Math.max(result, kind.number(ExtIds.extId(obj)));
		}
		return result;
	}

	private static TLObject create(String typeName) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(typeName));
	}

	private static void add(TLObject container, String reference, TLObject element) {
		List<Object> elements = new ArrayList<>((Collection<?>) container.tValueByName(reference));
		elements.add(element);
		container.tUpdateByName(reference, elements);
	}

	private static TLObject byExtId(String typeName, String extId) {
		List<TLObject> result = instances(typeName).stream()
			.filter(obj -> extId.equals(obj.tValueByName("extId")))
			.toList();
		assertEquals(1, result.size());
		return result.get(0);
	}

	private static List<TLObject> withExtId(String extId) {
		Predicate<TLObject> matches = obj -> extId.equals(obj.tValueByName("extId"));
		return instances("ehome.operational:Parameter").stream().filter(matches).toList();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestExtIds.class, ModelService.Module.INSTANCE,
			LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
