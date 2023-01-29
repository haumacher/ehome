/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.binding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.Log;
import com.top_logic.basic.util.I18NBundle;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.search.expr.config.dom.Expr;

import de.haumacher.ehome.importer.I18NConstants;
import de.haumacher.ehome.importer.handlers.Handler;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class DefaultImportContext implements ImportContext {

	private final Log _log;

	private final I18NBundle _resources;

	private final ModelBinding _binding;

	private final Map<String, Object> _vars = new HashMap<>();

	private final Map<String, List<Resolver>> _resolvers = new HashMap<>();

	/**
	 * Creates a {@link DefaultImportContext}.
	 */
	public DefaultImportContext(Log log, I18NBundle resources, ModelBinding binding) {
		_log = log;
		_resources = resources;
		_binding = binding;
	}

	@Override
	public Object withVar(String var, Object value, XMLStreamReader in, Handler inner) throws XMLStreamException {
		if (var == null) {
			return inner.importXml(this, in);
		}
		if (var.isEmpty()) {
			throw new IllegalArgumentException("Variable must not be empty.");
		}
		boolean override = _vars.containsKey(var);
		Object before = _vars.put(var, value);
		try {
			return inner.importXml(this, in);
		} finally {
			if (override) {
				_vars.put(var, before);
			} else {
				_vars.remove(var);
			}
		}
	}

	@Override
	public Object getVar(String name) {
		Object result = getVarOrNull(name);
		if (result == null && name != null && !_vars.containsKey(name)) {
			throw new IllegalArgumentException("Variable '" + name + "' not defined.");
		}
		return result;
	}

	@Override
	public Object getVarOrNull(String name) {
		return _vars.get(name);
	}

	@Override
	public void error(Location location, ResKey errorKey) {
//		_log.error(_resources.getString(I18NConstants.ERROR_PROBLEM__FILE_LINE_COL_MSG.fill(location.getSystemId(),
//			location.getLineNumber(), location.getColumnNumber(), errorKey)));

		// TODO
		System.err.println(errorKey.toString());
	}

	interface Resolution {

		/**
		 * TODO
		 *
		 * @param target
		 */
		void resolve(Object target);

	}

	abstract class Resolver implements Resolution {

		private final Location _location;

		private final String _id;

		/**
		 * Creates a {@link Resolver}.
		 *
		 * @param location
		 * @param id
		 */
		public Resolver(Location location, String id) {
			_location = location;
			_id = id;
		}

		/**
		 * TODO
		 *
		 */
		public void errorUnresolved() {
			error(_location, I18NConstants.ERROR_UNRESOLVED_ID__ID.fill(_id));
		}

		/**
		 * TODO
		 *
		 * @return
		 */
		public String getId() {
			return _id;
		}

	}

	class Ref extends Resolver {

		private List<Resolution> _resolutions = new ArrayList<>();

		private Object _target;

		/**
		 * Creates a {@link Ref}.
		 *
		 * @param id
		 */
		public Ref(Location location, String id) {
			super(location, id);
		}

		void deref(Resolution fun) {
			if (_resolutions == null) {
				fun.resolve(_target);
			} else {
				_resolutions.add(fun);
			}
		}

		@Override
		public void resolve(Object target) {
			_target = target;
			_resolutions.forEach(fun -> fun.resolve(target));
			_resolutions = null;
		}

	}

	private Resolver add(Resolver resolver) {
		String id = resolver.getId();
		List<Resolver> resolvers = _resolvers.get(id);
		if (resolvers == null) {
			resolvers = new ArrayList<>();
			_resolvers.put(id, resolvers);
		}
		resolvers.add(resolver);
		return resolver;
	}

	@Override
	public void setReference(Object obj, String reference, Object target) {
		deref(obj, x -> deref(target, y -> _binding.setReference(x, reference, y)));
	}

	private void deref(Object obj, Resolution fun) {
		if (obj instanceof Ref) {
			((Ref) obj).deref(fun);
		} else {
			fun.resolve(obj);
		}
	}

	@Override
	public Object resolveObject(String id) {
		Object result = resolveObjectBackwards(id);
		if (result != null) {
			return result;
		}

		return add(new Ref(null, id));
	}

	@Override
	public Object resolveObjectBackwards(String id) {
		return _binding.resolveObject(id);
	}

	@Override
	public Object createObject(String modelType, String id) {
		if (id != null) {
			Object existing = _binding.resolveObject(id);
			if (existing != null) {
				error(null, I18NConstants.ERROR_DUPLICATE_IDENTIFIER__ID.fill(id));
				return existing;
			}
		}
		Object result = _binding.createObject(modelType, id);
		resolveReferences(id, result);
		return result;
	}

	@Override
	public void assignId(Object value, String id) {
		assert value != null : "Null may not have an ID.";
		_binding.assignId(value, id);
		resolveReferences(id, value);
	}

	private void resolveReferences(String id, Object result) {
		if (id != null) {
			List<Resolver> resolvers = _resolvers.remove(id);
			if (resolvers != null) {
				resolvers.forEach(r -> r.resolve(result));
			}
		}
	}

	@Override
	public void setProperty(Object obj, String property, Object applicationValue) {
		deref(obj, x -> _binding.setProperty(x, property, applicationValue));
	}

	@Override
	public void isInstanceOf(Object obj, String type, Runnable yes, Runnable no) {
		deref(obj, x -> {
			if (_binding.isInstanceOf(x, type)) {
				yes.run();
			} else {
				no.run();
			}
		});
	}

	@Override
	public void addValue(Object obj, String part, Object element) {
		deref(obj, x -> deref(element, y -> _binding.addValue(x, part, y)));
	}

	@Override
	public void close() {
		_resolvers.values().forEach(l -> l.forEach(Resolver::errorUnresolved));
	}

	@Override
	public Object eval(Expr expr, Object... args) {
		return _binding.eval(expr, args);
	}
}
