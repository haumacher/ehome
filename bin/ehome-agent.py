#!/usr/bin/env python3
# Copyright (c) 2026 Bernhard Haumacher
#
# Drives a running E-Home session through its agent endpoint (doc/agent-access.md): the user issues
# an access token in E-Home (development menu, "Agent access") and hands over the invitation, one line
# "<url>/agent-api tlagt_...". The invitation is read from the file named by --invitation (default
# ~/.config/ehome/agent-invitation, keep it at mode 600) or from the variable EHOME_AGENT_INVITATION;
# the token is never printed.
#
#   ehome-agent.py windows                       the windows of the session
#   ehome-agent.py observe [--actions]           the observation of a window (JSON)
#   ehome-agent.py act <address> <command> [<arguments as JSON>]
#   ehome-agent.py navigate <route URL>          e.g. /ehome/view/projects
#
# --window <name> selects the window (default: the first one /windows lists).

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


def invitation(path):
	text = os.environ.get("EHOME_AGENT_INVITATION")
	if not text:
		try:
			with open(os.path.expanduser(path), encoding="utf-8") as f:
				text = f.read()
		except OSError as e:
			sys.exit("ehome-agent: no invitation (%s); issue a token in E-Home, development menu, Agent access" % e)
	parts = text.split()
	if len(parts) != 2 or not parts[1].startswith("tlagt_"):
		sys.exit("ehome-agent: the invitation is not '<url>/agent-api tlagt_...'")
	return parts[0].rstrip("/"), parts[1]


class _NoRedirect(urllib.request.HTTPRedirectHandler):
	"""A request the endpoint does not admit is redirected to the login page (TL #30125), not answered with 401."""

	def redirect_request(self, req, fp, code, msg, headers, newurl):
		return None


_OPENER = urllib.request.build_opener(_NoRedirect)


def call(base, token, method, path, body=None):
	data = None if body is None else json.dumps(body).encode("utf-8")
	request = urllib.request.Request(base + path, data=data, method=method)
	request.add_header("Authorization", "Bearer " + token)
	if data is not None:
		request.add_header("Content-Type", "application/json")
	try:
		with _OPENER.open(request, timeout=60) as response:
			return json.load(response)
	except urllib.error.HTTPError as e:
		if e.code in (301, 302, 303, 307, 308, 401):
			sys.exit("ehome-agent: the token is not admitted (withdrawn, expired, or its session has ended): "
				"issue a new one in E-Home, development menu, Agent access")
		detail = e.read().decode("utf-8", "replace")
		sys.exit("ehome-agent: %s %s answered %d: %s" % (method, path, e.code, detail))
	except urllib.error.URLError as e:
		sys.exit("ehome-agent: %s unreachable: %s" % (base, e.reason))


def window(base, token, name):
	if name:
		return name
	windows = call(base, token, "GET", "/windows").get("windows", [])
	if not windows:
		sys.exit("ehome-agent: the session has no window; the user must have E-Home open in a browser")
	first = windows[0]
	return first.get("windowName", first.get("name")) if isinstance(first, dict) else first


def main():
	parser = argparse.ArgumentParser(description="Drive an E-Home session through its agent endpoint.")
	parser.add_argument("--invitation", default="~/.config/ehome/agent-invitation")
	parser.add_argument("--window")
	sub = parser.add_subparsers(dest="cmd", required=True)
	sub.add_parser("windows")
	observe = sub.add_parser("observe")
	observe.add_argument("--actions", action="store_true", help="only the actionable nodes")
	act = sub.add_parser("act")
	act.add_argument("address")
	act.add_argument("command")
	act.add_argument("arguments", nargs="?", default="{}")
	navigate = sub.add_parser("navigate")
	navigate.add_argument("url")
	args = parser.parse_args()

	base, token = invitation(args.invitation)
	if args.cmd == "windows":
		result = call(base, token, "GET", "/windows")
	elif args.cmd == "observe":
		query = {"windowName": window(base, token, args.window)}
		if args.actions:
			query["mode"] = "actions"
		result = call(base, token, "GET", "/observe?" + urllib.parse.urlencode(query))
	elif args.cmd == "act":
		result = call(base, token, "POST", "/act", {
			"windowName": window(base, token, args.window),
			"address": json.loads(args.address) if args.address.startswith(("[", "{")) else args.address,
			"command": args.command,
			"arguments": json.loads(args.arguments),
		})
	else:
		result = call(base, token, "POST", "/navigate", {"windowName": window(base, token, args.window), "url": args.url})
	json.dump(result, sys.stdout, ensure_ascii=False, indent=1)
	print()


if __name__ == "__main__":
	main()
