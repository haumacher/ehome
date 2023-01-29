/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.binding;

import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.util.ResKey;

import de.haumacher.ehome.importer.handlers.Handler;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public interface ImportContext extends ModelBindingBase, AutoCloseable {

	/**
	 * Implicit variable assigned to the outer object by an inner object creation.
	 * 
	 * @see #THIS_VAR
	 */
	String SCOPE_VAR = "scope";

	/**
	 * Implicit variable assigned with the new object by an object creation.
	 * 
	 * @see #SCOPE_VAR
	 */
	String THIS_VAR = "this";

	void isInstanceOf(Object obj, String type, Runnable yes, Runnable no);

	Object withVar(String var, Object value, XMLStreamReader in, Handler inner) throws XMLStreamException;

	/**
	 * TODO
	 *
	 * @param name
	 * @return
	 */
	Object getVar(String name);

	/**
	 * TODO
	 *
	 * @param name
	 * @return
	 */
	Object getVarOrNull(String name);

	/**
	 * TODO
	 */
	void error(Location location, ResKey errorKey);

	@Override
	void close();

	/**
	 * Resolves the object with the given ID, if it was created before.
	 *
	 * @param id
	 *        The ID of the object to resolve.
	 * @return The object already created with the given ID, or <code>null</code> if no such object
	 *         was created before.
	 * 
	 * @see #resolveObject(String)
	 */
	Object resolveObjectBackwards(String id);

}
