/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
 * {@link ModelBinding} that does not add an object to a reference that already contains it.
 *
 * <p>
 * An import that reuses an existing object (<code>join-duplicates</code>), such as a datapoint type
 * known from an earlier import, links it to its container again; this binding makes that a no-op.
 * </p>
 *
 * <p>
 * The elements added to an unordered multiple reference of a persistent object (a set, such as the
 * parameters of a program) are stored at once by {@link #flush()}: the engine reads the whole set to
 * add one element, which makes adding the thousands of parameters of a program one by one
 * quadratic. Until then, reading such a reference through this binding (or evaluating an expression)
 * stores its elements first; code reading it directly sees the elements stored so far.
 * </p>
 */
public class JoiningModelBinding implements ModelBinding {

	private final ModelBinding _impl;

	private List<Object> _created = new ArrayList<>();

	private Set<Object> _resolved = new LinkedHashSet<>();

	/**
	 * The elements of the multiple attributes this binding added values to, by object and attribute.
	 *
	 * <p>
	 * Reading the value of a large attribute is expensive (the master data add each of some hundred
	 * manufacturers to the manufacturer root again, a program adds thousands of parameters one by
	 * one), so it is read once per import and kept up to date here.
	 * </p>
	 */
	private Map<Object, Map<String, Set<Object>>> _elements = new HashMap<>();

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
	 * Makes existing objects known on demand, see {@link #setLoader(Predicate)}.
	 */
	private Predicate<String> _loader = id -> false;

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
		Set<Object> elements = elements(obj, part);
		if (elements != null && elements.contains(element)) {
			return;
		}
		TLStructuredTypePart deferred = deferredPart((TLObject) obj, part, element);
		if (deferred != null) {
			_pending.computeIfAbsent((TLObject) obj, x -> new LinkedHashMap<>())
				.computeIfAbsent(deferred, x -> new ArrayList<>()).add((TLObject) element);
		} else {
			_impl.addValue(handler, obj, part, element);
		}
		if (elements != null) {
			elements.add(element);
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
		value.addAll(added);
		obj.tUpdate(part, value);
	}

	/**
	 * The current elements of the given attribute, <code>null</code> if it is not multiple.
	 */
	private Set<Object> elements(Object obj, String part) {
		Map<String, Set<Object>> byPart = _elements.computeIfAbsent(obj, x -> new HashMap<>());
		Set<Object> result = byPart.get(part);
		if (result == null) {
			Object value = ((TLObject) obj).tValueByName(part);
			if (!(value instanceof Collection<?> collection)) {
				return null;
			}
			result = new HashSet<>(collection);
			byPart.put(part, result);
		}
		return result;
	}

	/**
	 * Forgets the elements of the given attribute known from {@link #addValue(ImportPart, Object, String, Object)}.
	 */
	private void forgetElements(Object obj, String part) {
		Map<String, Set<Object>> byPart = _elements.get(obj);
		if (byPart != null) {
			byPart.remove(part);
		}
	}

	/**
	 * Sets what makes existing objects known (with {@link #assignId(Object, String)}) that may be
	 * known under an ID the import asks for and finds unknown.
	 *
	 * @param loader
	 *        Called with the unknown ID; returns whether it made objects known.
	 */
	public void setLoader(Predicate<String> loader) {
		_loader = loader;
	}

	@Override
	public Object resolveObject(ImportPart handler, Location location, String id) {
		Object result = _impl.resolveObject(handler, location, id);
		if (result == null && id != null && _loader.test(id)) {
			result = _impl.resolveObject(handler, location, id);
		}
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
	 * The existing objects the import found under their IDs since the last {@link #clearCreated()}:
	 * objects it reused or referenced, including those it created itself before, in the order they
	 * were first found.
	 */
	public Set<Object> getResolved() {
		return _resolved;
	}

	/**
	 * Forgets the objects created and found so far, see {@link #getCreated()} and
	 * {@link #getResolved()}, and the elements of attributes read so far: the next import starts
	 * from the database as it is then.
	 */
	public void clearCreated() {
		_created = new ArrayList<>();
		_resolved = new LinkedHashSet<>();
		_elements = new HashMap<>();
		_pending = new LinkedHashMap<>();
		_types.clear();
	}

	@Override
	public void assignId(Object value, String id) {
		_impl.assignId(value, id);
	}

	@Override
	public void setProperty(ImportPart handler, Object obj, String name, Object value) {
		flush(obj, name);
		forgetElements(obj, name);
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
		forgetElements(obj, name);
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
	 * Looked up once per import: the engine looks up the module by its name among all modules.
	 * </p>
	 */
	private TLType type(String name) {
		return _types.computeIfAbsent(name, TLModelUtil::findType);
	}

}
