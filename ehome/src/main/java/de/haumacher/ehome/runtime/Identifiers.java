/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import com.top_logic.model.TLObject;

import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.runtime.ApplicationDatapoint;
import de.haumacher.ehome.model.runtime.BlockInstance;
import de.haumacher.ehome.model.runtime.DerivedPart;
import de.haumacher.ehome.model.runtime.Function;
import de.haumacher.ehome.model.runtime.Page;
import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.model.runtime.RuleParameter;
import de.haumacher.ehome.model.runtime.RuleState;
import de.haumacher.ehome.model.runtime.RuleTimer;
import de.haumacher.ehome.model.runtime.ScheduleEntry;
import de.haumacher.ehome.model.runtime.TimelinePlan;

/**
 * The attribute <code>identifier</code> of the objects of a project that have one (the id of a rule,
 * of its parts, of a schedule entry, a datapoint, a function, a page), which the model declares on
 * each of these types on its own.
 */
public final class Identifiers {

	private Identifiers() {
		// Static utility.
	}

	/**
	 * The identifier of the given object, <code>null</code> for none set and for an object of a type
	 * without one.
	 */
	public static String of(TLObject obj) {
		if (obj instanceof Rule rule) {
			return rule.getIdentifier();
		}
		if (obj instanceof RuleParameter parameter) {
			return parameter.getIdentifier();
		}
		if (obj instanceof RuleTimer timer) {
			return timer.getIdentifier();
		}
		if (obj instanceof RuleState state) {
			return state.getIdentifier();
		}
		if (obj instanceof TimelinePlan plan) {
			return plan.getIdentifier();
		}
		if (obj instanceof ScheduleEntry entry) {
			return entry.getIdentifier();
		}
		if (obj instanceof ApplicationDatapoint datapoint) {
			return datapoint.getIdentifier();
		}
		if (obj instanceof GroupAddress address) {
			return address.getIdentifier();
		}
		if (obj instanceof BlockInstance instance) {
			return instance.getIdentifier();
		}
		if (obj instanceof Function function) {
			return function.getIdentifier();
		}
		if (obj instanceof Page page) {
			return page.getIdentifier();
		}
		if (obj instanceof DerivedPart part) {
			return part.getIdentifier();
		}
		return null;
	}

	/**
	 * Sets the identifier of the given object.
	 *
	 * @throws IllegalArgumentException
	 *         If the object is of a type without an identifier.
	 */
	public static void set(TLObject obj, String identifier) {
		if (obj instanceof Rule rule) {
			rule.setIdentifier(identifier);
		} else if (obj instanceof RuleParameter parameter) {
			parameter.setIdentifier(identifier);
		} else if (obj instanceof RuleTimer timer) {
			timer.setIdentifier(identifier);
		} else if (obj instanceof RuleState state) {
			state.setIdentifier(identifier);
		} else if (obj instanceof TimelinePlan plan) {
			plan.setIdentifier(identifier);
		} else if (obj instanceof ScheduleEntry entry) {
			entry.setIdentifier(identifier);
		} else if (obj instanceof ApplicationDatapoint datapoint) {
			datapoint.setIdentifier(identifier);
		} else if (obj instanceof GroupAddress address) {
			address.setIdentifier(identifier);
		} else if (obj instanceof BlockInstance instance) {
			instance.setIdentifier(identifier);
		} else if (obj instanceof Function function) {
			function.setIdentifier(identifier);
		} else if (obj instanceof Page page) {
			page.setIdentifier(identifier);
		} else if (obj instanceof DerivedPart part) {
			part.setIdentifier(identifier);
		} else {
			throw new IllegalArgumentException(
				"No identifier in an object of type " + (obj == null ? null : obj.tType()) + ".");
		}
	}

}
