/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.knx;

import com.top_logic.basic.annotation.InApp;
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.model.annotate.TLAttributeAnnotation;
import com.top_logic.model.search.expr.config.dom.Expr;

/**
 * Annotation of an attribute of type <code>ehome.logical:GA</code> stating the notation its group
 * addresses are written in.
 *
 * <p>
 * The {@link #getStyle() expression} receives the object the attribute is defined on and yields a
 * classifier of <code>ehome.logical:GroupAddressStyle</code> (or its name). Without the annotation,
 * or when the expression yields nothing, addresses are written in
 * {@link GroupAddressStyle#DEFAULT}.
 * </p>
 *
 * <pre>
 * &lt;property name="address" type="ehome.logical:GA"&gt;
 *   &lt;annotations&gt;
 *     &lt;ga-style style="ga -&gt; ..."/&gt;
 *   &lt;/annotations&gt;
 * &lt;/property&gt;
 * </pre>
 */
@TagName("ga-style")
@InApp
public interface GAStyle extends TLAttributeAnnotation {

	/**
	 * @see #getStyle()
	 */
	String STYLE = "style";

	/**
	 * Function computing the {@link GroupAddressStyle} from the object the annotated attribute is
	 * defined on.
	 */
	@Name(STYLE)
	@Mandatory
	Expr getStyle();

}
