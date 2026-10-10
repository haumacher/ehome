/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.col.Sink;
import com.top_logic.basic.config.AbstractConfiguredInstance;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.annotate.util.ConstraintCheck;
import com.top_logic.model.form.OverlayLookup;
import com.top_logic.model.util.Pointer;

/**
 * Constraint keeping group addresses and group ranges within the range of the group range that
 * contains them, and a range's start before its end.
 *
 * <p>
 * Annotated to <code>GroupAddress#address</code>, <code>GroupRange#rangeStart</code> and
 * <code>GroupRange#rangeEnd</code>. An object that is not (yet) contained in a group range is only
 * checked for the order of its own bounds.
 * </p>
 */
public class WithinGroupRange extends AbstractConfiguredInstance<WithinGroupRange.Config>
		implements ConstraintCheck {

	/**
	 * Configuration of {@link WithinGroupRange}.
	 */
	public interface Config extends PolymorphicConfiguration<WithinGroupRange> {
		// No options.
	}

	private static final String RANGE_START = "rangeStart";

	private static final String RANGE_END = "rangeEnd";

	/**
	 * Creates a {@link WithinGroupRange} from configuration.
	 */
	@CalledByReflection
	public WithinGroupRange(InstantiationContext context, Config config) {
		super(context, config);
	}

	@Override
	public ResKey check(TLObject object, TLStructuredTypePart attribute) {
		Integer value = number(object.tValueByName(attribute.getName()));
		if (value == null) {
			return null;
		}

		if (isRangeBound(attribute)) {
			Integer start = number(object.tValueByName(RANGE_START));
			Integer end = number(object.tValueByName(RANGE_END));
			if (start != null && end != null && start > end) {
				return I18NConstants.ERROR_RANGE_REVERSED__START_END.fill(display(object, RANGE_START),
					display(object, RANGE_END));
			}
		}

		TLObject range = enclosingRange(object);
		if (range == null) {
			return null;
		}
		Integer start = number(range.tValueByName(RANGE_START));
		Integer end = number(range.tValueByName(RANGE_END));
		if (start == null || end == null) {
			return null;
		}
		if (value < start || value > end) {
			return I18NConstants.ERROR_ADDRESS_OUTSIDE_RANGE__ADDRESS_START_END.fill(
				display(object, attribute.getName()), display(range, RANGE_START), display(range, RANGE_END));
		}
		return null;
	}

	@Override
	public void traceDependencies(TLObject object, TLStructuredTypePart attribute, Sink<Pointer> trace,
			OverlayLookup overlays) {
		if (isRangeBound(attribute)) {
			String other = RANGE_START.equals(attribute.getName()) ? RANGE_END : RANGE_START;
			trace.add(Pointer.create(object, object.tType().getPart(other)));
		}
		TLObject range = enclosingRange(object);
		if (range != null) {
			trace.add(Pointer.create(range, range.tType().getPart(RANGE_START)));
			trace.add(Pointer.create(range, range.tType().getPart(RANGE_END)));
		}
	}

	private static boolean isRangeBound(TLStructuredTypePart attribute) {
		return RANGE_START.equals(attribute.getName()) || RANGE_END.equals(attribute.getName());
	}

	private static TLObject enclosingRange(TLObject object) {
		TLObject container = object.tContainer();
		if (container == null || container.tType().getPart(RANGE_START) == null) {
			return null;
		}
		return container;
	}

	private static Integer number(Object value) {
		if (value instanceof GA ga) {
			return ga.getValue();
		}
		if (value instanceof Number number) {
			return number.intValue();
		}
		return null;
	}

	private static String display(TLObject object, String attribute) {
		return String.valueOf(object.tValueByName(attribute));
	}

}
