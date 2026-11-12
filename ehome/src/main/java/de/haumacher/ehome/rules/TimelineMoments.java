/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import de.haumacher.ehome.rules.Astro.GeoLocation;
import de.haumacher.ehome.rules.RuleSet.Plan;
import de.haumacher.ehome.rules.RuleSet.Point;
import de.haumacher.ehome.rules.RuleSet.Timeline;
import de.haumacher.ehome.rules.Val.Date;
import de.haumacher.ehome.rules.Val.DateTime;

/**
 * The moments of the switching points of a timeline (homecontrol <code>spec/rules.md</code> 7.13):
 * on a local date, the moment of each point of the date's plan as a UTC instant in whole seconds,
 * through the time zone of the {@link Engine.TimeSource}; the state due at an instant and the next
 * point after an instant.
 *
 * <p>
 * Dates are local dates as days since 1970-01-01. A clock point is its time on the date by the DST
 * rules S1 of <code>spec/schedules.md</code> (a time in the gap of the spring-forward day is the end
 * of the gap, a time that occurs twice its first occurrence); a sun point is the sun event of the
 * date at the location ({@link Astro#sunTime(int, int, int, GeoLocation, Astro.SunEvent)}, elevation
 * 0) plus its offset, moved up to its earliest and down to its latest time; every moment is then
 * kept within its date.
 * </p>
 */
final class TimelineMoments {

	/** The dates searched back for the due state and forward for the next point: today and seven more. */
	static final int WINDOW = 8;

	/**
	 * A switching point on a date.
	 *
	 * @param moment
	 *        The UTC instant in seconds.
	 * @param order
	 *        The position of the point in its plan (the plan order).
	 * @param point
	 *        The point.
	 */
	record Moment(long moment, int order, Point point) {
		// A value.
	}

	private final Engine.TimeSource _time;

	/** Whether a sun point had no moment for the lack of a location since the last {@link #reset()}. */
	private boolean _sunUnavailable;

	/** Whether a sun point had a location since the last {@link #reset()}. */
	private boolean _sunAvailable;

	TimelineMoments(Engine.TimeSource time) {
		_time = time;
	}

	/** Forgets whether sun points lacked a location. */
	void reset() {
		_sunUnavailable = false;
		_sunAvailable = false;
	}

	/** Whether a sun point had no moment because there is no location, since the last {@link #reset()}. */
	boolean sunUnavailable() {
		return _sunUnavailable;
	}

	/** Whether a sun point was computed at a location since the last {@link #reset()}. */
	boolean sunAvailable() {
		return _sunAvailable;
	}

	/** The local date of the instant, days since 1970-01-01. */
	long localDay(long utc) {
		DateTime local = _time.utcToLocal(utc);
		if (local == null) {
			return Math.floorDiv(utc, 86400L);
		}
		return Values.daysOf(local.year(), local.month(), local.day());
	}

	/** The instant of the local time (seconds of the day) on the date, by the DST rules S1. */
	long instant(long day, int seconds) {
		Date date = Values.civilOf(day + seconds / 86400);
		int rest = seconds % 86400;
		DateTime local = new DateTime(date.year(), date.month(), date.day(), rest / 3600, rest / 60 % 60, rest % 60,
			Values.weekdayOf(date.year(), date.month(), date.day()), DateTime.SUMMER_TIME);
		return _time.localToUtc(local);
	}

	/**
	 * The points of the date with their moments, ordered by moment and, at equal moments, by plan
	 * order. A sun point without a moment on the date (no location, no event) is left out.
	 */
	List<Moment> points(Timeline timeline, long day) {
		Date date = Values.civilOf(day);
		int weekday = Values.weekdayOf(date.year(), date.month(), date.day());
		int planIndex = timeline.planOf(weekday);
		List<Moment> result = new ArrayList<>();
		if (planIndex < 0) {
			return result;
		}
		Plan plan = timeline._plans.get(planIndex);
		long first = -1;
		long last = -1;
		for (int k = 0; k < plan._points.size(); k++) {
			Point point = plan._points.get(k);
			long moment;
			if (point._sun == null) {
				moment = instant(day, point._time);
			} else {
				GeoLocation location = _time.location();
				if (location == null || !location.valid()) {
					_sunUnavailable = true;
					continue;
				}
				_sunAvailable = true;
				Long event = Astro.sunTime(date.year(), date.month(), date.day(),
					new GeoLocation(location.latitude(), location.longitude(), 0), point._sun);
				if (event == null) {
					continue;
				}
				moment = event.longValue() + point._offset;
				if (point._notBefore >= 0) {
					moment = Math.max(moment, instant(day, point._notBefore));
				}
				if (point._notAfter >= 0) {
					moment = Math.min(moment, instant(day, point._notAfter));
				}
			}
			if (first < 0) {
				first = instant(day, 0);
				last = instant(day + 1, 0) - 1;
			}
			// A day plan describes its own day: kept within the date.
			moment = Math.max(first, Math.min(last, moment));
			result.add(new Moment(moment, k, point));
		}
		result.sort(Comparator.comparingLong(Moment::moment).thenComparingInt(Moment::order));
		return result;
	}

	/**
	 * The point whose state is due at the instant (7.13, 3): of the dates from the local date of the
	 * instant back over seven more, the first that has points not after the instant gives its latest
	 * (at equal moments the last in plan order); <code>null</code> when none of them has one.
	 */
	Moment due(Timeline timeline, long utc) {
		long today = localDay(utc);
		for (long day = today; day > today - WINDOW; day--) {
			Moment result = null;
			for (Moment moment : points(timeline, day)) {
				if (moment.moment() <= utc) {
					result = moment;
				}
			}
			if (result != null) {
				return result;
			}
		}
		return null;
	}

	/**
	 * The first point after the handled one (7.13, 2): whose moment lies after the instant, or at the
	 * instant after the given plan order (points at one moment apply one after the other), searched
	 * over the dates from the local date of the instant on (eight dates); <code>null</code> for none.
	 *
	 * @param order
	 *        The plan order of the point handled at the instant, {@link Integer#MAX_VALUE} when every
	 *        point at the instant is handled.
	 */
	Moment next(Timeline timeline, long utc, int order) {
		long start = localDay(utc);
		for (long day = start; day < start + WINDOW; day++) {
			for (Moment moment : points(timeline, day)) {
				if (moment.moment() > utc || moment.moment() == utc && moment.order() > order) {
					return moment;
				}
			}
		}
		return null;
	}

}
