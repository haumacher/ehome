/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.model.TLObject;

/**
 * The devices of a selection in the tree of the devices to be programmed (haumacher/ehome #218): a
 * multi-selection, an area, a line or a building part stands for its devices. Each row is a device
 * with the kind of its download, preset by the state of the device
 * ({@link ProgrammingFunctions#parametersOnlyByDefault(TLObject)}) and changeable per row and for all
 * until "Program" appends the rows to the project's {@link DownloadQueue}
 * ({@link #enqueue()}).
 *
 * <p>
 * Runtime state of the tab "Programming" of the selection (no model); a new selection gives a new
 * {@link DownloadSelection}.
 * </p>
 */
public final class DownloadSelection {

	/**
	 * A device of the selection with the kind of its download.
	 */
	public final class Row {

		private final TLObject _device;

		private final String _label;

		private final boolean _canChoose;

		private boolean _parametersOnly;

		Row(TLObject device) {
			_device = device;
			_label = DownloadQueue.label(device);
			_canChoose = DownloadQueue.allowsParametersOnly(device);
			_parametersOnly = ProgrammingFunctions.parametersOnlyByDefault(device);
		}

		/**
		 * The selection of this row.
		 */
		public DownloadSelection selection() {
			return DownloadSelection.this;
		}

		/**
		 * The device.
		 */
		public TLObject device() {
			return _device;
		}

		/**
		 * The device for a person (its name and its individual address).
		 */
		public String label() {
			return _label;
		}

		/**
		 * Whether the download writes the parameters only ({@link DownloadPlan.Kind#PARAMETERS}).
		 */
		public boolean parametersOnly() {
			synchronized (DownloadSelection.this) {
				return _parametersOnly;
			}
		}

		/**
		 * Whether the kind can be chosen: the program of the device allows the parameters only
		 * ({@link DownloadQueue#allowsParametersOnly(TLObject)}).
		 */
		public boolean canChoose() {
			return _canChoose;
		}

		/**
		 * Chooses the kind of the download; the parameters only only where the program allows them.
		 */
		public void setParametersOnly(boolean parametersOnly) {
			synchronized (DownloadSelection.this) {
				_parametersOnly = parametersOnly && _canChoose;
				_version++;
			}
		}

		@Override
		public String toString() {
			return _label + (parametersOnly() ? " (parameters)" : " (full)");
		}
	}

	private final TLObject _project;

	private final List<Row> _rows;

	private long _version;

	/**
	 * Creates a {@link DownloadSelection} of the given devices of the project, in the order of their
	 * individual addresses, each preset with the kind its state suggests.
	 */
	public DownloadSelection(TLObject project, Collection<? extends TLObject> devices) {
		_project = project;
		List<Row> rows = new ArrayList<>();
		for (TLObject device : DownloadQueue.sorted(devices)) {
			rows.add(new Row(device));
		}
		_rows = List.copyOf(rows);
	}

	/**
	 * The project.
	 */
	public TLObject project() {
		return _project;
	}

	/**
	 * The rows, by individual address.
	 */
	public List<Row> rows() {
		return _rows;
	}

	/**
	 * A number changing with every choice of a kind.
	 */
	public synchronized long version() {
		return _version;
	}

	/**
	 * Chooses the kind of the download of every row.
	 */
	public void setParametersOnly(boolean parametersOnly) {
		for (Row row : _rows) {
			row.setParametersOnly(parametersOnly);
		}
	}

	/**
	 * The number of rows with the download of the parameters only.
	 */
	public long parametersOnlyCount() {
		return _rows.stream().filter(Row::parametersOnly).count();
	}

	/**
	 * Appends the rows, each with its kind, to the project's {@link DownloadQueue} (a device waiting
	 * already is not appended again, see {@link DownloadQueue#enqueue(TLObject, boolean)}).
	 *
	 * @return The queue.
	 */
	public DownloadQueue enqueue() {
		DownloadQueue queue = DownloadQueue.of(_project);
		List<TLObject> devices = new ArrayList<>();
		Map<TLObject, Boolean> kinds = new HashMap<>();
		for (Row row : _rows) {
			if (row.device().tValid()) {
				devices.add(row.device());
				kinds.put(row.device(), Boolean.valueOf(row.parametersOnly()));
			}
		}
		queue.enqueue(devices, kinds::get);
		return queue;
	}

}
