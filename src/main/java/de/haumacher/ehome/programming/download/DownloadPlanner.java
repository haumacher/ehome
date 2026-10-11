/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
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
import de.haumacher.ehome.model.operational.MaskFeature;
import de.haumacher.ehome.model.operational.MaskResource;
import de.haumacher.ehome.model.operational.MaskVersion;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.ResourceLocation;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.DownloadImage.PropertyWrite;
import de.haumacher.ehome.programming.ParameterEncoding;

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
 * Only load state machines controlled through properties are supported (mask feature
 * <code>PropertyMappedLsms</code>, as on mask 7.5): load state machine <i>n</i> is the interface
 * object of index <i>n</i>, which the mask must name in one of its resources. <code>Unload</code>,
 * <code>Load</code>, <code>AbsSegment</code>, <code>TaskSegment</code>, <code>TaskCtrl1</code> and
 * <code>LoadCompleted</code> become {@link LoadControls load controls}. With dynamic table
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

	/** The feature of a mask whose load state machines are controlled through properties. */
	private static final String PROPERTY_MAPPED_LSMS = "PropertyMappedLsms";

	/** The flavour of a mask resource holding the download stamps of a load state machine. */
	private static final String STAMP_FLAVOUR = "Stamp_SystemB";

	/**
	 * The flag of a segment allocation marking a segment ETS writes and stamps; the image's memory in
	 * a segment without it is not written (as ETS did on the test system).
	 */
	private static final int LOADED = 0x80;

	/** The flag of a segment allocation marking a segment with a download stamp. */
	private static final int STAMPED = LOADED;

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

	/** The property values of the image the plan writes, by object index and property. */
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

		Allocation(int stateMachine, String step, int segmentType, int address, int size, int access, int memoryType,
				int flags) {
			_stateMachine = stateMachine;
			_step = step;
			_segmentType = segmentType;
			_address = address;
			_size = size;
			_access = access;
			_memoryType = memoryType;
			_flags = flags;
		}

		boolean contains(Block block) {
			return block.address() >= _address && block.end() <= _address + _size;
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
		_image = DownloadImage.compute(_device);
		for (String problem : _image.getProblems()) {
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
		for (Step step : steps) {
			LoadStep load = step.step();
			switch (step.kind()) {
				case "AbsSegment":
					if (load.getStateMachine() != null) {
						_allocations.computeIfAbsent(load.getStateMachine(), x -> new ArrayList<>())
							.add(new Allocation(load.getStateMachine(), step.label(), value(load.getSegmentType()),
								(int) value(load.getAddress()), (int) value(load.getSize()), value(load.getSegmentAccess()),
								value(load.getMemoryType()), value(load.getSegmentFlags())));
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
		addressAllocation._size = addresses.data().length;
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
			boolean table = block == _image.getAddressTable() || block == _image.getAssociationTable();
			boolean loaded = table || (allocation._flags & LOADED) != 0;
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
		if (_stamped.contains(allocation._stateMachine) && (allocation._flags & STAMPED) != 0) {
			for (int n = 0; n < allocation._write.length; n++) {
				if (allocation._source[n] < 0) {
					allocation._write[n] = true;
				}
			}
		}
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
				}
				break;
			case "Disconnect":
				_operations.add(new Operation.Disconnect(label));
				break;
			case "Unload": {
				Integer object = lsmObject(step, false);
				if (object != null) {
					_operations.add(new Operation.LoadControl(label, load.getStateMachine(), object,
						LoadControls.event(LoadControls.EVENT_UNLOAD), LoadControls.STATE_UNLOADED));
				}
				break;
			}
			case "Load": {
				Integer object = lsmObject(step, false);
				if (object != null) {
					_operations.add(new Operation.LoadControl(label, load.getStateMachine(), object,
						LoadControls.event(LoadControls.EVENT_LOAD), LoadControls.STATE_LOADING));
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
				Integer object = lsmObject(step, false);
				if (object != null) {
					_operations.add(new Operation.LoadControl(label, load.getStateMachine(), object,
						LoadControls.taskControl1((int) value(load.getAddress()), value(load.getCount())),
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
				_operations.add(new Operation.Skip(label, "a progress text of ETS, nothing is sent to the device"));
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
	 * The interface object of the load state machine of a step, <code>null</code> if there is none
	 * (then a problem is recorded unless the caller handles it).
	 */
	private Integer lsmObject(Step step, boolean optional) {
		LoadStep load = step.step();
		Integer lsm = load.getStateMachine();
		if (lsm == null) {
			_problems.add(I18NConstants.ERROR_LSM_BY_TYPE__STEP.fill(step.label()));
			return null;
		}
		if (feature(PROPERTY_MAPPED_LSMS) != 1) {
			_problems.add(I18NConstants.ERROR_MEMORY_MAPPED_LSM__STEP_MASK.fill(step.label(), maskName()));
			return null;
		}
		if (!maskHasObject(lsm)) {
			if (!optional) {
				_problems.add(I18NConstants.ERROR_NO_LSM_OBJECT__STEP_MASK_LSM.fill(step.label(), maskName(), lsm));
			}
			return null;
		}
		return lsm;
	}

	private void absoluteSegment(Step step) {
		LoadStep load = step.step();
		Integer object = lsmObject(step, false);
		if (object == null) {
			return;
		}
		int type = value(load.getSegmentType());
		if (type != 0 && type != 1) {
			_problems.add(I18NConstants.ERROR_SEGMENT_TYPE__STEP_TYPE.fill(step.label(), type));
			return;
		}
		Allocation allocation = allocation(step);
		_operations.add(new Operation.LoadControl(step.label(), load.getStateMachine(), object,
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
		Integer object = lsmObject(step, true);
		if (object == null) {
			if (lsm != null && feature(PROPERTY_MAPPED_LSMS) == 1) {
				_operations.add(new Operation.Skip(step.label(), "task segment "
					+ String.format("%04X", value(load.getAddress())) + " of load state machine " + lsm + ": mask "
					+ maskName() + " controls its load state machines through interface objects (PropertyMappedLsms)"
					+ " and has no interface object " + lsm));
			}
			return;
		}
		int address = _taskAddresses.getOrDefault(lsm, (int) value(load.getAddress()));
		int manufacturer = manufacturer(_program);
		int number = value(_program.getApplicationNumber());
		int version = value(_program.getApplicationVersion());
		_operations.add(new Operation.LoadControl(step.label(), lsm, object,
			LoadControls.taskSegment(address, value(_program.getPeiType()), manufacturer, number, version),
			LoadControls.STATE_LOADING));
		_applicationIds.put(lsm, applicationId(_program));
	}

	private void loadCompleted(Step step) {
		Integer lsm = step.step().getStateMachine();
		Integer object = lsmObject(step, false);
		if (object == null) {
			return;
		}
		writeSegments(lsm);
		_operations.add(new Operation.LoadControl(step.label(), lsm, object,
			LoadControls.event(LoadControls.EVENT_LOAD_COMPLETED), LoadControls.STATE_LOADED));
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
				String what = source >= 0 ? blocks.get(source).name() : "zero fill of a stamped segment";
				_operations.add(new Operation.WriteMemory(allocation._step, what, address,
					Arrays.copyOfRange(allocation._content, start, n), _verify));
				_written.set(address, address + n - start);
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
				String.format("download stamp of segment %04X", allocation._address), object, property, start + n, 1,
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
		String what = "inline data";
		if (data == null || data.length == 0) {
			data = null;
			for (PropertyWrite write : _image.getPropertyWrites()) {
				if (Objects.equals(write.objectIndex(), object) && write.propertyId() == property) {
					data = write.value();
					what = "parameters";
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
			for (int n = 0; n < inline.length; n += MAX_CHUNK) {
				_operations.add(new Operation.WriteMemory(step.label(), "inline data", address + n,
					Arrays.copyOfRange(inline, n, Math.min(inline.length, n + MAX_CHUNK)), verify));
			}
			_written.set(address, address + inline.length);
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
			_operations.add(new Operation.WriteMemory(step.label(), block.name(), start,
				Arrays.copyOfRange(block.data(), start - block.address(), n - block.address()), verify));
			_written.set(start, n);
		}
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

	private void checkWritten() {
		if (_image == null) {
			return;
		}
		for (Block block : _image.getBlocks()) {
			for (int n = 0; n < block.data().length; n++) {
				if (block.stateMachine() != null
					|| block.mask()[n] != 0 && !_written.get(block.address() + n) && !_notLoaded.get(block.address() + n)) {
					_problems.add(I18NConstants.ERROR_NOT_WRITTEN__ADDRESS_BLOCK.fill(
						String.format("%04X", block.address() + n), block.name()));
					break;
				}
			}
		}
		for (PropertyWrite write : _image.getPropertyWrites()) {
			if (write.objectIndex() != null && _writtenProperties.contains(List.of(write.objectIndex(), write.propertyId()))) {
				continue;
			}
			String target = write.objectIndex() != null ? "object " + write.objectIndex() : "object type " + write.objectType();
			_operations.add(new Operation.Skip("", "the parameter value " + Operation.hex(write.value()) + " of " + target
				+ " property " + write.propertyId() + ": the load procedure writes no such property"
				+ (Objects.equals(write.objectIndex(), DEVICE_OBJECT) ? " (a property of the device itself, e.g. its hardware type)" : "")));
		}
	}

	// The mask.

	/**
	 * The mask versions the device may have: the program's (a device of a newer mask accepting the
	 * program is not considered).
	 */
	private int[] masks() {
		return new int[] { _mask.getNumber() };
	}

	private int feature(String name) {
		for (MaskFeature feature : _mask.getFeatures()) {
			if (name.equals(feature.getName()) && feature.getValue() != null) {
				return feature.getValue();
			}
		}
		return 0;
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
