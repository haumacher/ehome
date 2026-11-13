/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.haumacher.ehome.model.operational.Button;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.ParameterView;

/**
 * The order of the items on one page of a program's structure, as ETS shows them
 * (haumacher/ehome #88): the one place that computes it, for the configuration of a device
 * ({@link de.haumacher.ehome.programming.DeviceConfiguration#getItems(Object)}) and for the pages
 * of a program's catalog ({@link ProgramPages}).
 *
 * <p>
 * The items are added in document order (the order of the structure, as a walk of it meets them);
 * {@link #getItems()} gives them in the order of display:
 * </p>
 * <ol>
 * <li>A parameter shown as the title of a block ({@link #addTitle(ParameterView)}) stands at the
 * top.</li>
 * <li>The parameters with a display order (ETS: <code>ParameterRef/@DisplayOrder</code>) are
 * sorted by it, stably: equal values keep the document order.</li>
 * <li>A communication object (ETS has no display order for it) stands after the parameter that
 * brought it in: the input of the innermost switch around it whose input is on the same page
 * (unless that input itself stands after the object, an input without display order following the
 * object in document order).</li>
 * <li>Any other item (a parameter without a display order, a communication object outside such a
 * switch, a button of the parameter dialog) stands after the item that precedes it in document
 * order (at the top if there is none).</li>
 * </ol>
 *
 * <p>
 * An item standing after another one comes, with the items standing after it, before the next item
 * standing after the same one, in document order, and all of them before the next parameter of the
 * sorted order. On a page without display orders this keeps the document order, as long as the
 * items between a switch's input and an object of its case belong to that switch (true for every
 * program of the samples). Nothing is stored: the structure keeps the document order, which the
 * export and the download depend on.
 * </p>
 */
public final class PageOrder {

	/** One item with what decides its place. */
	private static final class Entry {

		final Option _item;

		/** The display order of a parameter that has one, else <code>null</code>. */
		final Integer _order;

		final boolean _title;

		/** The item preceding this one in document order, <code>null</code> if none. */
		final Option _preceding;

		/** For a communication object: the inputs of the switches around it, innermost last. */
		final List<ParameterView> _inputs;

		/** The entry this one stands after, <code>null</code> for one at the top or sorted. */
		Entry _anchor;

		/** The items standing after this one, in document order. */
		final List<Entry> _followers = new ArrayList<>();

		Entry(Option item, Integer order, boolean title, Option preceding, List<ParameterView> inputs) {
			_item = item;
			_order = order;
			_title = title;
			_preceding = preceding;
			_inputs = inputs;
		}
	}

	private final List<Entry> _entries = new ArrayList<>();

	private final Map<Option, Entry> _byItem = new HashMap<>();

	private Option _last;

	private List<Option> _sorted;

	/**
	 * Adds the parameter a block shows as its title.
	 */
	public void addTitle(ParameterView title) {
		add(new Entry(title, null, true, null, null));
	}

	/**
	 * Adds a parameter placed on the page.
	 *
	 * @param order
	 *        Its display order, <code>null</code> if it has none.
	 */
	public void addParameter(ParameterView view, Integer order) {
		add(new Entry(view, order, false, _last, null));
	}

	/**
	 * Adds a button of the parameter dialog placed on the page (haumacher/ehome #158): it has no display
	 * order and stands after the item preceding it.
	 */
	public void addButton(Button button) {
		add(new Entry(button, null, false, _last, null));
	}

	/**
	 * Adds a communication object placed on the page.
	 *
	 * @param inputs
	 *        The inputs of the switches whose cases placed it, outermost first; copied.
	 */
	public void addObject(Interface placed, List<ParameterView> inputs) {
		add(new Entry(placed, null, false, _last, new ArrayList<>(inputs)));
	}

	private void add(Entry entry) {
		if (_byItem.putIfAbsent(entry._item, entry) != null) {
			// Placed on the page already: the first place counts.
			return;
		}
		_entries.add(entry);
		_last = entry._item;
		_sorted = null;
	}

	/**
	 * Whether the given item is on the page.
	 */
	public boolean contains(Option item) {
		return _byItem.containsKey(item);
	}

	/**
	 * The items of the page in the order of display, each once.
	 */
	public List<Option> getItems() {
		if (_sorted == null) {
			_sorted = Collections.unmodifiableList(sort());
		}
		return _sorted;
	}

	private List<Option> sort() {
		// First every item that is not sorted stands after the item preceding it: the anchors point
		// backwards in document order, without a cycle.
		for (Entry entry : _entries) {
			entry._followers.clear();
			entry._anchor = entry._title || entry._order != null || entry._preceding == null
				? null : _byItem.get(entry._preceding);
		}
		// Then a communication object moves to the input of its innermost switch on this page, unless
		// that input stands (directly or through others) after the object itself, which happens if the
		// input has no display order and follows the object in document order.
		for (Entry entry : _entries) {
			if (entry._inputs != null) {
				for (int n = entry._inputs.size() - 1; n >= 0; n--) {
					Entry input = _byItem.get(entry._inputs.get(n));
					if (input != null) {
						if (!standsAfter(input, entry)) {
							entry._anchor = input;
						}
						break;
					}
				}
			}
		}
		List<Entry> top = new ArrayList<>();
		List<Entry> sorted = new ArrayList<>();
		for (Entry entry : _entries) {
			if (entry._title) {
				top.add(entry);
			} else if (entry._order != null) {
				sorted.add(entry);
			} else if (entry._anchor == null) {
				top.add(entry);
			} else {
				entry._anchor._followers.add(entry);
			}
		}
		// Stable: equal values keep the document order.
		sorted.sort(Comparator.comparing(e -> e._order));
		List<Option> result = new ArrayList<>(_entries.size());
		for (Entry entry : top) {
			emit(entry, result);
		}
		for (Entry entry : sorted) {
			emit(entry, result);
		}
		return result;
	}

	/**
	 * Whether the given entry is the other one or stands after it, directly or through others.
	 */
	private static boolean standsAfter(Entry entry, Entry other) {
		for (Entry current = entry; current != null; current = current._anchor) {
			if (current == other) {
				return true;
			}
		}
		return false;
	}

	private static void emit(Entry entry, List<Option> result) {
		result.add(entry._item);
		for (Entry follower : entry._followers) {
			emit(follower, result);
		}
	}

}
