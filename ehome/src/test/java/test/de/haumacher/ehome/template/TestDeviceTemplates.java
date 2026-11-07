/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.template;

import static test.de.haumacher.ehome.importer.TestEtsImport.asRoot;
import static test.de.haumacher.ehome.importer.TestEtsImport.derived;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.roundtrip.TestRoundTrip;

import org.w3c.dom.NodeList;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.view.channel.ViewScripts;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.ExprFormat;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.blocks.RoomController;
import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.monitor.ProjectIndex;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.GroupAddressTypes;
import de.haumacher.ehome.runtime.BlockInstances;
import de.haumacher.ehome.runtime.Pages;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.template.DeviceTemplates;
import de.haumacher.ehome.template.ObjectTypes;

/**
 * Device templates (haumacher/ehome #103) with the KNX test system
 * (<code>samples/KNX-Testsystem.knxproj</code>): the Tastsensor's 2-byte object connected to
 * <code>0/0/28 Raumtemperatur</code> names no datapoint type, so the address is untyped.
 *
 * <p>
 * A template of the Tastsensor's program giving the object 9.001 types the object in the device and
 * the address connected to it at once, without a change of the project: the value type, the
 * options of the room controller's temperature input, the runtime image and the group monitor
 * follow. A type entered at the device wins over the template's; removing the template's type
 * returns to the manufacturer's (none). The overview counts the program's untyped connected
 * objects, and its filter lists the program until the template types the object. The export of
 * the project file writes no type of the template.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestDeviceTemplates extends BasicTestCase {

	private static final String VIEW = "src/main/webapp/WEB-INF/views/device-templates.view.xml";

	private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

	public void testTemplate() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		TLObject template = null;
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);
			TLObject temperature = address(project, "0/0/28");
			List<TLObject> connections = GroupAddressConnections.connections(temperature);
			assertEquals(1, connections.size());
			TLObject connector = connections.get(0).tContainer();
			Interface placed = (Interface) connector.tValueByName("port");
			TLObject program = program(placed);
			assertEquals("M-0008_A-10D7-11-A0E5-O000A", ExtIds.extId(program));
			assertEquals(16, ObjectTypes.size(placed));

			TLObject celsius = subtype("DPST-9-1");
			TLObject lux = subtype("DPST-9-4");

			// Untyped: in the chain and at the address; the overview counts the object, the filter lists the program.
			assertEquals(List.of(), ObjectTypes.manufacturer(placed));
			assertNull(ObjectTypes.effective(connector));
			assertNull(GroupAddressTypes.effective(temperature));
			assertNull(Pages.valueType(temperature));
			// The project connects 1 untyped object of the program; all projects of the database at least that one.
			assertEquals(List.of(placed), DeviceTemplates.untypedConnected(program, project));
			assertTrue(DeviceTemplates.untypedConnected(program).contains(placed));
			assertTrue(filtered().contains(program));
			assertNull(DeviceTemplates.template(program));
			assertEquals(1, DeviceTemplates.products(program).size());

			TLObject block = roomController(project);
			TLObject input = BlockInstances.port(block, "temperature");
			assertFalse(BlockInstances.bindingOptions(input).contains(temperature));

			byte[] untypedExport = TestRoundTrip.export(project);

			// The template: an overlay without a type for every object of the program.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				template = DeviceTemplates.create(program);
				tx.commit();
			}
			assertEquals(template, DeviceTemplates.template(program));
			List<?> overlays = (List<?>) template.tValueByName("overlays");
			assertEquals(DeviceTemplates.placements(program).size(), overlays.size());
			// 91 objects, placed 410 times in the channels and cases of the program's structure.
			assertEquals(91, ((Collection<?>) program.tValueByName("ports")).size());
			assertEquals(410, overlays.size());
			TLObject overlay = ObjectTypes.overlay(placed);
			assertNotNull(overlay);
			assertTrue(overlays.contains(overlay));
			assertNull("An overlay without a type changes nothing.", ObjectTypes.effective(connector));
			assertEquals(Boolean.TRUE, derived(overlay, "untypedConnected"));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				DeviceTemplates.create(program);
				fail("A second template of a program must be refused.");
			} catch (TopLogicException ex) {
				// Expected.
			}
			assertTrue(DeviceTemplates.typeChoices(placed).contains(celsius));
			assertFalse(DeviceTemplates.typeChoices(placed).contains(subtype("DPST-1-1")));

			// 9.001 in the template types the object, the address, the value type, the bind options, the image.
			setTypes(overlay, celsius);
			assertEquals(List.of(celsius), derived(overlay, "effectiveDatapointTypes"));
			assertEquals(List.of(), derived(overlay, "manufacturerDatapointTypes"));
			assertEquals(celsius, ObjectTypes.effective(connector));
			assertEquals(celsius, derived(connector, "effectiveDatapointType"));
			assertEquals(List.of(celsius), derived(connector, "inheritedDatapointTypes"));
			assertEquals(List.of(celsius), derived(connector, "effectiveDatapointTypes"));
			assertEquals(List.of(), connector.tValueByName("datapointTypes"));
			assertEquals(celsius, ObjectTypes.effective(connector.tContainer(), placed));
			assertEquals(new Dpt(9, 1), GroupAddressTypes.effective(temperature));
			assertEquals(celsius, derived(temperature, "effectiveDatapointType"));
			assertEquals(List.of(), temperature.tValueByName("datapointTypes"));
			assertEquals("real", Pages.valueType(temperature));
			assertTrue(BlockInstances.bindingOptions(input).contains(temperature));
			assertEquals(new Dpt(9, 1), ProjectIndex.of(project).address(value(temperature)).dpt());
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			assertTrue(image.getDatapointSources().contains(temperature));
			assertEquals("9.001", ((Map<?, ?>) datapoint(image, "0/0/28").get("knx")).get("dpt"));
			assertEquals(List.of(), DeviceTemplates.untypedConnected(program, project));
			assertFalse(DeviceTemplates.untypedConnected(program).contains(placed));

			// The export writes the project's data only: the same file as without the template's type.
			assertEquals(entries(untypedExport), entries(TestRoundTrip.export(project)));

			// An object of the device the project holds no connector of: the template's type is in
			// effect; "deviceObjectConnectorCreate" creates the connector to enter one of its own.
			// The import creates a connector for every object of the file; one without connections is
			// removed, as a device created in E-Home has none.
			TLObject device = connector.tContainer();
			Interface unconnected = DeviceSettings.interfaces(device).stream()
				.filter(i -> ObjectTypes.connector(device, i) != null
					&& ((Collection<?>) ObjectTypes.connector(device, i).tValueByName("connections")).isEmpty())
				.findFirst().orElseThrow();
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ObjectTypes.connector(device, unconnected).tDelete();
				tx.commit();
			}
			assertNull(ObjectTypes.connector(device, unconnected));
			TLObject unconnectedOverlay = ObjectTypes.overlay(unconnected);
			TLObject ofItsSize = DeviceTemplates.typeChoices(unconnected).get(0);
			setTypes(unconnectedOverlay, ofItsSize);
			assertEquals(ofItsSize, ObjectTypes.effective(device, unconnected));
			QueryExecutor create = ViewScripts.compile(
				ExprFormat.INSTANCE.getValue("function", "deviceObjectConnectorCreate($device, $item)"),
				List.of("device", "item"));
			TLObject created;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				created = (TLObject) asRoot(() -> create.execute(device, unconnected));
				tx.commit();
			}
			assertEquals(created, ObjectTypes.connector(device, unconnected));
			assertEquals(List.of(), created.tValueByName("datapointTypes"));
			assertEquals(List.of(ofItsSize), derived(created, "effectiveDatapointTypes"));
			assertEquals(ofItsSize, derived(created, "effectiveDatapointType"));
			setTypes(unconnectedOverlay);

			// A type entered at the device wins over the template's.
			setTypes(connector, lux);
			assertEquals(lux, ObjectTypes.effective(connector));
			assertEquals(List.of(lux), derived(connector, "effectiveDatapointTypes"));
			assertEquals(List.of(celsius), derived(connector, "inheritedDatapointTypes"));
			assertEquals(new Dpt(9, 4), GroupAddressTypes.effective(temperature));
			setTypes(connector);
			assertEquals(celsius, ObjectTypes.effective(connector));

			// Removing the template's type returns to the manufacturer's: none.
			setTypes(overlay);
			assertNull(ObjectTypes.effective(connector));
			assertNull(derived(connector, "effectiveDatapointType"));
			assertNull(GroupAddressTypes.effective(temperature));
			assertFalse(BlockInstances.bindingOptions(input).contains(temperature));
			assertEquals(List.of(placed), DeviceTemplates.untypedConnected(program, project));
			assertTrue(filtered().contains(program));
		} finally {
			SharedData.deleteProjects(projects);
			if (template != null) {
				try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
					template.tDelete();
					tx.commit();
				}
			}
		}
	}

	private static TLObject roomController(TLObject project) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject block = BlockInstances.create(project, BlockInstances.typeKey(RoomController.INSTANCE.manifest()),
				null, ResKey.text("Heizung"));
			tx.commit();
			return block;
		}
	}

	private static void setTypes(TLObject holder, TLObject... types) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			holder.tUpdateByName("datapointTypes", List.of(types));
			tx.commit();
		}
	}

	/** The programs the overview lists with its switch "only installed programs without type" on. */
	private static List<?> filtered() throws Exception {
		NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(VIEW))
			.getElementsByTagName("rows");
		for (int n = 0; n < nodes.getLength(); n++) {
			String text = nodes.item(n).getTextContent();
			if (text.contains("untypedOnly")) {
				QueryExecutor rows = ViewScripts.compile(ExprFormat.INSTANCE.getValue("rows", text), List.of("untypedOnly"));
				return new ArrayList<>((Collection<?>) asRoot(() -> rows.execute(Boolean.TRUE)));
			}
		}
		fail("No rows with the filter in " + VIEW);
		return null;
	}

	private static Map<String, String> entries(byte[] file) throws Exception {
		Map<String, String> result = new TreeMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(file))) {
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
				result.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
		}
		return result;
	}

	private static TLObject program(TLObject placed) {
		for (TLObject current = placed; current != null; current = current.tContainer()) {
			if (ExtIds.isA(current, "ehome.operational:Program")) {
				return current;
			}
		}
		fail("No program of " + placed);
		return null;
	}

	private static int value(TLObject address) {
		return ((GA) address.tValueByName("address")).getValue();
	}

	private static Map<?, ?> datapoint(RuntimeImage image, String id) {
		for (Object entry : (List<?>) image.getJson().get("datapoints")) {
			if (id.equals(((Map<?, ?>) entry).get("id"))) {
				return (Map<?, ?>) entry;
			}
		}
		fail("No datapoint " + id);
		return null;
	}

	private static TLObject address(TLObject project, String text) {
		return SharedData.within("ehome.logical:GroupAddress", List.of(project)).stream()
			.filter(a -> a.tValueByName("address") instanceof GA ga && ga.toString().equals(text))
			.findFirst().orElseThrow();
	}

	private static TLObject subtype(String extId) {
		return instances("ehome.datapoint:DatapointSubtype").stream()
			.filter(t -> extId.equals(t.tValueByName("extId"))).findFirst().orElseThrow();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDeviceTemplates.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
