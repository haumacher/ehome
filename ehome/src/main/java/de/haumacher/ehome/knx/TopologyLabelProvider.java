/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import com.top_logic.layout.LabelProvider;
import com.top_logic.model.TLObject;

/**
 * The label of an area or a line of the topology: its name, and without one its address
 * (<code>1</code> for an area, <code>1.1</code> for a line), as ETS shows it (haumacher/ehome #168).
 *
 * <p>
 * ETS 6 writes areas and lines without a name (the attribute <code>Name</code> is optional in the
 * project schema); the import keeps them without one, and the export writes none back. The address is
 * the label only, never stored as the name.
 * </p>
 */
public class TopologyLabelProvider implements LabelProvider {

	@Override
	public String getLabel(Object object) {
		if (!(object instanceof TLObject obj) || !obj.tValid()) {
			return null;
		}
		Object name = obj.tValueByName("name");
		if (name instanceof String text && !text.isBlank()) {
			return text;
		}
		return address(obj);
	}

	/**
	 * The address of an area (<code>1</code>) or a line (<code>1.1</code>); <code>null</code> if it has
	 * none.
	 */
	static String address(TLObject obj) {
		Object address = obj.tValueByName("address");
		if (address == null) {
			return null;
		}
		TLObject area = obj.tContainer();
		if (area != null && area.tType().getPart("lines") != null) {
			Object areaAddress = area.tValueByName("address");
			return (areaAddress == null ? "?" : areaAddress) + "." + address;
		}
		return address.toString();
	}

}
