/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.Collection;
import java.util.function.Consumer;

import javax.xml.stream.Location;

import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.xio.importer.binding.ModelBinding;
import com.top_logic.xio.importer.handlers.ImportPart;

/**
 * {@link ModelBinding} that does not add an object to a reference that already contains it.
 *
 * <p>
 * An import that reuses an existing object (<code>join-duplicates</code>), such as a datapoint type
 * known from an earlier import, links it to its container again; this binding makes that a no-op.
 * </p>
 */
public class JoiningModelBinding implements ModelBinding {

	private final ModelBinding _impl;

	/**
	 * Creates a {@link JoiningModelBinding}.
	 *
	 * @param impl
	 *        The binding to delegate to.
	 */
	public JoiningModelBinding(ModelBinding impl) {
		_impl = impl;
	}

	@Override
	public void addValue(ImportPart handler, Object obj, String part, Object element) {
		Object value = ((TLObject) obj).tValueByName(part);
		if (value instanceof Collection<?> collection && collection.contains(element)) {
			return;
		}
		_impl.addValue(handler, obj, part, element);
	}

	@Override
	public Object resolveObject(ImportPart handler, Location location, String id) {
		return _impl.resolveObject(handler, location, id);
	}

	@Override
	public Object createObject(ImportPart handler, Location location, String modelType, String id) {
		return _impl.createObject(handler, location, modelType, id);
	}

	@Override
	public void assignId(Object value, String id) {
		_impl.assignId(value, id);
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
		_impl.setReference(handler, obj, name, value);
	}

	@Override
	public Object eval(Expr expr, Object... args) {
		return _impl.eval(expr, args);
	}

	@Override
	public boolean isInstanceOf(Object obj, String type) {
		return _impl.isInstanceOf(obj, type);
	}

}
