/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;

/**
 * Probe of the group address view (issue #14) with the wiring the test system lacks: a key of the
 * Tastsensor and a channel of the actuator joined on one address from the address side. The
 * address then lists both with their directions, one sender; the actuator's download plan gains
 * the association while the Tastsensor's image stays as it was; a second sender is refused; the
 * removal restores everything.
 */
@SuppressWarnings("javadoc")
public class TestGroupAddressConnectionsProbe extends BasicTestCase {

	public void testProbe() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		TLObject project = projects.get(0);
		try {
			Map<Integer, TLObject> devices = devicesByAddress(project);
			TLObject sensor = devices.get(50);
			TLObject actuator = devices.get(5);
			TLObject address = groupAddress(sensor, 1);

			// 0/0/1: the Tastsensor's key 1 sends on it, nothing else is connected.
			List<TLObject> connections = GroupAddressConnections.connections(address);
			assertEquals(1, connections.size());
			assertSame(sensor, GroupAddressConnections.device(connections.get(0)));
			assertEquals(0, GroupAddressConnections.object(connections.get(0)).getPort().getIndex());
			assertEquals(1, GroupAddressConnections.senders(address));
			assertTrue(GroupAddressConnections.label(connections.get(0)), GroupAddressConnections.label(connections.get(0)).contains("1.3.50"));
			assertTrue(GroupAddressConnections.problems(address).toString(), GroupAddressConnections.problems(address).isEmpty());

			DownloadImage sensorBefore = DownloadImage.compute(sensor);
			DownloadPlan actuatorBefore = DownloadPlanner.plan(actuator, DownloadPlan.Kind.FULL);
			int associationsBefore = actuatorBefore.getImage().getAssociationTable().data()[0] & 0xFF;

			// The actuator's channel A (object 0, "Schalten") receives from it: wired from the address.
			Interface channelA = DeviceSettings.interfaces(actuator).stream()
				.filter(i -> i.getPort().getIndex() == 0)
				.findFirst().orElseThrow();
			TLObject receive = (TLObject) TLModelUtil.findPart("ehome.physical:ConnectionType#receive");
			TLObject send = (TLObject) TLModelUtil.findPart("ehome.physical:ConnectionType#send");
			TLObject connection;
			try (Transaction tx = kb.beginTransaction()) {
				connection = DeviceSettings.connect(actuator, channelA, address, receive);
				tx.commit();
			}
			connections = GroupAddressConnections.connections(address);
			assertEquals(2, connections.size());
			// Senders first.
			assertSame(sensor, GroupAddressConnections.device(connections.get(0)));
			assertSame(actuator, GroupAddressConnections.device(connections.get(1)));
			assertEquals(1, GroupAddressConnections.senders(address));
			assertTrue(GroupAddressConnections.label(connections.get(1)), GroupAddressConnections.label(connections.get(1)).contains("1.3.5)"));
			assertTrue(GroupAddressConnections.devices(address).containsAll(List.of(sensor, actuator)));

			// An object that already sends elsewhere cannot send on this address as well: refused,
			// so the address keeps its one sender.
			Interface sendingElsewhere = DeviceSettings.interfaces(actuator).stream()
				.filter(i -> i.getPort().getIndex() != 0 && DeviceSettings.connections(actuator, i).stream()
					.anyMatch(c -> c.tValueByName("kind") == send))
				.findFirst().orElseThrow();
			try (Transaction tx = kb.beginTransaction()) {
				DeviceSettings.connect(actuator, sendingElsewhere, address, send);
				fail("A second sending address accepted");
			} catch (TopLogicException ex) {
				// Expected.
			}
			assertEquals(1, GroupAddressConnections.senders(address));
			// The same object a second time on the address is refused.
			try (Transaction tx = kb.beginTransaction()) {
				DeviceSettings.connect(actuator, channelA, address, receive);
				fail("Connected twice");
			} catch (TopLogicException ex) {
				// Expected.
			}

			// The actuator's plan has the association, the Tastsensor is untouched.
			DownloadPlan actuatorAfter = DownloadPlanner.plan(actuator, DownloadPlan.Kind.FULL);
			assertTrue(actuatorAfter.getProblems().toString(), actuatorAfter.isExecutable());
			assertEquals(associationsBefore + 1, actuatorAfter.getImage().getAssociationTable().data()[0] & 0xFF);
			assertTrue(contains(actuatorAfter.getImage().getAddressTable(), 1));
			assertTrue(sameBytes(sensorBefore, DownloadImage.compute(sensor)));

			// Removed from the address side: as before.
			try (Transaction tx = kb.beginTransaction()) {
				DeviceSettings.disconnect(connection);
				tx.commit();
			}
			assertEquals(1, GroupAddressConnections.connections(address).size());
			assertTrue(sameBytes(actuatorBefore.getImage(), DownloadImage.compute(actuator)));
		} finally {
			try (Transaction tx = kb.beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	private static TLObject groupAddress(TLObject device, int number) {
		return DeviceSettings.groupAddresses(device).stream()
			.filter(a -> ((GA) a.tValueByName("address")).getValue() == number)
			.findFirst().orElseThrow();
	}

	private static boolean contains(Block addressTable, int groupAddress) {
		byte[] data = addressTable.data();
		for (int n = 1; n < (data[0] & 0xFF); n++) {
			if ((((data[1 + 2 * n] & 0xFF) << 8) | (data[2 + 2 * n] & 0xFF)) == groupAddress) {
				return true;
			}
		}
		return false;
	}

	private static boolean sameBytes(DownloadImage a, DownloadImage b) {
		List<Block> x = a.getBlocks();
		List<Block> y = b.getBlocks();
		if (x.size() != y.size()) {
			return false;
		}
		for (int n = 0; n < x.size(); n++) {
			if (x.get(n).address() != y.get(n).address() || !Arrays.equals(x.get(n).data(), y.get(n).data())) {
				return false;
			}
		}
		return true;
	}

	private static Map<Integer, TLObject> devicesByAddress(TLObject project) {
		Map<Integer, TLObject> result = new HashMap<>();
		for (Object area : (Collection<?>) project.tValueByName("areas")) {
			for (Object line : (Collection<?>) ((TLObject) area).tValueByName("lines")) {
				for (Object device : (Collection<?>) ((TLObject) line).tValueByName("devices")) {
					result.put((Integer) ((TLObject) device).tValueByName("address"), (TLObject) device);
				}
			}
		}
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestGroupAddressConnectionsProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
