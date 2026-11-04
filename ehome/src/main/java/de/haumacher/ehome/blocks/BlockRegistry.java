/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.blocks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The block types an image may use (homecontrol <code>BlockRegistry</code>): the built-in library
 * of the firmware, which E-Home has as Java ports ({@link #builtins()}), plus what a test adds (the
 * conformance type of <code>spec/blocks.md</code> 15). A type is found by its id and major version
 * (<code>spec/blocks.md</code> B16).
 */
public final class BlockRegistry {

	private static final BlockRegistry BUILTINS =
		new BlockRegistry(
			List.of(RoomController.INSTANCE, HeatingDemand.INSTANCE, HeatingCurve.INSTANCE, SunShading.INSTANCE,
				HouseMode.INSTANCE));

	private final List<BlockType> _types = new ArrayList<>();

	/**
	 * A registry of the given types.
	 */
	public BlockRegistry(List<? extends BlockType> types) {
		for (BlockType type : types) {
			add(type);
		}
	}

	/** The registry of the firmware: its built-in types. */
	public static BlockRegistry builtins() {
		return BUILTINS;
	}

	/**
	 * Adds a type.
	 *
	 * @throws IllegalArgumentException
	 *         When a type with its id and major version is there, or its manifest is invalid.
	 */
	private void add(BlockType type) {
		Manifest m = type.manifest();
		String problem = m.check();
		if (problem != null) {
			throw new IllegalArgumentException(m.type() + " " + m.version() + ": " + problem);
		}
		if (find(m.type(), m.major()) != null) {
			throw new IllegalArgumentException(m.type() + " " + m.major() + " is there already.");
		}
		_types.add(type);
	}

	/** A registry with these types and the given ones. */
	public BlockRegistry with(BlockType... more) {
		List<BlockType> all = new ArrayList<>(_types);
		Collections.addAll(all, more);
		return new BlockRegistry(all);
	}

	/** The types. */
	public List<BlockType> types() {
		return Collections.unmodifiableList(_types);
	}

	/** The type with the id and major version, <code>null</code> if none. */
	public BlockType find(String type, int major) {
		for (BlockType t : _types) {
			if (t.manifest().major() == major && t.manifest().type().equals(type)) {
				return t;
			}
		}
		return null;
	}

	/** Any version of the type, <code>null</code> if none. */
	public BlockType findAny(String type) {
		for (BlockType t : _types) {
			if (t.manifest().type().equals(type)) {
				return t;
			}
		}
		return null;
	}

	/**
	 * The type an image names by id and <code>major.minor</code> version: the same major, at least
	 * that minor; <code>null</code> if none.
	 */
	public BlockType resolve(String type, String version) {
		int[] v = BlockSet.parseVersion(version);
		if (v == null) {
			return null;
		}
		BlockType t = find(type, v[0]);
		return t != null && t.manifest().minor() >= v[1] ? t : null;
	}

}
