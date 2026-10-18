/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

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
 * Constraint of the installer's reporting override of a Zigbee function
 * (<code>ehome.zigbee:ZigbeeFunction#reportingMin</code>, <code>reportingMax</code>,
 * <code>reportingChange</code>): refuses what the controller's loader refuses, with its reason in
 * the user's language ({@link ZigbeeReporting#check(TLObject)}, the check the image validator makes).
 *
 * <p>
 * The refusal is reported at the field of the member it concerns; one that concerns the override as
 * a whole (the function takes none) at every field that has a value. The three fields depend on each
 * other (a maximum below the minimum), so each is checked again when another one changes.
 * </p>
 */
public class ReportingConstraint extends AbstractConfiguredInstance<ReportingConstraint.Config>
		implements ConstraintCheck {

	/**
	 * Configuration of {@link ReportingConstraint}.
	 */
	public interface Config extends PolymorphicConfiguration<ReportingConstraint> {
		// No options.
	}

	private static final String[] MEMBERS = { ZigbeeReporting.MIN, ZigbeeReporting.MAX, ZigbeeReporting.CHANGE };

	/**
	 * Creates a {@link ReportingConstraint} from configuration.
	 */
	@CalledByReflection
	public ReportingConstraint(InstantiationContext context, Config config) {
		super(context, config);
	}

	@Override
	public ResKey check(TLObject object, TLStructuredTypePart attribute) {
		ZigbeeReporting.Refusal refusal = ZigbeeReporting.check(object);
		if (refusal == null) {
			return null;
		}
		String name = attribute.getName();
		if (name.equals(refusal.member().attribute())
			|| (refusal.member() == ZigbeeReporting.Member.WHOLE && object.tValueByName(name) != null)) {
			return refusal.reason();
		}
		return null;
	}

	@Override
	public void traceDependencies(TLObject object, TLStructuredTypePart attribute, Sink<Pointer> trace,
			OverlayLookup overlays) {
		for (String member : MEMBERS) {
			if (!member.equals(attribute.getName())) {
				trace.add(Pointer.create(object, object.tType().getPart(member)));
			}
		}
	}

}
