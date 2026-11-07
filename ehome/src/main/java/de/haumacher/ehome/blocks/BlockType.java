/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.blocks;

import com.top_logic.basic.util.ResKey;

/**
 * A block type (homecontrol <code>spec/blocks.md</code> 3.1): its {@link Manifest} and its calls. A
 * type keeps no per-instance data: everything an instance needs between calls is in its state
 * record, which the {@link BlockRuntime} owns. A port of homecontrol
 * <code>core/include/hc/blocks/block_type.h</code>.
 */
public abstract class BlockType {

	/**
	 * A refusal of a cross-parameter check (<code>spec/blocks.md</code> B17).
	 *
	 * @param reason
	 *        Why the values are refused, as the controller gives it (English).
	 * @param message
	 *        The same for the user.
	 * @param parameter
	 *        The index of the parameter to blame, -1 for none.
	 */
	public record Blame(String reason, ResKey message, int parameter) {
		// A value.
	}

	/** The manifest. */
	public abstract Manifest manifest();

	/**
	 * Cross-parameter checks at load: <code>null</code>, or why the values (one per manifest
	 * parameter) are refused.
	 */
	public Blame check(double[] params) {
		return null;
	}

	/** The instance starts. */
	public abstract void start(BlockContext cx, StartReason reason, boolean restored);

	/** An update of an input port's binding, per the port's trigger. */
	public abstract void input(BlockContext cx, int port);

	/** One of the instance's timers expired. */
	public abstract void timer(BlockContext cx, int timer);

	/** The type's period elapsed. */
	public abstract void period(BlockContext cx);

	/** An adjustable parameter changed. */
	public abstract void parameter(BlockContext cx, int index);

	/** Why an instance starts (<code>spec/blocks.md</code> 3.1). */
	public enum StartReason {
		/** The controller started. */
		BOOT("boot"),
		/** A new image. */
		IMAGE("image"),
		/** The enable switch was turned on. */
		ENABLE("enable"),
		/** A restart after a fault. */
		RESTART("restart");

		private final String _name;

		StartReason(String name) {
			_name = name;
		}

		/** The name of the specification. */
		public String externalName() {
			return _name;
		}
	}

}
