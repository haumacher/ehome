#!/usr/bin/env python3
"""Prints the time of the test suites and their slowest tests and classes, from the surefire reports.

Run after the gate (./mvnw install -DskipTests=false) from the repository root:

    bin/slowest-tests.py [report...] [count]

Without a report, it reads the suite of each module that has one: the app (ehome/) and the
development tooling (ehome-virtual/, not built with -Pproduction). Each module runs its suite in a
JVM and a database of its own; the total is the sum of the suites.

The budget (.claude/skills/ehome-workflow/SKILL.md, section 2): all suites together at most 95 s, a new
test class at most 5 s unless its javadoc names why it needs more.
"""
import os
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

MODULES = ['ehome', 'ehome-virtual']
REPORT = 'target/surefire-reports/TEST-test.TestAll.xml'

args = sys.argv[1:]
count = 10
if args and args[-1].isdigit():
    count = int(args.pop())
reports = args or [os.path.join(module, REPORT) for module in MODULES if os.path.exists(os.path.join(module, REPORT))]
if not reports:
    sys.exit('No surefire report found; run the gate first: ./mvnw install -DskipTests=false')

load = os.getloadavg()[0] if hasattr(os, 'getloadavg') else float('nan')
total_time = 0.0
total_tests = 0
cases = []
classes = defaultdict(float)
for report in reports:
    root = ET.parse(report).getroot()
    suite_time = float(root.get('time') or 0)
    tests = 0
    for case in root.iter('testcase'):
        seconds = float(case.get('time') or 0)
        cases.append((seconds, case.get('classname', '') + '#' + case.get('name', '')))
        classes[case.get('classname', '')] += seconds
        tests += 1
    print('Suite %s: %.1f s, %d tests' % (report, suite_time, tests))
    total_time += suite_time
    total_tests += tests

print('Total: %.1f s, %d tests (load average now %.1f)' % (total_time, total_tests, load))
print('Slowest tests:')
for seconds, name in sorted(cases, reverse=True)[:count]:
    print('  %6.1f s  %s' % (seconds, name))
print('Slowest classes:')
for name, seconds in sorted(classes.items(), key=lambda item: item[1], reverse=True)[:count]:
    print('  %6.1f s  %s' % (seconds, name))
