/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.binding;

import com.top_logic.model.search.expr.config.dom.Expr;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public interface ModelBindingBase {

	/**
	 * Resolves the object with the given ID.
	 * 
	 * <p>
	 * The object must not necessarily be defined before this call. In that case, a future for the
	 * object is returned.
	 * </p>
	 * 
	 * @param id
	 *        The ID of the object to resolve.
	 * @return The object or future for the object with the given ID.
	 */
	Object resolveObject(String id);

	/**
	 * TODO
	 *
	 * @param modelType
	 * @param id
	 * @return
	 */
	Object createObject(String modelType, String id);

	void assignId(Object value, String id);

	/**
	 * TODO
	 *
	 * @param property
	 * @param applicationValue
	 */
	void setProperty(Object obj, String property, Object applicationValue);

	/**
	 * TODO
	 *
	 * @param obj
	 * @param part
	 * @param element
	 */
	void addValue(Object obj, String part, Object element);

	/**
	 * TODO
	 *
	 * @param obj
	 * @param reference
	 * @param target
	 */
	void setReference(Object obj, String reference, Object target);

	/**
	 * TODO
	 * @param expr
	 * @param args TODO
	 * @return
	 */
	Object eval(Expr expr, Object... args);

}
