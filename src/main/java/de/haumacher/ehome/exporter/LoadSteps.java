/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.exporter;

import static de.haumacher.ehome.exporter.Values.bool;
import static de.haumacher.ehome.exporter.Values.classifier;
import static de.haumacher.ehome.exporter.Values.hex;
import static de.haumacher.ehome.exporter.Values.list;
import static de.haumacher.ehome.exporter.Values.number;
import static de.haumacher.ehome.exporter.Values.string;

import java.util.Set;

import javax.xml.stream.XMLStreamException;

import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;

/**
 * Writes the steps of a load procedure (<code>ehome.operational:LoadStep</code>) of a mask version or
 * an application program, as the import reads them (<code>load-step</code> in
 * <code>etsImporter.xml</code>): the element name is <code>LdCtrl</code> and the kind of the step.
 */
final class LoadSteps {

	/**
	 * The kinds of steps with the (required) attribute <code>Verify</code>; the model reads a missing
	 * boolean as <code>false</code>, so the kind decides whether the attribute is written.
	 */
	private static final Set<String> VERIFIED = Set.of("WriteMem", "WriteProp", "WriteRelMem");

	private LoadSteps() {
		// Static helpers.
	}

	/**
	 * Writes the steps of the given load procedure as children of the open element.
	 */
	static void write(XmlOut out, TLObject procedure) throws XMLStreamException {
		for (TLObject step : list(procedure, "steps")) {
			String kind = ((TLClassifier) step.tValueByName("kind")).getName();
			out.open("LdCtrl" + kind)
				.attr("LsmIdx", number(step, "stateMachine"))
				.attr("ObjIdx", number(step, "objectIndex"))
				.attr("ObjType", number(step, "objectType"))
				.attr("Occurrence", number(step, "occurrence"))
				.attr("PropId", number(step, "propertyId"))
				.attr("StartElement", number(step, "startElement"))
				.attr("Count", number(step, "count"))
				.attr("Address", number(step, "address"))
				.attr("Size", number(step, "size"))
				.attr("Offset", number(step, "offset"))
				.attr("InlineData", hex(step, "inlineData"))
				.attr("Verify", VERIFIED.contains(kind) ? bool(step, "verify") : null)
				.attr("SegType", number(step, "segmentType"))
				.attr("Access", number(step, "segmentAccess"))
				.attr("MemType", number(step, "memoryType"))
				.attr("SegFlags", number(step, "segmentFlags"))
				.attr("Mode", number(step, "mode"))
				.attr("Fill", number(step, "fill"))
				.attr("InitPtr", number(step, "initPointer"))
				.attr("SavePtr", number(step, "savePointer"))
				.attr("SerialPtr", number(step, "serialPointer"))
				.attr("Callback", number(step, "callback"))
				.attr("Seg0", number(step, "segment0"))
				.attr("Seg1", number(step, "segment1"))
				.attr("MilliSeconds", number(step, "milliseconds"))
				.attr("Name", classifier(step, "controlVariable"))
				.attr("Value", kind.equals("SetControlVariable") ? bool(step, "controlValue") : null)
				.attr("LdCtrlFilter", number(step, "errorFilter"))
				.attr("OriginalError", number(step, "originalError"))
				.attr("MappedError", number(step, "mappedError"))
				.attr("TextId", number(step, "textId"))
				.attr("PropType", string(step, "propertyType"))
				.attr("MaxElements", number(step, "maxElements"))
				.attr("ReadAccess", number(step, "readLevel"))
				.attr("WriteAccess", number(step, "writeLevel"))
				.attr("Writable", kind.equals("DeclarePropDesc") ? bool(step, "writable") : null)
				.attr("UseFunctionProp", Values.trueOnly(step, "useFunctionProperty", "true"))
				.attr("MergeId", number(step, "mergeId"))
				.attr("AppliesTo", classifier(step, "appliesTo"))
				.attr("AddressSpace", classifier(step, "addressSpace"))
				.close();
		}
	}

}
