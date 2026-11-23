/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.top_logic.model.TLObject;

import de.haumacher.ehome.model.operational.Allocator;
import de.haumacher.ehome.model.operational.CodeSegment;
import de.haumacher.ehome.model.operational.DateType;
import de.haumacher.ehome.model.operational.EhomeOperationalFactory;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.FloatingPointType;
import de.haumacher.ehome.model.operational.IPAddressType;
import de.haumacher.ehome.model.operational.Location;
import de.haumacher.ehome.model.operational.ModuleArgument;
import de.haumacher.ehome.model.operational.ModuleDefinition;
import de.haumacher.ehome.model.operational.NumericType;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterType;
import de.haumacher.ehome.model.operational.PlacementArgument;
import de.haumacher.ehome.model.operational.Pointer;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.TextType;
import de.haumacher.ehome.model.operational.TimeType;
import de.haumacher.ehome.modules.InstanceInfo;
import de.haumacher.ehome.programming.DownloadImage.Block;

/**
 * The memory every parameter of a program can place its value in (haumacher/ehome #223): the
 * parameters of the program and of its module definitions, every member of a union, in every module
 * instance the program can have, whatever the values of a device say is in effect.
 *
 * <p>
 * A read-out reads these bytes besides those its download image writes, so that one read-out holds
 * the value of every parameter a take-over may find in effect: a device whose values switch on
 * parameters the project's values switch off (the filter tables of an IP router) is decoded
 * completely from one read-out.
 * </p>
 *
 * <p>
 * The offset of a parameter of a module lies in the argument its location names
 * ({@link Location#getBaseOffset()}); the values that argument can take are those of its placements:
 * a fixed value, or every value of an allocator from its start to its limit in the steps of the
 * arguments drawing from it, each plus the values of the enclosing module's argument a placement names
 * as its base. An allocator without a limit reaches to the end of the memory; only the parts of
 * segments a download writes are read anyway ({@link #readMask(Block, BitSet)}).
 * </p>
 */
public final class ParameterMemory {

	/** The size of the address space of the memory. */
	private static final int MEMORY_SIZE = 0x10000;

	private final BitSet _memory = new BitSet();

	private final Map<ModuleArgument, Set<Long>> _values = new HashMap<>();

	private final Set<ModuleArgument> _computing = new HashSet<>();

	private ParameterMemory() {
		// Built by of().
	}

	/**
	 * The addresses of the bytes of the memory (absolute segments) any parameter of the given program
	 * can place its value in.
	 */
	public static BitSet of(Program program) {
		ParameterMemory result = new ParameterMemory();
		if (program != null) {
			result.parameters(program.getParameters());
			Set<ModuleDefinition> seen = new HashSet<>();
			for (ModuleDefinition module : program.getModules()) {
				result.module(module, seen);
			}
		}
		return result._memory;
	}

	/**
	 * The bytes of the given block a read-out reads: those its mask writes and those of the given
	 * parameter memory ({@link #of(Program)}) within it. A block of a relative segment is not read.
	 */
	public static byte[] readMask(Block block, BitSet parameters) {
		byte[] mask = block.mask().clone();
		if (block.stateMachine() != null) {
			return mask;
		}
		int start = block.address();
		for (int address = parameters.nextSetBit(start); address >= 0 && address < start + mask.length;
				address = parameters.nextSetBit(address + 1)) {
			if (mask[address - start] == 0) {
				mask[address - start] = (byte) 0xFF;
			}
		}
		return mask;
	}

	/**
	 * The number of bytes of the given block {@link #readMask(Block, BitSet)} reads.
	 */
	public static int readCount(Block block, BitSet parameters) {
		int result = 0;
		for (byte bits : readMask(block, parameters)) {
			if (bits != 0) {
				result++;
			}
		}
		return result;
	}

	/**
	 * The size in bits of the values of a parameter type, as {@link ParameterEncoding} writes them,
	 * <code>-1</code> if it is not known.
	 */
	public static int bits(ParameterType type) {
		if (type instanceof EnumerationType enumeration) {
			return enumeration.getBits();
		}
		if (type instanceof NumericType numeric) {
			return numeric.getBits();
		}
		if (type instanceof TimeType time) {
			return time.getBits();
		}
		if (type instanceof TextType text) {
			return text.getBits();
		}
		if (type instanceof FloatingPointType floating) {
			String encoding = floating.getEncoding() == null ? "DPT9" : floating.getEncoding().getName();
			return switch (encoding) {
				case "IEEE754Single" -> 32;
				case "IEEE754Double" -> 64;
				default -> 16;
			};
		}
		if (type instanceof DateType) {
			return 24;
		}
		if (type instanceof IPAddressType) {
			return 32;
		}
		return -1;
	}

	private void module(ModuleDefinition module, Set<ModuleDefinition> seen) {
		if (!seen.add(module)) {
			return;
		}
		parameters(module.getParameters());
		for (ModuleDefinition sub : module.getModules()) {
			module(sub, seen);
		}
	}

	private void parameters(Collection<? extends Parameter> parameters) {
		for (Parameter parameter : parameters) {
			Location location = parameter.getAddress();
			if (!(location instanceof Pointer pointer)
				|| !(pointer.getSegment() instanceof CodeSegment segment) || segment.getAddress() == null) {
				continue;
			}
			Set<Long> bases = location.getBaseOffset() == null ? Set.of(Long.valueOf(0)) : values(location.getBaseOffset());
			int size = parameter.getType() == null ? -1 : bits(parameter.getType());
			for (Long base : bases) {
				long start = segment.getAddress().longValue() + location.getByteOffset() + base.longValue();
				if (location.getBits() != null) {
					// The union the parameter is a member of.
					mark(start, location.getBitOffset(), location.getBits().intValue());
				}
				if (size > 0) {
					mark(start + value(parameter.getUnionOffset()),
						location.getBitOffset() + value(parameter.getUnionBitOffset()), size);
				}
			}
		}
	}

	private void mark(long address, int bitOffset, int bits) {
		long first = address + Math.floorDiv(bitOffset, 8);
		long end = address + Math.floorDiv(bitOffset + bits + 7, 8);
		if (end <= first) {
			return;
		}
		_memory.set((int) Math.max(0, Math.min(first, MEMORY_SIZE)), (int) Math.max(0, Math.min(end, MEMORY_SIZE)));
	}

	/**
	 * The values the given argument of a module definition can take in an instance.
	 */
	private Set<Long> values(ModuleArgument argument) {
		Set<Long> result = _values.get(argument);
		if (result != null) {
			return result;
		}
		if (!_computing.add(argument)) {
			// A cycle of bases: no further values.
			return Set.of();
		}
		result = new TreeSet<>();
		for (TLObject referer : argument.tReferers(EhomeOperationalFactory.getArgumentPlacementArgumentAttr())) {
			if (!(referer instanceof PlacementArgument placement)) {
				continue;
			}
			Set<Long> own = own(placement);
			if (placement.getBase() == null) {
				result.addAll(own);
				continue;
			}
			Set<Long> bases = values(placement.getBase());
			for (Long value : own) {
				for (Long base : bases) {
					long sum = value.longValue() + base.longValue();
					if (sum >= 0 && sum < MEMORY_SIZE) {
						result.add(Long.valueOf(sum));
					}
				}
			}
		}
		_computing.remove(argument);
		_values.put(argument, result);
		return result;
	}

	/**
	 * The values a placement gives its argument, without the base of the enclosing instance.
	 */
	private static Set<Long> own(PlacementArgument placement) {
		Allocator allocator = placement.getAllocator();
		if (allocator == null) {
			return Set.of(Long.valueOf(InstanceInfo.parse(placement.getValue())));
		}
		long start = allocator.getStart() == null ? 0 : allocator.getStart().longValue();
		long max = allocator.getMaxInclusive() == null ? MEMORY_SIZE - 1 : allocator.getMaxInclusive().longValue();
		long step = step(allocator);
		Set<Long> result = new TreeSet<>();
		for (long value = start; value <= max && value < MEMORY_SIZE; value += step) {
			result.add(Long.valueOf(value));
		}
		return result;
	}

	/**
	 * The step every value of an allocator is a multiple of from its start: the greatest common
	 * divisor of what the arguments drawing from it allocate.
	 */
	private static long step(Allocator allocator) {
		long result = 0;
		for (TLObject referer : allocator.tReferers(EhomeOperationalFactory.getAllocatorPlacementArgumentAttr())) {
			if (!(referer instanceof PlacementArgument placement) || placement.getArgument() == null) {
				continue;
			}
			Long allocates = placement.getArgument().getAllocates();
			result = gcd(result, allocates == null ? 1 : Math.abs(allocates.longValue()));
		}
		return result <= 0 ? 1 : result;
	}

	private static long gcd(long a, long b) {
		return b == 0 ? a : gcd(b, a % b);
	}

	private static int value(Integer value) {
		return value == null ? 0 : value.intValue();
	}

}
