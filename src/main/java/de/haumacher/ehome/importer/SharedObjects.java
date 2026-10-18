/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLModel;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.util.TLModelUtil;

/**
 * Makes the objects of the data shared between projects (master data, catalogs, hardware,
 * application programs) known to an import under their ETS IDs.
 *
 * <p>
 * This data exists only once: the import reuses the objects that exist (or skips them, see
 * {@link UnlessKnown}), creates only the missing ones, and references from the imported project
 * resolve to the existing ones.
 * </p>
 *
 * <p>
 * The parts of an application program (parameter types, parameters, communication objects,
 * segments, ...) are by far the most of these objects: about ten thousand per program. An import
 * refers only to the programs of its own project, so they are made known when the import first asks
 * for an ID within a program (the ETS IDs of a program's parts start with the program's ID, see
 * {@link #load(String)}), not all of them at the start of every import. All other shared objects are
 * made known at the start ({@link #registerEagerly()}).
 * </p>
 */
final class SharedObjects {

	/**
	 * The modules holding the data ETS shares between projects: the datapoint types and the
	 * manufacturer data (catalogs, hardware, application programs).
	 */
	private static final List<String> SHARED_MODULES =
		List.of("ehome.datapoint", "ehome.catalog", "ehome.operational");

	private static final String EXT_ID = "extId";

	private static final String PROGRAM = "ehome.operational:Program";

	private final JoiningModelBinding _binding;

	/**
	 * The classes whose instances are always parts of an application program, made known per
	 * program on demand.
	 */
	private final Set<TLClass> _programParts;

	/** The application programs by their IDs. */
	private final Map<String, TLObject> _programs = new HashMap<>();

	/** The IDs of the programs whose parts are known already. */
	private final Set<String> _loaded = new HashSet<>();

	/** The composite references of a class, by the class. */
	private final Map<TLClass, List<TLReference>> _compositeParts = new HashMap<>();

	/**
	 * Creates a {@link SharedObjects} for an import with the given binding.
	 */
	SharedObjects(JoiningModelBinding binding) {
		_binding = binding;
		_programParts = programParts((TLClass) TLModelUtil.findType(PROGRAM));
	}

	/**
	 * Makes all existing shared objects known that are not parts of an application program, and the
	 * programs themselves.
	 */
	void registerEagerly() {
		TLModel model = ((TLClass) TLModelUtil.findType(PROGRAM)).getModel();
		for (String moduleName : SHARED_MODULES) {
			TLModule module = model.getModule(moduleName);
			for (TLClass type : module.getClasses()) {
				if (type.getPart(EXT_ID) == null || _programParts.contains(type)) {
					continue;
				}
				boolean program = TLModelUtil.qualifiedName(type).equals(PROGRAM);
				for (TLObject obj : MetaElementUtil.getAllDirectInstancesOf(type, TLObject.class)) {
					String id = (String) obj.tValueByName(EXT_ID);
					if (id != null) {
						_binding.assignId(obj, id);
						if (program) {
							_programs.put(id, obj);
						}
					}
				}
			}
		}
	}

	/**
	 * Makes the parts of the existing application program known whose ID the given ID starts with
	 * (followed by <code>_</code>), if they are not known already.
	 *
	 * @return Whether parts were made known.
	 */
	boolean load(String id) {
		for (int index = id.indexOf('_'); index > 0; index = id.indexOf('_', index + 1)) {
			String prefix = id.substring(0, index);
			TLObject program = _programs.get(prefix);
			if (program != null) {
				if (!_loaded.add(prefix) || !program.tValid()) {
					return false;
				}
				registerParts(program);
				return true;
			}
		}
		return false;
	}

	private void registerParts(TLObject program) {
		Deque<TLObject> todo = new ArrayDeque<>();
		todo.add(program);
		while (!todo.isEmpty()) {
			TLObject obj = todo.removeFirst();
			TLClass type = (TLClass) obj.tType();
			if (obj != program && type.getPart(EXT_ID) != null) {
				String id = (String) obj.tValueByName(EXT_ID);
				if (id != null) {
					_binding.assignId(obj, id);
				}
			}
			for (TLReference reference : compositeParts(type)) {
				Object value = obj.tValue(reference);
				if (value instanceof Iterable<?> parts) {
					for (Object part : parts) {
						todo.add((TLObject) part);
					}
				} else if (value != null) {
					todo.add((TLObject) value);
				}
			}
		}
	}

	private List<TLReference> compositeParts(TLClass type) {
		return _compositeParts.computeIfAbsent(type, SharedObjects::composites);
	}

	private static List<TLReference> composites(TLClass type) {
		List<TLReference> result = new ArrayList<>();
		for (TLStructuredTypePart part : type.getAllParts()) {
			if (part instanceof TLReference reference && reference.isComposite() && !reference.isBackwards()
				&& !reference.isDerived()) {
				result.add(reference);
			}
		}
		return result;
	}

	/**
	 * The classes whose instances are always (transitively) parts of an application program: those
	 * reachable from the program by composite references, but from no other concrete class (without
	 * passing through a program).
	 */
	private static Set<TLClass> programParts(TLClass program) {
		TLModel model = program.getModel();
		Set<TLClass> result = closure(Set.of(program), program);
		result.remove(program);

		Set<TLClass> others = new HashSet<>();
		for (TLModule module : model.getModules()) {
			for (TLClass type : module.getClasses()) {
				// An abstract class has no instances of its own: its composite references are those
				// of its concrete specializations.
				if (type != program && !type.isAbstract() && !result.contains(type)) {
					others.add(type);
				}
			}
		}
		Set<TLClass> outside = closure(others, program);
		outside.removeAll(others);
		result.removeAll(outside);
		return result;
	}

	/**
	 * The classes reachable from the given ones by composite references (with all their
	 * specializations), including the given ones, not passing through the given program class.
	 */
	private static Set<TLClass> closure(Set<TLClass> start, TLClass program) {
		Set<TLClass> result = new HashSet<>(start);
		Deque<TLClass> todo = new ArrayDeque<>(start);
		while (!todo.isEmpty()) {
			TLClass type = todo.removeFirst();
			for (TLReference reference : composites(type)) {
				if (!(reference.getType() instanceof TLClass target)) {
					continue;
				}
				for (TLClass part : TLModelUtil.getReflexiveTransitiveSpecializations(target)) {
					if (part != program && result.add(part)) {
						todo.add(part);
					}
				}
			}
		}
		return result;
	}

}
