/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.modules;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.Allocator;
import de.haumacher.ehome.model.operational.Location;
import de.haumacher.ehome.model.operational.ModuleArgument;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.PlacementArgument;
import de.haumacher.ehome.model.operational.Pointer;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.modules.InstanceInfo;
import de.haumacher.ehome.modules.ModuleInstances;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.planning.DevicePlanning;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;

/**
 * Repeats, allocators, sub-modules and arguments on top of a base in a real program (haumacher/ehome
 * #119): the product database of the Zennio DALI BOX Interface 64 v3 3.0
 * (<code>src/test/fixtures/Zennio_DALI_BOX_64_v3.knxprod</code>), program
 * <code>M-0071_A-3351-30-E765</code>: the channels (module DALI_Channel) repeated by the parameter
 * <code>P-1</code>, in each channel the scenes, sequences and standby (sub-modules) repeated by
 * parameters of the channel, ten allocators (three of the program for the channels, seven of the
 * channel module for its sub-modules, on top of the channel's offsets).
 *
 * <p>
 * Without hardware: a device planned in E-Home, with different counts of scenes, sequences and standby
 * entries. The program has one channel (its type numChannels allows 1 to 1, a second channel is
 * refused), so different scene counts per channel are a matter of the artificial fixture of
 * <code>TestDynamicModules</code>. The instances must follow the ID scheme of KNX, the values of each
 * allocator must advance by the Allocates of their arguments (no two instances overlap) and stay
 * within its limit, the parameters in effect must lie inside their segment, and a count change
 * recomputes the instances.
 * </p>
 *
 * <p>
 * The program is 79 MB of XML. The test imports its module structure only (see
 * {@link ModuleStructure}), once for all checks.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestZennioDaliBox extends BasicTestCase {

	private static final String DATABASE = "src/test/fixtures/Zennio_DALI_BOX_64_v3.knxprod";

	private static final String PROGRAM = "M-0071_A-3351-30-E765";

	private static final String P = PROGRAM + "_";

	private static final String ASSIGNMENT = "M-0071_H-ZDID64V3-1_HP-3351-30-E765";

	private static final Pattern INSTANCE = Pattern.compile(
		"MD-\\d+_M-\\d+_MI-\\d+(-\\d+)*(_SM-\\d+_M-\\d+_MI-\\d+(-\\d+)*)*");

	private static final Pattern REPEAT_INDEX = Pattern.compile("\\d+x\\d+( \\d+x\\d+)*");

	public void testChannels() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		// The samples' master data first, which the database's program refers to.
		importSample("samples/Test.knxproj");
		assertEquals(List.of(), ProductDatabases.importWithoutMasterData(DATABASE, true));
		Program program = program();
		assertFalse(program.getUsesModules());
		assertEquals(1, program.getModules().size());
		assertEquals(3, program.getModules().get(0).getModules().size());
		assertEquals(3, program.getAllocators().size());
		assertEquals(7, program.getModules().get(0).getAllocators().size());

		try {
			TLObject line = SharedData.within("ehome.physical:Line", SharedData.newProjects(before)).get(0);
			TLObject device;
			try (Transaction tx = kb().beginTransaction()) {
				TLObject assignment = assignment();
				TLObject variant = (TLObject) ((Collection<?>) ((TLObject) assignment.tContainer())
					.tValueByName("variants")).iterator().next();
				device = DevicePlanning.create(line, variant, assignment, Integer.valueOf(20), "TEST DALI", null);
				tx.commit();
			}
			// The program has one channel (its type numChannels allows 1 to 1): a second is refused.
			try {
				set(device, P + "P-1_R-1", 2);
				fail("The program has one channel.");
			} catch (TopLogicException ex) {
				// Expected.
			}
			String channel = "MD-1_M-1_MI-1_";
			// Scenes, sequences and standby switched on in the channel, then their counts.
			set(device, P + channel + "P-57_R-29", 1);
			set(device, P + channel + "P-60_R-37", 1);
			set(device, P + channel + "P-63_R-40", 1);
			set(device, P + channel + "P-59_R-36", 3);
			set(device, P + channel + "P-61_R-38", 2);
			set(device, P + channel + "P-62_R-39", 2);
			checkLayout(device);
			assertEquals(1 + 3 + 2 + 2, new DeviceConfiguration(device).getInstances().size());
			// The channel from the program's allocators, its scenes from the channel's on top of the
			// channel's offset (Scene_params Start 11, Allocates 417), its standby objects on top of the
			// channel's objects (Standby_objs Start 19).
			Map<String, String> channels = subModules(device, "MD-1_M-1_MI-1");
			assertEquals("[621, 9, 1]", channels.get("MD-1_M-1_MI-1"));
			Map<String, String> scenesOf3 = subModules(device, channel + "SM-1_");
			assertEquals(scenesOf3.toString(), Set.of("[1, 632]", "[2, 1049]", "[3, 1466]"), Set.copyOf(scenesOf3.values()));
			assertEquals(Set.of("[1, 28186, 28, 1]", "[2, 28198, 29, 1]"),
				Set.copyOf(subModules(device, channel + "SM-3_").values()));
			Map<String, String> sequences = subModules(device, channel + "SM-2_");
			Map<String, String> standby = subModules(device, channel + "SM-3_");
			assertEquals(2, standby.size());

			// Five scenes: scenes 4 and 5 follow the first three, sequences and standby are unchanged
			// (each sub-module has allocators of its own).
			set(device, P + channel + "P-59_R-36", 5);
			checkLayout(device);
			Map<String, String> scenes = subModules(device, channel + "SM-1_");
			assertEquals(scenes.toString(), 5, scenes.size());
			assertEquals(sequences, subModules(device, channel + "SM-2_"));
			assertEquals(standby, subModules(device, channel + "SM-3_"));

			// One scene: the others are no longer in effect, kept.
			set(device, P + channel + "P-59_R-36", 1);
			checkLayout(device);
			assertEquals(1, subModules(device, channel + "SM-1_").size());
			assertEquals(4, ModuleInstances.stored(device).stream()
				.filter(i -> Boolean.FALSE.equals(i.tValueByName(ModuleInstances.ACTIVE))).count());
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	/**
	 * The instances in effect follow the ID scheme, the values of the allocators do not overlap and
	 * stay within their limits, and the parameters in effect lie inside their segment.
	 */
	private static void checkLayout(TLObject device) {
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		assertEquals(List.of(), configuration.getModuleProblems());
		List<InstanceInfo> instances = configuration.getInstances();
		Map<Object, List<long[]>> allocations = new HashMap<>();
		for (InstanceInfo instance : instances) {
			assertTrue(instance.getId(), INSTANCE.matcher(instance.getId()).matches());
			assertTrue(instance.getRepeatIndex(), REPEAT_INDEX.matcher(instance.getRepeatIndex()).matches());
			// One entry per enclosing repeat and module: 2 for a channel, 4 for a sub-module of it.
			assertEquals(instance.getId(), instance.getParent() == null ? 2 : 4,
				instance.getRepeatIndex().split(" ").length);
			for (PlacementArgument argument : instance.getPlacement().getArguments()) {
				Allocator allocator = argument.getAllocator();
				if (allocator == null) {
					continue;
				}
				ModuleArgument definition = argument.getArgument();
				long value = InstanceInfo.parse(instance.value(definition));
				long base = argument.getBase() == null ? 0 : instance.getParent().number(argument.getBase());
				long relative = value - base;
				assertTrue(allocator.getName() + " " + relative, relative <= allocator.getMaxInclusive().longValue());
				assertTrue(allocator.getName() + " " + relative, relative >= allocator.getStart().longValue());
				long step = definition.getAllocates() == null ? 1 : definition.getAllocates().longValue();
				Object key = ModuleParts.definitionOf(allocator) == null ? allocator
					: List.of(allocator, instance.getParent().getId());
				allocations.computeIfAbsent(key, x -> new ArrayList<>()).add(new long[] { value, step });
			}
		}
		for (Map.Entry<Object, List<long[]>> entry : allocations.entrySet()) {
			List<long[]> values = new ArrayList<>(entry.getValue());
			values.sort((a, b) -> Long.compare(a[0], b[0]));
			for (int n = 1; n < values.size(); n++) {
				assertTrue(entry.getKey() + ": " + values.get(n - 1)[0] + " + " + values.get(n - 1)[1] + " > "
					+ values.get(n)[0], values.get(n - 1)[0] + values.get(n - 1)[1] <= values.get(n)[0]);
			}
		}
		for (Parameter parameter : configuration.getActiveViews().keySet()) {
			Location location = parameter.getAddress();
			if (location instanceof Pointer pointer) {
				int bits = location.getBits() != null ? location.getBits().intValue() : bits(parameter);
				int end = location.getByteOffset() + (location.getBitOffset() + bits + 7) / 8;
				assertTrue(ModuleParts.partId(parameter) + " ends at " + end,
					end <= pointer.getSegment().getSize().intValue());
			}
		}
	}

	private static int bits(Parameter parameter) {
		Object bits = parameter.getType().tValueByName("bits");
		return bits instanceof Number number ? number.intValue() : 8;
	}

	/**
	 * The instances in effect whose IDs begin as given, with their arguments.
	 */
	private static Map<String, String> subModules(TLObject device, String prefix) {
		Map<String, String> result = new HashMap<>();
		for (InstanceInfo instance : new DeviceConfiguration(device).getInstances()) {
			if (instance.getId().startsWith(prefix)) {
				result.put(instance.getId(), instance.getValues().values().toString());
			}
		}
		return result;
	}

	/**
	 * Sets the value of the given placement (by its ID in the device) through the editor where it is
	 * shown. The channel count <code>P-1_R-1</code> is not: its parameter is not shown (access None),
	 * the program sets it from the placement <code>P-1_R-27</code> by an <code>Assign</code>
	 * (haumacher/ehome #142); it is set as a restore of a value does, which checks the value against its
	 * type.
	 */
	private static void set(TLObject device, String id, int value) {
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		ParameterView view = null;
		for (Object item : configuration.getShownItems().keySet()) {
			if (item instanceof ParameterView candidate && id.equals(ModuleParts.partId(candidate))) {
				view = candidate;
			}
		}
		try (Transaction tx = kb().beginTransaction()) {
			if (view != null) {
				DeviceSettings.setValue(device, view, Integer.valueOf(value));
			} else {
				DeviceSettings.restoreValue(device, hidden(configuration, id), Integer.valueOf(value));
			}
			tx.commit();
		}
	}

	private static ParameterView hidden(DeviceConfiguration configuration, String id) {
		Program program = configuration.getProgram();
		for (Parameter parameter : program.getParameters()) {
			for (ParameterView view : parameter.getViews()) {
				if (id.equals(view.getExtId())) {
					return view;
				}
			}
		}
		for (InstanceInfo instance : configuration.getInstances()) {
			for (Parameter parameter : instance.getDefinition().getParameters()) {
				for (ParameterView view : parameter.getViews()) {
					ParameterView placed = (ParameterView) ModuleParts.wrap(view, instance);
					if (id.equals(ModuleParts.partId(placed))) {
						return placed;
					}
				}
			}
		}
		fail("No placement " + id);
		return null;
	}

	private static TLObject assignment() {
		return instances("ehome.catalog:ProgramAssignment").stream()
			.filter(a -> ASSIGNMENT.equals(a.tValueByName("extId"))).findFirst().orElseThrow();
	}

	private static Program program() {
		return (Program) instances("ehome.operational:Program").stream()
			.filter(p -> PROGRAM.equals(p.tValueByName("extId"))).findFirst().orElseThrow();
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestZennioDaliBox.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
