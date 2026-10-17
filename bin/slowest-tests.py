#!/usr/bin/env python3
"""Prints the time of the test suite and its slowest tests and classes, from the surefire report.

Run after the gate (./mvnw install -DskipTests=false) from the project root:

    bin/slowest-tests.py [report] [count]

The budget (.claude/skills/ehome-workflow/SKILL.md, section 2): the whole suite at most 120 s on an
idle machine, a new test class at most 5 s unless its javadoc names why it needs more.
"""
import os
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

report = sys.argv[1] if len(sys.argv) > 1 else 'target/surefire-reports/TEST-test.TestAll.xml'
count = int(sys.argv[2]) if len(sys.argv) > 2 else 10

root = ET.parse(report).getroot()
cases = []
classes = defaultdict(float)
for case in root.iter('testcase'):
    seconds = float(case.get('time') or 0)
    name = case.get('classname', '') + '#' + case.get('name', '')
    cases.append((seconds, name))
    classes[case.get('classname', '')] += seconds

load = os.getloadavg()[0] if hasattr(os, 'getloadavg') else float('nan')
print('Suite: %.1f s, %d tests (load average now %.1f)' % (float(root.get('time') or 0), len(cases), load))
print('Slowest tests:')
for seconds, name in sorted(cases, reverse=True)[:count]:
    print('  %6.1f s  %s' % (seconds, name))
print('Slowest classes:')
for name, seconds in sorted(classes.items(), key=lambda item: item[1], reverse=True)[:count]:
    print('  %6.1f s  %s' % (seconds, name))
