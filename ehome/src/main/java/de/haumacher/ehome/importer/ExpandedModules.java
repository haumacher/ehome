/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.HashMap;
import java.util.Map;

import com.top_logic.basic.col.TypedAnnotatable;
import com.top_logic.basic.col.TypedAnnotatable.Property;
import com.top_logic.xio.importer.binding.ImportContext;

/**
 * What the expansion of the dynamic modules (see {@link ModuleExpansion}) found in the program files
 * of an import, kept in its {@link ImportContext} for the handlers reading the programs: the construct
 * that prevented the expansion of a program (see {@link ModulesNotImported}), and the completion of a
 * program an earlier import left without its modules (see {@link CompleteModules}).
 */
final class ExpandedModules {

	/**
	 * The {@link ExpandedModules} of an import in its {@link ImportContext}.
	 */
	static final Property<ExpandedModules> PROPERTY = TypedAnnotatable.property(ExpandedModules.class, "expandedModules");

	private final Map<String, String> _refused = new HashMap<>();

	private final Map<String, ModuleCompletion> _completions = new HashMap<>();

	/**
	 * The {@link ExpandedModules} of the given import, created on first use.
	 */
	static ExpandedModules get(ImportContext context) {
		ExpandedModules result = context.get(PROPERTY);
		if (result == null) {
			result = new ExpandedModules();
			context.set(PROPERTY, result);
		}
		return result;
	}

	/**
	 * Records that the modules of the given program were not expanded because of the given construct.
	 */
	void refused(String program, String construct) {
		_refused.put(program, construct);
	}

	/**
	 * The construct that prevented the expansion of the modules of the given program,
	 * <code>null</code> if the import did not refuse them.
	 */
	String getRefusal(String program) {
		return _refused.get(program);
	}

	/**
	 * Records the completion of a program in the database.
	 */
	void complete(String program, ModuleCompletion completion) {
		_completions.put(program, completion);
	}

	/**
	 * The completion of the given program, <code>null</code> if it is not to be completed.
	 */
	ModuleCompletion getCompletion(String program) {
		return _completions.get(program);
	}

}
