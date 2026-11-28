/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import com.top_logic.layout.LabelProvider;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.model.physical.Area;
import de.haumacher.ehome.model.physical.Line;

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
		String name = obj instanceof Area area ? area.getName() : obj instanceof Line line ? line.getName() : null;
		if (name != null && !name.isBlank()) {
			return name;
		}
		return address(obj);
	}

	/**
	 * The address of an area (<code>1</code>) or a line (<code>1.1</code>); <code>null</code> if it has
	 * none.
	 */
	static String address(TLObject obj) {
		if (obj instanceof Line line) {
			Integer address = line.getAddress();
			if (address == null) {
				return null;
			}
			if (line.tContainer() instanceof Area area) {
				Integer areaAddress = area.getAddress();
				return (areaAddress == null ? "?" : areaAddress) + "." + address;
			}
			return address.toString();
		}
		if (obj instanceof Area area) {
			Integer address = area.getAddress();
			return address == null ? null : address.toString();
		}
		return null;
	}

}
