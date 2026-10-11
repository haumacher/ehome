/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.exporter;

import static de.haumacher.ehome.exporter.Values.classifier;
import static de.haumacher.ehome.exporter.Values.classifiers;
import static de.haumacher.ehome.exporter.Values.extId;
import static de.haumacher.ehome.exporter.Values.hex;
import static de.haumacher.ehome.exporter.Values.list;
import static de.haumacher.ehome.exporter.Values.medium;
import static de.haumacher.ehome.exporter.Values.number;
import static de.haumacher.ehome.exporter.Values.ref;
import static de.haumacher.ehome.exporter.Values.string;

import java.util.List;

import javax.xml.stream.XMLStreamException;

import com.top_logic.model.TLObject;

import de.haumacher.ehome.knx.ExtIds;

/**
 * Writes <code>knx_master.xml</code>: the master data a project uses (datapoint types, mask
 * versions, manufacturers) with the translations of the datapoint types.
 *
 * <p>
 * ETS writes its whole master data into every export; the model holds what the imported files
 * contained, so the export writes the part the project needs: the datapoint types its programs,
 * devices and group addresses name (each main type with all its subtypes), the mask versions of its
 * programs and those they are downward compatible to, and the manufacturers of its devices and
 * programs.
 * </p>
 */
final class MasterDataExport {

	private final Usage _usage;

	private final Texts _texts = new Texts("en");

	MasterDataExport(Usage usage) {
		_usage = usage;
	}

	void write(XmlOut out) throws XMLStreamException {
		out.open("MasterData");
		writeDatapointTypes(out);
		writeMasks(out);
		writeManufacturers(out);
		_texts.writeLanguages(out);
		out.close();
	}

	private void writeDatapointTypes(XmlOut out) throws XMLStreamException {
		if (_usage._datapointTypes.isEmpty()) {
			return;
		}
		TLObject root = _usage._datapointTypes.iterator().next().tContainer();
		out.open("DatapointTypes");
		for (TLObject type : list(root, "types")) {
			if (!_usage._datapointTypes.contains(type)) {
				continue;
			}
			_texts.unit(extId(type));
			Object number = type.tValueByName("number");
			out.open("DatapointType")
				.attr("Id", extId(type))
				.attr("Number", number)
				// Not modeled: ETS names a main type by its number.
				.attr("Name", number + ".xxx")
				.attr("Text", _texts.text(type, "description", "Text"))
				.attr("SizeInBit", number(type, "sizeInBit"));
			List<TLObject> subtypes = list(type, "subtypes");
			if (!subtypes.isEmpty()) {
				TLObject defaultSubtype = ref(type, "defaultSubtype");
				out.open("DatapointSubtypes");
				for (TLObject subtype : subtypes) {
					out.open("DatapointSubtype")
						.attr("Id", extId(subtype))
						.attr("Number", number(subtype, "number"))
						.attr("Name", string(subtype, "name"))
						.attr("Text", _texts.text(subtype, "description", "Text"))
						.attr("Default", subtype == defaultSubtype ? "true" : null)
						.close();
				}
				out.close();
			}
			out.close();
		}
		out.close();
	}

	private void writeMasks(XmlOut out) throws XMLStreamException {
		if (_usage._masks.isEmpty()) {
			return;
		}
		TLObject root = _usage._masks.iterator().next().tContainer();
		out.open("MaskVersions");
		for (TLObject mask : list(root, "masks")) {
			if (_usage._masks.contains(mask)) {
				writeMask(out, mask);
			}
		}
		out.close();
	}

	private void writeMask(XmlOut out, TLObject mask) throws XMLStreamException {
		out.open("MaskVersion")
			.attr("Id", extId(mask))
			.attr("MaskVersion", number(mask, "number"))
			.attr("Name", string(mask, "name"))
			.attr("ManagementModel", classifier(mask, "managementModel"))
			.attr("MediumTypeRefId", medium(mask, "medium"))
			.attr("OtherMediumTypeRefId", medium(mask, "otherMedium"))
			.attr("MgmtDescriptor01", hex(mask, "managementDescriptor"));

		List<TLObject> compatible = list(mask, "compatibleMasks");
		if (!compatible.isEmpty()) {
			out.open("DownwardCompatibleMasks");
			for (TLObject other : compatible) {
				out.open("DownwardCompatibleMask").attr("RefId", extId(other)).close();
			}
			out.close();
		}
		List<TLObject> entries = list(mask, "entries");
		if (!entries.isEmpty()) {
			out.open("MaskEntries");
			for (TLObject entry : entries) {
				out.open("MaskEntry")
					// The ID of an entry is formed by ETS from the mask and the entry name.
					.attr("Id", extId(mask) + "_ME-" + ExtIds.escape(string(entry, "name")))
					.attr("Name", string(entry, "name"))
					.attr("Address", number(entry, "address"))
					.close();
			}
			out.close();
		}

		List<TLObject> features = list(mask, "features");
		List<TLObject> resources = list(mask, "resources");
		List<TLObject> procedures = list(mask, "procedures");
		List<TLObject> memory = list(mask, "memory");
		if (!features.isEmpty() || !resources.isEmpty() || !procedures.isEmpty() || !memory.isEmpty()) {
			out.open("HawkConfigurationData");
			if (!features.isEmpty()) {
				out.open("Features");
				for (TLObject feature : features) {
					out.open("Feature").attr("Name", string(feature, "name")).attr("Value", number(feature, "value"))
						.close();
				}
				out.close();
			}
			if (!resources.isEmpty()) {
				out.open("Resources");
				for (TLObject resource : resources) {
					out.open("Resource")
						.attr("Name", string(resource, "name"))
						.attr("Access", classifiers(resource, "access"))
						.attr("MgmtStyle", classifiers(resource, "management"));
					writeLocation(out, "Location", ref(resource, "location"));
					writeLocation(out, "ImgLocation", ref(resource, "imageLocation"));
					out.open("ResourceType")
						.attr("Length", number(resource, "length"))
						.attr("Flavour", string(resource, "flavour"))
						.close();
					writeAccessRights(out, resource);
					out.close();
				}
				out.close();
			}
			if (!procedures.isEmpty()) {
				out.open("Procedures");
				for (TLObject procedure : procedures) {
					out.open("Procedure")
						.attr("ProcedureType", classifier(procedure, "kind"))
						.attr("ProcedureSubType", classifier(procedure, "scope"))
						.attr("Access", classifiers(procedure, "access"));
					LoadSteps.write(out, procedure);
					out.close();
				}
				out.close();
			}
			if (!memory.isEmpty()) {
				out.open("MemorySegments");
				for (TLObject segment : memory) {
					out.open("MemorySegment")
						.attr("Length", number(segment, "length"))
						.attr("Optional", Values.trueOnly(segment, "optional", "true"))
						.attr("MemoryType", classifier(segment, "memoryType"));
					writeLocation(out, "Location", ref(segment, "location"));
					writeAccessRights(out, segment);
					out.close();
				}
				out.close();
			}
			out.close();
		}
		out.close();
	}

	private static void writeLocation(XmlOut out, String element, TLObject location) throws XMLStreamException {
		if (location == null) {
			return;
		}
		out.open(element)
			.attr("AddressSpace", classifier(location, "addressSpace"))
			.attr("StartAddress", number(location, "startAddress"))
			.attr("InterfaceObjectRef", number(location, "interfaceObject"))
			.attr("PropertyID", number(location, "propertyId"))
			.attr("Occurrence", number(location, "occurrence"))
			.attr("PtrResource", string(location, "pointerResource"))
			.close();
	}

	private static void writeAccessRights(XmlOut out, TLObject obj) throws XMLStreamException {
		out.open("AccessRights")
			.attr("Read", classifier(obj, "readRights"))
			.attr("Write", classifier(obj, "writeRights"))
			.close();
	}

	private void writeManufacturers(XmlOut out) throws XMLStreamException {
		if (_usage._masterManufacturers.isEmpty()) {
			return;
		}
		out.open("Manufacturers");
		for (TLObject manufacturer : Values.sortedById(_usage._masterManufacturers)) {
			out.open("Manufacturer")
				.attr("Id", extId(manufacturer))
				.attr("Name", string(manufacturer, "name"))
				.attr("KnxManufacturerId", number(manufacturer, "id"))
				.close();
		}
		out.close();
	}

}
