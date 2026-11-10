#!/usr/bin/env python3
# Copyright (c) 2026 Bernhard Haumacher
#
# Calls the MCP endpoint of E-Home (doc/ai.md, "External agents over MCP") without an MCP client: the
# same tools and resources Claude Code reaches with "claude mcp add", from a shell, without restarting
# a client configuration. The personal access token (tlpat_..., created in E-Home under Settings,
# Access tokens) is read from the variable EHOME_MCP_TOKEN or from the file named by --token-file
# (default ~/.config/ehome/mcp-token, keep it at mode 600); it is never printed.
#
#   ehome-mcp.py tools                           the published tools (name and title)
#   ehome-mcp.py tools --full                    the same with descriptions and input schemas (JSON)
#   ehome-mcp.py call <tool> '<arguments JSON>'  e.g. call model-query__query '{"expression": "all(`ehome.project:Project`)"}'
#   ehome-mcp.py resources                       the resources and resource templates
#   ehome-mcp.py read <uri>                      e.g. read toplogic://model/modules
#
# --url names the endpoint (default: the variable EHOME_MCP_URL, else http://localhost:8080/ehome/mcp).
# Every invocation opens a session of its own and closes it again.

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

PROTOCOL_VERSION = "2025-06-18"


def token(path):
	value = os.environ.get("EHOME_MCP_TOKEN")
	if not value:
		try:
			with open(os.path.expanduser(path), encoding="utf-8") as f:
				value = f.read()
		except OSError as e:
			sys.exit("ehome-mcp: no token (%s); create one in E-Home, Settings, Access tokens" % e)
	value = value.strip()
	if not value.startswith("tlpat_"):
		sys.exit("ehome-mcp: the token is not a personal access token (tlpat_...)")
	return value


class Session:

	def __init__(self, url, secret):
		self.url = url
		self.secret = secret
		self.session = None
		self.next_id = 0

	def post(self, message):
		request = urllib.request.Request(self.url, data=json.dumps(message).encode("utf-8"), method="POST")
		self.headers(request)
		request.add_header("Content-Type", "application/json")
		request.add_header("Accept", "application/json, text/event-stream")
		try:
			with urllib.request.urlopen(request, timeout=120) as response:
				if self.session is None:
					self.session = response.headers.get("mcp-session-id")
				return response.read().decode("utf-8")
		except urllib.error.HTTPError as e:
			if e.code == 401:
				sys.exit("ehome-mcp: the token is not admitted (unknown, revoked or expired): "
					"create a new one in E-Home, Settings, Access tokens")
			sys.exit("ehome-mcp: %s answered %d: %s" % (self.url, e.code, e.read().decode("utf-8", "replace")))
		except urllib.error.URLError as e:
			sys.exit("ehome-mcp: %s unreachable: %s" % (self.url, e.reason))

	def headers(self, request):
		request.add_header("Authorization", "Bearer " + self.secret)
		if self.session:
			request.add_header("mcp-session-id", self.session)
			request.add_header("mcp-protocol-version", PROTOCOL_VERSION)

	def request(self, method, params):
		self.next_id += 1
		message = json.loads(self.answer(self.post(
			{"jsonrpc": "2.0", "id": self.next_id, "method": method, "params": params})))
		if "error" in message:
			sys.exit("ehome-mcp: %s refused: %s" % (method, json.dumps(message["error"], ensure_ascii=False)))
		return message.get("result")

	def notify(self, method):
		self.post({"jsonrpc": "2.0", "method": method})

	def open(self):
		result = self.request("initialize", {
			"protocolVersion": PROTOCOL_VERSION,
			"capabilities": {},
			"clientInfo": {"name": "ehome-mcp.py", "version": "1"},
		})
		self.notify("notifications/initialized")
		return result

	def close(self):
		if not self.session:
			return
		request = urllib.request.Request(self.url, method="DELETE")
		self.headers(request)
		try:
			urllib.request.urlopen(request, timeout=10).close()
		except (urllib.error.HTTPError, urllib.error.URLError):
			pass

	@staticmethod
	def answer(body):
		"""The JSON-RPC message of an answer, sent as JSON or as the data of a server-sent event."""
		text = body.strip()
		if text.startswith("{"):
			return text
		data = [line[len("data:"):].strip() for line in text.splitlines() if line.startswith("data:")]
		if not data:
			sys.exit("ehome-mcp: an answer without a message: %r" % body[:200])
		return data[-1]


def main():
	parser = argparse.ArgumentParser(description="Call the MCP endpoint of E-Home.")
	parser.add_argument("--url", default=os.environ.get("EHOME_MCP_URL", "http://localhost:8080/ehome/mcp"))
	parser.add_argument("--token-file", default="~/.config/ehome/mcp-token")
	sub = parser.add_subparsers(dest="cmd", required=True)
	tools = sub.add_parser("tools")
	tools.add_argument("--full", action="store_true", help="descriptions and input schemas")
	call = sub.add_parser("call")
	call.add_argument("tool")
	call.add_argument("arguments", nargs="?", default="{}")
	sub.add_parser("resources")
	read = sub.add_parser("read")
	read.add_argument("uri")
	args = parser.parse_args()

	session = Session(args.url.rstrip("/"), token(args.token_file))
	session.open()
	try:
		if args.cmd == "tools":
			listing = session.request("tools/list", {})
			if args.full:
				json.dump(listing, sys.stdout, ensure_ascii=False, indent=1)
				print()
			else:
				for tool in listing.get("tools", []):
					print("%-45s %s" % (tool.get("name"), tool.get("title", "")))
		elif args.cmd == "call":
			result = session.request("tools/call", {"name": args.tool, "arguments": json.loads(args.arguments)})
			for part in result.get("content", []):
				print(part.get("text", json.dumps(part, ensure_ascii=False)))
			if result.get("isError"):
				sys.exit(1)
		elif args.cmd == "resources":
			listing = {
				"resources": session.request("resources/list", {}).get("resources", []),
				"resourceTemplates": session.request("resources/templates/list", {}).get("resourceTemplates", []),
			}
			json.dump(listing, sys.stdout, ensure_ascii=False, indent=1)
			print()
		else:
			for content in session.request("resources/read", {"uri": args.uri}).get("contents", []):
				print(content.get("text", json.dumps(content, ensure_ascii=False)))
	finally:
		session.close()


if __name__ == "__main__":
	main()
