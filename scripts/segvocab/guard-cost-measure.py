#!/usr/bin/env python3
"""Cost-test SEGMENT_RARITY_MAX_FRACTION at Keycloak scale: does the rarity guard save
rows-considered / wall-clock on segmentCandidates' actual join query, given the budget
(limit - results.size) already bounds what returns?

Runs the exact two production queries from SqliteStorageAdapter.segmentCandidates (copied
verbatim, modulo Kotlin string interpolation -> Python f-string) against the database built by
guard-cost-build-synthetic.py, guard on (accepted terms only, mirroring what production actually
sends once the rarity filter has run) vs guard off (every term, unfiltered, as if
SEGMENT_RARITY_MAX_FRACTION were 1.0).

Usage: guard-cost-measure.py <synthetic.db>
See docs/identifier-segment-vocabulary.md section 16 for the numbers this produced and what they
license.
"""
import sqlite3
import sys
import time

DB_PATH = sys.argv[1] if len(sys.argv) > 1 else "synthetic.db"
REPEATS = 25

conn = sqlite3.connect(DB_PATH)
conn.execute("PRAGMA query_only=ON")


def sql_in_list(values):
    return ",".join("'" + v.replace("'", "''") + "'" for v in values)


def reach_query(terms):
    cur = conn.execute(
        f"SELECT segment AS seg, COUNT(DISTINCT name) AS cnt FROM name_segment_vocab "
        f"WHERE segment IN ({sql_in_list(terms)}) GROUP BY segment"
    )
    return dict(cur.fetchall())


def rows_considered(terms):
    """COUNT(*) matching the WHERE clause before the join -- what the join has to walk."""
    cur = conn.execute(
        f"SELECT COUNT(*) FROM name_segment_vocab WHERE segment IN ({sql_in_list(terms)})"
    )
    return cur.fetchone()[0]


def join_query(terms, budget):
    return (
        "SELECT DISTINCT n.id FROM name_segment_vocab v "
        "JOIN nodes n ON n.label = v.name "
        f"WHERE v.segment IN ({sql_in_list(terms)}) "
        f"ORDER BY n.id LIMIT {budget}"
    )


def explain(terms, budget):
    cur = conn.execute("EXPLAIN QUERY PLAN " + join_query(terms, budget))
    return "\n".join(f"    {row}" for row in cur.fetchall())


def time_join(terms, budget, repeats=REPEATS):
    q = join_query(terms, budget)
    # warm the page cache first (uncounted), then time.
    conn.execute(q).fetchall()
    times = []
    for _ in range(repeats):
        t0 = time.perf_counter()
        rows = conn.execute(q).fetchall()
        t1 = time.perf_counter()
        times.append(t1 - t0)
    times.sort()
    n = len(times)
    return {
        "min_ms": times[0] * 1000,
        "median_ms": times[n // 2] * 1000,
        "max_ms": times[-1] * 1000,
        "row_count": len(rows),
    }


GUARD_CUT_FRACTION = 0.05


def run_scenario(name, terms, budget):
    print(f"\n=== scenario: {name} (budget={budget}) ===")
    print(f"terms: {terms}")
    total_names = conn.execute("SELECT COUNT(DISTINCT name) FROM name_segment_vocab").fetchone()[0]
    reach = reach_query(terms)
    for t in terms:
        r = reach.get(t, 0)
        frac = r / total_names if total_names else 0
        flag = "OVER CUT (suppressed by guard)" if frac > GUARD_CUT_FRACTION else "under cut"
        print(f"  {t}: reach={r} ({frac*100:.2f}% of {total_names}) -> {flag}")

    accepted = [t for t in terms if (reach.get(t, 0) / total_names if total_names else 0) <= GUARD_CUT_FRACTION and reach.get(t, 0) > 0]

    print(f"  guard-ON accepted terms: {accepted}")
    print(f"  guard-OFF terms (all):   {terms}")

    rc_off = rows_considered(terms)
    rc_on = rows_considered(accepted) if accepted else 0
    print(f"  rows_considered (WHERE match count) guard-OFF: {rc_off}")
    print(f"  rows_considered (WHERE match count) guard-ON:  {rc_on}")

    print("  EXPLAIN QUERY PLAN, guard-OFF:")
    print(explain(terms, budget))
    if accepted:
        print("  EXPLAIN QUERY PLAN, guard-ON:")
        print(explain(accepted, budget))
    else:
        print("  EXPLAIN QUERY PLAN, guard-ON: n/a (accepted list empty -> segmentCandidates short-circuits, zero query issued)")

    timing_off = time_join(terms, budget)
    print(f"  wall-clock guard-OFF (n={REPEATS} reps): median={timing_off['median_ms']:.3f} ms  min={timing_off['min_ms']:.3f}  max={timing_off['max_ms']:.3f}  rows_returned={timing_off['row_count']}")
    if accepted:
        timing_on = time_join(accepted, budget)
        print(f"  wall-clock guard-ON  (n={REPEATS} reps): median={timing_on['median_ms']:.3f} ms  min={timing_on['min_ms']:.3f}  max={timing_on['max_ms']:.3f}  rows_returned={timing_on['row_count']}")
    else:
        print("  wall-clock guard-ON: 0 ms (segmentCandidates returns emptyList() before issuing any join query -- acceptedTerms is empty)")


# Scenario 1: a query that is just one very common identifier sub-word ("get") -- the case where
# the guard's on/off difference should be starkest, since guard-on drops the join entirely
# (acceptedTerms empty -> short-circuit) while guard-off must fully walk and sort a ~27,000-row
# posting list.
run_scenario("single common term only", ["get"], budget=5)
run_scenario("single common term only", ["get"], budget=25)

# Scenario 2: realistic mixed natural-language query -- one common domain word plus five ordinary
# rare identifier sub-words (the shape excalidraw's own nine questions have: mostly rare terms,
# occasionally one common one like "element").
run_scenario("mixed: 1 common + 5 rare", ["get", "tail_000001", "tail_000002", "tail_000003", "td_0001", "tc_0001"], budget=5)
run_scenario("mixed: 1 common + 5 rare", ["get", "tail_000001", "tail_000002", "tail_000003", "td_0001", "tc_0001"], budget=25)

# Scenario 3: stress case -- three common terms at once plus two rare ones.
run_scenario("stress: 3 common + 2 rare", ["get", "service", "config", "tail_000005", "tail_000006"], budget=5)

# Scenario 4: all-rare query -- the common case in practice (per this document's own finding that
# the guard rarely has anything to catch); guard-on and guard-off should be identical here since
# nothing is suppressed.
run_scenario("all rare (nothing to suppress)", ["tail_000010", "tail_000011", "td_0002", "tc_0002"], budget=5)
