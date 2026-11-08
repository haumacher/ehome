/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.xml.stream.Location;

import com.top_logic.basic.col.TypedAnnotatable;
import com.top_logic.basic.col.TypedAnnotatable.Property;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.impl.TransientModelFactory;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.binding.ModelBinding;
import com.top_logic.xio.importer.handlers.ImportPart;

/**
 * {@link ModelBinding} of the ETS import: creates transient candidates of known master data, and
 * records the objects an import created and found.
 */
public class JoiningModelBinding implements ModelBinding {

	/**
	 * The {@link JoiningModelBinding} of an import in its {@link ImportContext}.
	 */
	public static final Property<JoiningModelBinding> PROPERTY =
		TypedAnnotatable.property(JoiningModelBinding.class, "joiningModelBinding");

	private final ModelBinding _impl;

	private List<Object> _created = new ArrayList<>();

	/** The objects of {@link #_created}, for {@link #isCreated(Object)}. */
	private Set<Object> _createdSet = Collections.newSetFromMap(new IdentityHashMap<>());

	private Set<Object> _resolved = new LinkedHashSet<>();

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
		_impl.addValue(handler, obj, part, element);
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
			return TransientModelFactory.createTransientObject((TLClass) TLModelUtil.findType(modelType));
		}
		Object result = _impl.createObject(handler, location, modelType, id);
		_created.add(result);
		_createdSet.add(result);
		return result;
	}

	/**
	 * The objects created through this binding since the last {@link #clearCreated()}.
	 */
	public List<Object> getCreated() {
		return _created;
	}

	/**
	 * Whether the given object was created through this binding since the last
	 * {@link #clearCreated()}.
	 */
	public boolean isCreated(Object obj) {
		return _createdSet.contains(obj);
	}

	/**
	 * Whether the given object was created by the import of the given context, see
	 * {@link #isCreated(Object)}; <code>false</code> if the context has no {@link JoiningModelBinding}.
	 */
	public static boolean isCreated(ImportContext context, Object obj) {
		JoiningModelBinding binding = context.get(PROPERTY);
		return binding != null && binding.isCreated(obj);
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
	 * {@link #getResolved()}: the next import starts from the database as it is then.
	 */
	public void clearCreated() {
		_created = new ArrayList<>();
		_createdSet = Collections.newSetFromMap(new IdentityHashMap<>());
		_resolved = new LinkedHashSet<>();
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
		_impl.setProperty(handler, obj, name, value);
	}

	@Override
	public void getProperty(ImportPart handler, Object obj, String name, Consumer<Object> continuation) {
		_impl.getProperty(handler, obj, name, continuation);
	}

	@Override
	public void setReference(ImportPart handler, Object obj, String name, Object value) {
		if (linksTransientToPersistent(obj, value)) {
			return;
		}
		_impl.setReference(handler, obj, name, value);
	}

	@Override
	public Object eval(Expr expr, Object... args) {
		return _impl.eval(expr, args);
	}

	@Override
	public boolean isInstanceOf(Object obj, String type) {
		return TLModelUtil.isCompatibleInstance(TLModelUtil.findType(type), obj);
	}

}
