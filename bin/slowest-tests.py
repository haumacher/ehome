#!/usr/bin/env python3
"""Prints the time of the test suites and their slowest tests and classes, from the surefire reports.

Run after the gate (./mvnw install -DskipTests=false) from the repository root:

    bin/slowest-tests.py [report...] [count]

Without a report, it reads the suite of each module that has one: the app (ehome/) and the
development tooling (ehome-virtual/, not built with -Pproduction). Each module runs its suite in a
JVM and a database of its own; the total is the sum of the suites.

The surefire report has the time of each test method only. The setups and teardowns around them
(the services of a test class, the imports of its TestSetup) are in the timeline the suite writes
beside the report (test-timeline.txt, written by test.de.haumacher.ehome.TimelineListener): the
classes are also listed by their wall time, which counts the time from the end of the test before a
class's first test to the end of its last test, so its setup (and the teardown of the class before
it) belongs to it.

The budget (.claude/skills/ehome-workflow/SKILL.md, section 2): all suites together at most 95 s, a new
test class at most 5 s unless its javadoc names why it needs more.
"""
import os
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

MODULES = ['ehome', 'ehome-virtual']
REPORT = 'target/surefire-reports/TEST-test.TestAll.xml'
TIMELINE = 'test-timeline.txt'

args = sys.argv[1:]
count = 10
if args and args[-1].isdigit():
    count = int(args.pop())
reports = args or [os.path.join(module, REPORT) for module in MODULES if os.path.exists(os.path.join(module, REPORT))]
if not reports:
    sys.exit('No surefire report found; run the gate first: ./mvnw install -DskipTests=false')


def class_of(name):
    return name.split('#', 1)[0]


def wall_times(timeline):
    """The wall time of each class (with the time before its first test), and the time outside tests."""
    walls = defaultdict(float)
    previous_end = 0
    current = None
    block_start = 0
    in_tests = 0
    started = {}
    finished = None
    with open(timeline, encoding='utf-8') as lines:
        for line in lines:
            event, millis, name = (line.rstrip('\n').split('\t') + ['', ''])[:3]
            millis = int(millis)
            if event == 'start':
                started[name] = millis
                cls = class_of(name)
                if cls != current:
                    current = cls
                    block_start = previous_end
            elif event == 'end':
                in_tests += millis - started.pop(name, millis)
                walls[current] += (millis - max(previous_end, block_start)) / 1000
                previous_end = millis
            elif event == 'finished':
                finished = millis
    outside = None if finished is None else (finished - in_tests) / 1000
    return walls, outside


load = os.getloadavg()[0] if hasattr(os, 'getloadavg') else float('nan')
total_time = 0.0
total_tests = 0
cases = []
classes = defaultdict(float)
walls = defaultdict(float)
for report in reports:
    root = ET.parse(report).getroot()
    suite_time = float(root.get('time') or 0)
    tests = 0
    for case in root.iter('testcase'):
        seconds = float(case.get('time') or 0)
        cases.append((seconds, case.get('classname', '') + '#' + case.get('name', '')))
        classes[case.get('classname', '')] += seconds
        tests += 1
    line = 'Suite %s: %.1f s, %d tests' % (report, suite_time, tests)
    timeline = os.path.join(os.path.dirname(report), TIMELINE)
    if os.path.exists(timeline) and os.path.getmtime(timeline) >= os.path.getmtime(report) - 60:
        module_walls, outside = wall_times(timeline)
        for name, seconds in module_walls.items():
            walls[name] += seconds
        if outside is not None:
            line += ', %.1f s of it outside the test methods (setups, teardowns)' % outside
    print(line)
    total_time += suite_time
    total_tests += tests

print('Total: %.1f s, %d tests (load average now %.1f)' % (total_time, total_tests, load))
print('Slowest tests:')
for seconds, name in sorted(cases, reverse=True)[:count]:
    print('  %6.1f s  %s' % (seconds, name))
print('Slowest classes (time of their test methods):')
for name, seconds in sorted(classes.items(), key=lambda item: item[1], reverse=True)[:count]:
    print('  %6.1f s  %s' % (seconds, name))
if walls:
    print('Slowest classes by wall time (with their setup, from the timeline):')
    for name, seconds in sorted(walls.items(), key=lambda item: item[1], reverse=True)[:count]:
        print('  %6.1f s  %s (tests %.1f s)' % (seconds, name, classes.get(name, 0.0)))
