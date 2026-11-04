/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.blocks;

import java.util.List;

import de.haumacher.ehome.blocks.BlockContext;
import de.haumacher.ehome.blocks.BlockContext.InputValue;
import de.haumacher.ehome.blocks.BlockRegistry;
import de.haumacher.ehome.blocks.BlockType;
import de.haumacher.ehome.blocks.Manifest;
import de.haumacher.ehome.blocks.Manifest.Aggregation;
import de.haumacher.ehome.blocks.Manifest.Field;
import de.haumacher.ehome.blocks.Manifest.InputTrigger;
import de.haumacher.ehome.blocks.Manifest.Param;
import de.haumacher.ehome.blocks.Manifest.ParamType;
import de.haumacher.ehome.blocks.Manifest.Port;
import de.haumacher.ehome.blocks.Manifest.PortType;

/**
 * The conformance type <code>org.homecontrol.conformance</code> of homecontrol
 * <code>spec/blocks.md</code> 15 (B18): a block simple enough to compute by hand that faults on
 * demand, in versions 1.1 and 2.0. It exists only in the test harnesses, as on the device (homecontrol
 * <code>core/test/conformance_block.cpp</code>).
 */
public final class ConformanceBlock extends BlockType {

	/** Version 1.1. */
	public static final ConformanceBlock V1 = new ConformanceBlock(false);

	/** Version 2.0. */
	public static final ConformanceBlock V2 = new ConformanceBlock(true);

	/** The registry of the corpus: the built-in types and both versions of the conformance type. */
	public static final BlockRegistry CORPUS_REGISTRY = BlockRegistry.builtins().with(V1, V2);

	private static final int X = 0;

	private static final int ARM = 1;

	private static final int Y = 3;

	private static final int PULSE = 4;

	private static final int CALLS = 5;

	private static final int TICKS = 6;

	private static final int GAIN = 0;

	private static final int OFFSET = 1;

	private static final int FAULT_ABOVE = 2;

	private static final int FOURTH = 3;

	private static final int COUNT = 0;

	private static final int TICKS_FIELD = 1;

	private static final int PULSE_ON = 2;

	private final Manifest _manifest;

	private final boolean _second;

	private ConformanceBlock(boolean second) {
		_second = second;
		List<Port> ports = List.of(
			Port.in("x", PortType.REAL, "x", "", true, 4, List.of(Aggregation.MEAN, Aggregation.MIN, Aggregation.MAX),
				InputTrigger.CHANGE),
			Port.in("arm", PortType.BOOLEAN, "arm", "", false, 1, List.of(), InputTrigger.CHANGE),
			Port.in("tick", PortType.BOOLEAN, "tick", "", false, 1, List.of(), InputTrigger.UPDATE),
			Port.out("y", PortType.REAL, "y", "", -1, null, null),
			Port.out("pulse", PortType.BOOLEAN, "pulse", "", 0, null, null),
			Port.diagnostic("calls", PortType.INTEGER, "calls", "", null, null),
			Port.diagnostic("ticks", PortType.INTEGER, "ticks", "", null, null));
		List<Param> params = List.of(
			new Param("gain", ParamType.REAL, "gain", "", 1, Double.valueOf(0), Double.valueOf(second ? 5 : 10), 0,
				List.of(), true),
			new Param("offset", ParamType.REAL, "offset", "", 0, Double.valueOf(-100), Double.valueOf(100), 0, List.of(),
				false),
			new Param("faultAbove", ParamType.REAL, "faultAbove", "", 1000, null, null, 0, List.of(), false),
			second
				? new Param("level", ParamType.INTEGER, "level", "", 0, Double.valueOf(0), Double.valueOf(9), 0,
					List.of(), true)
				: new Param("delay", ParamType.DURATION, "delay", "ms", 5000, Double.valueOf(100), Double.valueOf(60000),
					0, List.of(), true));
		_manifest = new Manifest("org.homecontrol.conformance", second ? 2 : 1, second ? 0 : 1, "Conformance",
			"spec/blocks.md 15", ports, params,
			List.of(new Field("count", true, 0), new Field("ticks", true, 0), new Field("pulseOn", false, 0)),
			List.of("t"), 10000, 60000);
	}

	@Override
	public Manifest manifest() {
		return _manifest;
	}

	private static void update(BlockContext cx) {
		InputValue x = cx.read(X);
		if (x.good()) {
			if (x.value() > cx.param(FAULT_ABOVE)) {
				cx.fault("x above faultAbove");
			} else {
				cx.write(Y, cx.param(GAIN) * x.value() + cx.param(OFFSET));
			}
		}
		cx.write(CALLS, cx.state()[COUNT]);
	}

	@Override
	public void start(BlockContext cx, StartReason reason, boolean restored) {
		cx.state()[COUNT] += 1;
		update(cx);
	}

	@Override
	public void input(BlockContext cx, int port) {
		cx.state()[COUNT] += 1;
		if (port == ARM) {
			if (cx.read(ARM).isTrue()) {
				cx.setTimer(0, _second ? 5000 : (long) cx.param(FOURTH));
			} else {
				cx.cancelTimer(0);
			}
		}
		update(cx);
	}

	@Override
	public void timer(BlockContext cx, int timer) {
		double[] s = cx.state();
		s[COUNT] += 1;
		s[PULSE_ON] = 1 - s[PULSE_ON];
		cx.write(PULSE, s[PULSE_ON]);
		cx.write(CALLS, s[COUNT]);
	}

	@Override
	public void period(BlockContext cx) {
		double[] s = cx.state();
		s[COUNT] += 1;
		s[TICKS_FIELD] += 1;
		cx.write(TICKS, s[TICKS_FIELD]);
		cx.write(CALLS, s[COUNT]);
	}

	@Override
	public void parameter(BlockContext cx, int index) {
		cx.state()[COUNT] += 1;
		update(cx);
	}

}
