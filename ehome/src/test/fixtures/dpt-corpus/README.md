# DPT conformance corpus (copy)

The `dpt-*.json` files and `dpt-corpus.md` (their format and rules) are verbatim copies of
`spec/fixtures/dpt/` and `spec/dpt-corpus.md` of the private repository haumacher/homecontrol, the
corpus that HomeControl's DPT codecs and E-Home's `DatapointCodec` (`DatapointValues`) both pass
(`test.de.haumacher.ehome.monitor.TestDptCorpus`).

Copied from homecontrol commit `c0f8891ee7499e97e37ba7f3f7dcd52af0fa979a` (the corpus last changed
in `3cf99da74566f406e44b8f911797695616006e43`, 2026-10-17).

Do not edit the copies: a corpus entry that looks wrong is an issue at haumacher/homecontrol. To
refresh them, from the root of this repository:

```sh
gh repo clone haumacher/homecontrol <dir>   # or git -C <dir> pull
bin/sync-dpt-corpus.sh <dir>
```

The script replaces the copies and prints the commit; write it into this file.
