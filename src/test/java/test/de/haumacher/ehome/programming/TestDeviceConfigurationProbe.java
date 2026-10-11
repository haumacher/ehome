/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.ExtIds.Kind;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.model.operational.Case;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Switch;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;

/**
 * Probe of the device configuration (issue #4) on the real Tastsensor of
 * <code>samples/Test.knxproj</code>, composed with the download image: switching a case through
 * its input parameter changes the structure, the parameters in effect and the image, and switching
 * back restores all three; a hidden parameter cannot be set but can be reset; a value of the wrong
 * kind is refused without a trace; a connection made in the app reaches the tables of the image.
 */
@SuppressWarnings("javadoc")
public class TestDeviceConfigurationProbe extends BasicTestCase {

	private TLObject _device;

	public void testProbe() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/Test.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		_device = device(project);
		try {
			switchCase();
			wrongValues();
			wiring();
		} finally {
			try (Transaction tx = kb().beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	private void switchCase() throws Exception {
		DeviceConfiguration original = new DeviceConfiguration(_device);
		DownloadImage originalImage = DownloadImage.compute(_device);
		int settingsBefore = settings().size();

		// A switch in effect whose input is an enumeration with an entry selecting another case
		// that holds a parameter placement.
		Switch choice = null;
		EnumerationValue other = null;
		Case otherCase = null;
		for (Object obj : instances("ehome.operational:Switch")) {
			Switch candidate = (Switch) obj;
			ParameterView input = candidate.getInput();
			if (input == null || candidate.tContainer() == null
				|| !original.getActiveViews().containsValue(input)
				|| !(input.getParameter().getType() instanceof EnumerationType type)
				|| !DeviceConfiguration.isEditable(input)) {
				continue;
			}
			Case selected = original.select(candidate);
			for (EnumerationValue entry : type.getValues()) {
				Case target = caseFor(candidate, String.valueOf(entry.getValue()));
				if (target != null && target != selected && !views(target).isEmpty()) {
					choice = candidate;
					other = entry;
					otherCase = target;
					break;
				}
			}
			if (choice != null) {
				break;
			}
		}
		assertNotNull("No switch to flip in the Tastsensor", choice);
		Case selectedBefore = original.select(choice);
		Set<ParameterView> hiddenViews = views(otherCase);
		assertFalse(original.getActiveViews().values().containsAll(hiddenViews));
		ParameterView hidden = hiddenViews.iterator().next();

		// A parameter of the other case cannot be set while hidden, but it can be reset.
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.setValue(_device, hidden, firstValue(hidden));
			fail("Hidden parameter accepted a value");
		} catch (TopLogicException ex) {
			// Expected.
		}
		try (Transaction tx = kb().beginTransaction()) {
			assertNull(DeviceSettings.setValue(_device, hidden, null));
			tx.commit();
		}
		assertEquals(settingsBefore, settings().size());

		// Flip the switch: the other case is selected, its views are in effect, the old ones are not,
		// and the image writes the other case's parameters.
		try (Transaction tx = kb().beginTransaction()) {
			assertNotNull(DeviceSettings.setValue(_device, choice.getInput(), other));
			tx.commit();
		}
		DeviceConfiguration flipped = new DeviceConfiguration(_device);
		assertSame(otherCase, flipped.select(choice));
		assertTrue(flipped.getSelectedCases().contains(otherCase));
		assertFalse(flipped.getSelectedCases().contains(selectedBefore));
		assertTrue(flipped.getActiveViews().values().containsAll(hiddenViews));
		for (ParameterView view : views(selectedBefore)) {
			if (!views(otherCase).contains(view)) {
				assertFalse(view.getParameter().getName(), flipped.getActiveViews().containsValue(view));
			}
		}
		assertTrue(flipped.getActiveViews().containsValue(choice.getInput()));
		DownloadImage flippedImage = DownloadImage.compute(_device);
		assertEquals(flippedImage.getProblems().toString(), 0, flippedImage.getProblems().size());
		assertFalse("Image unchanged by the switch", sameBytes(originalImage, flippedImage));

		// Now the formerly hidden parameter accepts a value, and resetting it is a no-op.
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.setValue(_device, hidden, firstValue(hidden));
			tx.commit();
		}
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.setValue(_device, hidden, null);
			tx.commit();
		}

		// Back to the default: structure, values and image are what they were.
		try (Transaction tx = kb().beginTransaction()) {
			assertNull(DeviceSettings.setValue(_device, choice.getInput(), null));
			tx.commit();
		}
		DeviceConfiguration restored = new DeviceConfiguration(_device);
		assertEquals(original.getSelectedCases(), restored.getSelectedCases());
		assertEquals(original.getActiveViews(), restored.getActiveViews());
		assertEquals(settingsBefore, settings().size());
		assertTrue(sameBytes(originalImage, DownloadImage.compute(_device)));
	}

	private void wrongValues() throws Exception {
		DeviceConfiguration configuration = new DeviceConfiguration(_device);
		int settingsBefore = settings().size();
		ParameterView enumView = null;
		EnumerationValue foreign = null;
		for (Map.Entry<Parameter, ParameterView> active : configuration.getActiveViews().entrySet()) {
			if (active.getKey().getType() instanceof EnumerationType type && DeviceConfiguration.isEditable(active.getValue())) {
				for (Object obj : instances("ehome.operational:EnumerationValue")) {
					EnumerationValue entry = (EnumerationValue) obj;
					if (entry.tContainer() != type) {
						enumView = active.getValue();
						foreign = entry;
						break;
					}
				}
			}
			if (enumView != null) {
				break;
			}
		}
		assertNotNull(enumView);
		// An entry of another enumeration, a text for an enumeration: refused, nothing stored.
		for (Object wrong : List.of(foreign, "not an entry", 1.5)) {
			try (Transaction tx = kb().beginTransaction()) {
				DeviceSettings.setValue(_device, enumView, wrong);
				tx.commit();
				fail("Accepted " + wrong);
			} catch (TopLogicException ex) {
				// Expected.
			}
		}
		assertEquals(settingsBefore, settings().size());
		assertNull(DeviceSettings.editVeto(_device, enumView));
	}

	private void wiring() throws Exception {
		DownloadImage originalImage = DownloadImage.compute(_device);
		List<Interface> interfaces = DeviceSettings.interfaces(_device);
		assertFalse(interfaces.isEmpty());
		List<TLObject> addresses = DeviceSettings.groupAddresses(_device);
		assertEquals(10, addresses.size());
		// An object without connections and an address no connector of the device uses yet.
		Interface free = interfaces.stream()
			.filter(i -> DeviceSettings.connections(_device, i).isEmpty())
			.findFirst().orElseThrow();
		Set<TLObject> used = new HashSet<>();
		for (Interface placed : interfaces) {
			for (TLObject connection : DeviceSettings.connections(_device, placed)) {
				used.add((TLObject) connection.tValueByName("target"));
			}
		}
		// The Tastsensor uses every address of its project: two new ones, created as the address tree
		// creates them, are offered for the device right away.
		TLObject unused = newGroupAddress(_device, 100);
		TLObject another = newGroupAddress(_device, 101);
		assertEquals(12, DeviceSettings.groupAddresses(_device).size());
		assertTrue(DeviceSettings.groupAddresses(_device).contains(unused));
		int unusedNumber = ((GA) unused.tValueByName("address")).getValue();

		TLObject connection;
		try (Transaction tx = kb().beginTransaction()) {
			connection = DeviceSettings.connect(_device, free, unused,
				(TLObject) TLModelUtil.findPart("ehome.physical:ConnectionType#send"));
			tx.commit();
		}
		assertEquals(1, DeviceSettings.connections(_device, free).size());
		DownloadImage wired = DownloadImage.compute(_device);
		Block table = wired.getAddressTable();
		assertEquals(originalImage.getAddressTable().data()[0] + 1, table.data()[0]);
		assertTrue(contains(table, unusedNumber));
		assertEquals(originalImage.getAssociationTable().data()[0] + 1, wired.getAssociationTable().data()[0]);
		assertTrue(association(wired.getAssociationTable(), free.getPort().getIndex()));

		// A second sending address of the same object is refused; the same address twice as well.
		TLObject receive = (TLObject) TLModelUtil.findPart("ehome.physical:ConnectionType#receive");
		for (Object[] attempt : new Object[][] { { another, TLModelUtil.findPart("ehome.physical:ConnectionType#send") },
			{ unused, receive } }) {
			try (Transaction tx = kb().beginTransaction()) {
				DeviceSettings.connect(_device, free, (TLObject) attempt[0], (TLObject) attempt[1]);
				tx.commit();
				fail("Accepted " + Arrays.toString(attempt));
			} catch (TopLogicException ex) {
				// Expected.
			}
		}
		assertEquals(1, DeviceSettings.connections(_device, free).size());

		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.disconnect(connection);
			tx.commit();
		}
		assertTrue(DeviceSettings.connections(_device, free).isEmpty());
		assertTrue(sameBytes(originalImage, DownloadImage.compute(_device)));
	}

	private static TLObject newGroupAddress(TLObject device, int number) throws Exception {
		TLObject project = device.tContainer().tContainer().tContainer();
		assertEquals("Project", project.tType().getName());
		// A range taking the address (the order of the project's ranges is not defined).
		TLObject range = ExtIds.members(project, Kind.GROUP_RANGE).stream()
			.filter(r -> within(r, number))
			.findFirst().orElseThrow(() -> new AssertionError("No group range takes the address " + number));
		try (Transaction tx = kb().beginTransaction()) {
			TLObject address = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.logical:GroupAddress"));
			address.tUpdateByName("address", new GA(number, GroupAddressStyle.ThreeLevel));
			address.tUpdateByName("name", "Probe " + Integer.toHexString(number));
			List<Object> parts = new ArrayList<>((Collection<?>) range.tValueByName("parts"));
			parts.add(address);
			range.tUpdateByName("parts", parts);
			tx.commit();
			return address;
		}
	}

	private static boolean within(TLObject range, int number) {
		Object start = range.tValueByName("rangeStart");
		Object end = range.tValueByName("rangeEnd");
		return start instanceof GA s && end instanceof GA e && s.getValue() <= number && number <= e.getValue();
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

	private static boolean association(Block associationTable, int object) {
		byte[] data = associationTable.data();
		for (int n = 0; n < (data[0] & 0xFF); n++) {
			if ((data[2 + 2 * n] & 0xFF) == object) {
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

	private static Case caseFor(Switch choice, String value) {
		Case fallback = null;
		for (Case c : choice.getAlternatives()) {
			{
				if (c.getIsDefault()) {
					fallback = c;
				} else if (DeviceConfiguration.matches(c.getPattern(), value)) {
					return c;
				}
			}
		}
		return fallback;
	}

	/** The parameter placements directly in the given case. */
	private static Set<ParameterView> views(Case c) {
		Set<ParameterView> result = new HashSet<>();
		for (Object content : c.getContents()) {
			if (content instanceof ParameterView view && view.getParameter() != null
				&& DeviceConfiguration.isEditable(view)) {
				result.add(view);
			}
		}
		return result;
	}

	private static Object firstValue(ParameterView view) {
		Parameter parameter = view.getParameter();
		if (parameter.getType() instanceof EnumerationType type) {
			return type.getValues().iterator().next();
		}
		return 1L;
	}

	private List<?> settings() {
		return new ArrayList<>((Collection<?>) _device.tValueByName("settings"));
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
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDeviceConfigurationProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
