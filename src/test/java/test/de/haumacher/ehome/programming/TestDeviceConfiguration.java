/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.derived;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.model.operational.Case;
import de.haumacher.ehome.model.operational.Channel;
import de.haumacher.ehome.model.operational.ChannelIndependent;
import de.haumacher.ehome.model.operational.CodeSegment;
import de.haumacher.ehome.model.operational.ConfigFragment;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterGroup;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Pointer;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.Switch;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;

/**
 * Test of the configuration of a device by the structure of its program (haumacher/ehome #4) on the
 * fixture <code>TestDynamic.knxproj</code>: the effective values, the case selection of the
 * conditional channel, the pages and their items, changing a value through
 * {@link DeviceSettings#setValue(TLObject, ParameterView, Object)} (a setting exactly where the value
 * differs from the default, in the notation the import reads), connecting a communication object to
 * group addresses, and the download image following an edited value at exactly its bits.
 */
@SuppressWarnings("javadoc")
public class TestDeviceConfiguration extends BasicTestCase {

	private static final String PREFIX = "M-0008_A-10D7-11-A0E5-O000E_";

	private TLObject _device;

	private Program _program;

	public void testConfiguration() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("src/test/fixtures/TestDynamic.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		try {
			_device = device(project);
			_program = DeviceConfiguration.program(_device);
			assertEquals(PREFIX.substring(0, PREFIX.length() - 1), _program.getExtId());

			checkEffectiveValues();
			checkStructure();
			checkCaseSelection();
			checkSetValue();
			checkConnections();
			checkImage();
		} finally {
			try (Transaction tx = kb().beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	/** The value of a placement: the device's setting, else the placement's default, else the parameter's. */
	private void checkEffectiveValues() {
		ParameterView setpoint = view("P-9001_R-9001");
		TLObject setting = DeviceSettings.setting(_device, setpoint);
		assertNotNull(setting);
		assertSame(setting, DeviceConfiguration.valueHolder(_device, setpoint));
		assertEquals(Double.valueOf(21.75), DeviceSettings.value(_device, setpoint));

		ParameterView delay = view("P-9002_R-9002");
		assertNull(DeviceSettings.setting(_device, delay));
		assertSame(delay.getParameter(), DeviceConfiguration.valueHolder(_device, delay));
		assertEquals(Long.valueOf(90), DeviceSettings.value(_device, delay));

		// A value that does not fit its type is kept as text: no typed value, the ETS notation stays.
		ParameterView brightness = view("P-9006_R-9006");
		assertNull(DeviceSettings.value(_device, brightness));
		assertEquals("300", new DeviceConfiguration(_device).value(brightness));

		EnumerationValue on = (EnumerationValue) DeviceSettings.value(_device, channelSwitch());
		assertEquals(1, on.getValue());
	}

	/** The pages of the configuration and what each shows. */
	private void checkStructure() {
		DeviceConfiguration configuration = new DeviceConfiguration(_device);
		List<ConfigFragment> top = configuration.getPages(_device);
		assertEquals(2, top.size());
		assertTrue(top.get(0) instanceof ChannelIndependent);
		assertTrue(top.get(1) instanceof Channel);
		assertSame(_device, configuration.getParentPage(top.get(1)));

		// The block of the independent part shows the parameter the channel depends on.
		List<ConfigFragment> independent = configuration.getPages(top.get(0));
		assertEquals(1, independent.size());
		assertEquals(List.of(channelSwitch()), configuration.getItems(independent.get(0)));
		assertTrue(configuration.getItems(_device).isEmpty());

		// The block "Allgemein" of the channel shows its title parameter first, then its parameters.
		ParameterGroup general = (ParameterGroup) byExtId(configuration.getPages(top.get(1)), PREFIX + "PB-3762");
		assertSame(top.get(1), configuration.getParentPage(general));
		List<Option> items = configuration.getItems(general);
		assertSame(general.getInput(), items.get(0));
		for (String id : List.of("P-9001_R-9001", "P-9002_R-9002", "P-9003_R-9003", "P-9004_R-9004",
			"P-9005_R-9005", "P-9006_R-9006")) {
			assertTrue(id, items.contains(view(id)));
			assertTrue(id, configuration.isShown(view(id)));
		}
		// Neither a page nor anything not in effect has items.
		assertTrue(configuration.getItems(view("P-9001_R-9001")).isEmpty());
		assertTrue(configuration.getPages(_program).isEmpty());
	}

	/** The channel is shown in the default case, and hidden when its parameter is set to "Aus". */
	private void checkCaseSelection() throws Exception {
		Switch choice = (Switch) _program.getContents().get(1);
		assertSame(channelSwitch(), choice.getInput());
		Case channelCase = choice.getAlternatives().get(0);
		Case otherwise = choice.getAlternatives().get(1);
		assertEquals("1", channelCase.getPattern());

		DeviceConfiguration shown = new DeviceConfiguration(_device);
		assertSame(channelCase, shown.select(choice));
		assertTrue(shown.getSelectedCases().contains(channelCase));
		Parameter setpoint = view("P-9001_R-9001").getParameter();
		assertTrue(shown.getActiveViews().containsKey(setpoint));
		int interfaces = shown.getActiveInterfaces().size();
		assertTrue(interfaces > 0);

		EnumerationValue off = entry(channelSwitch(), 0);
		TLObject setting;
		try (Transaction tx = kb().beginTransaction()) {
			setting = DeviceSettings.setValue(_device, channelSwitch(), off);
			tx.commit();
		}
		assertNotNull(setting);
		assertSame(off, setting.tValueByName("choice"));
		assertEquals("0", derived(setting, "encodedValue"));
		assertNotNull(_device.tValueByName("lastModified"));

		DeviceConfiguration hidden = new DeviceConfiguration(_device);
		assertSame(otherwise, hidden.select(choice));
		assertFalse(hidden.getSelectedCases().contains(channelCase));
		assertEquals(1, hidden.getPages(_device).size());
		assertFalse(hidden.getActiveViews().containsKey(setpoint));
		assertTrue(hidden.getActiveInterfaces().isEmpty());
		assertFalse(hidden.isShown(view("P-9001_R-9001")));

		// A parameter of the hidden channel cannot be changed: the reason is given.
		assertNotNull(DeviceSettings.editVeto(_device, view("P-9002_R-9002")));
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.setValue(_device, view("P-9002_R-9002"), 120L);
			fail("A parameter not in the configuration was changed.");
		} catch (TopLogicException ex) {
			// Expected.
		}
		// The settings of the hidden channel stay: they are in effect again with the channel.
		assertNotNull(DeviceSettings.setting(_device, view("P-9001_R-9001")));

		// Back to the default: the setting is gone, the channel is shown again.
		try (Transaction tx = kb().beginTransaction()) {
			assertNull(DeviceSettings.setValue(_device, channelSwitch(), entry(channelSwitch(), 1)));
			tx.commit();
		}
		assertFalse(setting.tValid());
		assertNull(DeviceSettings.setting(_device, channelSwitch()));
		DeviceConfiguration again = new DeviceConfiguration(_device);
		assertSame(channelCase, again.select(choice));
		assertEquals(interfaces, again.getActiveInterfaces().size());
		assertTrue(again.isShown(view("P-9001_R-9001")));
	}

	/** A setting exists exactly where the value differs from the default; values are checked by type. */
	private void checkSetValue() throws Exception {
		// A time: the default creates nothing, another value a setting in the notation of ETS.
		ParameterView delay = view("P-9002_R-9002");
		int settings = settings().size();
		try (Transaction tx = kb().beginTransaction()) {
			assertNull(DeviceSettings.setValue(_device, delay, 90L));
			tx.commit();
		}
		assertEquals(settings, settings().size());
		TLObject delaySetting;
		try (Transaction tx = kb().beginTransaction()) {
			delaySetting = DeviceSettings.setValue(_device, delay, 120);
			tx.commit();
		}
		assertEquals(settings + 1, settings().size());
		assertEquals(Long.valueOf(120), delaySetting.tValueByName("number"));
		assertEquals("120", derived(delaySetting, "encodedValue"));
		assertEquals(Long.valueOf(120), DeviceSettings.value(_device, delay));
		// Changing it again changes the setting, no second one.
		try (Transaction tx = kb().beginTransaction()) {
			assertSame(delaySetting, DeviceSettings.setValue(_device, delay, "150"));
			tx.commit();
		}
		assertEquals(settings + 1, settings().size());
		assertEquals("150", derived(delaySetting, "encodedValue"));
		assertRefused(delay, 5000L);
		assertRefused(delay, 2.5);
		assertRefused(delay, "soon");
		try (Transaction tx = kb().beginTransaction()) {
			assertNull(DeviceSettings.setValue(_device, delay, 90.0));
			tx.commit();
		}
		assertFalse(delaySetting.tValid());
		assertEquals(settings, settings().size());

		// A floating point number set by the import: back to its default removes the setting, the
		// value read by the import is written as the import read it.
		ParameterView setpoint = view("P-9001_R-9001");
		try (Transaction tx = kb().beginTransaction()) {
			assertNull(DeviceSettings.setValue(_device, setpoint, 20.5));
			tx.commit();
		}
		assertNull(DeviceSettings.setting(_device, setpoint));
		TLObject setpointSetting;
		try (Transaction tx = kb().beginTransaction()) {
			setpointSetting = DeviceSettings.setValue(_device, setpoint, 21.75);
			tx.commit();
		}
		assertEquals("21.75", derived(setpointSetting, "encodedValue"));
		assertRefused(setpoint, 100.5);

		// A text: at most as many characters as its type holds.
		ParameterView room = view("P-9003_R-9003");
		assertRefused(room, "Wohnzimmer2");
		TLObject roomSetting;
		try (Transaction tx = kb().beginTransaction()) {
			roomSetting = DeviceSettings.setValue(_device, room, "Bad");
			tx.commit();
		}
		assertEquals("Bad", derived(roomSetting, "encodedValue"));
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.reset(roomSetting);
			tx.commit();
		}
		assertNull(DeviceSettings.setting(_device, room));

		// An enumeration: an entry of its type, or its numeric value.
		assertRefused(channelSwitch(), entry(channelSwitch(), 0).getValue() + 7);
		// A parameter without value cannot be set.
		ParameterView title = view("P-3762_R-3762");
		assertTrue(new DeviceConfiguration(_device).isShown(title));
		assertNotNull(DeviceSettings.editVeto(_device, title));
		assertRefused(title, "x");
	}

	/** Connecting a communication object to group addresses of the project, and removing that. */
	private void checkConnections() throws Exception {
		Interface placed = null;
		for (Interface candidate : DeviceSettings.interfaces(_device)) {
			if (DeviceSettings.connections(_device, candidate).isEmpty()) {
				placed = candidate;
				break;
			}
		}
		assertNotNull("No communication object without connections.", placed);
		List<TLObject> addresses = DeviceSettings.groupAddresses(_device);
		assertTrue(addresses.size() >= 2);
		TLObject first = addresses.get(0);
		TLObject second = addresses.get(1);
		assertEquals("send", DeviceSettings.defaultKind(_device, placed).getName());

		DownloadImage original = DownloadImage.compute(_device);
		TLObject send;
		try (Transaction tx = kb().beginTransaction()) {
			send = DeviceSettings.connect(_device, placed, first, kind("send"));
			tx.commit();
		}
		TLObject connector = send.tContainer();
		assertSame(placed, connector.tValueByName("port"));
		assertEquals(Boolean.TRUE, connector.tValueByName("active"));
		assertTrue(((Collection<?>) _device.tValueByName("connectors")).contains(connector));
		assertEquals(List.of(send), DeviceSettings.connections(_device, placed));
		assertEquals("receive", DeviceSettings.defaultKind(_device, placed).getName());

		assertConnectRefused(placed, first, kind("receive"));
		assertConnectRefused(placed, second, kind("send"));
		assertConnectRefused(placed, null, kind("receive"));
		TLObject receive;
		try (Transaction tx = kb().beginTransaction()) {
			receive = DeviceSettings.connect(_device, placed, second, kind("receive"));
			tx.commit();
		}
		assertEquals(Set.of(send, receive), Set.copyOf(DeviceSettings.connections(_device, placed)));

		// The address table holds both addresses now.
		DownloadImage wired = DownloadImage.compute(_device);
		List<Integer> table = addressTable(wired);
		assertTrue(table.contains(address(first)));
		assertTrue(table.contains(address(second)));
		Set<Integer> expected = new TreeSet<>(addressTable(original));
		expected.add(address(first));
		expected.add(address(second));
		assertEquals(new ArrayList<>(expected), table);

		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.disconnect(send);
			DeviceSettings.disconnect(receive);
			tx.commit();
		}
		assertTrue(DeviceSettings.connections(_device, placed).isEmpty());
		assertEquals(addressTable(original), addressTable(DownloadImage.compute(_device)));
	}

	/** A value changed as the view changes it changes the image at exactly the bits of its parameter. */
	private void checkImage() throws Exception {
		DownloadImage original = DownloadImage.compute(_device);
		DeviceConfiguration configuration = new DeviceConfiguration(_device);
		ParameterView view = null;
		Pointer pointer = null;
		EnumerationValue other = null;
		for (Map.Entry<Parameter, ParameterView> active : configuration.getActiveViews().entrySet()) {
			Parameter parameter = active.getKey();
			if (!(parameter.getType() instanceof EnumerationType type)
				|| !(parameter.getAddress() instanceof Pointer location)
				|| type.getBits() > 8
				|| parameter.getUnionOffset() != null && parameter.getUnionOffset() != 0
				|| location.getBitOffset() + memberBitOffset(parameter) + type.getBits() > 8
				|| DeviceSettings.editVeto(_device, active.getValue()) != null) {
				continue;
			}
			String current = configuration.value(active.getValue());
			for (EnumerationValue entry : type.getValues()) {
				if (!String.valueOf(entry.getValue()).equals(current) && entry.getValue() < (1 << type.getBits())) {
					view = active.getValue();
					pointer = location;
					other = entry;
					break;
				}
			}
			if (view != null) {
				break;
			}
		}
		assertNotNull("No enumeration parameter in effect to edit.", view);
		int address = ((CodeSegment) pointer.getSegment()).getAddress() + pointer.getByteOffset();
		int bits = ((EnumerationType) view.getParameter().getType()).getBits();
		int shift = 8 - pointer.getBitOffset() - memberBitOffset(view.getParameter()) - bits;
		int fieldMask = ((1 << bits) - 1) << shift;
		Integer before = original.byteAt(address);
		assertNotNull(before);

		TLObject setting;
		try (Transaction tx = kb().beginTransaction()) {
			setting = DeviceSettings.setValue(_device, view, other);
			tx.commit();
		}
		// Whether the edit opens or closes a part of the structure decides what else changes; the
		// parameter's own bits carry the new value in any case.
		DownloadImage edited = DownloadImage.compute(_device);
		int after = edited.byteAt(address);
		assertEquals(other.getValue(), (after & fieldMask) >> shift);
		assertEquals(String.valueOf(other.getValue()), derived(setting, "encodedValue"));
		if (new DeviceConfiguration(_device).getSelectedCases().equals(configuration.getSelectedCases())) {
			assertEquals(before & ~fieldMask, after & ~fieldMask);
			assertSameExcept(original, edited, address);
		}
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.setValue(_device, view, null);
			tx.commit();
		}
		assertSameExcept(original, DownloadImage.compute(_device), -1);
	}

	private void assertRefused(ParameterView view, Object value) {
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.setValue(_device, view, value);
			fail("Value accepted: " + value);
		} catch (TopLogicException ex) {
			// Expected.
		}
	}

	private void assertConnectRefused(Interface placed, TLObject address, TLObject kind) {
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.connect(_device, placed, address, kind);
			fail("Connection accepted: " + address);
		} catch (TopLogicException ex) {
			// Expected.
		}
	}

	private List<TLObject> settings() {
		return new ArrayList<>((Collection<? extends TLObject>) _device.tValueByName("settings"));
	}

	private ParameterView channelSwitch() {
		return view("P-9010_R-9010");
	}

	private ParameterView view(String id) {
		for (TLObject view : instances("ehome.operational:ParameterView")) {
			if ((PREFIX + id).equals(view.tValueByName("extId"))) {
				return (ParameterView) view;
			}
		}
		throw new AssertionError("No placement " + id);
	}

	private static EnumerationValue entry(ParameterView view, int value) {
		for (EnumerationValue entry : ((EnumerationType) view.getParameter().getType()).getValues()) {
			if (entry.getValue() == value) {
				return entry;
			}
		}
		throw new AssertionError("No entry " + value);
	}

	private static TLObject byExtId(Collection<? extends TLObject> objects, String extId) {
		for (TLObject obj : objects) {
			if (extId.equals(obj.tValueByName("extId"))) {
				return obj;
			}
		}
		throw new AssertionError("Not found: " + extId);
	}

	private static TLClassifier kind(String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.physical:ConnectionType")).getClassifier(name);
	}

	private static int address(TLObject groupAddress) {
		return ((GA) groupAddress.tValueByName("address")).getValue();
	}

	private static List<Integer> addressTable(DownloadImage image) {
		Block block = image.getAddressTable();
		List<Integer> result = new ArrayList<>();
		byte[] data = block.data();
		int entries = data[0] & 0xFF;
		for (int n = 1; n < entries; n++) {
			result.add(((data[1 + 2 * n] & 0xFF) << 8) | (data[2 + 2 * n] & 0xFF));
		}
		return result;
	}

	private static int memberBitOffset(Parameter parameter) {
		return parameter.getUnionBitOffset() == null ? 0 : parameter.getUnionBitOffset();
	}

	private static void assertSameExcept(DownloadImage expected, DownloadImage actual, int exceptAddress) {
		List<Block> expectedBlocks = expected.getBlocks();
		List<Block> actualBlocks = actual.getBlocks();
		assertEquals(expectedBlocks.size(), actualBlocks.size());
		for (int n = 0; n < expectedBlocks.size(); n++) {
			Block a = expectedBlocks.get(n);
			Block b = actualBlocks.get(n);
			assertEquals(a.name(), b.name());
			assertEquals(a.address(), b.address());
			assertTrue(Arrays.equals(a.mask(), b.mask()));
			for (int i = 0; i < a.data().length; i++) {
				if (a.address() + i == exceptAddress) {
					continue;
				}
				assertEquals(a.name() + " at " + Integer.toHexString(a.address() + i), a.data()[i], b.data()[i]);
			}
		}
	}

	private static TLObject device(TLObject project) {
		List<TLObject> devices = new ArrayList<>();
		for (Object area : (Collection<?>) project.tValueByName("areas")) {
			for (Object line : (Collection<?>) ((TLObject) area).tValueByName("lines")) {
				for (Object device : (Collection<?>) ((TLObject) line).tValueByName("devices")) {
					devices.add((TLObject) device);
				}
			}
		}
		assertEquals(1, devices.size());
		return devices.get(0);
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDeviceConfiguration.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
