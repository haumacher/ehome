/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;
import com.top_logic.model.util.TLModelNamingConvention;

import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.ProgramRequirement;

/**
 * What an application program needs of ETS besides its product data (ETS: the attributes of
 * <code>Static/Extension</code>, haumacher/ehome #159): a plug-in of the manufacturer for the user
 * interface (<code>EtsUiPlugin</code>), one doing the download (<code>EtsDownloadPlugin</code>), a
 * handler of the program's data (<code>EtsDataHandler</code>), or software outside ETS
 * (<code>RequiresExternalSoftware</code>), stored as <code>Program#requirements</code>.
 *
 * <p>
 * E-Home runs none of them; the plug-in's files stay in the baggage of the manufacturer. With a user
 * interface plug-in only, a device of the program is edited and downloaded as the product data declare
 * it, and the notice says that the plug-in offers functions E-Home does not have. With any other
 * requirement, a device of the program is planned, parameterized and connected, but its download is
 * refused naming the requirements ({@link #downloadProblem(Program)}, a problem of the download plan);
 * the comparison with the device only reads it and stays allowed.
 * </p>
 *
 * <p>
 * The TL-Script function <code>programRequirementNotice(x)</code> (registered in
 * <code>ehomeConf.config.xml</code>) gives the notice of a program, a program assignment of a product,
 * or a device; <code>null</code> where the program needs nothing.
 * </p>
 */
public class PluginRequirements extends GenericMethod {

	/** The reference of a program to its requirements. */
	public static final String REQUIREMENTS = "requirements";

	private static final String PROGRAM = "program";

	/**
	 * Creates a {@link PluginRequirements}.
	 */
	protected PluginRequirements(String name, SearchExpression[] arguments) {
		super(name, arguments);
	}

	/**
	 * The names of the classifiers of {@link ProgramRequirement} that the attributes of an
	 * <code>Extension</code> element of a program declare.
	 *
	 * @param attributes
	 *        The value of the attribute of the given name, <code>null</code> if it is missing.
	 */
	public static Set<String> fromExtension(UnaryOperator<String> attributes) {
		Set<String> result = new LinkedHashSet<>();
		if (present(attributes.apply("EtsUiPlugin"))) {
			result.add(ProgramRequirement.USER_INTERFACE_PLUGIN_CLASSIFIER);
		}
		if (present(attributes.apply("EtsDownloadPlugin"))) {
			result.add(ProgramRequirement.DOWNLOAD_PLUGIN_CLASSIFIER);
		}
		if (present(attributes.apply("EtsDataHandler"))) {
			result.add(ProgramRequirement.DATA_HANDLER_CLASSIFIER);
		}
		String external = attributes.apply("RequiresExternalSoftware");
		if (external != null && ("true".equals(external.trim()) || "1".equals(external.trim()))) {
			result.add(ProgramRequirement.EXTERNAL_SOFTWARE_CLASSIFIER);
		}
		return result;
	}

	private static boolean present(String value) {
		return value != null && !value.isBlank();
	}

	/**
	 * The given requirements of a program together with the classifiers of the given names, in the
	 * order of the enumeration.
	 */
	public static Set<TLClassifier> union(Collection<?> requirements, Collection<String> names) {
		Set<TLClassifier> result = new LinkedHashSet<>();
		for (TLClassifier classifier : ProgramRequirement.type().getClassifiers()) {
			if (names.contains(classifier.getName()) || requirements != null && requirements.contains(classifier)) {
				result.add(classifier);
			}
		}
		return result;
	}

	/**
	 * The requirements of the given program, in the order of the enumeration.
	 */
	public static List<TLClassifier> requirements(Program program) {
		List<TLClassifier> result = new ArrayList<>();
		if (program == null) {
			return result;
		}
		Collection<?> stored = (Collection<?>) program.tValueByName(REQUIREMENTS);
		if (stored == null || stored.isEmpty()) {
			return result;
		}
		TLEnumeration type = ProgramRequirement.type();
		for (TLClassifier classifier : type.getClassifiers()) {
			if (stored.contains(classifier)) {
				result.add(classifier);
			}
		}
		return result;
	}

	/**
	 * Whether the given requirements make E-Home refuse the download: anything but a user interface
	 * plug-in.
	 */
	public static boolean refusesDownload(Collection<TLClassifier> requirements) {
		for (TLClassifier requirement : requirements) {
			if (!ProgramRequirement.USER_INTERFACE_PLUGIN_CLASSIFIER.equals(requirement.getName())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Why a download of the given program is refused for its requirements, <code>null</code> if they
	 * allow it.
	 */
	public static ResKey downloadProblem(Program program) {
		List<TLClassifier> requirements = requirements(program);
		if (!refusesDownload(requirements)) {
			return null;
		}
		return I18NConstants.ERROR_DOWNLOAD_REQUIRES__REQUIREMENTS.fill(names(requirements));
	}

	/**
	 * The notice saying what the given program needs of ETS and what E-Home does with it,
	 * <code>null</code> if it needs nothing.
	 */
	public static ResKey notice(Program program) {
		List<TLClassifier> requirements = requirements(program);
		if (requirements.isEmpty()) {
			return null;
		}
		if (refusesDownload(requirements)) {
			return I18NConstants.NOTICE_REQUIRES_NO_DOWNLOAD__REQUIREMENTS.fill(names(requirements));
		}
		return I18NConstants.NOTICE_REQUIRES_USER_INTERFACE_PLUGIN__REQUIREMENTS.fill(names(requirements));
	}

	/**
	 * The labels of the given requirements as a list ("Download plug-in, Data handler"), in the
	 * language the text is shown in.
	 */
	public static ResKey names(List<TLClassifier> requirements) {
		ResKey result = null;
		for (TLClassifier requirement : requirements) {
			ResKey name = TLModelNamingConvention.classifierKey(requirement);
			result = result == null ? name : I18NConstants.LIST__FIRST_NEXT.fill(result, name);
		}
		return result;
	}

	/**
	 * The program of the given program, program assignment of a product or device; <code>null</code>
	 * for anything else.
	 */
	public static Program program(Object obj) {
		Object current = obj;
		for (int n = 0; n < 3; n++) {
			if (current instanceof Program program) {
				return program;
			}
			if (!(current instanceof TLObject holder) || holder.tType().getPart(PROGRAM) == null) {
				return null;
			}
			current = holder.tValueByName(PROGRAM);
		}
		return null;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new PluginRequirements(getName(), arguments);
	}

	@Override
	public TLType getType(List<TLType> argumentTypes) {
		return null;
	}

	@Override
	public boolean isSideEffectFree() {
		return true;
	}

	@Override
	public boolean canEvaluateAtCompileTime(Object[] arguments) {
		return false;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		return notice(program(arguments[0]));
	}

	/**
	 * Builder of the function <code>programRequirementNotice(x)</code>.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<PluginRequirements> {

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
		}

		@Override
		public PluginRequirements build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkSingleArg(expr, args);
			return new PluginRequirements(getConfig().getName(), args);
		}
	}

}
