/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.xml.stream.Location;

import com.top_logic.basic.Logger;
import com.top_logic.basic.col.TypedAnnotatable;
import com.top_logic.basic.col.TypedAnnotatable.Property;
import com.top_logic.dob.identifier.ObjectKey;
import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.knowledge.service.PersistencyLayer;
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
 *
 * <p>
 * The binding knows the objects of the import under their ETS IDs itself (the binding it delegates
 * to only creates objects and sets their values). So an import committed step by step (the product
 * data, one application program per transaction, haumacher/ehome #160) can let go of what a step
 * committed, see {@link #release(String)}: the import then holds the objects of earlier steps only
 * by their {@link ObjectKey}s, and the database's cache, which holds its objects softly, may drop
 * them.
 * </p>
 */
public class JoiningModelBinding implements ModelBinding {

	/**
	 * The {@link JoiningModelBinding} of an import in its {@link ImportContext}.
	 */
	public static final Property<JoiningModelBinding> PROPERTY =
		TypedAnnotatable.property(JoiningModelBinding.class, "joiningModelBinding");

	private final ModelBinding _impl;

	/**
	 * The objects known under their IDs: the object itself, or its {@link ObjectKey} after a
	 * {@link #release(String)}.
	 */
	private final Map<String, Object> _ids = new HashMap<>();

	/**
	 * The objects created (or the keys of those released), in the order of their creation.
	 */
	private List<Object> _created = new ArrayList<>();

	/** The ID each object of {@link #_created} was created with, <code>null</code> for none. */
	private List<String> _createdIds = new ArrayList<>();

	/** The objects of {@link #_created} not released, for {@link #isCreated(Object)}. */
	private Set<Object> _createdSet = Collections.newSetFromMap(new IdentityHashMap<>());

	/** The keys of the objects of {@link #_created} released, for {@link #isCreated(Object)}. */
	private Set<ObjectKey> _createdKeys = new HashSet<>();

	/**
	 * The objects found under their IDs (or the keys of those released), each with the ID it was
	 * found under first.
	 */
	private Map<Object, String> _resolved = new LinkedHashMap<>();

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
		Object result = lookup(id);
		if (result != null) {
			_resolved.putIfAbsent(result, id);
		}
		return result;
	}

	/**
	 * The object known under the given ID, the released ones resolved by their keys;
	 * <code>null</code> if there is none.
	 */
	private Object lookup(String id) {
		if (id == null) {
			return null;
		}
		Object result = _ids.get(id);
		if (result instanceof ObjectKey key) {
			result = resolve(key);
			if (result == null) {
				_ids.remove(id);
			} else {
				_ids.put(id, result);
			}
		}
		return result;
	}

	@Override
	public Object createObject(ImportPart handler, Location location, String modelType, String id) {
		if (_transient) {
			// Never registered under an ID: references to the ID resolve to the persistent object.
			return TransientModelFactory.createTransientObject((TLClass) TLModelUtil.findType(modelType));
		}
		// Registered here, not in the binding delegated to, see release(String).
		Object result = _impl.createObject(handler, location, modelType, null);
		put(id, result);
		_created.add(result);
		_createdIds.add(id);
		_createdSet.add(result);
		return result;
	}

	/**
	 * The objects created through this binding since the last {@link #clearCreated()}, in the order
	 * of their creation; those released by {@link #release(String)} as far as they are kept.
	 */
	public List<Object> getCreated() {
		List<Object> result = new ArrayList<>(_created.size());
		for (Object obj : _created) {
			Object live = obj instanceof ObjectKey key ? resolve(key) : obj;
			if (live != null) {
				result.add(live);
			}
		}
		return result;
	}

	/**
	 * The number of objects {@link #getCreated()} holds now, to ask for those created after it, see
	 * {@link #getCreatedSince(int)}.
	 */
	public int mark() {
		return _created.size();
	}

	/**
	 * The objects created since the given {@link #mark()}, which no {@link #release(String)} came
	 * between.
	 */
	public List<Object> getCreatedSince(int mark) {
		return new ArrayList<>(_created.subList(mark, _created.size()));
	}

	/**
	 * The objects created since the given {@link #mark()} under an ID that does not name a part of
	 * the given application program (the program itself, and objects of other programs or of the
	 * shared data), by their IDs.
	 */
	public Map<String, Object> getIdsSince(int mark, String program) {
		String prefix = program + "_";
		Map<String, Object> result = new LinkedHashMap<>();
		for (int n = mark, cnt = _created.size(); n < cnt; n++) {
			String id = _createdIds.get(n);
			if (id != null && !id.startsWith(prefix)) {
				result.put(id, _created.get(n));
			}
		}
		return result;
	}

	/**
	 * Whether the given object was created through this binding since the last
	 * {@link #clearCreated()}.
	 */
	public boolean isCreated(Object obj) {
		if (_createdSet.contains(obj)) {
			return true;
		}
		return !_createdKeys.isEmpty() && obj instanceof TLObject object && !object.tTransient()
			&& _createdKeys.contains(object.tId());
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
		Set<Object> result = new LinkedHashSet<>();
		for (Object obj : _resolved.keySet()) {
			Object live = obj instanceof ObjectKey key ? resolve(key) : obj;
			if (live != null) {
				result.add(live);
			}
		}
		return result;
	}

	/**
	 * Forgets the objects created and found so far, see {@link #getCreated()} and
	 * {@link #getResolved()}: the next import starts from the database as it is then.
	 */
	public void clearCreated() {
		_created = new ArrayList<>();
		_createdIds = new ArrayList<>();
		_createdSet = Collections.newSetFromMap(new IdentityHashMap<>());
		_createdKeys = new HashSet<>();
		_resolved = new LinkedHashMap<>();
	}

	/**
	 * Lets go of the objects of the import after a step of it was committed (or rolled back): the
	 * import forgets the parts of the given application program, and holds every other object only by
	 * its {@link ObjectKey}, resolving it again when it is asked for.
	 *
	 * <p>
	 * The parts of the program are the objects whose IDs begin with the program's ID (followed by
	 * <code>_</code>), and the objects without an ID created since the last release (the structure of
	 * the program: switches, cases, placements). They are no longer known under their IDs, and no
	 * longer listed by {@link #getCreated()} and {@link #getResolved()}; the program itself is.
	 * </p>
	 *
	 * @param program
	 *        The ID of the application program the step imported; <code>null</code> to keep every
	 *        object (by its key).
	 */
	public void release(String program) {
		String prefix = program == null ? null : program + "_";
		for (Iterator<Map.Entry<String, Object>> it = _ids.entrySet().iterator(); it.hasNext();) {
			Map.Entry<String, Object> entry = it.next();
			if (prefix != null && entry.getKey().startsWith(prefix)) {
				it.remove();
			} else if (entry.getValue() instanceof TLObject obj && !obj.tTransient()) {
				entry.setValue(obj.tId());
			}
		}

		List<Object> created = new ArrayList<>();
		List<String> createdIds = new ArrayList<>();
		for (int n = 0, cnt = _created.size(); n < cnt; n++) {
			Object obj = _created.get(n);
			String id = _createdIds.get(n);
			if (obj instanceof TLObject object) {
				if (prefix != null && (id == null || id.startsWith(prefix))) {
					continue;
				}
				ObjectKey key = object.tId();
				_createdKeys.add(key);
				obj = key;
			}
			created.add(obj);
			createdIds.add(id);
		}
		_created = created;
		_createdIds = createdIds;
		_createdSet = Collections.newSetFromMap(new IdentityHashMap<>());

		Map<Object, String> resolved = new LinkedHashMap<>();
		for (Map.Entry<Object, String> entry : _resolved.entrySet()) {
			Object obj = entry.getKey();
			String id = entry.getValue();
			if (obj instanceof TLObject object) {
				if (object.tTransient() || (prefix != null && id.startsWith(prefix))) {
					continue;
				}
				obj = object.tId();
			}
			resolved.putIfAbsent(obj, id);
		}
		_resolved = resolved;
	}

	private static Object resolve(ObjectKey key) {
		KnowledgeItem item = PersistencyLayer.getKnowledgeBase().resolveObjectKey(key);
		if (item == null) {
			return null;
		}
		TLObject result = item.getWrapper();
		return result.tValid() ? result : null;
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
		put(id, value);
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
		put(id, value);
		if (value != null && id != null) {
			_resolved.putIfAbsent(value, id);
		}
	}

	private void put(String id, Object value) {
		if (id == null) {
			return;
		}
		Object clash = _ids.put(id, value);
		if (clash != null && clash != value
			&& !(clash instanceof ObjectKey key && value instanceof TLObject obj && key.equals(obj.tId()))) {
			Logger.warn("Inconsistent assignment to ID '" + id + "': " + clash + ", vs. " + value,
				JoiningModelBinding.class);
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
