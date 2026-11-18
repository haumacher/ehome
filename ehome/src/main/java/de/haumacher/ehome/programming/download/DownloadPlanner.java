/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.HistoryUtils;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.model.operational.CodeSegment;
import de.haumacher.ehome.model.operational.LoadProcedure;
import de.haumacher.ehome.model.operational.LoadStep;
import de.haumacher.ehome.model.operational.MaskResource;
import de.haumacher.ehome.model.operational.MaskVersion;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.ResourceLocation;
import de.haumacher.ehome.model.operational.Segment;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.DownloadImage.PropertyWrite;
import de.haumacher.ehome.programming.ParameterEncoding;
import de.haumacher.ehome.programming.PluginRequirements;
import de.haumacher.ehome.modules.ModuleInstances;

/**
 * Computes the {@link DownloadPlan} of a device from its project data, without any bus.
 *
 * <h2>Which procedure</h2>
 * <p>
 * By the program's load procedure style: <code>ProductProcedure</code> the program's own load
 * procedure, <code>DefaultProcedure</code> the mask's load procedure for the full download
 * (sub type <code>full</code>, else <code>all</code>), <code>MergedProcedure</code> the mask's with
 * each <code>Merge</code> step replaced by the program's procedures of that merge ID. Of the steps,
 * those apply whose <code>appliesTo</code> is empty, <code>auto</code>, <code>all</code>,
 * <code>full</code>, <code>fullpar</code> or <code>fullgrp</code>.
 * </p>
 *
 * <h2>Load controls</h2>
 * <p>
 * <code>Unload</code>, <code>Load</code>, <code>AbsSegment</code>, <code>TaskSegment</code>,
 * <code>TaskPtr</code>, <code>TaskCtrl1</code>, <code>TaskCtrl2</code> and <code>LoadCompleted</code>
 * become {@link LoadControls load controls},
 * by the mask (see {@link LoadStateMachines}): through properties (mask feature
 * <code>PropertyMappedLsms</code>, as on mask 7.5), load state machine <i>n</i> being the interface
 * object of index <i>n</i>, which the mask must name in one of its resources; or in memory (mask
 * 7.1, BIM M112, haumacher/ehome #22), written into the mask's load control of the load state machine
 * and checked by its load state, both in memory. A <code>TaskSegment</code> of a load state machine
 * the mask has no load control for (load state machine 5 on the masks 7.5 and 7.1) is left out: the
 * device has nothing to load there. With dynamic table
 * management, the segment of the address table is allocated with the length of the table, the
 * segment of the association table from the end of the address table to the end of its static
 * segment, and the task segment of the association table points to the table (as ETS did it on the
 * Tastsensor of the test system: table pointer 4045, stamps of 0x45 and 0x19F bytes).
 * </p>
 *
 * <h2>Which bytes</h2>
 * <p>
 * The memory of the download image is written into the segments the procedure allocates: right
 * before the <code>LoadCompleted</code> of a load state machine, every block of the image lying in
 * one of its segments, in chunks of at most {@value #MAX_CHUNK} bytes. Only the bytes of the image's
 * mask are written; a chunk ends at a byte outside the mask, at the end of a block and after
 * {@value #MAX_CHUNK} bytes. A segment whose download stamp the procedure writes
 * (<code>LoadImageProp</code> of a property the mask names as a stamp) is written completely, the
 * bytes outside the image as zero, so that the stamp's CRC describes the memory. A
 * <code>WriteMem</code> step writes its inline data, or the bytes of the image in its range. Every
 * byte of the image's masks must be written by the plan, or the plan is refused. Parameters stored
 * in properties are written by a <code>WriteProp</code> step without inline data; a property value
 * of the image no step writes is listed as not written.
 * </p>
 *
 * <h2>The hardware</h2>
 * <p>
 * A property the mask requires to be identical to the program's (flavour
 * <code>HardwareConfig_Identical</code>, the hardware type: object 0, property 78), for which the
 * image has a value, is compared right after the mask check, before anything is written, unless the
 * procedure compares it itself (<code>CompareProp</code>). A device holding another value refuses
 * the download.
 * </p>
 *
 * <h2>Memory-based procedures (BCU 1)</h2>
 * <p>
 * A procedure that allocates no segment (the mask's generic procedure of BCU 1, mask 0012) loads
 * the device by memory writes alone: it stops the application (<code>WriteMem</code> of 00 into
 * the mask's <code>RunError</code>), empties the address table (its count 01), writes the ranges of
 * the image it names, writes the count of the address table from the image, starts the
 * application (FF into <code>RunError</code>) and restarts the device. Inline data is described by
 * the mask resource it writes. <code>LoadImageMem</code> sends nothing: ETS does not document what
 * it does; the procedures of mask 0012 and 0020 take the image of the count of the address table
 * with it and write that byte from the image at their end, which the plan checks (every byte of the
 * range must be written from the image by a later step). A byte of the image such a procedure does
 * not write is listed as not loaded if it is the program's static data (as with ETS, the device
 * keeps what it has there, e.g. 01FF on BCU 1), and refuses the plan otherwise.
 * </p>
 *
 * <h2>BCU 2 (masks 0020, 0021, 0025; haumacher/ehome #191)</h2>
 * <p>
 * Load state machines through properties as on mask 7.5. While the control variable
 * <code>EnableSegmentWrite</code> is off, a segment allocated then is not written by the load (the
 * mask's procedure of 2.0 and 2.1 allocates its segments so and writes the memory afterwards as on BCU
 * 1); a plan whose segments are all allocated so is memory-based as above. On a mask without download
 * stamps (feature <code>DownloadStamp</code>) the load writes the segments of EEPROM (memory type 3),
 * whatever their flags: on BCU 2 the flag 80 switches on the segment's checksum. With dynamic table
 * management the address table's segment takes the checksum byte of <code>AllocExtraByte</code> (see
 * {@link DownloadImage#tableChecksum(Program)}). After the mask check the plan authorizes with the key
 * of the delivery state ({@link #DEFAULT_KEY}), as ETS does: BCU 2 starts a connection at the free
 * access level.
 * </p>
 *
 * <p>
 * Memory writes are verified (read back and compared) unless the program says
 * <code>LegacyNoMemoryVerifyMode</code>; <code>SetControlVariable EnableVerifyOnWriteDirect</code>
 * or <code>EnableMemoryAutoVerify</code> switches the verification. A step kind not listed here
 * refuses the plan with its name.
 * </p>
 */
public class DownloadPlanner {

	/**
	 * The most bytes a memory write carries: a memory write in a standard frame on TP.
	 */
	public static final int MAX_CHUNK = 12;

	/** The interface object of the device. */
	private static final int DEVICE_OBJECT = 0;

	/** The flavour of a mask resource holding the download stamps of a load state machine. */
	private static final String STAMP_FLAVOUR = "Stamp_SystemB";

	/**
	 * The flavour of a mask resource whose value in the device must be identical to the program's
	 * (the hardware type); only the manufacturer writes it.
	 */
	private static final String IDENTICAL_FLAVOUR = "HardwareConfig_Identical";

	/**
	 * The flag of a segment allocation marking a segment ETS writes and stamps; the image's memory in
	 * a segment without it is not written (as ETS did on the test system).
	 */
	private static final int LOADED = 0x80;

	/** The flag of a segment allocation marking a segment with a download stamp. */
	private static final int STAMPED = LOADED;

	/** The control variable switching the writing of the memory of allocated segments. */
	private static final String SEGMENT_WRITE = "EnableSegmentWrite";

	/** The mask feature of a mask whose download stamps the procedure writes. */
	private static final String DOWNLOAD_STAMP = "DownloadStamp";

	/** The mask feature naming the number of access levels. */
	private static final String AUTHORIZE_LEVELS = "AuthorizeLevels";

	/** The memory type of EEPROM in a segment allocation. */
	private static final int EEPROM = 3;

	/** The key of every access level in the delivery state of a device. */
	static final byte[] DEFAULT_KEY = { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };

	/** The scopes of steps and procedures belonging to a full download. */
	private static final Set<String> FULL_SCOPES = Set.of("auto", "all", "full", "fullpar", "fullgrp");

	private final TLObject _device;

	/** The revision the plan is computed from, see {@link DownloadPlan#getRevision()}. */
	private final long _revision = HistoryUtils.getLastRevision().getCommitNumber();

	private final Set<ResKey> _problems = new LinkedHashSet<>();

	private final List<Operation> _operations = new ArrayList<>();

	private Program _program;

	private MaskVersion _mask;

	private DownloadImage _image;

	private ResKey _procedureName;

	private boolean _verify;

	/** The allocations of the procedure by load state machine, in the order of the procedure. */
	private final Map<Integer, List<Allocation>> _allocations = new HashMap<>();

	/** The task segment addresses replaced by the place of a table (dynamic table management). */
	private final Map<Integer, Integer> _taskAddresses = new HashMap<>();

	/** The load state machines whose download stamps the procedure writes. */
	private final Set<Integer> _stamped = new HashSet<>();

	/** The load state machines whose memory is written already. */
	private final Set<Integer> _loaded = new HashSet<>();

	/** The blocks of the image lying in segments the procedure does not load. */
	private final List<Block> _unloaded = new ArrayList<>();

	/** The addresses of {@link #_unloaded}. */
	private final BitSet _notLoaded = new BitSet();

	/** The memory addresses the plan writes. */
	private final BitSet _written = new BitSet();

	/** The memory addresses the plan writes with the bytes of the image. */
	private final BitSet _writtenFromImage = new BitSet();

	/** The ranges whose image a <code>LoadImageMem</code> step takes, by step, written from the image after it. */
	private final Map<String, int[]> _imageRanges = new LinkedHashMap<>();

	/** The property values of the image the plan writes or compares, by object index and property. */
	private final Set<List<Integer>> _writtenProperties = new HashSet<>();

	/** The application ID the task segment of a load state machine sets, by load state machine. */
	private final Map<Integer, byte[]> _applicationIds = new HashMap<>();

	private boolean _maskChecked;

	/**
	 * A segment the procedure allocates, with the memory it holds after the download.
	 */
	private static final class Allocation {

		final int _stateMachine;

		final String _step;

		final int _segmentType;

		int _address;

		int _size;

		final int _access;

		final int _memoryType;

		final int _flags;

		/** The memory of the segment after the download: the image, zero elsewhere. */
		byte[] _content;

		/** Per byte of the segment, the block of the image it comes from, -1 for none. */
		int[] _source;

		/** Per byte, whether the plan writes it. */
		boolean[] _write;

		/**
		 * Whether the load of the segment writes its memory: the control variable
		 * <code>EnableSegmentWrite</code> was not switched off at its allocation.
		 */
		final boolean _segmentWrite;

		Allocation(int stateMachine, String step, int segmentType, int address, int size, int access, int memoryType,
				int flags, boolean segmentWrite) {
			_stateMachine = stateMachine;
			_step = step;
			_segmentType = segmentType;
			_address = address;
			_size = size;
			_access = access;
			_memoryType = memoryType;
			_flags = flags;
			_segmentWrite = segmentWrite;
		}

		boolean contains(Block block) {
			return block.address() >= _address && block.end() <= _address + _size;
		}
	}

	/**
	 * Where a load control of a step goes: the interface object of a load state machine controlled
	 * through properties, or the memory of one controlled in memory.
	 *
	 * @param stateMachine
	 *        The load state machine.
	 * @param memory
	 *        The load state machine in memory, <code>null</code> if it is controlled through the
	 *        property of the interface object of its index.
	 */
	private record Lsm(int stateMachine, LoadStateMachines.InMemory memory) {

		/**
		 * The operation writing the given load control and checking the expected state.
		 */
		Operation control(String step, byte[] control, int expectedState) {
			if (memory == null) {
				return new Operation.LoadControl(step, stateMachine, stateMachine, control, expectedState);
			}
			return new Operation.MemoryLoadControl(step, stateMachine, memory.control(), memory.status(), control,
				expectedState);
		}
	}

	/**
	 * A step of the procedure with its number.
	 */
	private record Step(int number, LoadStep step) {

		String kind() {
			return step.getKind().getName();
		}

		String label() {
			Integer lsm = step.getStateMachine();
			return "#" + number + " " + kind() + (lsm != null ? " LSM " + lsm : "");
		}
	}

	private DownloadPlanner(TLObject device) {
		_device = device;
	}

	/**
	 * The plan of a download of the given kind into the given device (<code>ehome.physical:Device</code>).
	 */
	public static DownloadPlan plan(TLObject device, DownloadPlan.Kind kind) {
		return new DownloadPlanner(device).build(kind);
	}

	/**
	 * An individual address as text (area.line.device).
	 */
	public static String formatAddress(int address) {
		return (address >>> 12) + "." + ((address >>> 8) & 0x0F) + "." + (address & 0xFF);
	}

	private DownloadPlan build(DownloadPlan.Kind kind) {
		Integer address = DownloadImage.individualAddress(_device);
		String device = (address == null ? "(no address)" : formatAddress(address)) + " "
			+ Objects.toString(_device.tValueByName("extId"), "");
		if (address == null) {
			_problems.add(I18NConstants.ERROR_NO_ADDRESS);
		}
		if (kind == DownloadPlan.Kind.ADDRESS) {
			if (address != null) {
				_operations.add(new Operation.WriteIndividualAddress("", address));
			}
			return result(device, address, kind);
		}

		_program = DeviceConfiguration.program(_device);
		if (_program == null) {
			_problems.add(I18NConstants.ERROR_NO_PROGRAM);
			return result(device, address, kind);
		}
		if (_program.getUsesModules()) {
			_problems.add(I18NConstants.ERROR_PROGRAM_USES_MODULES);
			return result(device, address, kind);
		}
		// A plug-in of the manufacturer doing the download, a data handler or external software
		// (haumacher/ehome #159): the image is still computed, so the comparison stays allowed.
		ResKey requirement = PluginRequirements.downloadProblem(_program);
		if (requirement != null) {
			_problems.add(requirement);
		}
		if (!_program.getModules().isEmpty()) {
			// The layout of the device's dynamic modules (haumacher/ehome #119).
			_problems.addAll(new DeviceConfiguration(_device).getModuleProblems());
			String differences = ModuleInstances.differenceText(_device);
			if (differences != null && kind != DownloadPlan.Kind.LAYOUT) {
				_problems.add(I18NConstants.ERROR_MODULE_LAYOUT_DIFFERS__DIFFERENCES.fill(differences));
			}
		}
		if (kind == DownloadPlan.Kind.LAYOUT && ModuleInstances.differenceText(_device) == null) {
			_problems.add(I18NConstants.ERROR_NO_LAYOUT_DIFFERENCE);
		}
		_image = DownloadImage.compute(_device);
		for (ResKey problem : _image.getProblems()) {
			_problems.add(I18NConstants.ERROR_IMAGE__PROBLEM.fill(problem));
		}
		_mask = _program.getMask();
		if (_mask == null) {
			_problems.add(I18NConstants.ERROR_NO_MASK);
			return result(device, address, kind);
		}
		TLClassifier medium = _mask.getMedium();
		if (medium != null && !"TP".equals(medium.getName())) {
			_problems.add(I18NConstants.ERROR_UNSUPPORTED_MEDIUM__MEDIUM_MASK.fill(medium.getName(), maskName()));
			return result(device, address, kind);
		}
		List<Step> steps = procedure();
		if (steps == null) {
			return result(device, address, kind);
		}
		_verify = !_program.getNoMemoryVerify();
		allocations(steps);
		for (Step step : steps) {
			step(step);
		}
		compareIdentical(steps);
		checkWritten();
		return result(device, address, kind);
	}

	private DownloadPlan result(String device, Integer address, DownloadPlan.Kind kind) {
		return new DownloadPlan(_revision, device, address, kind, _procedureName, _image, _unloaded, _operations,
			new ArrayList<>(_problems));
	}

	private String maskName() {
		return _mask.getName() + " (" + _mask.getExtId() + ")";
	}

	// The procedure.

	private List<Step> procedure() {
		String style = name(_program.getLoadProcedureStyle());
		if (style == null) {
			style = _program.getLoadProcedures().isEmpty() ? "DefaultProcedure" : "ProductProcedure";
		}
		List<LoadStep> steps = new ArrayList<>();
		switch (style) {
			case "ProductProcedure": {
				LoadProcedure procedure = programProcedure();
				if (procedure == null) {
					_problems.add(I18NConstants.ERROR_NO_PROCEDURE__MASK.fill(maskName()));
					return null;
				}
				_procedureName = I18NConstants.PROCEDURE_PRODUCT;
				steps.addAll(procedure.getSteps());
				break;
			}
			case "MergedProcedure":
			case "DefaultProcedure": {
				LoadProcedure procedure = maskProcedure();
				if (procedure == null) {
					_problems.add(I18NConstants.ERROR_NO_PROCEDURE__MASK.fill(maskName()));
					return null;
				}
				boolean merged = "MergedProcedure".equals(style);
				_procedureName = (merged ? I18NConstants.PROCEDURE_MERGED__SCOPE_MASK : I18NConstants.PROCEDURE_DEFAULT__SCOPE_MASK)
					.fill(name(procedure.getScope()), maskName());
				for (LoadStep step : procedure.getSteps()) {
					if (merged && "Merge".equals(step.getKind().getName())) {
						for (LoadProcedure part : _program.getLoadProcedures()) {
							if (Objects.equals(part.getMergeId(), step.getMergeId())) {
								steps.addAll(part.getSteps());
							}
						}
					} else {
						steps.add(step);
					}
				}
				break;
			}
			default:
				_problems.add(I18NConstants.ERROR_NO_PROCEDURE__MASK.fill(maskName()));
				return null;
		}
		List<Step> result = new ArrayList<>();
		for (LoadStep step : steps) {
			TLClassifier appliesTo = step.getAppliesTo();
			if (appliesTo != null && !FULL_SCOPES.contains(appliesTo.getName())) {
				continue;
			}
			result.add(new Step(result.size() + 1, step));
		}
		return result;
	}

	private LoadProcedure programProcedure() {
		for (LoadProcedure procedure : _program.getLoadProcedures()) {
			if (isLoad(procedure) && procedure.getMergeId() == null) {
				return procedure;
			}
		}
		return null;
	}

	private LoadProcedure maskProcedure() {
		LoadProcedure all = null;
		for (LoadProcedure procedure : _mask.getProcedures()) {
			if (!isLoad(procedure) || !remote(procedure)) {
				continue;
			}
			String scope = name(procedure.getScope());
			if ("full".equals(scope)) {
				return procedure;
			}
			if ("all".equals(scope) && all == null) {
				all = procedure;
			}
		}
		return all;
	}

	private static boolean isLoad(LoadProcedure procedure) {
		String kind = name(procedure.getKind());
		String scope = name(procedure.getScope());
		return (kind == null || "Load".equals(kind)) && (scope == null || FULL_SCOPES.contains(scope));
	}

	private static boolean remote(LoadProcedure procedure) {
		Set<TLClassifier> access = procedure.getAccess();
		return access.isEmpty() || access.stream().anyMatch(a -> "remote".equals(a.getName()));
	}

	// The segments.

	private void allocations(List<Step> steps) {
		boolean segmentWrite = true;
		for (Step step : steps) {
			LoadStep load = step.step();
			switch (step.kind()) {
				case "SetControlVariable":
					if (SEGMENT_WRITE.equals(name(load.getControlVariable()))) {
						segmentWrite = load.getControlValue();
					}
					break;
				case "AbsSegment":
					if (load.getStateMachine() != null) {
						_allocations.computeIfAbsent(load.getStateMachine(), x -> new ArrayList<>())
							.add(new Allocation(load.getStateMachine(), step.label(), value(load.getSegmentType()),
								(int) value(load.getAddress()), (int) value(load.getSize()), value(load.getSegmentAccess()),
								value(load.getMemoryType()), value(load.getSegmentFlags()), segmentWrite));
					}
					break;
				case "LoadImageProp":
					if (load.getObjectIndex() != null && stampResource(load.getObjectIndex(), value(load.getPropertyId()))) {
						// Load state machine n is the interface object n (PropertyMappedLsms).
						_stamped.add(load.getObjectIndex());
					}
					break;
				default:
					break;
			}
		}
		if (_program.getDynamicTableManagement()) {
			placeTables();
		}
		for (List<Allocation> allocations : _allocations.values()) {
			for (Allocation allocation : allocations) {
				content(allocation);
			}
		}
	}

	/**
	 * Dynamic table management: the segments of the tables take the places the image gives the
	 * tables.
	 */
	private void placeTables() {
		Block addresses = _image.getAddressTable();
		Block associations = _image.getAssociationTable();
		if (addresses == null || associations == null || _program.getAssociationTable() == null
			|| !(_program.getAssociationTable().getSegment() instanceof CodeSegment associationSegment)) {
			return;
		}
		Allocation addressAllocation = null;
		Allocation associationAllocation = null;
		for (List<Allocation> allocations : _allocations.values()) {
			for (Allocation allocation : allocations) {
				if (addressAllocation == null && allocation._address <= addresses.address()
					&& addresses.address() < allocation._address + allocation._size) {
					addressAllocation = allocation;
				}
				if (associationAllocation == null && Objects.equals(associationSegment.getAddress(), allocation._address)) {
					associationAllocation = allocation;
				}
			}
		}
		if (addressAllocation == null || associationAllocation == null || addressAllocation == associationAllocation) {
			return;
		}
		addressAllocation._address = addresses.address();
		// With the checksum byte the mask appends to a segment with checksum control (AllocExtraByte).
		addressAllocation._size = addresses.data().length + DownloadImage.tableChecksum(_program);
		int end = associationAllocation._address + associationAllocation._size;
		associationAllocation._address = associations.address();
		associationAllocation._size = end - associations.address();
		if (associations.end() > end) {
			_problems.add(I18NConstants.ERROR_ASSOCIATION_SPACE__STEP_SEGMENT_LENGTH.fill(associationAllocation._step,
				String.format("%04X-%04X", associations.address(), end - 1), associations.data().length));
		}
		_taskAddresses.put(associationAllocation._stateMachine, associations.address());
	}

	private void content(Allocation allocation) {
		allocation._content = new byte[Math.max(0, allocation._size)];
		allocation._source = new int[allocation._content.length];
		allocation._write = new boolean[allocation._content.length];
		Arrays.fill(allocation._source, -1);
		List<Block> blocks = _image.getBlocks();
		for (int index = 0; index < blocks.size(); index++) {
			Block block = blocks.get(index);
			if (block.stateMachine() != null || !allocation.contains(block)) {
				continue;
			}
			if (!allocation._segmentWrite) {
				// The procedure writes this memory itself (EnableSegmentWrite off: BCU 2, mask 2.0).
				continue;
			}
			boolean table = block == _image.getAddressTable() || block == _image.getAssociationTable();
			boolean loaded = table || loads(allocation);
			if (!loaded) {
				// ETS does not write a segment whose allocation lacks the flag (the Tastsensor's 454E
				// holds zeros where the image has its static data).
				if (!_unloaded.contains(block)) {
					_unloaded.add(block);
				}
				_notLoaded.set(block.address(), block.end());
			}
			int offset = block.address() - allocation._address;
			for (int n = 0; n < block.data().length; n++) {
				if (allocation._source[offset + n] >= 0) {
					// Lies in an earlier block already (a block within another one).
					continue;
				}
				allocation._content[offset + n] = block.data()[n];
				allocation._source[offset + n] = index;
				allocation._write[offset + n] = loaded && writable(block, n);
			}
		}
		if (allocation._segmentWrite && _stamped.contains(allocation._stateMachine) && (allocation._flags & STAMPED) != 0) {
			for (int n = 0; n < allocation._write.length; n++) {
				if (allocation._source[n] < 0) {
					allocation._write[n] = true;
				}
			}
		}
	}

	/**
	 * Whether the load of a segment writes the image's memory into it: on a mask with download stamps
	 * (feature {@value #DOWNLOAD_STAMP}, mask 7.5), a segment allocated with the flag {@value #LOADED}
	 * (as ETS did on the test system); on any other mask, a segment of EEPROM (memory type
	 * {@value #EEPROM}): BCU 2 allocates its parameter and table segments without that flag, which
	 * there switches on the checksum control of the segment, and RAM holds nothing to load.
	 */
	private boolean loads(Allocation allocation) {
		if (feature(DOWNLOAD_STAMP)) {
			return (allocation._flags & LOADED) != 0;
		}
		return (allocation._memoryType & 0x07) == EEPROM;
	}

	private boolean feature(String name) {
		return _mask.getFeatures().stream().anyMatch(f -> name.equals(f.getName()) && Objects.equals(f.getValue(), 1));
	}

	private boolean writable(Block block, int index) {
		int mask = block.mask()[index] & 0xFF;
		if (mask == 0xFF) {
			return true;
		}
		if (mask != 0) {
			_problems.add(I18NConstants.ERROR_PARTIAL_MASK__ADDRESS_MASK.fill(
				String.format("%04X", block.address() + index), String.format("%02X", mask)));
		}
		return false;
	}

	// The steps.

	private void step(Step step) {
		LoadStep load = step.step();
		String label = step.label();
		switch (step.kind()) {
			case "Connect":
				_operations.add(new Operation.Connect(label));
				if (!_maskChecked) {
					_maskChecked = true;
					_operations.add(new Operation.CheckMask("", masks()));
					if (authorizes()) {
						_operations.add(new Operation.Authorize("", DEFAULT_KEY));
					}
				}
				break;
			case "Disconnect":
				_operations.add(new Operation.Disconnect(label));
				break;
			case "Unload": {
				Lsm lsm = lsm(step, false);
				if (lsm != null) {
					_operations.add(lsm.control(label, LoadControls.event(LoadControls.EVENT_UNLOAD), LoadControls.STATE_UNLOADED));
				}
				break;
			}
			case "Load": {
				Lsm lsm = lsm(step, false);
				if (lsm != null) {
					_operations.add(lsm.control(label, LoadControls.event(LoadControls.EVENT_LOAD), LoadControls.STATE_LOADING));
				}
				break;
			}
			case "AbsSegment":
				absoluteSegment(step);
				break;
			case "TaskSegment":
				taskSegment(step);
				break;
			case "TaskCtrl1": {
				Lsm lsm = lsm(step, false);
				if (lsm != null) {
					_operations.add(lsm.control(label,
						LoadControls.taskControl1((int) value(load.getAddress()), value(load.getCount())),
						LoadControls.STATE_LOADING));
				}
				break;
			}
			case "TaskPtr": {
				Lsm lsm = lsm(step, false);
				if (lsm != null) {
					_operations.add(lsm.control(label, LoadControls.taskPointer(value(load.getInitPointer()),
						value(load.getSavePointer()), value(load.getSerialPointer())), LoadControls.STATE_LOADING));
				}
				break;
			}
			case "TaskCtrl2": {
				Lsm lsm = lsm(step, false);
				if (lsm != null) {
					_operations.add(lsm.control(label, LoadControls.taskControl2(value(load.getCallback()),
						(int) value(load.getAddress()), value(load.getSegment0()), value(load.getSegment1())),
						LoadControls.STATE_LOADING));
				}
				break;
			}
			case "LoadCompleted":
				loadCompleted(step);
				break;
			case "LoadImageProp":
				loadImageProperty(step);
				break;
			case "CompareProp": {
				byte[] data = ParameterEncoding.toBytes(load.getInlineData());
				if (data == null || data.length == 0) {
					_problems.add(I18NConstants.ERROR_NO_INLINE_DATA__STEP.fill(label));
				} else if (object(step) != null) {
					_operations.add(new Operation.CompareProperty(label, object(step), value(load.getPropertyId()),
						start(load), count(load), data));
				}
				break;
			}
			case "WriteProp":
				writeProperty(step);
				break;
			case "WriteMem":
				writeMemory(step);
				break;
			case "LoadImageMem":
				loadImageMemory(step);
				break;
			case "CompareMem": {
				byte[] data = ParameterEncoding.toBytes(load.getInlineData());
				if (!standardMemory(step)) {
					break;
				}
				if (data == null || data.length == 0) {
					_problems.add(I18NConstants.ERROR_NO_INLINE_DATA__STEP.fill(label));
				} else {
					_operations.add(new Operation.CompareMemory(label, (int) value(load.getAddress()), data));
				}
				break;
			}
			case "Restart":
				_operations.add(new Operation.Restart(label));
				break;
			case "Delay":
				_operations.add(new Operation.Delay(label, value(load.getMilliseconds())));
				break;
			case "SetControlVariable":
				controlVariable(step);
				break;
			case "ProgressText":
				_operations.add(new Operation.Skip(label, I18NConstants.SKIP_PROGRESS_TEXT));
				break;
			case "Merge":
				_problems.add(I18NConstants.ERROR_MERGE__STEP_ID.fill(label, load.getMergeId()));
				break;
			default:
				_problems.add(I18NConstants.ERROR_UNSUPPORTED_STEP__STEP_KIND_MASK.fill(label, step.kind(), maskName()));
				break;
		}
	}

	/**
	 * Where the load controls of the load state machine of a step go, <code>null</code> if the mask
	 * has no load control for it (then a problem is recorded unless the step is optional).
	 */
	private Lsm lsm(Step step, boolean optional) {
		LoadStep load = step.step();
		Integer lsm = load.getStateMachine();
		if (lsm == null) {
			_problems.add(I18NConstants.ERROR_LSM_BY_TYPE__STEP.fill(step.label()));
			return null;
		}
		if (LoadStateMachines.propertyMapped(_mask)) {
			if (!maskHasObject(lsm)) {
				if (!optional) {
					_problems.add(I18NConstants.ERROR_NO_LSM_OBJECT__STEP_MASK_LSM.fill(step.label(), maskName(), lsm));
				}
				return null;
			}
			return new Lsm(lsm, null);
		}
		LoadStateMachines.InMemory memory = LoadStateMachines.inMemory(_mask, lsm);
		if (memory == null) {
			if (!optional) {
				_problems.add(I18NConstants.ERROR_NO_LOAD_CONTROL__STEP_MASK_LSM.fill(step.label(), maskName(), lsm));
			}
			return null;
		}
		return new Lsm(lsm, memory);
	}

	private void absoluteSegment(Step step) {
		LoadStep load = step.step();
		Lsm lsm = lsm(step, false);
		if (lsm == null) {
			return;
		}
		int type = value(load.getSegmentType());
		if (type != 0 && type != 1) {
			_problems.add(I18NConstants.ERROR_SEGMENT_TYPE__STEP_TYPE.fill(step.label(), type));
			return;
		}
		Allocation allocation = allocation(step);
		_operations.add(lsm.control(step.label(),
			LoadControls.absoluteSegment(type, allocation._address, allocation._size, allocation._access,
				allocation._memoryType, allocation._flags),
			LoadControls.STATE_LOADING));
	}

	private Allocation allocation(Step step) {
		for (Allocation allocation : _allocations.get(step.step().getStateMachine())) {
			if (allocation._step.equals(step.label())) {
				return allocation;
			}
		}
		throw new IllegalStateException("No allocation of " + step.label());
	}

	private void taskSegment(Step step) {
		LoadStep load = step.step();
		Integer lsm = load.getStateMachine();
		Lsm target = lsm(step, true);
		if (target == null) {
			// A load state machine the mask has no load control for (LSM 5 on the masks 7.5 and 7.1):
			// the device has nothing to load there.
			return;
		}
		int address = _taskAddresses.getOrDefault(lsm, (int) value(load.getAddress()));
		int manufacturer = manufacturer(_program);
		int number = value(_program.getApplicationNumber());
		int version = value(_program.getApplicationVersion());
		_operations.add(target.control(step.label(),
			LoadControls.taskSegment(address, value(_program.getPeiType()), manufacturer, number, version),
			LoadControls.STATE_LOADING));
		_applicationIds.put(lsm, applicationId(_program));
	}

	private void loadCompleted(Step step) {
		Integer lsm = step.step().getStateMachine();
		Lsm target = lsm(step, false);
		if (target == null) {
			return;
		}
		// The interface object of the loaded part: of the index of the load state machine (through
		// properties), of its object type (in memory); the same number.
		int object = lsm;
		writeSegments(lsm);
		_operations.add(target.control(step.label(), LoadControls.event(LoadControls.EVENT_LOAD_COMPLETED),
			LoadControls.STATE_LOADED));
		ResourceLocation programVersion = systemProperty("ApplicationId");
		byte[] applicationId = _applicationIds.get(lsm);
		if (applicationId != null && programVersion != null && Objects.equals(programVersion.getInterfaceObject(), object)) {
			// Checks that the device took the program's identity from the task segment.
			_operations.add(new Operation.CompareProperty("", object, value(programVersion.getPropertyId()), 1, 1,
				applicationId));
		}
	}

	/**
	 * The memory writes of the segments of a load state machine.
	 */
	private void writeSegments(int lsm) {
		if (!_loaded.add(lsm)) {
			return;
		}
		List<Block> blocks = _image.getBlocks();
		for (Allocation allocation : _allocations.getOrDefault(lsm, List.of())) {
			if (!allocation._segmentWrite) {
				continue;
			}
			int n = 0;
			while (n < allocation._write.length) {
				if (!allocation._write[n]) {
					n++;
					continue;
				}
				int start = n;
				int source = allocation._source[n];
				while (n < allocation._write.length && n - start < MAX_CHUNK && allocation._write[n]
					&& allocation._source[n] == source) {
					n++;
				}
				int address = allocation._address + start;
				ResKey what = source >= 0 ? blocks.get(source).label() : I18NConstants.WHAT_ZERO_FILL;
				_operations.add(new Operation.WriteMemory(allocation._step, what, address,
					Arrays.copyOfRange(allocation._content, start, n), _verify));
				_written.set(address, address + n - start);
				if (source >= 0) {
					_writtenFromImage.set(address, address + n - start);
				}
			}
		}
	}

	private void loadImageProperty(Step step) {
		LoadStep load = step.step();
		Integer object = load.getObjectIndex();
		int property = value(load.getPropertyId());
		if (object == null || !stampResource(object, property)) {
			_problems.add(I18NConstants.ERROR_UNKNOWN_IMAGE__STEP_OBJECT_PROPERTY.fill(step.label(), object, property));
			return;
		}
		List<Allocation> stamped = _allocations.getOrDefault(object, List.of()).stream()
			.filter(a -> (a._flags & STAMPED) != 0)
			.toList();
		int count = count(load);
		if (count != stamped.size()) {
			_problems.add(I18NConstants.ERROR_STAMP_COUNT__STEP_COUNT_SEGMENTS.fill(step.label(), count, stamped.size()));
			return;
		}
		int start = start(load);
		for (int n = 0; n < count; n++) {
			Allocation allocation = stamped.get(n);
			_operations.add(new Operation.WriteProperty(step.label(),
				I18NConstants.WHAT_STAMP__SEGMENT.fill(String.format("%04X", allocation._address)), object, property,
				start + n, 1,
				LoadControls.stamp(allocation._access, allocation._content)));
		}
	}

	private void writeProperty(Step step) {
		LoadStep load = step.step();
		Integer object = object(step);
		if (object == null) {
			return;
		}
		int property = value(load.getPropertyId());
		byte[] data = ParameterEncoding.toBytes(load.getInlineData());
		ResKey what = I18NConstants.WHAT_INLINE_DATA;
		if (data == null || data.length == 0) {
			data = null;
			for (PropertyWrite write : _image.getPropertyWrites()) {
				if (Objects.equals(write.objectIndex(), object) && write.propertyId() == property) {
					data = write.value();
					what = I18NConstants.WHAT_PARAMETERS;
					_writtenProperties.add(List.of(object, property));
				}
			}
		}
		if (data == null) {
			_problems.add(I18NConstants.ERROR_NO_INLINE_DATA__STEP.fill(step.label()));
			return;
		}
		_operations.add(new Operation.WriteProperty(step.label(), what, object, property, start(load), count(load), data));
	}

	private void writeMemory(Step step) {
		LoadStep load = step.step();
		if (!standardMemory(step)) {
			return;
		}
		boolean verify = _verify || load.getVerify();
		int address = (int) value(load.getAddress());
		byte[] inline = ParameterEncoding.toBytes(load.getInlineData());
		if (inline != null && inline.length > 0) {
			if (load.getSize() != null && load.getSize().longValue() != inline.length) {
				_problems.add(I18NConstants.ERROR_DATA_SIZE__STEP_DATA_SIZE.fill(step.label(), inline.length, load.getSize()));
				return;
			}
			String resources = resourceNames(address, inline.length);
			ResKey what = resources.isEmpty() ? I18NConstants.WHAT_INLINE_DATA
				: I18NConstants.WHAT_INLINE_DATA_RESOURCES__RESOURCES.fill(resources);
			for (int n = 0; n < inline.length; n += MAX_CHUNK) {
				_operations.add(new Operation.WriteMemory(step.label(), what, address + n,
					Arrays.copyOfRange(inline, n, Math.min(inline.length, n + MAX_CHUNK)), verify));
			}
			_written.set(address, address + inline.length);
			_writtenFromImage.clear(address, address + inline.length);
			return;
		}
		// The bytes of the image in the range.
		int end = address + (int) value(load.getSize());
		int n = address;
		while (n < end) {
			Block block = blockAt(n);
			if (block == null || !writable(block, n - block.address())) {
				n++;
				continue;
			}
			int start = n;
			while (n < end && n < block.end() && n - start < MAX_CHUNK && writable(block, n - block.address())) {
				n++;
			}
			_operations.add(new Operation.WriteMemory(step.label(), block.label(), start,
				Arrays.copyOfRange(block.data(), start - block.address(), n - block.address()), verify));
			_written.set(start, n);
			_writtenFromImage.set(start, n);
		}
	}

	/**
	 * <code>LoadImageMem</code>: nothing is sent; the range must be written from the image by a later
	 * step (see the class comment).
	 */
	private void loadImageMemory(Step step) {
		LoadStep load = step.step();
		if (!standardMemory(step)) {
			return;
		}
		int address = (int) value(load.getAddress());
		int size = (int) value(load.getSize());
		String range = range(address, size);
		_imageRanges.put(step.label(), new int[] { address, size });
		_writtenFromImage.clear(address, address + size);
		String resources = resourceNames(address, size);
		_operations.add(new Operation.Skip(step.label(), resources.isEmpty()
			? I18NConstants.SKIP_IMAGE_MEMORY__RANGE.fill(range)
			: I18NConstants.SKIP_IMAGE_MEMORY_RESOURCES__RANGE_RESOURCES.fill(range, resources)));
	}

	private static String range(int address, int size) {
		return size <= 1 ? String.format("%04X", address) : String.format("%04X-%04X", address, address + size - 1);
	}

	/**
	 * The names of the mask's resources in standard memory overlapping the given range, separated by
	 * commas (empty if there are none).
	 */
	private String resourceNames(int address, int size) {
		Set<String> names = new LinkedHashSet<>();
		for (MaskResource resource : _mask.getResources()) {
			ResourceLocation location = resource.getLocation();
			if (location == null || location.getAddressSpace() == null
				|| !"StandardMemory".equals(location.getAddressSpace().getName()) || location.getStartAddress() == null) {
				continue;
			}
			long start = location.getStartAddress();
			long length = resource.getLength() == null ? 1 : Math.max(1, resource.getLength());
			if (start < address + size && address < start + length) {
				names.add(resource.getName());
			}
		}
		return String.join(", ", names);
	}
	private Block blockAt(int address) {
		for (Block block : _image.getBlocks()) {
			if (block.stateMachine() == null && block.contains(address)) {
				return block;
			}
		}
		return null;
	}

	private boolean standardMemory(Step step) {
		TLClassifier space = step.step().getAddressSpace();
		if (space != null && !"Standard".equals(space.getName())) {
			_problems.add(I18NConstants.ERROR_MEMORY_SPACE__STEP_SPACE.fill(step.label(), space.getName()));
			return false;
		}
		return true;
	}

	private void controlVariable(Step step) {
		LoadStep load = step.step();
		String variable = name(load.getControlVariable());
		if ("EnableVerifyOnWriteDirect".equals(variable) || "EnableMemoryAutoVerify".equals(variable)) {
			_verify = load.getControlValue();
		} else if (SEGMENT_WRITE.equals(variable)) {
			// Taken by the allocations (see allocations(List)): while it is off, the load of a segment
			// does not write its memory, the procedure's memory writes do.
		} else if ("EnableOptimisticWrite".equals(variable)) {
			// E-Home writes every byte (never optimistically skipping bytes it believes to be in the
			// device), which serves both settings.
		} else {
			_problems.add(I18NConstants.ERROR_CONTROL_VARIABLE__STEP_VARIABLE.fill(step.label(), variable));
		}
	}

	private Integer object(Step step) {
		LoadStep load = step.step();
		if (load.getObjectIndex() == null) {
			_problems.add(I18NConstants.ERROR_LSM_BY_TYPE__STEP.fill(step.label()));
			return null;
		}
		return load.getObjectIndex();
	}

	/**
	 * Compares the properties the mask requires to be identical to the program's (resources of the
	 * flavour {@value #IDENTICAL_FLAVOUR}, the hardware type) with the image's values, right after
	 * the mask check and before anything is written; a property the procedure compares itself
	 * (<code>CompareProp</code>) is not compared twice.
	 */
	private void compareIdentical(List<Step> steps) {
		Set<List<Integer>> compared = new HashSet<>();
		for (Step step : steps) {
			LoadStep load = step.step();
			if ("CompareProp".equals(step.kind()) && load.getObjectIndex() != null && load.getPropertyId() != null) {
				compared.add(List.of(load.getObjectIndex(), load.getPropertyId()));
			}
		}
		int index = 0;
		for (int n = 0; n < _operations.size(); n++) {
			if (_operations.get(n) instanceof Operation.CheckMask) {
				index = n + 1;
				break;
			}
		}
		for (MaskResource resource : _mask.getResources()) {
			ResourceLocation location = resource.getLocation();
			if (!IDENTICAL_FLAVOUR.equals(resource.getFlavour()) || location == null || location.getAddressSpace() == null
				|| !"SystemProperty".equals(location.getAddressSpace().getName()) || location.getInterfaceObject() == null
				|| location.getPropertyId() == null) {
				continue;
			}
			List<Integer> property = List.of(location.getInterfaceObject(), location.getPropertyId());
			for (PropertyWrite write : _image.getPropertyWrites()) {
				if (!Objects.equals(write.objectIndex(), property.get(0)) || write.propertyId() != property.get(1)) {
					continue;
				}
				_writtenProperties.add(property);
				if (compared.add(property)) {
					_operations.add(index++,
						new Operation.CompareProperty("", property.get(0), property.get(1), 1, 1, write.value()));
				}
			}
		}
	}

	private void checkWritten() {
		if (_image == null) {
			return;
		}
		// Memory written by the procedure's memory writes alone: no segment (BCU 1), or no segment whose
		// load writes it (EnableSegmentWrite off, BCU 2).
		boolean memoryBased = _allocations.values().stream().flatMap(List::stream).noneMatch(a -> a._segmentWrite);
		for (Block block : _image.getBlocks()) {
			int unwritten = -1;
			for (int n = 0; n <= block.data().length; n++) {
				int address = block.address() + n;
				boolean missing = n < block.data().length && block.stateMachine() == null && block.mask()[n] != 0
					&& !_written.get(address) && !_notLoaded.get(address);
				if (missing && memoryBased && isStatic(block, n)) {
					// Not written by the mask's procedure, as with ETS: the device keeps its content.
					if (unwritten < 0) {
						unwritten = n;
					}
					continue;
				}
				if (unwritten >= 0) {
					Block part = new Block(I18NConstants.BLOCK_STATIC_PART__BLOCK.fill(block.label()),
						block.address() + unwritten, null, Arrays.copyOfRange(block.data(), unwritten, n),
						Arrays.copyOfRange(block.mask(), unwritten, n));
					_unloaded.add(part);
					_notLoaded.set(part.address(), part.end());
					unwritten = -1;
				}
				if (n < block.data().length && (block.stateMachine() != null || missing)) {
					_problems.add(I18NConstants.ERROR_NOT_WRITTEN__ADDRESS_BLOCK.fill(String.format("%04X", address), block.label()));
					break;
				}
			}
		}
		for (Map.Entry<String, int[]> entry : _imageRanges.entrySet()) {
			int address = entry.getValue()[0];
			int size = entry.getValue()[1];
			for (int n = address; n < address + size; n++) {
				Block block = blockAt(n);
				if (block != null && block.mask()[n - block.address()] != 0 && !_writtenFromImage.get(n)) {
					_problems.add(I18NConstants.ERROR_IMAGE_NOT_WRITTEN__STEP_RANGE.fill(entry.getKey(), range(address, size)));
					break;
				}
			}
		}
		for (PropertyWrite write : _image.getPropertyWrites()) {
			if (write.objectIndex() != null && _writtenProperties.contains(List.of(write.objectIndex(), write.propertyId()))) {
				continue;
			}
			String value = Operation.hex(write.value());
			ResKey reason;
			if (write.objectIndex() == null) {
				reason = I18NConstants.SKIP_PARAMETER_OF_TYPE__VALUE_TYPE_PROPERTY.fill(value, write.objectType(),
					write.propertyId());
			} else if (Objects.equals(write.objectIndex(), DEVICE_OBJECT)) {
				reason = I18NConstants.SKIP_DEVICE_PARAMETER__VALUE_OBJECT_PROPERTY.fill(value, write.objectIndex(),
					write.propertyId());
			} else {
				reason = I18NConstants.SKIP_PARAMETER__VALUE_OBJECT_PROPERTY.fill(value, write.objectIndex(),
					write.propertyId());
			}
			_operations.add(new Operation.Skip("", reason));
		}
	}

	/**
	 * Whether the byte of the block is the static data of the program's segment there (no parameter
	 * or table changed it).
	 */
	private boolean isStatic(Block block, int index) {
		int address = block.address() + index;
		for (Segment segment : _program.getSegments()) {
			if (segment instanceof CodeSegment code && code.getAddress() != null && address >= code.getAddress()) {
				byte[] data = ParameterEncoding.toBytes(code.getData());
				int offset = address - code.getAddress();
				if (data != null && offset < data.length) {
					return data[offset] == block.data()[index];
				}
			}
		}
		return false;
	}

	// The mask.

	/**
	 * The mask versions the device may have: the program's (a device of a newer mask accepting the
	 * program is not considered).
	 */
	private int[] masks() {
		return new int[] { _mask.getNumber() };
	}

	/**
	 * Whether the download authorizes with the {@link #DEFAULT_KEY key of the delivery state} after
	 * the mask check, as ETS does: on BCU 2 (management model <code>Bcu2</code> with the feature
	 * {@value #AUTHORIZE_LEVELS}), whose connection starts at the free access level 3, while the mask
	 * gives the load controls and the configuration write access only.
	 */
	private boolean authorizes() {
		TLClassifier model = _mask.getManagementModel();
		return model != null && "Bcu2".equals(model.getName())
			&& _mask.getFeatures().stream().anyMatch(f -> AUTHORIZE_LEVELS.equals(f.getName()));
	}

	private boolean maskHasObject(int objectIndex) {
		for (MaskResource resource : _mask.getResources()) {
			ResourceLocation location = resource.getLocation();
			if (location != null && Objects.equals(location.getInterfaceObject(), objectIndex)) {
				return true;
			}
		}
		return false;
	}

	private boolean stampResource(int objectIndex, int propertyId) {
		for (MaskResource resource : _mask.getResources()) {
			ResourceLocation location = resource.getLocation();
			if (STAMP_FLAVOUR.equals(resource.getFlavour()) && location != null
				&& Objects.equals(location.getInterfaceObject(), objectIndex)
				&& Objects.equals(location.getPropertyId(), propertyId)) {
				return true;
			}
		}
		return false;
	}

	private ResourceLocation systemProperty(String resourceName) {
		for (MaskResource resource : _mask.getResources()) {
			ResourceLocation location = resource.getLocation();
			if (resourceName.equals(resource.getName()) && location != null && location.getAddressSpace() != null
				&& "SystemProperty".equals(location.getAddressSpace().getName()) && location.getInterfaceObject() != null
				&& location.getPropertyId() != null) {
				return location;
			}
		}
		return null;
	}

	/**
	 * The application ID of a program as a device reports it after the download
	 * (<code>PID_PROGRAM_VERSION</code>): manufacturer, application number, version.
	 */
	public static byte[] applicationId(Program program) {
		return LoadControls.applicationId(manufacturer(program), value(program.getApplicationNumber()),
			value(program.getApplicationVersion()));
	}

	/**
	 * The KNX number of the program's manufacturer.
	 */
	private static int manufacturer(Program program) {
		TLObject manufacturer = program.getManufacturer();
		if (manufacturer != null && manufacturer.tValueByName("id") instanceof Integer id) {
			return id;
		}
		String extId = program.getExtId();
		if (extId != null && extId.startsWith("M-") && extId.length() >= 6) {
			return Integer.parseInt(extId.substring(2, 6), 16);
		}
		return 0;
	}

	private static int start(LoadStep step) {
		return step.getStartElement() == null ? 1 : step.getStartElement();
	}

	private static int count(LoadStep step) {
		return step.getCount() == null ? 1 : step.getCount();
	}

	private static String name(TLClassifier classifier) {
		return classifier == null ? null : classifier.getName();
	}

	private static int value(Integer value) {
		return value == null ? 0 : value.intValue();
	}

	private static long value(Long value) {
		return value == null ? 0 : value.longValue();
	}

}
