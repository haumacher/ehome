/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

import com.top_logic.basic.AbortExecutionException;
import com.top_logic.basic.Logger;
import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.layout.view.job.JobMonitor;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceChanges;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;

/**
 * The programming of several devices of a project one after another (haumacher/ehome #200): a list of
 * devices, each with the kind of its download (full, or the parameters only, haumacher/ehome #195),
 * worked through by {@link #run(JobMonitor, Opener)} strictly in order over the project's path to the
 * bus, one connection and one device at a time.
 *
 * <p>
 * The queue is runtime state of the view that shows it (no model): each {@link Entry} says what
 * became of its device (waiting, running with its progress, done, failed with the reason, cancelled,
 * aborted). A waiting entry is cancelled ({@link #cancel(Collection)}, {@link #cancelWaiting()})
 * without touching its device; the running one is aborted ({@link #abortRunning()}) before its next
 * operation, as a cancel of the job aborts it, and the queue goes on with the next device. A failure
 * does not stop the queue. Each download is performed and recorded as a single one
 * ({@link DownloadService}, {@link DownloadRecord}); a device not reached keeps its pending state.
 * </p>
 *
 * <p>
 * While it runs, the queue holds the project's {@link DownloadLock}: a single download of the
 * project is refused meanwhile, and the queue does not start while one runs.
 * </p>
 */
public class DownloadQueue {

	/**
	 * Opens the connection a download writes into its device through.
	 */
	public interface Opener {

		/**
		 * Opens the connection for the given plan of the given device.
		 */
		CalimeroWriter open(TLObject device, DownloadPlan plan) throws BusException;
	}

	/**
	 * The connection over the project's path to the bus ({@link BusPath#of(TLObject)}).
	 */
	public static final Opener BUS = (device, plan) -> CalimeroWriter.open(BusPath.of(device), plan.getIndividualAddress());

	/**
	 * What became of an entry.
	 */
	public enum State {
		/** Not started yet. */
		WAITING,
		/** Its download runs. */
		RUNNING,
		/** Downloaded and recorded. */
		DONE,
		/** The download was refused or failed. */
		FAILED,
		/** Cancelled before it started: its device is untouched. */
		CANCELLED,
		/** Aborted while its download ran. */
		ABORTED;

		/**
		 * Whether the entry is finished.
		 */
		public boolean isFinal() {
			return this != WAITING && this != RUNNING;
		}
	}

	/**
	 * A device of the queue with the kind of its download and what became of it.
	 */
	public final class Entry {

		private final TLObject _device;

		private final String _label;

		private boolean _parametersOnly;

		private State _state = State.WAITING;

		private int _done;

		private int _count;

		private ResKey _details;

		private volatile boolean _abort;

		Entry(TLObject device, boolean parametersOnly) {
			_device = device;
			_label = DownloadQueue.label(device);
			_parametersOnly = parametersOnly;
		}

		/**
		 * The queue of this entry.
		 */
		public DownloadQueue queue() {
			return DownloadQueue.this;
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
			synchronized (DownloadQueue.this) {
				return _parametersOnly;
			}
		}

		/**
		 * The kind of the download.
		 */
		public DownloadPlan.Kind kind() {
			return parametersOnly() ? DownloadPlan.Kind.PARAMETERS : DownloadPlan.Kind.FULL;
		}

		/**
		 * What became of the entry.
		 */
		public State state() {
			synchronized (DownloadQueue.this) {
				return _state;
			}
		}

		/**
		 * The details of the state: the operation running, the outcome, the reason of a failure;
		 * <code>null</code> while the entry waits.
		 */
		public ResKey details() {
			synchronized (DownloadQueue.this) {
				return _details;
			}
		}

		/**
		 * The operations performed of the download, while it runs and after.
		 */
		public int done() {
			synchronized (DownloadQueue.this) {
				return _done;
			}
		}

		/**
		 * The number of operations of the download, <code>0</code> before it starts.
		 */
		public int count() {
			synchronized (DownloadQueue.this) {
				return _count;
			}
		}

		/**
		 * Whether the kind can still be chosen: the entry waits.
		 */
		public boolean canChoose() {
			return state() == State.WAITING;
		}

		/**
		 * Chooses the kind of the download of a waiting entry; does nothing for another.
		 */
		public void setParametersOnly(boolean parametersOnly) {
			synchronized (DownloadQueue.this) {
				if (_state == State.WAITING) {
					_parametersOnly = parametersOnly;
					_version++;
				}
			}
		}

		void progress(int done, int count, ResKey details) {
			synchronized (DownloadQueue.this) {
				_done = done;
				_count = count;
				_details = details;
				_version++;
			}
		}

		void finish(State state, ResKey details) {
			synchronized (DownloadQueue.this) {
				_state = state;
				_details = details;
				_version++;
			}
		}

		@Override
		public String toString() {
			return _label + " " + state();
		}
	}

	/**
	 * Thrown out of a download by {@link Entry#_abort}: the operation not performed.
	 */
	private static final class Aborted extends RuntimeException {

		final int _number;

		final int _count;

		Aborted(int number, int count) {
			super("Aborted before operation " + number + " of " + count + ".", null, false, false);
			_number = number;
			_count = count;
		}
	}

	private final TLObject _project;

	private final List<Entry> _entries;

	private boolean _running;

	private Entry _current;

	private long _version;

	/**
	 * Creates a {@link DownloadQueue} of the given devices of the project, in the order of their
	 * individual addresses, each preset with the kind of download its state suggests
	 * ({@link ProgrammingFunctions#parametersOnlyByDefault(TLObject)}).
	 *
	 * @throws IllegalArgumentException
	 *         If a device is not one of the project.
	 */
	public DownloadQueue(TLObject project, Collection<? extends TLObject> devices) {
		_project = project;
		List<Entry> entries = new ArrayList<>();
		for (TLObject device : sorted(devices)) {
			if (BusPath.project(device) != project) {
				throw new IllegalArgumentException("The device " + device + " is not one of the project " + project + ".");
			}
			entries.add(new Entry(device, ProgrammingFunctions.parametersOnlyByDefault(device)));
		}
		_entries = Collections.unmodifiableList(entries);
	}

	/**
	 * The project.
	 */
	public TLObject project() {
		return _project;
	}

	/**
	 * The entries, in the order they are worked through.
	 */
	public List<Entry> entries() {
		return _entries;
	}

	/**
	 * A number changing with every change of the queue or of one of its entries.
	 */
	public synchronized long version() {
		return _version;
	}

	/**
	 * Whether the queue is working through its entries.
	 */
	public synchronized boolean isRunning() {
		return _running;
	}

	/**
	 * The entry whose download runs, <code>null</code> if none.
	 */
	public synchronized Entry current() {
		return _current;
	}

	/**
	 * The entries in the given state.
	 */
	public synchronized List<Entry> entries(State state) {
		return _entries.stream().filter(e -> e._state == state).toList();
	}

	/**
	 * Chooses the kind of download of every waiting entry.
	 */
	public void setParametersOnly(boolean parametersOnly) {
		for (Entry entry : _entries) {
			entry.setParametersOnly(parametersOnly);
		}
	}

	/**
	 * Cancels the given entries that wait; their devices are not touched.
	 *
	 * @return The number of entries cancelled.
	 */
	public synchronized int cancel(Collection<Entry> entries) {
		int result = 0;
		for (Entry entry : entries) {
			if (entry.queue() == this && entry._state == State.WAITING) {
				entry._state = State.CANCELLED;
				entry._details = I18NConstants.QUEUE_CANCELLED;
				result++;
				_version++;
			}
		}
		return result;
	}

	/**
	 * Cancels every waiting entry.
	 *
	 * @return The number of entries cancelled.
	 */
	public int cancelWaiting() {
		return cancel(_entries);
	}

	/**
	 * Aborts the download running now before its next operation; the queue goes on with the next
	 * entry.
	 *
	 * @return Whether a download runs.
	 */
	public synchronized boolean abortRunning() {
		if (_current == null) {
			return false;
		}
		_current._abort = true;
		_version++;
		return true;
	}

	/**
	 * Why the queue cannot start now, <code>null</code> if it can: it runs already, no entry waits,
	 * writing to the bus is switched off, the project's path to the bus cannot be used, or a download
	 * of the project runs.
	 */
	public ResKey startVeto() {
		if (isRunning()) {
			return I18NConstants.QUEUE_RUNNING;
		}
		if (entries(State.WAITING).isEmpty()) {
			return I18NConstants.QUEUE_NOTHING_WAITS;
		}
		if (!CalimeroWriter.writeEnabled()) {
			return I18NConstants.ERROR_WRITE_DISABLED;
		}
		ResKey path = BusPath.veto(_project);
		if (path != null) {
			return path;
		}
		return DownloadLock.veto(_project);
	}

	/**
	 * Works through the waiting entries one after another, as a job.
	 *
	 * <p>
	 * A cancel of the job aborts the download running and cancels the entries waiting; the job then
	 * ends with the summary of what was done.
	 * </p>
	 *
	 * @param opener
	 *        How a download connects to its device: {@link #BUS} over the project's path to the bus.
	 * @return The summary: how many devices were programmed, failed, were cancelled or aborted.
	 * @throws TopLogicException
	 *         If the queue cannot start ({@link #startVeto()}).
	 */
	public ResKey run(JobMonitor job, Opener opener) throws InterruptedException {
		ResKey veto = startVeto();
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		try (DownloadLock lock = DownloadLock.acquire(_project, I18NConstants.LOCK_QUEUE__COUNT.fill(_entries.size()))) {
			synchronized (this) {
				if (_running) {
					throw new TopLogicException(I18NConstants.QUEUE_RUNNING);
				}
				_running = true;
			}
			try {
				while (true) {
					Entry entry = next();
					if (entry == null) {
						break;
					}
					try {
						run(job, entry, opener);
					} catch (AbortExecutionException ex) {
						entry.finish(State.ABORTED, entry.count() == 0 ? I18NConstants.QUEUE_ABORTED
							: I18NConstants.QUEUE_ABORTED_AT__NUMBER_COUNT.fill(entry.done() + 1, entry.count()));
						cancelWaiting();
						return summary();
					} finally {
						synchronized (this) {
							_current = null;
						}
					}
				}
			} finally {
				synchronized (this) {
					_running = false;
				}
			}
		}
		job.progress(_entries.size(), _entries.size());
		return summary();
	}

	/**
	 * The first waiting entry, now running; <code>null</code> if none waits.
	 */
	private synchronized Entry next() {
		for (Entry entry : _entries) {
			if (entry._state == State.WAITING) {
				entry._state = State.RUNNING;
				entry._details = null;
				_current = entry;
				_version++;
				return entry;
			}
		}
		return null;
	}

	private void run(JobMonitor job, Entry entry, Opener opener) throws InterruptedException {
		int index = _entries.indexOf(entry);
		int total = _entries.size();
		job.progress(index, total);
		TLObject device = entry.device();
		if (!device.tValid()) {
			entry.finish(State.FAILED, I18NConstants.QUEUE_DEVICE_DELETED);
			return;
		}
		DownloadPlan.Kind kind = entry.kind();
		ResKey veto = ProgrammingFunctions.downloadVeto(device, kind);
		if (veto != null) {
			entry.finish(State.FAILED, veto);
			return;
		}
		DownloadPlan plan = DownloadPlanner.plan(device, kind);
		String path = BusPath.describe(device);
		entry.progress(0, plan.getOperations().size(), I18NConstants.PROGRESS_CONNECTING__GATEWAY.fill(path));
		// Reported after the entry changed: the display of the queue follows the reports of the job.
		job.message(I18NConstants.QUEUE_PROGRESS_STARTING__DEVICE.fill(entry.label()));
		DownloadExecutor.Result result;
		try (CalimeroWriter access = opener.open(device, plan)) {
			result = DownloadService.download(device, plan, access, new DownloadExecutor(), (number, count, operation) -> {
				if (entry._abort) {
					throw new Aborted(number, count);
				}
				entry.progress(number - 1, count,
					I18NConstants.PROGRESS_OPERATION__NUMBER_COUNT_TEXT.fill(number, count, operation.description()));
				job.progress(index + (number - 1) / (double) count, total);
				job.message(I18NConstants.QUEUE_PROGRESS__DEVICE_NUMBER_COUNT_TEXT.fill(entry.label(), number, count,
					operation.description()));
			});
		} catch (Aborted ex) {
			entry.finish(State.ABORTED, I18NConstants.QUEUE_ABORTED_AT__NUMBER_COUNT.fill(ex._number, ex._count));
			return;
		} catch (AbortExecutionException ex) {
			// The job is cancelled: the queue ends.
			throw ex;
		} catch (BusException ex) {
			entry.finish(State.FAILED, I18NConstants.ERROR_CONNECTION__PATH_DETAIL.fill(path, ex.getErrorKey()));
			return;
		} catch (TopLogicException ex) {
			entry.finish(State.FAILED, ex.getErrorKey());
			return;
		} catch (RuntimeException ex) {
			Logger.error("Download into " + entry.label() + " failed.", ex, DownloadQueue.class);
			entry.finish(State.FAILED, I18NConstants.QUEUE_INTERNAL_ERROR__DETAIL.fill(String.valueOf(ex.getMessage())));
			return;
		}
		if (result.success()) {
			entry.progress(result.done(), result.count(), null);
			entry.finish(State.DONE, I18NConstants.QUEUE_DONE__COUNT_SECONDS.fill(result.done(),
				Double.valueOf(result.duration().toMillis() / 1000.0)));
		} else {
			entry.progress(result.done(), result.count(), null);
			entry.finish(State.FAILED, I18NConstants.QUEUE_FAILED__NUMBER_OPERATION_REASON.fill(result.done() + 1,
				result.failed().description(), result.reason()));
		}
	}

	/**
	 * What was done, for a person.
	 */
	public synchronized ResKey summary() {
		int[] counts = new int[State.values().length];
		for (Entry entry : _entries) {
			counts[entry._state.ordinal()]++;
		}
		return I18NConstants.QUEUE_SUMMARY__DONE_FAILED_CANCELLED_ABORTED.fill(counts[State.DONE.ordinal()],
			counts[State.FAILED.ordinal()], counts[State.CANCELLED.ordinal()], counts[State.ABORTED.ordinal()]);
	}

	/**
	 * The given devices, each once, in the order of their individual addresses (devices in no line
	 * last).
	 */
	public static List<TLObject> sorted(Collection<? extends TLObject> devices) {
		List<TLObject> result = new ArrayList<>(new LinkedHashSet<>(devices));
		result.sort(Comparator.comparingLong(DownloadQueue::order));
		return result;
	}

	private static long order(TLObject device) {
		Integer address = DownloadImage.individualAddress(device);
		if (address != null) {
			return address.longValue();
		}
		Object number = device.tValueByName("address");
		return 0x10000L + (number instanceof Number n ? n.longValue() : 0xFFFF);
	}

	/**
	 * Whether a download of the parameters only can be chosen for the device: its program allows it
	 * (not <code>LegacyNoPartialDownload</code>).
	 */
	public static boolean allowsParametersOnly(TLObject device) {
		Program program = DeviceConfiguration.program(device);
		return program != null && !program.getNoPartialDownload();
	}

	/**
	 * The devices of the project that need a download (<code>Device#downloadPending</code>), in the
	 * order of their individual addresses.
	 */
	public static List<TLObject> pendingDevices(TLObject project) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject device : DeviceChanges.devices(project)) {
			if (Boolean.TRUE.equals(device.tValueByName("downloadPending"))) {
				result.add(device);
			}
		}
		return sorted(result);
	}

	private static String label(TLObject device) {
		return MetaLabelProvider.INSTANCE.getLabel(device);
	}

}
