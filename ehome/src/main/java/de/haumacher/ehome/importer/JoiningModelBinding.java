/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.xml.stream.Location;

import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.TLType;
import com.top_logic.model.impl.TransientModelFactory;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.xio.importer.binding.ModelBinding;
import com.top_logic.xio.importer.handlers.ImportPart;

/**
 * {@link ModelBinding} of the ETS import: stores set additions at once, caches the model types, creates
 * transient candidates of known master data, and records the objects an import created and found.
 *
 * <p>
 * The elements added to an unordered multiple reference of a persistent object (a set, such as the
 * parameters of a program) are stored at once by {@link #flush()}: the engine reads the whole set (TL #29889) to
 * add one element, which makes adding the thousands of parameters of a program one by one
 * quadratic. Until then, reading such a reference through this binding (or evaluating an expression)
 * stores its elements first; code reading it directly sees the elements stored so far. An element
 * the set holds already (an object joined by <code>join-duplicates</code>, linked to its container
 * again) leaves it unchanged; the engine links any other reference only once itself.
 * </p>
 */
public class JoiningModelBinding implements ModelBinding {

	private final ModelBinding _impl;

	private List<Object> _created = new ArrayList<>();

	private Set<Object> _resolved = new LinkedHashSet<>();

	/**
	 * The elements added to unordered multiple references and not yet stored, see {@link #flush()}.
	 */
	private Map<TLObject, Map<TLStructuredTypePart, List<TLObject>>> _pending = new LinkedHashMap<>();

	/** The model types by their qualified names, see {@link #type(String)}. */
	private final Map<String, TLType> _types = new HashMap<>();

	/**
	 * Whether objects are created transiently, see {@link #setTransient(boolean)}.
	 */
	private boolean _transient;

	/**
	 * Creates a {@link JoiningModelBinding}.
	 *
	 * @param impl
	 *        The binding to delegate to.
	 */
	public JoiningModelBinding(ModelBinding impl) {
		_impl = impl;
	}

	/**
	 * Whether the objects created from now on are transient: a candidate of known master data that is
	 * compared with the known object and never stored (see {@link VersionedMasterData}).
	 *
	 * <p>
	 * A transient object is not linked to a persistent one, and is not reported as created.
	 * </p>
	 */
	public void setTransient(boolean value) {
		_transient = value;
	}

	/**
	 * Whether objects are created transiently, see {@link #setTransient(boolean)}.
	 */
	public boolean isTransient() {
		return _transient;
	}

	private static boolean linksTransientToPersistent(Object obj, Object value) {
		return obj instanceof TLObject self && !self.tTransient() && value instanceof TLObject other
			&& other.tTransient();
	}

	@Override
	public void addValue(ImportPart handler, Object obj, String part, Object element) {
		if (linksTransientToPersistent(obj, element)) {
			return;
		}
		TLStructuredTypePart deferred = deferredPart((TLObject) obj, part, element);
		if (deferred != null) {
			_pending.computeIfAbsent((TLObject) obj, x -> new LinkedHashMap<>())
				.computeIfAbsent(deferred, x -> new ArrayList<>()).add((TLObject) element);
		} else {
			_impl.addValue(handler, obj, part, element);
		}
	}

	/**
	 * The unordered multiple reference of a persistent object with the given name, to which the given
	 * element is added later by {@link #flush()}, <code>null</code> if it is added at once.
	 */
	private static TLStructuredTypePart deferredPart(TLObject obj, String name, Object element) {
		if (obj.tTransient() || !(element instanceof TLObject value) || value.tTransient()) {
			return null;
		}
		TLStructuredTypePart part = obj.tType().getPart(name);
		if (part instanceof TLReference reference && reference.isMultiple() && !reference.isOrdered()
			&& !reference.isDerived()) {
			return part;
		}
		return null;
	}

	/**
	 * Stores the elements added to unordered multiple references of persistent objects so far (see
	 * the class comment).
	 */
	public void flush() {
		Map<TLObject, Map<TLStructuredTypePart, List<TLObject>>> pending = _pending;
		_pending = new LinkedHashMap<>();
		for (Map.Entry<TLObject, Map<TLStructuredTypePart, List<TLObject>>> entry : pending.entrySet()) {
			for (Map.Entry<TLStructuredTypePart, List<TLObject>> added : entry.getValue().entrySet()) {
				store(entry.getKey(), added.getKey(), added.getValue());
			}
		}
	}

	private void flush(Object obj, String name) {
		Map<TLStructuredTypePart, List<TLObject>> byPart = _pending.get(obj);
		if (byPart == null) {
			return;
		}
		TLStructuredTypePart part = ((TLObject) obj).tType().getPart(name);
		List<TLObject> added = byPart.remove(part);
		if (added != null) {
			store((TLObject) obj, part, added);
		}
	}

	private static void store(TLObject obj, TLStructuredTypePart part, List<TLObject> added) {
		Set<Object> value = new LinkedHashSet<>((Collection<?>) obj.tValue(part));
		if (value.addAll(added)) {
			obj.tUpdate(part, value);
		}
	}

	@Override
	public Object resolveObject(ImportPart handler, Location location, String id) {
		Object result = _impl.resolveObject(handler, location, id);
		if (result != null) {
			_resolved.add(result);
		}
		return result;
	}

	@Override
	public Object createObject(ImportPart handler, Location location, String modelType, String id) {
		if (_transient) {
			// Never registered under an ID: references to the ID resolve to the persistent object.
			return TransientModelFactory.createTransientObject((TLClass) type(modelType));
		}
		Object result = _impl.createObject(handler, location, modelType, id);
		_created.add(result);
		return result;
	}

	/**
	 * The objects created through this binding since the last {@link #clearCreated()}.
	 */
	public List<Object> getCreated() {
		return _created;
	}

	/**
	 * The objects the import found under their IDs since the last {@link #clearCreated()}: existing
	 * objects it reused or referenced, and objects it created itself (see {@link #getCreated()}), in
	 * the order they were first found or created.
	 */
	public Set<Object> getResolved() {
		return _resolved;
	}

	/**
	 * Forgets the objects created and found so far, see {@link #getCreated()} and
	 * {@link #getResolved()}, and the set additions not stored yet: the next import starts from the
	 * database as it is then.
	 */
	public void clearCreated() {
		_created = new ArrayList<>();
		_resolved = new LinkedHashSet<>();
		_pending = new LinkedHashMap<>();
		_types.clear();
	}

	/**
	 * Makes an existing object known under the given ID, so that the import resolves the ID to it
	 * (see {@link SharedObjects}).
	 *
	 * <p>
	 * Unlike {@link #assignId(Object, String)}, the object does not count as found (see
	 * {@link #getResolved()}) until the import asks for it.
	 * </p>
	 */
	public void register(Object value, String id) {
		_impl.assignId(value, id);
	}

	/**
	 * Assigns the ID to the given object.
	 *
	 * <p>
	 * Called for an object the import created, and for an existing one an
	 * {@link com.top_logic.xio.importer.binding.IdLookup} found under the ID (such as
	 * <code>&lt;resolve-existing&gt;</code> in <code>etsImporter.xml</code>), which counts as found
	 * (see {@link #getResolved()}).
	 * </p>
	 */
	@Override
	public void assignId(Object value, String id) {
		_impl.assignId(value, id);
		if (value != null && id != null) {
			_resolved.add(value);
		}
	}

	@Override
	public void setProperty(ImportPart handler, Object obj, String name, Object value) {
		flush(obj, name);
		_impl.setProperty(handler, obj, name, value);
	}

	@Override
	public void getProperty(ImportPart handler, Object obj, String name, Consumer<Object> continuation) {
		flush(obj, name);
		_impl.getProperty(handler, obj, name, continuation);
	}

	@Override
	public void setReference(ImportPart handler, Object obj, String name, Object value) {
		if (linksTransientToPersistent(obj, value)) {
			return;
		}
		flush(obj, name);
		_impl.setReference(handler, obj, name, value);
	}

	@Override
	public Object eval(Expr expr, Object... args) {
		// The expression may read any reference.
		flush();
		return _impl.eval(expr, args);
	}

	@Override
	public boolean isInstanceOf(Object obj, String type) {
		return TLModelUtil.isCompatibleInstance(type(type), obj);
	}

	/**
	 * The model type with the given qualified name.
	 *
	 * <p>
	 * Looked up once per import: the engine looks up the module by its name among all modules (TL #29890).
	 * </p>
	 */
	private TLType type(String name) {
		return _types.computeIfAbsent(name, TLModelUtil::findType);
	}

}
