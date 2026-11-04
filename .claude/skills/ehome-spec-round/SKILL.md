---
name: ehome-spec-round
description: A specification round for E-Home and its HomeControl controller — the user proposes features one by one, the session looks up the evidence in both code bases, asks only the questions that change the design, and files precise GitHub tickets (no implementation, builds or commits); optionally it supervises an implementing agent in another session, answering its design questions from the round's decisions. Use when the user asks for a "specification round", wants to "only discuss features and file tickets", or asks to keep an implementing agent going.
---

# Specification round

A round turns the user's feature ideas into tickets an implementing agent can work through
without coming back. The session **discusses and files; it does not implement**: no code edits,
no builds, no tests, no commits (except to this skill or memory, when asked). Read the skill
`ehome-workflow` for the product context: E-Home (`haumacher/ehome`, `~/devel/ehome`) and the
HomeControl firmware (`haumacher/homecontrol`, `~/devel/homecontrol`) are **one product**, and
every proposal is considered on both sides.

## 1. The loop per proposal

1. **Look up the evidence before answering.** Find how it works today, in both repositories:
   the specs (`homecontrol/spec/*.md`: `api.md`, `runtime-image.md`, `rules.md`,
   `schedules.md`, ...), the firmware headers and limits (`core/include/hc/*.h`,
   `partitions-*.csv`), the web UI (`web/ui/dist/`), and on the E-Home side the model
   (`WEB-INF/model/*.model.xml`), the image writer (`RuntimeImage.java`), the views and the
   docs (`doc/`). Quote the evidence with `file:line` in the answer. Never design from memory: a
   recalled limit or member name is checked in the code first.
2. **Decide what kind of answer it needs:**
   - *Totally clear* (the user said what to do, the code shows where): file the ticket right
     away and report its number.
   - *Something is missing, or can be done better*: answer with what exists, then a short list of
     **numbered** questions or suggestions, each with a recommendation the user can just accept
     ("(a) ... — I'd do X because Y"). Ask only what changes the design: a conventional default
     is stated as the default, not asked. Point out what the user forgot (limits, migration of
     existing data, the older firmware, access rights, i18n, the simulation, the test corpus).
3. **The user's answer is the design.** Take their words literally and quote them in the
   ticket; don't re-argue a decision once made. If an answer opens a new gap, ask that one
   follow-up and file then.
4. **File** (§2) and continue with the next proposal.

## 2. Tickets

- **One ticket per repository side**, cross-referenced (`haumacher/homecontrol#54` ↔
  `haumacher/ehome#72`): the firmware ticket owns the format/API/spec change, the E-Home ticket
  owns the model, the editor and the image writer. A feature without a firmware part gets an
  E-Home ticket only, and vice versa.
- **Separate tickets for separable things** (a chart element vs. the history it needs; a spike
  vs. the contract it decides), with dependencies stated ("depends on #60") and listed in a
  comment on the parent.
- **Body** — write it to a file in the scratchpad and pass `--body-file`:
  - *Motivation*: the user's words, quoted.
  - *Today*: the evidence with `file:line`.
  - *Design*: the decisions of the discussion, each as a statement (members, limits, defaults,
    refusals with their reason text, what an older firmware does under the versioning rules I4/I5
    of `spec/runtime-image.md`).
  - *Rejected alternatives*, with the reason (the spec documents use the same style).
  - *Acceptance*: what proves it done — host/unit tests, the shared corpus run on both sides
    (C++ and the Java simulation), a check on the board, a browser check.
  - *Out of scope*.
- Labels: the repository's existing ones only (`enhancement`, `bug`, `question`,
  `documentation`, and in homecontrol e.g. `decision`); don't invent new ones.
- Product, not one home: the user's own house (`~/incoming/...`, openHAB configurations, the
  `.knxproj` in memory) is **evidence of a need**, never the specification; generalize (the
  user: "We are not building an app for my home. We are building a product.").
- Reread each body before filing; fix a wrong reference or a confusing sentence right after
  filing with `gh issue edit` rather than leaving it.

## 3. Supervising the implementing agent

When the user asks to "keep an eye on" an implementing session (another Claude session on this
machine, e.g. `ehome-11`):

- **Address it** by the `from` of its messages (`uds:/tmp/cc-socks/<pid>.sock`); its transcript
  is `~/.claude/projects/-home-haui-devel-ehome/<session-id>.jsonl`. Read the tail of the
  transcript with a short `python3 -I` filter (last assistant texts and tool calls) instead of
  guessing what it is doing.
- **Idle notices**: `SendMessage` with `notify_when_idle: true` is one-shot; re-subscribe after
  each notice **only while the session is busy**. Subscribing to an idle session fires at once
  with its old status, again and again — then wait for new output instead: a background loop
  that counts `"type":"assistant"` lines in its transcript and exits when the count grows.
- **Answer design questions from the round's decisions**: the tickets, their decision comments,
  the specs. Check the code before answering (a versioning question is answered from I4/I5, not
  from intuition). Answer with a numbered decision per question, the reason, and what to write
  into which spec; then record the same decisions as a comment on the ticket, so they survive
  both sessions.
- **When you cannot decide** (it is genuinely the user's call): tell the agent to note the
  question in the issue, park only the part that depends on it, and continue with work that does
  not; then bring the question to the user.
- Status notices that need nothing ("waiting for the OTA upload") get no message; just keep
  watching.
- When it reports its queues empty: check `gh issue list` on both repositories, and report to the
  user what was delivered (with commits), what waits on the user or on hardware, and any
  discrepancy in its report (e.g. an issue number that does not match the issue's title).

## 4. Standing constraints

The memory rules hold in a round too: never delete outside the scratchpad; the user's home
project file is for analysis only (unzip it into the scratchpad, never commit it, never program
it); KNX hardware only via the test router; no confirmation question at the end of a turn —
decide, file, report.
