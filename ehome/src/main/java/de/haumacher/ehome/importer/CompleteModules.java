/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.xml.XMLStreamUtil;
import com.top_logic.model.TLObject;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.Handler;
import com.top_logic.xio.importer.handlers.NestedImportHandler;

/**
 * Completes a known application program (the <code>this</code> of an <code>&lt;update&gt;</code> of
 * <code>&lt;unless-known&gt;</code>) that an earlier import left without its dynamic modules
 * (haumacher/ehome #119), if the scan of the file planned it (see {@link ModuleCompletion}), or without
 * its parameter calculations (haumacher/ehome #123, <code>calculationsRead</code> not set) or without its
 * parameter validations (haumacher/ehome #124, <code>validationsRead</code> not set); otherwise applies its
 * content handlers (the update of a known program).
 *
 * <p>
 * The completion reads what the earlier import skipped; the rest of the program exists: the
 * allocators of the program ({@link Config#getAllocators()}), its module definitions
 * ({@link Config#getModuleDefs()}), and each <code>Module</code> and <code>Repeat</code> of the dynamic
 * part (not nested in another one, {@link Config#getModule()}, {@link Config#getRepeat()}) into the
 * structure element its plan names, moved to its position there. Then the mark
 * <code>usesModules</code> is removed, and the import reports the completion. The calculations of the
 * program and of its module definitions ({@link Config#getCalculations()}) and their scripts are read
 * into the program and its module definitions (those the module completion creates read their
 * calculations themselves), and <code>calculationsRead</code> is set. Likewise the validations
 * ({@link Config#getValidations()}), and <code>validationsRead</code> is set.
 * </p>
 */
public class CompleteModules<C extends CompleteModules.Config<?>> extends NestedImportHandler<C> {

	private static final String CONTENTS = "contents";

	private static final String STATIC = "Static";

	private static final String DYNAMIC = "Dynamic";

	private static final String USES_MODULES = "usesModules";

	/**
	 * Configuration of {@link CompleteModules}.
	 */
	@TagName("complete-modules")
	public interface Config<I extends CompleteModules<?>> extends NestedImportHandler.Config<I> {

		/**
		 * The handler reading the allocators of the program (the element <code>Allocators</code>).
		 */
		@Name("allocators")
		List<PolymorphicConfiguration<? extends Handler>> getAllocators();

		/**
		 * The handler reading the module definitions (the element <code>ModuleDefs</code>).
		 */
		@Name("module-defs")
		List<PolymorphicConfiguration<? extends Handler>> getModuleDefs();

		/**
		 * The handler reading a placement of a module (the element <code>Module</code>) into a
		 * structure element.
		 */
		@Name("module")
		List<PolymorphicConfiguration<? extends Handler>> getModule();

		/**
		 * The handler reading a repeated part (the element <code>Repeat</code>) into a structure
		 * element.
		 */
		@Name("repeat")
		List<PolymorphicConfiguration<? extends Handler>> getRepeat();

		/**
		 * The handler reading the calculations (the content of the element
		 * <code>ParameterCalculations</code>) into a program or module definition.
		 */
		@Name("calculations")
		List<PolymorphicConfiguration<? extends Handler>> getCalculations();

		/**
		 * The handler reading the validations (the content of the element
		 * <code>ParameterValidations</code>) into a program or module definition.
		 */
		@Name("validations")
		List<PolymorphicConfiguration<? extends Handler>> getValidations();

	}

	private final Handler _allocators;

	private final Handler _moduleDefs;

	private final Handler _module;

	private final Handler _repeat;

	private final Handler _calculations;

	private final Handler _validations;

	private static final String CALCULATIONS_READ = "calculationsRead";

	private static final String VALIDATIONS_READ = "validationsRead";

	private static final String MODULE_DEF = "ModuleDef";

	/**
	 * Creates a {@link CompleteModules} from configuration.
	 */
	@CalledByReflection
	public CompleteModules(InstantiationContext context, C config) {
		super(context, config);
		_allocators = buildHandlers(context, config.getAllocators());
		_moduleDefs = buildHandlers(context, config.getModuleDefs());
		_module = buildHandlers(context, config.getModule());
		_repeat = buildHandlers(context, config.getRepeat());
		_calculations = buildHandlers(context, config.getCalculations());
		_validations = buildHandlers(context, config.getValidations());
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		Object known = context.getVarOrNull(ImportContext.THIS_VAR);
		ModuleCompletion completion = null;
		if (known instanceof TLObject program && Boolean.TRUE.equals(program.tValueByName(USES_MODULES))) {
			completion = ModuleScan.get(context).getCompletion(ProgramImported.id(program));
		}
		boolean modules = completion != null && completion.getProgram() == known;
		boolean calculations = unread(known, CALCULATIONS_READ);
		boolean validations = unread(known, VALIDATIONS_READ);
		if (!modules && !calculations && !validations) {
			return importXmlInScope(context, in);
		}
		complete(context, in, (TLObject) known, modules ? completion : null, calculations, validations);
		return null;
	}

	/**
	 * Whether the given known program lacks the part the given mark records as read.
	 */
	private static boolean unread(Object known, String mark) {
		return known instanceof TLObject program && program.tType().getPart(mark) != null
			&& !Boolean.TRUE.equals(program.tValueByName(mark));
	}

	private void complete(ImportContext context, XMLStreamReader in, TLObject program, ModuleCompletion completion,
			boolean calculations, boolean validations) throws XMLStreamException {
		String id = ProgramImported.id(program);
		ProgramLanguages languages = ProgramLanguages.get(context);
		if (languages != null) {
			// The translations of the new parts are set in every language (see TranslationsImport).
			languages.known(id, program);
		}
		boolean modules = completion != null;
		Map<TLObject, Integer> inserted = new HashMap<>();
		String section = null;
		int depth = 0;
		int sequence = 0;
		// The names of the open elements and the IDs of the open module definitions.
		List<String> path = new ArrayList<>();
		List<String> definitions = new ArrayList<>();
		while (true) {
			int event = in.next();
			if (event == XMLStreamConstants.START_ELEMENT) {
				depth++;
				String name = in.getLocalName();
				if (depth == 1) {
					section = name;
				}
				Handler handler = null;
				TLObject target = program;
				ModuleCompletion.Target place = null;
				if (depth == 1 && "ModuleDefs".equals(name) && modules) {
					handler = _moduleDefs;
				} else if (depth == 2 && STATIC.equals(section) && "Allocators".equals(name) && modules) {
					handler = _allocators;
				} else if (calculations && depth == 2 && STATIC.equals(section) && "Script".equals(name)) {
					context.setProperty(this, program, "script", in.getElementText());
					depth--;
					continue;
				} else if (calculations && "Script".equals(name) && path.size() >= 2
					&& STATIC.equals(path.get(path.size() - 1)) && MODULE_DEF.equals(path.get(path.size() - 2))) {
					Object definition = context.resolveObjectBackwards(this, in.getLocation(),
						definitions.get(definitions.size() - 2));
					String text = in.getElementText();
					if (definition instanceof TLObject module) {
						context.setProperty(this, module, "script", text);
					}
					depth--;
					continue;
				} else if (calculations && "ParameterCalculations".equals(name) && path.size() >= 1
					&& STATIC.equals(path.get(path.size() - 1))) {
					if (depth == 2) {
						handler = _calculations;
					} else if (path.size() >= 2 && MODULE_DEF.equals(path.get(path.size() - 2))
						&& context.resolveObjectBackwards(this, in.getLocation(),
							definitions.get(definitions.size() - 2)) instanceof TLObject definition) {
						handler = _calculations;
						target = definition;
					}
				} else if (validations && "ParameterValidations".equals(name) && path.size() >= 1
					&& STATIC.equals(path.get(path.size() - 1))) {
					if (depth == 2) {
						handler = _validations;
					} else if (path.size() >= 2 && MODULE_DEF.equals(path.get(path.size() - 2))
						&& context.resolveObjectBackwards(this, in.getLocation(),
							definitions.get(definitions.size() - 2)) instanceof TLObject definition) {
						handler = _validations;
						target = definition;
					}
				} else if (modules && DYNAMIC.equals(section) && ModuleCompletion.isPlacement(name)) {
					place = completion.target(sequence++);
					if (place == null) {
						XMLStreamUtil.skipUpToMatchingEndTag(in);
						depth--;
						continue;
					}
					handler = "Module".equals(name) ? _module : _repeat;
					target = place.fragment();
				}
				if (handler != null) {
					if (place != null) {
						insert(context, in, place, handler, inserted);
					} else {
						context.withVar(ImportContext.THIS_VAR, target, in, handler);
					}
					// The handler consumed the element up to its end tag.
					depth--;
				} else {
					path.add(name);
					definitions.add(MODULE_DEF.equals(name) ? in.getAttributeValue(null, "Id") : null);
				}
			} else if (event == XMLStreamConstants.END_ELEMENT) {
				if (depth == 0) {
					break;
				}
				depth--;
				path.remove(path.size() - 1);
				definitions.remove(definitions.size() - 1);
			} else if (event == XMLStreamConstants.END_DOCUMENT) {
				break;
			}
		}
		if (modules) {
			context.setProperty(this, program, USES_MODULES, Boolean.FALSE);
			HiddenProgramValues.reportPending(context);
			context.log(Level.INFO, in.getLocation(), I18NConstants.INFO_MODULES_COMPLETED__PROGRAM.fill(id));
		}
		if (calculations) {
			context.setProperty(this, program, CALCULATIONS_READ, Boolean.TRUE);
			context.log(Level.INFO, in.getLocation(), I18NConstants.INFO_CALCULATIONS_COMPLETED__PROGRAM.fill(id));
		}
		if (validations) {
			context.setProperty(this, program, VALIDATIONS_READ, Boolean.TRUE);
			context.log(Level.INFO, in.getLocation(), I18NConstants.INFO_VALIDATIONS_COMPLETED__PROGRAM.fill(id));
		}
	}

	/**
	 * Reads a placement or repeat into the structure element of its target, and moves it to its
	 * position there.
	 */
	private void insert(ImportContext context, XMLStreamReader in, ModuleCompletion.Target target, Handler handler,
			Map<TLObject, Integer> inserted) throws XMLStreamException {
		TLObject fragment = target.fragment();
		int before = contents(fragment).size();
		context.withVar(ImportContext.THIS_VAR, fragment, in, handler);
		List<Object> contents = contents(fragment);
		int added = contents.size() - before;
		if (added <= 0) {
			return;
		}
		int offset = inserted.getOrDefault(fragment, Integer.valueOf(0)).intValue();
		List<Object> created = new ArrayList<>(contents.subList(before, contents.size()));
		contents.subList(before, contents.size()).clear();
		contents.addAll(target.index() + offset, created);
		fragment.tUpdateByName(CONTENTS, contents);
		inserted.put(fragment, Integer.valueOf(offset + added));
	}

	private static List<Object> contents(TLObject fragment) {
		return new ArrayList<>((Collection<?>) fragment.tValueByName(CONTENTS));
	}

}
