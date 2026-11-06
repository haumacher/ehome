/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.util.ResKey;

/**
 * The progress of an import by the entries of the archive, reported to an {@link ImportProgress}
 * in the user's terms (haumacher/ehome #84).
 *
 * <p>
 * An entry is named by what it holds: the master data, the hardware and catalog of a manufacturer,
 * an application program of a manufacturer (with its number among the programs of the file, or as
 * already known), the project. The names of manufacturers, programs and projects are learned from
 * the elements read ({@link #startElement(XMLStreamReader)}): those of the manufacturers from the
 * master data, that of a program or project from the beginning of its entry. Where a name is not
 * known yet, the message names the entry's folder (<code>M-0008</code>) or the program's ID.
 * </p>
 */
final class EntryProgress {

	private static final String MASTER_DATA = "knx_master.xml";

	private static final String MANUFACTURER = "Manufacturer";

	private static final String APPLICATION_PROGRAM = "ApplicationProgram";

	private static final String PROJECT_INFORMATION = "ProjectInformation";

	private static final String ID = "Id";

	private static final String NAME = "Name";

	private enum Kind {
		MASTER_DATA, HARDWARE, PROGRAM, PROJECT, OTHER;
	}

	private final ImportProgress _progress;

	private final int _total;

	private final int _programs;

	private final Set<String> _knownPrograms;

	private final Map<String, String> _manufacturerNames = new HashMap<>();

	private final Map<String, String> _projectNames = new HashMap<>();

	private int _done;

	private int _program;

	private Kind _kind = Kind.OTHER;

	private String _entry;

	private String _folder;

	/**
	 * The message reported last.
	 */
	private ResKey _message;

	/**
	 * Creates a {@link EntryProgress}.
	 *
	 * @param progress
	 *        The callback to report to.
	 * @param total
	 *        The number of entries of the file, 0 if not counted.
	 * @param programs
	 *        The number of application programs of the file, 0 if not counted.
	 * @param knownPrograms
	 *        The IDs of the application programs that exist before the import, which it skips.
	 */
	EntryProgress(ImportProgress progress, int total, int programs, Set<String> knownPrograms) {
		_progress = progress;
		_total = total;
		_programs = programs;
		_knownPrograms = knownPrograms;
	}

	/**
	 * Reports the beginning of the entry with the given name (its path in the file).
	 */
	void beginEntry(String name) {
		_entry = name;
		String[] path = name.split("/");
		_folder = path.length > 1 ? path[0] : null;
		if (MASTER_DATA.equals(name)) {
			_kind = Kind.MASTER_DATA;
		} else if (Importer.isProgram(path)) {
			_kind = Kind.PROGRAM;
			_program++;
		} else if (_folder != null && _folder.startsWith(Importer.MANUFACTURER_PREFIX)) {
			_kind = Kind.HARDWARE;
		} else if (_folder != null && _folder.startsWith(Importer.PROJECT_PREFIX)) {
			_kind = Kind.PROJECT;
		} else {
			_kind = Kind.OTHER;
		}
		String programId = _kind == Kind.PROGRAM ? path[1].substring(0, path[1].length() - ".xml".length()) : null;
		report(message(programId, programId));
	}

	/**
	 * Learns the names of what the import reads from the element it has just started.
	 */
	void startElement(XMLStreamReader in) {
		String element = in.getLocalName();
		switch (_kind) {
			case MASTER_DATA:
				if (MANUFACTURER.equals(element)) {
					String id = in.getAttributeValue(null, ID);
					String name = in.getAttributeValue(null, NAME);
					if (id != null && name != null && !name.isBlank()) {
						_manufacturerNames.put(id, name);
					}
				}
				break;
			case PROGRAM:
				if (APPLICATION_PROGRAM.equals(element)) {
					String id = in.getAttributeValue(null, ID);
					String name = in.getAttributeValue(null, NAME);
					if (id != null) {
						report(message(id, name == null || name.isBlank() ? id : name));
					}
				}
				break;
			case PROJECT:
				if (PROJECT_INFORMATION.equals(element)) {
					String name = in.getAttributeValue(null, NAME);
					if (name != null && !name.isBlank()) {
						_projectNames.put(_folder, name);
						report(message(null, null));
					}
				}
				break;
			default:
				break;
		}
	}

	/**
	 * Reports the end of the current entry.
	 */
	void endEntry() {
		_done++;
	}

	/**
	 * Reports the end of the import, all entries done.
	 */
	void finish() {
		int done = Math.max(_done, _total);
		_progress.progress(done, done, _message);
	}

	private void report(ResKey message) {
		_message = message;
		_progress.progress(_done, _total, message);
	}

	private ResKey message(String programId, String programName) {
		switch (_kind) {
			case MASTER_DATA:
				return I18NConstants.PROGRESS_MASTER_DATA;
			case HARDWARE:
				return I18NConstants.PROGRESS_HARDWARE__MANUFACTURER.fill(manufacturer());
			case PROGRAM:
				if (_knownPrograms.contains(programId)) {
					return I18NConstants.PROGRESS_PROGRAM_KNOWN__MANUFACTURER_PROGRAM.fill(manufacturer(), programName);
				}
				return I18NConstants.PROGRESS_PROGRAM__MANUFACTURER_PROGRAM_NUMBER_COUNT.fill(manufacturer(),
					programName, _program, Math.max(_program, _programs));
			case PROJECT:
				return I18NConstants.PROGRESS_PROJECT__NAME.fill(_projectNames.getOrDefault(_folder, _folder));
			default:
				return _entry == null ? null : ResKey.text(_entry);
		}
	}

	private String manufacturer() {
		return _manufacturerNames.getOrDefault(_folder, _folder);
	}

}
