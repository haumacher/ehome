/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.exporter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.stream.XMLStreamException;

import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.knx.ExtIds;

/**
 * Exports a project (<code>ehome.project:Project</code>) as an ETS project archive
 * (<code>.knxproj</code>, project schema 11 as written by ETS 4), the inverse of the import
 * (<code>de.haumacher.ehome.importer.Importer</code> with <code>etsImporter.xml</code>).
 *
 * <p>
 * The archive holds <code>knx_master.xml</code> (the master data the project uses), per manufacturer
 * of the project's devices and programs a folder <code>M-xxxx/</code> with <code>Catalog.xml</code>,
 * <code>Hardware.xml</code> and one file per application program, and the folder of the project
 * <code>P-xxxx/</code> with <code>0.xml</code> (the installation) and <code>Project.xml</code>. The
 * entries come in the order ETS writes them, which is the order the import needs (master data before
 * manufacturer data before the project).
 * </p>
 *
 * <p>
 * The archive is <b>not signed</b>: ETS verifies the signature files (<code>M-xxxx.signature</code>,
 * <code>P-xxxx.signature</code>) of a project archive on import, and they can only be created by an
 * installed ETS. ETS therefore refuses the exported file; E-Home and other tools reading the schema
 * read it. See <code>doc/knx/export.md</code> for what is written and what the model does not hold.
 * </p>
 */
public class KnxprojExporter {

	/** The type of the stub of a device the catalog does not identify (haumacher/ehome #183). */
	private static final String STUB_TYPE = "ehome.physical:DeviceStub";

	/**
	 * Writes the given project as a <code>.knxproj</code> archive to the given stream (which stays
	 * open).
	 *
	 * @return The devices left out: the stubs of devices the catalog does not identify
	 *         (haumacher/ehome #183), which have no product ETS could reference.
	 */
	public static List<TLObject> export(TLObject project, OutputStream out) throws IOException {
		Usage usage = new Usage(project);
		ZipOutputStream zip = new ZipOutputStream(out);
		try {
			entry(zip, "knx_master.xml", xml -> new MasterDataExport(usage).write(xml));
			for (TLObject manufacturer : Values.sortedById(usage._manufacturers)) {
				String folder = Values.extId(manufacturer) + "/";
				ManufacturerExport data = new ManufacturerExport(usage, manufacturer);
				if (data.hasCatalog()) {
					entry(zip, folder + "Catalog.xml", data::writeCatalog);
				}
				if (data.hasHardware()) {
					entry(zip, folder + "Hardware.xml", data::writeHardware);
				}
				for (TLObject program : usage.programs(manufacturer)) {
					entry(zip, folder + Values.extId(program) + ".xml", xml -> new ProgramExport(program).write(xml));
				}
			}
			String folder = Values.extId(project) + "/";
			ProjectExport projectExport = new ProjectExport(project, usage);
			entry(zip, folder + "0.xml", projectExport::writeInstallation);
			entry(zip, folder + "Project.xml", projectExport::writeProject);
		} catch (XMLStreamException ex) {
			throw new IOException(ex);
		}
		zip.finish();
		return leftOut(project);
	}

	/**
	 * Whether the given device of the project goes into the export: a device of the catalog, not the
	 * stub of a device the catalog does not identify (haumacher/ehome #183), which has no product.
	 */
	static boolean exported(TLObject device) {
		return !ExtIds.isA(device, STUB_TYPE);
	}

	/**
	 * The devices of the given project the export leaves out ({@link #exported(TLObject)}), in the
	 * order of the topology.
	 */
	public static List<TLObject> leftOut(TLObject project) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject area : Values.list(project, "areas")) {
			for (TLObject line : Values.list(area, "lines")) {
				for (TLObject device : Values.list(line, "devices")) {
					if (!exported(device)) {
						result.add(device);
					}
				}
			}
		}
		for (TLObject device : Values.list(project, "unassignedDevices")) {
			if (!exported(device)) {
				result.add(device);
			}
		}
		return result;
	}

	/**
	 * What the export of the given project says about the devices it leaves out, naming them;
	 * <code>null</code> if it leaves out none.
	 */
	public static ResKey leftOutNote(TLObject project) {
		List<TLObject> leftOut = leftOut(project);
		if (leftOut.isEmpty()) {
			return null;
		}
		List<String> names = new ArrayList<>();
		for (TLObject device : leftOut) {
			names.add(MetaLabelProvider.INSTANCE.getLabel(device));
		}
		return I18NConstants.EXPORT_LEFT_OUT__DEVICES.fill(String.join(", ", names));
	}

	private interface Content {
		void write(XmlOut out) throws XMLStreamException;
	}

	private static void entry(ZipOutputStream zip, String name, Content content)
			throws IOException, XMLStreamException {
		zip.putNextEntry(new ZipEntry(name));
		XmlOut out = new XmlOut(zip);
		content.write(out);
		out.finish();
		zip.closeEntry();
	}

}
