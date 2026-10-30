/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import javax.xml.parsers.DocumentBuilderFactory;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.view.channel.ViewScripts;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.ExprFormat;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.util.TLContext;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.ExtIds.Kind;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.GroupAddresses;
import de.haumacher.ehome.programming.I18NConstants;

/**
 * Creating a group address from the connect dialog (haumacher/ehome #18), on the user's test system
 * (<code>samples/KNX-Testsystem.knxproj</code>): the middle group 0/0 "M0" (1 to 255) holds the
 * addresses 0/0/1 to 0/0/54, its main group 0 "H0" no address of its own.
 *
 * <p>
 * The address is created by the script of the dialog <code>projects/create-group-address.view.xml</code>
 * and connected by the one of <code>projects/connect-group-address.view.xml</code>.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestGroupAddressCreation extends BasicTestCase {

	private static final String CREATE_VIEW = "src/main/webapp/WEB-INF/views/projects/create-group-address.view.xml";

	private static final String CONNECT_VIEW = "src/main/webapp/WEB-INF/views/projects/connect-group-address.view.xml";

	public void testCreation() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		try {
			check(project);
		} finally {
			try (Transaction tx = kb().beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	private void check(TLObject project) throws Exception {
		TLObject tastsensor = device(project, 50);
		TLObject ga1 = groupAddress(tastsensor, 1);
		TLObject middle = ga1.tContainer();
		TLObject main = middle.tContainer();
		assertEquals("M0", middle.tValueByName("name"));
		assertEquals("H0", main.tValueByName("name"));

		// Only the middle group takes addresses; it is proposed, also for the range of a chosen address.
		assertEquals(List.of(middle), GroupAddresses.ranges(tastsensor));
		assertSame(middle, GroupAddresses.defaultRange(tastsensor, null));
		assertSame(middle, GroupAddresses.defaultRange(tastsensor, ga1));

		// The next free address follows the highest one of the range.
		GA next = GroupAddresses.nextFree(middle);
		assertEquals(55, next.getValue());
		assertEquals("0/0/55", next.toString());
		// A project is no range.
		assertNull(GroupAddresses.nextFree(project));

		// Refused: outside the range, in a range below, taken, no range, no address.
		assertVeto(I18NConstants.ERROR_OUTSIDE_RANGE__ADDRESS_RANGE_START_END.fill("", "", "", ""),
			GroupAddresses.veto(middle, ga(256)));
		assertVeto(I18NConstants.ERROR_OUTSIDE_RANGE__ADDRESS_RANGE_START_END.fill("", "", "", ""),
			GroupAddresses.veto(middle, ga(0)));
		assertVeto(I18NConstants.ERROR_IN_SUB_RANGE__ADDRESS_RANGE.fill("", ""), GroupAddresses.veto(main, ga(60)));
		ResKey taken = GroupAddresses.veto(middle, ga(1));
		assertVeto(I18NConstants.ERROR_ADDRESS_TAKEN__ADDRESS_EXISTING.fill("", ""), taken);
		assertEquals("0/0/1", taken.arguments()[0].toString());
		assertEquals("0/0/1 Taste1", taken.arguments()[1]);
		assertVeto(I18NConstants.ERROR_NO_GROUP_RANGE, GroupAddresses.veto(null, ga(60)));
		assertVeto(I18NConstants.ERROR_NO_ADDRESS_NUMBER, GroupAddresses.veto(middle, null));
		assertNull(GroupAddresses.veto(middle, next));

		// A refused creation creates nothing.
		int count = DeviceSettings.groupAddresses(tastsensor).size();
		try (Transaction tx = kb().beginTransaction()) {
			GroupAddresses.create(middle, ga(1), "Doppelt", null);
			fail("A taken address was created.");
		} catch (TopLogicException ex) {
			assertVeto(I18NConstants.ERROR_ADDRESS_TAKEN__ADDRESS_EXISTING.fill("", ""), ex.getErrorKey());
		}
		assertEquals(count, DeviceSettings.groupAddresses(tastsensor).size());

		// Created with the next free address by the script of the dialog.
		Interface taste1 = GroupAddressConnections.objects(tastsensor).stream()
			.filter(i -> i.getPort().getIndex() == 0)
			.findFirst().orElseThrow(() -> new AssertionError("No object 0"));
		Object datapointType = taste1.getDatapointTypes().isEmpty()
			? taste1.getPort().getDatapointTypes().stream().findFirst().orElse(null)
			: taste1.getDatapointTypes().get(0);
		QueryExecutor create = ViewScripts.compile(ExprFormat.INSTANCE.getValue("function",
			viewAttribute(CREATE_VIEW, "execute-script", "function")), List.of("number", "name", "datapointType", "range"));
		TLObject created;
		try (Transaction tx = kb().beginTransaction()) {
			created = (TLObject) asRoot(() -> create.execute(next, "Probe", datapointType, middle));
			tx.commit();
		}
		assertSame(middle, created.tContainer());
		assertEquals(55, ((GA) created.tValueByName("address")).getValue());
		assertEquals("Probe", created.tValueByName("name"));
		assertEquals(datapointType == null ? List.of() : List.of(datapointType),
			new ArrayList<>((Collection<?>) created.tValueByName("datapointTypes")));
		String extId = ExtIds.extId(created);
		assertTrue(extId, extId.matches("P-0563-0_GA-\\d+"));
		assertTrue(extId, Kind.GROUP_ADDRESS.number(extId) > 54);
		List<TLObject> addresses = DeviceSettings.groupAddresses(tastsensor);
		assertEquals(count + 1, addresses.size());
		assertSame(created, addresses.get(addresses.size() - 1));
		assertEquals(56, GroupAddresses.nextFree(middle).getValue());

		// The address tree lists the addresses of a range in numeric order, the new one last.
		List<TLObject> parts = GroupAddresses.parts(middle);
		assertEquals(new HashSet<>(ExtIds.list(middle, "parts")), new HashSet<>(parts));
		int last = -1;
		for (TLObject part : parts) {
			int value = ((GA) part.tValueByName("address")).getValue();
			assertTrue(parts.toString(), value > last);
			last = value;
		}
		assertSame(created, parts.get(parts.size() - 1));
		assertTrue(GroupAddresses.parts(project).contains(main));
		assertTrue(GroupAddresses.parts(main).contains(middle));
		assertVeto(I18NConstants.ERROR_ADDRESS_TAKEN__ADDRESS_EXISTING.fill("", ""),
			GroupAddresses.veto(middle, created.tValueByName("address")));

		// Connected to the object with the script of the connect dialog.
		TLClassifier kind = DeviceSettings.defaultKind(tastsensor, taste1);
		QueryExecutor connect = ViewScripts.compile(ExprFormat.INSTANCE.getValue("function",
			viewAttribute(CONNECT_VIEW, "execute-script", "function")), List.of("device", "interface", "kind", "address"));
		TLObject connection;
		try (Transaction tx = kb().beginTransaction()) {
			connection = (TLObject) asRoot(() -> connect.execute(tastsensor, taste1, kind, created));
			tx.commit();
		}
		assertSame(created, connection.tValueByName("target"));
		assertTrue(DeviceSettings.connections(tastsensor, taste1).contains(connection));
		assertEquals(List.of(connection), asRoot(() -> GroupAddressConnections.connections(created)));

		// Removed again.
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.disconnect(connection);
			created.tDelete();
			tx.commit();
		}
		assertEquals(count, DeviceSettings.groupAddresses(tastsensor).size());
		assertEquals(55, GroupAddresses.nextFree(middle).getValue());
	}

	private static void assertVeto(ResKey expected, ResKey actual) {
		assertNotNull("Not refused, expected: " + expected.plain().getKey(), actual);
		assertEquals(expected.plain().getKey(), actual.plain().getKey());
	}

	private static GA ga(int number) {
		return new GA(number, null);
	}

	private static TLObject device(TLObject project, int address) {
		for (Object area : (Collection<?>) project.tValueByName("areas")) {
			for (Object line : (Collection<?>) ((TLObject) area).tValueByName("lines")) {
				for (Object device : (Collection<?>) ((TLObject) line).tValueByName("devices")) {
					if (Integer.valueOf(address).equals(((TLObject) device).tValueByName("address"))) {
						return (TLObject) device;
					}
				}
			}
		}
		throw new AssertionError("No device " + address);
	}

	private static TLObject groupAddress(TLObject device, int number) {
		return DeviceSettings.groupAddresses(device).stream()
			.filter(a -> ((GA) a.tValueByName("address")).getValue() == number)
			.findFirst().orElseThrow(() -> new AssertionError("No group address " + number));
	}

	private static String viewAttribute(String file, String element, String attribute) throws Exception {
		NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(file))
			.getElementsByTagName(element);
		assertEquals(1, nodes.getLength());
		return ((Element) nodes.item(0)).getAttribute(attribute);
	}

	/**
	 * The value computed by the supplier as the technical administrator (derived attributes are
	 * computed with the rights of the reading user).
	 */
	private static Object asRoot(Supplier<Object> supplier) {
		TLContext context = TLContext.getContext();
		Person before = context.getPerson();
		String contextId = context.getContextId();
		context.setPerson(PersonManager.getManager().getRoot());
		try {
			return context.withLocale(Locale.ENGLISH, supplier);
		} finally {
			context.setPerson(before);
			if (before == null) {
				context.setContextId(contextId);
			}
		}
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestGroupAddressCreation.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
