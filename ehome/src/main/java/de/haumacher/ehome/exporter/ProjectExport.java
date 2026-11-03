/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.exporter;

import static de.haumacher.ehome.exporter.Values.classifier;
import static de.haumacher.ehome.exporter.Values.date;
import static de.haumacher.ehome.exporter.Values.extId;
import static de.haumacher.ehome.exporter.Values.extIds;
import static de.haumacher.ehome.exporter.Values.flag;
import static de.haumacher.ehome.exporter.Values.groupAddress;
import static de.haumacher.ehome.exporter.Values.list;
import static de.haumacher.ehome.exporter.Values.number;
import static de.haumacher.ehome.exporter.Values.ref;
import static de.haumacher.ehome.exporter.Values.string;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import javax.xml.stream.XMLStreamException;

import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;

import de.haumacher.ehome.knx.ParameterValues;

/**
 * Writes the files of a project: <code>P-xxxx/Project.xml</code> (the project information) and
 * <code>P-xxxx/0.xml</code> (the installation: topology with the devices, buildings, group
 * addresses).
 */
final class ProjectExport {

	/**
	 * The download parts of a device and the attributes ETS records them in.
	 */
	private static final String[][] LOADED = {
		{ "IndividualAddress", "IndividualAddressLoaded" },
		{ "ApplicationProgram", "ApplicationProgramLoaded" },
		{ "Parameters", "ParametersLoaded" },
		{ "GroupAddresses", "CommunicationPartLoaded" },
		{ "MediumConfig", "MediumConfigLoaded" },
	};

	private final TLObject _project;

	private final Usage _usage;

	ProjectExport(TLObject project, Usage usage) {
		_project = project;
		_usage = usage;
	}

	void writeProject(XmlOut out) throws XMLStreamException {
		out.open("Project").attr("Id", extId(_project));
		out.open("ProjectInformation")
			.attr("Name", string(_project, "name"))
			.attr("GroupAddressStyle", classifier(_project, "groupAddressStyle"))
			.close();
		out.close();
	}

	void writeInstallation(XmlOut out) throws XMLStreamException {
		out.open("Project").attr("Id", extId(_project));
		out.open("Installations");
		// Not modeled: a project has one installation; its name and number are required.
		out.open("Installation").attr("Name", "").attr("InstallationId", "0");
		writeTopology(out);
		writeBuildings(out);
		writeGroupAddresses(out);
		out.close();
		out.close();
		out.close();
	}

	private void writeTopology(XmlOut out) throws XMLStreamException {
		out.open("Topology");
		for (TLObject area : byAddress(list(_project, "areas"))) {
			out.open("Area")
				.attr("Id", extId(area))
				.attr("Name", string(area, "name"))
				.attr("Address", number(area, "address"));
			for (TLObject line : byAddress(list(area, "lines"))) {
				out.open("Line")
					.attr("Id", extId(line))
					.attr("Name", string(line, "name"))
					.attr("Address", number(line, "address"))
					// Not modeled; required by the schema.
					.attr("MediumTypeRefId", "MT-0");
				for (TLObject device : byAddress(list(line, "devices"))) {
					writeDevice(out, device);
				}
				out.close();
			}
			out.close();
		}
		List<TLObject> unassigned = list(_project, "unassignedDevices");
		if (!unassigned.isEmpty()) {
			out.open("UnassignedDevices");
			for (TLObject device : unassigned) {
				writeDevice(out, device);
			}
			out.close();
		}
		out.close();
	}

	private static List<TLObject> byAddress(List<TLObject> objects) {
		List<TLObject> result = Values.sortedById(objects);
		result.sort(Comparator.comparing(o -> (Integer) o.tValueByName("address"),
			Comparator.nullsLast(Comparator.naturalOrder())));
		return result;
	}

	private void writeDevice(XmlOut out, TLObject device) throws XMLStreamException {
		Collection<?> loaded = (Collection<?>) device.tValueByName("loaded");
		out.open("DeviceInstance")
			.attr("Id", extId(device))
			.attr("Name", string(device, "title"))
			.attr("Description", string(device, "description"))
			.attr("InstallationHints", string(device, "installationHints"))
			.attr("SerialNumber", string(device, "serialNumber"))
			.attr("ProductRefId", extId(ref(device, "type")))
			.attr("Hardware2ProgramRefId", extId(ref(device, "program")))
			.attr("Address", number(device, "address"))
			.attr("LastModified", date(device, "lastModified"))
			.attr("LastDownload", date(device, "lastDownload"));
		for (String[] part : LOADED) {
			boolean isLoaded = loaded != null
				&& loaded.stream().anyMatch(p -> ((TLClassifier) p).getName().equals(part[0]));
			out.attr(part[1], isLoaded ? "1" : "0");
		}

		List<TLObject> settings = list(device, "settings");
		if (!settings.isEmpty()) {
			out.open("ParameterInstanceRefs");
			for (TLObject setting : settings) {
				out.open("ParameterInstanceRef")
					.attr("RefId", extId(ref(setting, "parameter")))
					.attr("Value", ParameterValues.encode(setting))
					.close();
			}
			out.close();
		}
		List<TLObject> connectors = Values.sortedById(list(device, "connectors"));
		connectors.sort(Comparator.comparing(c -> extId(ref(c, "port")), Values::compareIds));
		if (!connectors.isEmpty()) {
			out.open("ComObjectInstanceRefs");
			for (TLObject connector : connectors) {
				out.open("ComObjectInstanceRef")
					.attr("RefId", extId(ref(connector, "port")))
					.attr("Text", string(connector, "text"))
					.attr("FunctionText", string(connector, "function"))
					.attr("Description", string(connector, "description"))
					.attr("DatapointType", extIds(list(connector, "datapointTypes")))
					.attr("Priority", classifier(connector, "priority"))
					.attr("IsActive", flag(connector, "active"))
					.attr("ReadFlag", classifier(connector, "canRead"))
					.attr("WriteFlag", classifier(connector, "canWrite"))
					.attr("CommunicationFlag", classifier(connector, "communication"))
					.attr("TransmitFlag", classifier(connector, "transmission"))
					.attr("UpdateFlag", classifier(connector, "update"))
					.attr("ReadOnInitFlag", classifier(connector, "readOnInit"));
				writeConnections(out, connector);
				out.close();
			}
			out.close();
		}
		out.close();
	}

	private static void writeConnections(XmlOut out, TLObject connector) throws XMLStreamException {
		List<TLObject> connections = new ArrayList<>(list(connector, "connections"));
		if (connections.isEmpty()) {
			return;
		}
		// The schema puts the sending connection first.
		connections.sort(Comparator
			.comparing((TLObject c) -> ((TLClassifier) c.tValueByName("kind")).getName().equals("send") ? 0 : 1)
			.thenComparing(c -> extId(ref(c, "target")), Values::compareIds));
		out.open("Connectors");
		for (TLObject connection : connections) {
			boolean send = ((TLClassifier) connection.tValueByName("kind")).getName().equals("send");
			out.open(send ? "Send" : "Receive")
				.attr("GroupAddressRefId", extId(ref(connection, "target")))
				.attr("Acknowledge", Values.trueOnly(connection, "acknowledge", "1"))
				.close();
		}
		out.close();
	}

	private void writeBuildings(XmlOut out) throws XMLStreamException {
		out.open("Buildings");
		for (TLObject part : list(_project, "buildings")) {
			writeBuildingPart(out, part);
		}
		out.close();
	}

	private void writeBuildingPart(XmlOut out, TLObject part) throws XMLStreamException {
		out.open("BuildingPart")
			.attr("Id", extId(part))
			.attr("Name", string(part, "name"))
			.attr("Type", buildingType(part));
		for (TLObject child : list(part, "parts")) {
			writeBuildingPart(out, child);
		}
		for (TLObject device : Values.sortedById(_usage._devices)) {
			if (ref(device, "location") == part) {
				out.open("DeviceInstanceRef").attr("RefId", extId(device)).close();
			}
		}
		out.close();
	}

	private static String buildingType(TLObject part) {
		String type = part.tType().getName();
		return switch (type) {
			case "Building", "Floor", "Stairway", "Room", "Corridor", "DistributionBoard" -> type;
			default -> "BuildingPart";
		};
	}

	private void writeGroupAddresses(XmlOut out) throws XMLStreamException {
		out.open("GroupAddresses");
		out.open("GroupRanges");
		for (TLObject definition : byGroupAddress(list(_project, "groupAddresses"))) {
			writeGroupDefinition(out, definition);
		}
		out.close();
		out.close();
	}

	private void writeGroupDefinition(XmlOut out, TLObject definition) throws XMLStreamException {
		if (isRange(definition)) {
			out.open("GroupRange")
				.attr("Id", extId(definition))
				.attr("Name", string(definition, "name"))
				.attr("RangeStart", groupAddress(definition, "rangeStart"))
				.attr("RangeEnd", groupAddress(definition, "rangeEnd"))
				.attr("Unfiltered", Values.trueOnly(definition, "unfiltered", "1"))
				.attr("Description", string(definition, "description"));
			List<TLObject> parts = byGroupAddress(list(definition, "parts"));
			// The schema puts the ranges before the addresses.
			for (TLObject part : parts) {
				if (isRange(part)) {
					writeGroupDefinition(out, part);
				}
			}
			for (TLObject part : parts) {
				if (!isRange(part)) {
					writeGroupDefinition(out, part);
				}
			}
			out.close();
		} else {
			out.open("GroupAddress")
				.attr("Id", extId(definition))
				.attr("Name", string(definition, "name"))
				.attr("Address", groupAddress(definition, "address"))
				.attr("Central", Values.trueOnly(definition, "central", "1"))
				.attr("Unfiltered", Values.trueOnly(definition, "unfiltered", "1"))
				.attr("Description", string(definition, "description"))
				.attr("DatapointType", extIds(list(definition, "datapointTypes")))
				.close();
		}
	}

	private static boolean isRange(TLObject definition) {
		return TLModelUtil.qualifiedName(definition.tType()).equals("ehome.logical:GroupRange");
	}

	private static List<TLObject> byGroupAddress(List<TLObject> definitions) {
		List<TLObject> result = Values.sortedById(definitions);
		result.sort(Comparator.comparingInt(ProjectExport::start));
		return result;
	}

	private static int start(TLObject definition) {
		String value = groupAddress(definition, isRange(definition) ? "rangeStart" : "address");
		return value == null ? Integer.MAX_VALUE : Integer.parseInt(value);
	}

}
