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

import javax.xml.stream.Location;

import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.xio.importer.binding.IdLookup;

/**
 * Makes the parts of the existing application programs known to an import under their ETS IDs.
 *
 * <p>
 * The data shared between projects (master data, catalogs, hardware, application programs) exists
 * only once: the import reuses the objects that exist (or skips them, see {@link UnlessKnownProgram}),
 * creates only the missing ones, and references from the imported project resolve to the existing
 * ones. The shared objects other than the parts of programs are found by
 * <code>&lt;resolve-existing&gt;</code> in <code>etsImporter.xml</code>.
 * </p>
 *
 * <p>
 * The parts of an application program (parameter types, parameters, communication objects,
 * segments, ...) are by far the most of these objects: about ten thousand per program. An import
 * refers only to the programs of its own project, so they are made known when the import first asks
 * for an ID within a program (the ETS IDs of a program's parts start with the program's ID, see
 * {@link #lookup(Location, String)}), not all of them at the start of every import, as one index of
 * <code>&lt;resolve-existing&gt;</code> would.
 * </p>
 */
final class SharedObjects implements IdLookup {

	private static final String EXT_ID = "extId";

	private static final String PROGRAM = "ehome.operational:Program";

	private final JoiningModelBinding _binding;

	/** The application programs existing before the import, by their IDs. */
	private final Map<String, TLObject> _programs = new HashMap<>();

	/** The IDs of the programs whose parts are known already. */
	private final Set<String> _loaded = new HashSet<>();

	/** The composite references of a class, by the class. */
	private final Map<TLClass, List<TLReference>> _compositeParts = new HashMap<>();

	/**
	 * Creates a {@link SharedObjects} for an import with the given binding, knowing the application
	 * programs that exist now.
	 */
	SharedObjects(JoiningModelBinding binding) {
		_binding = binding;
		TLClass program = (TLClass) TLModelUtil.findType(PROGRAM);
		for (TLObject obj : MetaElementUtil.getAllDirectInstancesOf(program, TLObject.class)) {
			String id = (String) obj.tValueByName(EXT_ID);
			if (id != null) {
				_programs.put(id, obj);
			}
		}
	}

	/**
	 * Makes the parts of the existing application program known whose ID the given ID starts with
	 * (followed by <code>_</code>), if they are not known already, and returns the part with the given
	 * ID.
	 */
	@Override
	public Object lookup(Location location, String id) {
		if (id == null) {
			return null;
		}
		for (int index = id.indexOf('_'); index > 0; index = id.indexOf('_', index + 1)) {
			String prefix = id.substring(0, index);
			TLObject program = _programs.get(prefix);
			if (program != null) {
				if (!_loaded.add(prefix) || !program.tValid()) {
					return null;
				}
				registerParts(program);
				return _binding.resolveObject(null, location, id);
			}
		}
		return null;
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
					_binding.register(obj, id);
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

}
