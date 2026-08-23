#!/usr/bin/env python3
"""Build a SQLite database shaped like a Keycloak-scale `name_segment_vocab` + `nodes`, with a
realistically skewed segment-frequency distribution (a few very common segments, a long tail of
rare ones) -- for cost-testing SEGMENT_RARITY_MAX_FRACTION on a dimension excalidraw's 9-question
retrieval set is too small to exercise: candidate-set size and wall-clock at Keycloak scale.

D5 caps this run at two real Keycloak indexes and both are spent, so this is a synthetic stand-in,
not a real index -- schema matches V1__init.sql (nodes, idx_nodes_label) and
V6__name_segment_vocab.sql (name_segment_vocab, WITHOUT ROWID, PK (segment, name)) exactly, and no
ANALYZE is run, since production never runs it either (grepped: no ANALYZE/PRAGMA anywhere in
modules/storage-sqlite/src/main).

Usage: guard-cost-build-synthetic.py <output.db>
See docs/identifier-segment-vocabulary.md section 16 for how this was used and what it found.
"""
import random
import sqlite3
import sys

random.seed(20260823)

DB_PATH = sys.argv[1] if len(sys.argv) > 1 else "synthetic.db"

NUM_NAMES = 120_000  # distinct node labels -- same order of magnitude as Keycloak's real
                      # non-file node count (235,152 nodes total per this document's section 5).

# Tiered reach distribution. "reach" = COUNT(DISTINCT name) for a segment, i.e. how many of
# NUM_NAMES that segment's posting list touches. Five real domain words get a deliberately huge
# reach (Java/Keycloak-flavoured: these are the words a guard is supposed to catch), everything
# else decays through progressively larger, progressively rarer tiers down to a long tail of
# once-or-twice-used segments -- the same shape this codebase's own KDoc describes for excalidraw
# at 1/25th this scale (`element` reaching 359 of 4,764 names, 7.5%; most segments far under the
# 5% cut).
TIER_A = [  # very common -- real identifier sub-words that recur constantly in a large Java
            # codebase; every one of these clears the 5% cut at this scale (6,000).
    ("service", 32000), ("get", 27000), ("config", 21000), ("provider", 16000), ("user", 12500),
]
TIER_B_COUNT, TIER_B_REACH_RANGE = 60, (1200, 2800)     # common but under the 5% cut
TIER_C_COUNT, TIER_C_REACH_RANGE = 600, (120, 260)      # moderate
TIER_D_COUNT, TIER_D_REACH_RANGE = 6000, (10, 30)       # infrequent
# long tail: fill remaining row budget at 1-4 reach each (real long-tail identifier sub-words: a
# specific class/method-name fragment used in a handful of places)
TARGET_TOTAL_ROWS = 600_000  # same order this codebase's own KDoc already cites for Keycloak
                              # scale (SqliteStorageAdapter.kt's cachedTotalVocabNames comment:
                              # "~960 ms at Keycloak scale (600K rows)")

names = [f"Name{i:07d}" for i in range(NUM_NAMES)]

conn = sqlite3.connect(DB_PATH)
conn.execute("PRAGMA journal_mode=OFF")
conn.execute("PRAGMA synchronous=OFF")
conn.executescript("""
DROP TABLE IF EXISTS nodes;
DROP TABLE IF EXISTS name_segment_vocab;
CREATE TABLE nodes (
    id TEXT PRIMARY KEY,
    type TEXT NOT NULL,
    label TEXT NOT NULL,
    properties TEXT NOT NULL DEFAULT '{}',
    confidence REAL NOT NULL DEFAULT 1.0
);
CREATE INDEX idx_nodes_label ON nodes(label);
CREATE TABLE name_segment_vocab (
    segment TEXT NOT NULL,
    name TEXT NOT NULL,
    PRIMARY KEY (segment, name)
) WITHOUT ROWID;
""")

# nodes: one node per name (1:1 -- real data has some label reuse across nodes, but a 1:1 floor is
# the conservative case for this measurement: it cannot make the join artificially cheaper than
# reality by collapsing lookups onto fewer distinct labels).
conn.executemany(
    "INSERT INTO nodes(id, type, label) VALUES (?, 'Function', ?)",
    ((f"n{i:07d}", name) for i, name in enumerate(names)),
)
conn.commit()

rows_written = 0
segment_reach = {}  # segment -> reach, recorded for the report

def write_segment(segment: str, reach: int, cur):
    global rows_written
    reach = min(reach, NUM_NAMES)
    picked = random.sample(names, reach)
    cur.executemany(
        "INSERT INTO name_segment_vocab(segment, name) VALUES (?, ?)",
        ((segment, n) for n in picked),
    )
    rows_written += reach
    segment_reach[segment] = reach

cur = conn.cursor()

for seg, reach in TIER_A:
    write_segment(seg, reach, cur)

for i in range(TIER_B_COUNT):
    write_segment(f"tb_{i:04d}", random.randint(*TIER_B_REACH_RANGE), cur)

for i in range(TIER_C_COUNT):
    write_segment(f"tc_{i:04d}", random.randint(*TIER_C_REACH_RANGE), cur)

for i in range(TIER_D_COUNT):
    write_segment(f"td_{i:04d}", random.randint(*TIER_D_REACH_RANGE), cur)

tail_i = 0
while rows_written < TARGET_TOTAL_ROWS:
    reach = random.randint(1, 4)
    write_segment(f"tail_{tail_i:06d}", reach, cur)
    tail_i += 1

conn.commit()
conn.execute("VACUUM")
conn.close()

print(f"wrote {DB_PATH}")
print(f"total name_segment_vocab rows: {rows_written}")
print(f"distinct names: {NUM_NAMES}")
print(f"distinct segments: {len(segment_reach)}")
guard_cut = int(NUM_NAMES * 0.05)
print(f"5% rarity cut at this scale: reach > {guard_cut} is suppressed")
over_cut = [s for s, r in segment_reach.items() if r > guard_cut]
print(f"segments over the cut ({len(over_cut)}): " + ", ".join(f"{s}={segment_reach[s]}" for s in over_cut))
