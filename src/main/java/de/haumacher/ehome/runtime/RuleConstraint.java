/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.col.Sink;
import com.top_logic.basic.config.AbstractConfiguredInstance;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.annotate.util.ConstraintCheck;
import com.top_logic.model.form.OverlayLookup;
import com.top_logic.model.util.Pointer;

/**
 * {@link ConstraintCheck} of a field of a rule or schedule entry (haumacher/ehome #57): the field is
 * refused as the controller would refuse it ({@link RuleExport#fieldProblem(Object, String, Object, boolean)}).
 *
 * <p>
 * With {@link Config#isSyntax()} it checks that the text is an expression or literal of the rule
 * language, an error (a text that does not parse is not stored); without, it reports the refusal of
 * the controller's loader as a warning (a rule may be saved half-done). The check depends on every
 * field of the object it belongs to (a value is checked against the datapoint of its action), so a
 * form checks a field again when another field of the object changes.
 * </p>
 */
public class RuleConstraint extends AbstractConfiguredInstance<RuleConstraint.Config<?>> implements ConstraintCheck {

	/**
	 * Configuration of {@link RuleConstraint}.
	 */
	@TagName("rule-constraint")
	public interface Config<I extends RuleConstraint> extends PolymorphicConfiguration<I> {

		/**
		 * Whether the check is the one of the syntax (an error) instead of the controller's
		 * refusals (a warning).
		 */
		@Name("syntax")
		boolean isSyntax();
	}

	/**
	 * Creates a {@link RuleConstraint} from configuration.
	 */
	@CalledByReflection
	public RuleConstraint(InstantiationContext context, Config<?> config) {
		super(context, config);
	}

	@Override
	public ResKey check(TLObject object, TLStructuredTypePart attribute) {
		boolean syntax = getConfig().isSyntax();
		String problem = RuleExport.fieldProblem(object, attribute.getName(), object.tValue(attribute), syntax);
		if (problem == null) {
			return null;
		}
		return syntax ? I18NConstants.RULE_SYNTAX__REASON.fill(problem.replaceFirst("^malformed: ", ""))
			: I18NConstants.RULE_REFUSED__REASON.fill(problem);
	}

	@Override
	public void traceDependencies(TLObject object, TLStructuredTypePart attribute, Sink<Pointer> trace,
			OverlayLookup overlays) {
		for (TLStructuredTypePart part : object.tType().getAllParts()) {
			if (part != attribute) {
				trace.add(Pointer.create(object, part));
			}
		}
	}

	@Override
	public ConstraintType type() {
		return getConfig().isSyntax() ? ConstraintType.ERROR : ConstraintType.WARNING;
	}

}
