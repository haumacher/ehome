/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.exporter;

import static de.haumacher.ehome.exporter.Values.base64;
import static de.haumacher.ehome.exporter.Values.bool;
import static de.haumacher.ehome.exporter.Values.classifier;
import static de.haumacher.ehome.exporter.Values.decimal;
import static de.haumacher.ehome.exporter.Values.decimals;
import static de.haumacher.ehome.exporter.Values.extId;
import static de.haumacher.ehome.exporter.Values.extIds;
import static de.haumacher.ehome.exporter.Values.flag;
import static de.haumacher.ehome.exporter.Values.list;
import static de.haumacher.ehome.exporter.Values.number;
import static de.haumacher.ehome.exporter.Values.objectSize;
import static de.haumacher.ehome.exporter.Values.ref;
import static de.haumacher.ehome.exporter.Values.sorted;
import static de.haumacher.ehome.exporter.Values.string;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLStreamException;

import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;

import de.haumacher.ehome.importer.ProgramLanguages;
import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.EnumerationValue;

/**
 * Writes the file of an application program (<code>M-xxxx/M-xxxx_A-….xml</code>): the static part
 * (segments, parameter types, parameters, references, communication objects, tables, load
 * procedures, options), the dynamic part (the configuration structure) and the translations.
 */
final class ProgramExport {

	private final TLObject _program;

	private final Texts _texts;

	ProgramExport(TLObject program) {
		_program = program;
		_texts = new Texts("de", ProgramLanguages.languages(program));
	}

	void write(XmlOut out) throws XMLStreamException {
		TLObject manufacturer = ref(_program, "manufacturer");
		_texts.unit(extId(_program));
		out.open("ManufacturerData");
		out.open("Manufacturer").attr("RefId", extId(manufacturer));
		out.open("ApplicationPrograms");
		out.open("ApplicationProgram")
			.attr("Id", extId(_program))
			.attr("ApplicationNumber", number(_program, "applicationNumber"))
			.attr("ApplicationVersion", number(_program, "applicationVersion"))
			// Not modeled; required by the schema.
			.attr("ProgramType", "ApplicationProgram")
			.attr("MaskVersion", extId(ref(_program, "mask")))
			.attr("Name", _texts.text(_program, "name", "Name"))
			.attr("LoadProcedureStyle", classifier(_program, "loadProcedureStyle"))
			.attr("PeiType", number(_program, "peiType"))
			.attr("DefaultLanguage", _texts.defaultLanguage())
			.attr("DynamicTableManagement", flag(_program, "dynamicTableManagement"))
			// Not modeled; required by the schema.
			.attr("Linkable", "0")
			.attr("OriginalManufacturer", extId(ref(_program, "originalManufacturer")));
		writeStatic(out);
		writeDynamic(out);
		out.close();
		out.close();
		_texts.writeLanguages(out);
		out.close();
		out.close();
	}

	private void writeStatic(XmlOut out) throws XMLStreamException {
		out.open("Static");
		writeCode(out);
		writeParameterTypes(out);
		writeParameters(out);
		writeParameterRefs(out);
		writeComObjects(out);
		writeTable(out, "AddressTable", ref(_program, "addressTable"));
		writeTable(out, "AssociationTable", ref(_program, "associationTable"));
		writeLoadProcedures(out);
		writeOptions(out);
		out.close();
	}

	private void writeCode(XmlOut out) throws XMLStreamException {
		List<TLObject> segments = sorted(_program, "segments");
		if (segments.isEmpty()) {
			return;
		}
		out.open("Code");
		for (TLObject segment : segments) {
			if (isA(segment, "ehome.operational:CodeSegment")) {
				out.open("AbsoluteSegment")
					.attr("Id", extId(segment))
					.attr("Name", string(segment, "name"))
					.attr("Address", number(segment, "address"))
					.attr("Size", number(segment, "size"))
					.attr("UserMemory", flag(segment, "userMemory"))
					.attr("MemoryType", classifier(segment, "memoryType"));
				writeSegmentContent(out, segment);
				out.close();
			}
		}
		for (TLObject segment : segments) {
			if (isA(segment, "ehome.operational:RelativeSegment")) {
				out.open("RelativeSegment")
					.attr("Id", extId(segment))
					.attr("Name", string(segment, "name"))
					.attr("Offset", number(segment, "offset"))
					.attr("Size", number(segment, "size"))
					.attr("LoadStateMachine", number(segment, "stateMachine"));
				writeSegmentContent(out, segment);
				out.close();
			}
		}
		out.close();
	}

	private static void writeSegmentContent(XmlOut out, TLObject segment) throws XMLStreamException {
		String data = base64(segment, "data");
		if (data != null) {
			out.open("Data").text(data).close();
		}
		String mask = base64(segment, "mask");
		if (mask != null) {
			out.open("Mask").text(mask).close();
		}
	}

	private void writeParameterTypes(XmlOut out) throws XMLStreamException {
		List<TLObject> types = sorted(_program, "parameterTypes");
		if (types.isEmpty()) {
			return;
		}
		out.open("ParameterTypes");
		for (TLObject type : types) {
			out.open("ParameterType")
				.attr("Id", extId(type))
				.attr("Name", string(type, "name"));
			switch (TLModelUtil.qualifiedName(type.tType())) {
				case "ehome.operational:VoidType" -> out.open("TypeNone").close();
				case "ehome.operational:PictureType" -> out.open("TypePicture")
					.attr("RefId", extId(ref(type, "picture")))
					.close();
				case "ehome.operational:TextType" -> out.open("TypeText")
					.attr("SizeInBit", number(type, "bits"))
					.attr("Pattern", string(type, "pattern"))
					.close();
				case "ehome.operational:NumericType" -> out.open("TypeNumber")
					.attr("SizeInBit", number(type, "bits"))
					.attr("Type", classifier(type, "kind"))
					.attr("minInclusive", number(type, "min"))
					.attr("maxInclusive", number(type, "max"))
					.close();
				case "ehome.operational:FloatingPointType" -> out.open("TypeFloat")
					.attr("Encoding", classifier(type, "encoding"))
					.attr("minInclusive", decimal(type, "min"))
					.attr("maxInclusive", decimal(type, "max"))
					.attr("DisplayFormat", decimals(type, "decimals"))
					.close();
				case "ehome.operational:EnumerationType" -> {
					out.open("TypeRestriction")
						.attr("Base", classifier(type, "kind"))
						.attr("SizeInBit", number(type, "bits"));
					for (TLObject value : list(type, "values")) {
						out.open("Enumeration")
							.attr("Text", _texts.text(value, "name", "Text"))
							.attr("Value", Long.toString(((EnumerationValue) value).getUnsignedValue()))
							.attr("Id", extId(value))
							.attr("DisplayOrder", number(value, "index"))
							.attr("BinaryValue", base64(value, "binary"))
							.close();
					}
					out.close();
				}
				case "ehome.operational:TimeType" -> out.open("TypeTime")
					.attr("SizeInBit", number(type, "bits"))
					.attr("Unit", classifier(type, "unit"))
					.attr("minInclusive", number(type, "min"))
					.attr("maxInclusive", number(type, "max"))
					.close();
				case "ehome.operational:DateType" -> out.open("TypeDate")
					// Without the attribute, ETS shows the year (the import's default).
					.attr("DisplayTheYear", Boolean.FALSE.equals(type.tValueByName("showsYear")) ? "false" : null)
					.close();
				case "ehome.operational:IPAddressType" -> {
					TLClassifier version = (TLClassifier) type.tValueByName("version");
					out.open("TypeIPAddress")
						.attr("AddressType", classifier(type, "addressKind"))
						// Without a version, ETS means IPv4 (the import's default).
						.attr("Version", version == null || version.getName().equals("IPv4") ? null : version.getName())
						.close();
				}
				default -> {
					// A type without a counterpart in schema 11 (the color of ETS 6); written without its
					// definition (doc/knx/export.md).
				}
			}
			out.close();
		}
		out.close();
	}

	private void writeParameters(XmlOut out) throws XMLStreamException {
		List<TLObject> parameters = sorted(_program, "parameters");
		if (parameters.isEmpty()) {
			return;
		}
		// The members of a union share its location; the union is written where its first member is.
		Map<TLObject, List<TLObject>> unions = new LinkedHashMap<>();
		List<Object> items = new ArrayList<>();
		for (TLObject parameter : parameters) {
			TLObject location = ref(parameter, "address");
			if (location != null && location.tValueByName("bits") != null) {
				List<TLObject> members = unions.get(location);
				if (members == null) {
					members = new ArrayList<>();
					unions.put(location, members);
					items.add(location);
				}
				members.add(parameter);
			} else {
				items.add(parameter);
			}
		}
		out.open("Parameters");
		for (Object item : items) {
			TLObject obj = (TLObject) item;
			if (unions.containsKey(obj)) {
				out.open("Union").attr("SizeInBit", number(obj, "bits"));
				writeLocation(out, obj);
				for (TLObject member : unions.get(obj)) {
					openParameter(out, member)
						.attr("Offset", number(member, "unionOffset"))
						.attr("BitOffset", number(member, "unionBitOffset"))
						.close();
				}
				out.close();
			} else {
				openParameter(out, obj);
				writeLocation(out, ref(obj, "address"));
				out.close();
			}
		}
		out.close();
	}

	private XmlOut openParameter(XmlOut out, TLObject parameter) throws XMLStreamException {
		return out.open("Parameter")
			.attr("Id", extId(parameter))
			.attr("Name", string(parameter, "name"))
			.attr("ParameterType", extId(ref(parameter, "type")))
			.attr("Text", _texts.text(parameter, "description", "Text"))
			.attr("Access", classifier(parameter, "access"))
			.attr("Value", ParameterValues.encode(parameter));
	}

	private static void writeLocation(XmlOut out, TLObject location) throws XMLStreamException {
		if (location == null) {
			return;
		}
		if (isA(location, "ehome.operational:PropertyLocation")) {
			out.open("Property")
				.attr("ObjectIndex", number(location, "objectIndex"))
				.attr("ObjectType", number(location, "objectType"))
				.attr("Occurrence", number(location, "occurrence"))
				.attr("PropertyId", number(location, "propertyId"))
				.attr("Offset", number(location, "byteOffset"))
				.attr("BitOffset", number(location, "bitOffset"))
				.close();
		} else {
			out.open("Memory")
				.attr("CodeSegment", extId(ref(location, "segment")))
				.attr("Offset", number(location, "byteOffset"))
				.attr("BitOffset", number(location, "bitOffset"))
				.close();
		}
	}

	private void writeParameterRefs(XmlOut out) throws XMLStreamException {
		List<TLObject> references = new ArrayList<>();
		for (TLObject parameter : list(_program, "parameters")) {
			references.addAll(list(parameter, "views"));
		}
		if (references.isEmpty()) {
			return;
		}
		out.open("ParameterRefs");
		for (TLObject reference : Values.sortedById(references)) {
			out.open("ParameterRef")
				.attr("Id", extId(reference))
				.attr("RefId", extId(ref(reference, "parameter")))
				.attr("Name", string(reference, "name"))
				.attr("Text", _texts.text(reference, "description", "Text"))
				.attr("Access", classifier(reference, "access"))
				.attr("Value", ParameterValues.encode(reference))
				.attr("DisplayOrder", number(reference, "order"))
				.attr("Tag", number(reference, "tag"))
				.close();
		}
		out.close();
	}

	private void writeComObjects(XmlOut out) throws XMLStreamException {
		TLObject table = ref(_program, "objectTable");
		List<TLObject> ports = sorted(_program, "ports");
		if (table != null || !ports.isEmpty()) {
			out.open("ComObjectTable");
			if (table != null) {
				out.attr("CodeSegment", extId(ref(table, "segment")))
					.attr("Offset", number(table, "offset"));
			}
			for (TLObject port : ports) {
				out.open("ComObject")
					.attr("Id", extId(port))
					.attr("Name", string(port, "name"))
					.attr("Text", _texts.text(port, "text", "Text"))
					.attr("Number", number(port, "index"))
					.attr("FunctionText", _texts.text(port, "function", "FunctionText"))
					.attr("VisibleDescription", string(port, "description"))
					.attr("ObjectSize", objectSize(port, "size"))
					.attr("ReadFlag", classifier(port, "canRead"))
					.attr("WriteFlag", classifier(port, "canWrite"))
					.attr("CommunicationFlag", classifier(port, "communication"))
					.attr("TransmitFlag", classifier(port, "transmission"))
					.attr("UpdateFlag", classifier(port, "update"))
					.attr("ReadOnInitFlag", classifier(port, "readOnInit"))
					.attr("DatapointType", extIds(list(port, "datapointTypes")))
					.attr("Priority", classifier(port, "priority"))
					.close();
			}
			out.close();
		}
		List<TLObject> references = new ArrayList<>();
		for (TLObject port : ports) {
			references.addAll(list(port, "interfaces"));
		}
		if (references.isEmpty()) {
			return;
		}
		out.open("ComObjectRefs");
		for (TLObject reference : Values.sortedById(references)) {
			out.open("ComObjectRef")
				.attr("Id", extId(reference))
				.attr("RefId", extId(ref(reference, "port")))
				.attr("Text", _texts.text(reference, "name", "Text"))
				.attr("FunctionText", _texts.text(reference, "function", "FunctionText"))
				.attr("VisibleDescription", string(reference, "description"))
				.attr("DatapointType", extIds(list(reference, "datapointTypes")))
				.attr("Tag", number(reference, "tag"))
				.attr("ObjectSize", objectSize(reference, "size"))
				.attr("Priority", classifier(reference, "priority"))
				.attr("ReadFlag", classifier(reference, "canRead"))
				.attr("WriteFlag", classifier(reference, "canWrite"))
				.attr("CommunicationFlag", classifier(reference, "communication"))
				.attr("TransmitFlag", classifier(reference, "transmission"))
				.attr("UpdateFlag", classifier(reference, "update"))
				.attr("ReadOnInitFlag", classifier(reference, "readOnInit"))
				.close();
		}
		out.close();
	}

	private static void writeTable(XmlOut out, String element, TLObject table) throws XMLStreamException {
		if (table == null) {
			return;
		}
		out.open(element)
			.attr("CodeSegment", extId(ref(table, "segment")))
			.attr("Offset", number(table, "offset"))
			.attr("MaxEntries", number(table, "maxEntries"))
			.close();
	}

	private void writeLoadProcedures(XmlOut out) throws XMLStreamException {
		List<TLObject> procedures = list(_program, "loadProcedures");
		if (procedures.isEmpty()) {
			return;
		}
		out.open("LoadProcedures");
		for (TLObject procedure : procedures) {
			out.open("LoadProcedure").attr("MergeId", number(procedure, "mergeId"));
			LoadSteps.write(out, procedure);
			out.close();
		}
		out.close();
	}

	private void writeOptions(XmlOut out) throws XMLStreamException {
		out.open("Options")
			.attr("PreferPartialDownloadIfApplicationLoaded", flag(_program, "preferPartialDownload"))
			.attr("SetObjectTableLengthAlwaysToOne", flag(_program, "objectTableLengthAlwaysOne"))
			.attr("TextParameterEncoding", string(_program, "textEncoding"))
			.attr("TextParameterEncodingSelector", classifier(_program, "textEncodingSource"))
			.attr("TextParameterZeroTerminate", flag(_program, "textZeroTerminated"))
			.attr("ParameterByteOrder", classifier(_program, "parameterByteOrder"))
			.attr("LegacyNoPartialDownload", flag(_program, "noPartialDownload"))
			.attr("LegacyNoMemoryVerifyMode", flag(_program, "noMemoryVerify"))
			.attr("LegacyKeepObjectTableGaps", flag(_program, "keepObjectTableGaps"))
			.attr("DeviceInfoIgnoreRunState", flag(_program, "ignoreRunState"))
			.attr("DeviceInfoIgnoreLoadedState", flag(_program, "ignoreLoadedState"))
			.close();
	}

	private void writeDynamic(XmlOut out) throws XMLStreamException {
		List<TLObject> contents = list(_program, "contents");
		if (contents.isEmpty()) {
			return;
		}
		out.open("Dynamic");
		writeContents(out, contents);
		out.close();
	}

	private void writeContents(XmlOut out, List<TLObject> contents) throws XMLStreamException {
		for (TLObject option : contents) {
			switch (TLModelUtil.qualifiedName(option.tType())) {
				case "ehome.operational:ChannelIndependent" -> {
					out.open("ChannelIndependentBlock");
					writeContents(out, list(option, "contents"));
					out.close();
				}
				case "ehome.operational:Channel" -> {
					out.open("Channel")
						.attr("Id", extId(option))
						.attr("Name", string(option, "name"))
						.attr("Text", _texts.text(option, "description", "Text"))
						.attr("Number", number(option, "number"));
					writeContents(out, list(option, "contents"));
					out.close();
				}
				case "ehome.operational:ParameterGroup" -> {
					out.open("ParameterBlock")
						.attr("Id", extId(option))
						.attr("Name", string(option, "name"))
						.attr("Text", _texts.text(option, "description", "Text"))
						.attr("Access", classifier(option, "access"))
						.attr("ParamRefId", extId(ref(option, "input")));
					writeContents(out, list(option, "contents"));
					out.close();
				}
				case "ehome.operational:Switch" -> {
					out.open("choose").attr("ParamRefId", extId(ref(option, "input")));
					for (TLObject alternative : list(option, "alternatives")) {
						out.open("when")
							.attr("test", string(alternative, "pattern"))
							.attr("default", bool(alternative, "isDefault"));
						writeContents(out, list(alternative, "contents"));
						out.close();
					}
					out.close();
				}
				case "ehome.operational:ParameterView" ->
					out.open("ParameterRefRef").attr("RefId", extId(option)).close();
				case "ehome.operational:Interface" ->
					out.open("ComObjectRefRef").attr("RefId", extId(option)).close();
				case "ehome.operational:SharedPlacement" -> {
					// A further place of a reference (ETS 6): the same placement element once more.
					TLObject placed = ref(option, "placed");
					if (placed != null) {
						out.open(isA(placed, "ehome.operational:Interface") ? "ComObjectRefRef" : "ParameterRefRef")
							.attr("RefId", extId(placed))
							.close();
					}
				}
				case "ehome.operational:Assign" ->
					// A value the program gives a parameter reference in this case (haumacher/ehome #142).
					out.open("Assign")
						.attr("TargetParamRefRef", extId(ref(option, "parameter")))
						.attr("SourceParamRefRef", extId(ref(option, "source")))
						.attr("Value", ParameterValues.encode(option))
						.close();
				default -> {
					// No other structure elements.
				}
			}
		}
	}

	private static boolean isA(TLObject obj, String typeName) {
		return TLModelUtil.qualifiedName(obj.tType()).equals(typeName);
	}

}
