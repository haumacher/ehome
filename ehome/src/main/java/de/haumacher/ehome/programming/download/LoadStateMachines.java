/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import com.top_logic.basic.util.ResKey;

import de.haumacher.ehome.model.operational.MaskConfiguration;
import de.haumacher.ehome.model.operational.MaskResource;
import de.haumacher.ehome.model.operational.MaskVersion;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.ResourceLocation;

/**
 * How the load state machines of a mask are controlled, from the mask's master data.
 *
 * <ul>
 * <li>Through properties (feature {@value #PROPERTY_MAPPED_LSMS} 1, mask 7.5): load state machine
 * <i>n</i> is the property {@link LoadControls#PID_LOAD_STATE_CONTROL} of the interface object
 * <i>n</i>.</li>
 * <li>In memory (the feature 0, mask 7.1, BIM M112): load state machine <i>n</i> (the object type of
 * the loaded part) has the resources <code>&lt;part&gt;LoadControl</code> and
 * <code>&lt;part&gt;LoadStatus</code> of the flavour {@value #MEMORY_FLAVOUR} in the standard memory,
 * the part being <code>GroupAddressTable</code> (1), <code>GroupAssociationTable</code> (2),
 * <code>Application</code> (3) and <code>Peiprog</code> (4). The load control is written as a
 * {@link LoadControls#memoryRecord(int, byte[]) memory record} into the address of the first, the load
 * state is the byte at the address of the second. The addresses are the mask's (0104 and B6EA-B6ED
 * on mask 7.1).</li>
 * </ul>
 */
public final class LoadStateMachines {

	/** The feature of a mask whose load state machines are controlled through properties. */
	public static final String PROPERTY_MAPPED_LSMS = MaskVersion.PROPERTY_MAPPED_LSMS;

	/** The flavour of the resources of a load state machine controlled in memory (BIM M112). */
	public static final String MEMORY_FLAVOUR = "LoadControl_M112";

	/** The address space of a resource in the standard memory. */
	private static final String STANDARD_MEMORY = "StandardMemory";

	/** The parts loaded by the load state machines 1 to 4, as the mask's resources name them. */
	private static final String[] PARTS = { "GroupAddressTable", "GroupAssociationTable", "Application", "Peiprog" };

	/**
	 * A load state machine controlled in memory.
	 *
	 * @param stateMachine
	 *        The load state machine (the object type of its part, 1 to 4).
	 * @param control
	 *        The address of its load control.
	 * @param status
	 *        The address of its load state (one byte).
	 */
	public record InMemory(int stateMachine, int control, int status) {
		// A value.
	}

	private LoadStateMachines() {
		// Static helpers.
	}

	/**
	 * Whether the load state machines of the mask configuration are controlled through properties;
	 * <code>false</code> for no configuration.
	 *
	 * @see MaskVersion#getConfiguration(Program)
	 */
	public static boolean propertyMapped(MaskConfiguration configuration) {
		return MaskVersion.propertyMapped(configuration);
	}

	/**
	 * The address of the association table the device names in memory: the pointer
	 * <code>GroupAssociationTablePtr</code> of a mask keeping it there (BCU 1, flavour
	 * <code>Ptr_StandardMemory100</code>: one byte, the address minus 0100). <code>null</code> if the
	 * mask keeps no such pointer or the byte was not read (haumacher/ehome #222).
	 *
	 * @param memory
	 *        The memory read, valid where <code>known</code> is set.
	 */
	public static Integer associationTablePointer(Program program, byte[] memory, BitSet known) {
		MaskVersion mask = program == null ? null : program.getMask();
		if (mask == null) {
			return null;
		}
		for (MaskResource resource : mask.getResources(program)) {
			ResourceLocation location = resource.getLocation();
			if ("GroupAssociationTablePtr".equals(resource.getName()) && "Ptr_StandardMemory100".equals(resource.getFlavour())
				&& location != null && location.getAddressSpace() != null
				&& "StandardMemory".equals(location.getAddressSpace().getName()) && location.getStartAddress() != null) {
				int address = location.getStartAddress().intValue();
				return known.get(address) ? Integer.valueOf(0x100 + (memory[address] & 0xFF)) : null;
			}
		}
		return null;
	}

	/**
	 * The load state machine of the mask configuration controlled in memory, <code>null</code> if the
	 * configuration controls its load state machines through properties or names no load control and
	 * load state in memory for it, or if there is no configuration.
	 */
	public static InMemory inMemory(MaskConfiguration mask, int stateMachine) {
		if (mask == null || propertyMapped(mask) || stateMachine < 1 || stateMachine > PARTS.length) {
			return null;
		}
		String part = PARTS[stateMachine - 1];
		Long control = memoryAddress(mask, part + "LoadControl");
		Long status = memoryAddress(mask, part + "LoadStatus");
		if (control == null || status == null) {
			return null;
		}
		return new InMemory(stateMachine, control.intValue(), status.intValue());
	}

	/**
	 * The load state machines of the mask configuration controlled in memory, in their order.
	 */
	public static List<InMemory> inMemory(MaskConfiguration mask) {
		List<InMemory> result = new ArrayList<>();
		for (int stateMachine = 1; stateMachine <= PARTS.length; stateMachine++) {
			InMemory lsm = inMemory(mask, stateMachine);
			if (lsm != null) {
				result.add(lsm);
			}
		}
		return result;
	}

	/**
	 * Reads the load states of a device of the given mask configuration, for a person: per load state machine
	 * "Load state machine <i>n</i>: <i>state</i>", or why it could not be read. Through properties the
	 * load state machines 1 to 3 (address table, association table, application), in memory every one
	 * the mask names. Nothing for a mask without load state machines.
	 */
	public static List<ResKey> readStates(MaskConfiguration mask, DeviceReader reader) {
		List<ResKey> result = new ArrayList<>();
		if (propertyMapped(mask)) {
			for (int lsm = 1; lsm <= 3; lsm++) {
				ResKey state;
				try {
					byte[] value = reader.readProperty(lsm, LoadControls.PID_LOAD_STATE_CONTROL, 1, 1);
					state = value.length == 0 ? I18NConstants.FACT_NO_ANSWER : LoadControls.stateName(value[0] & 0xFF);
				} catch (BusException ex) {
					state = ex.getErrorKey();
				}
				result.add(I18NConstants.FACT_LOAD_STATE__LSM_STATE.fill(lsm, state));
			}
			return result;
		}
		for (InMemory lsm : inMemory(mask)) {
			ResKey state;
			try {
				byte[] value = reader.readMemory(lsm.status(), 1);
				state = value.length == 0 ? I18NConstants.FACT_NO_ANSWER : LoadControls.stateName(value[0] & 0xFF);
			} catch (BusException ex) {
				state = ex.getErrorKey();
			}
			result.add(I18NConstants.FACT_LOAD_STATE__LSM_STATE.fill(lsm.stateMachine(), state));
		}
		return result;
	}

	private static Long memoryAddress(MaskConfiguration mask, String name) {
		for (MaskResource resource : mask.getResources()) {
			ResourceLocation location = resource.getLocation();
			if (name.equals(resource.getName()) && MEMORY_FLAVOUR.equals(resource.getFlavour()) && location != null
				&& location.getAddressSpace() != null && STANDARD_MEMORY.equals(location.getAddressSpace().getName())
				&& location.getStartAddress() != null) {
				return location.getStartAddress();
			}
		}
		return null;
	}

}
