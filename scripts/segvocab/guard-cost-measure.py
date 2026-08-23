#!/usr/bin/env python3
"""Cost-test SEGMENT_RARITY_MAX_FRACTION at Keycloak scale: does the rarity guard save
rows-considered / wall-clock on segmentCandidates as a whole, given the budget
(limit - results.size) already bounds what returns?

**Both sides of the comparison must be honest about what production actually runs.**
segmentCandidates always computes the reach query (`namesReached`) first, unconditionally,
to decide which terms the guard accepts -- there is no code path today where the join runs
without it. So "guard ON" is properly `reach_query(all terms) + join(accepted terms)`, not the
join alone: an earlier version of this script timed only the join on both sides, which let the
guard-ON number skip the very cost the reach query itself might be paying to buy the guard its
answer. "Guard OFF" is what the code looks like if SEGMENT_RARITY_MAX_FRACTION and its use are
removed entirely (D18's stated fallback) -- no reach query at all, since nothing downstream would
need that statistic any more: `join(all terms)` alone.

Runs the exact production queries from SqliteStorageAdapter.segmentCandidates (copied verbatim,
modulo Kotlin string interpolation -> Python f-string) against the database built by
guard-cost-build-synthetic.py.

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


def reach_query_sql(terms):
    return (
        "SELECT segment AS seg, COUNT(DISTINCT name) AS cnt FROM name_segment_vocab "
        f"WHERE segment IN ({sql_in_list(terms)}) GROUP BY segment"
    )


def reach_query(terms):
    cur = conn.execute(reach_query_sql(terms))
    return dict(cur.fetchall())


def rows_considered(terms):
    """COUNT(*) matching the WHERE clause -- what a query over these terms has to walk,
    whether that's the reach query or the join."""
    if not terms:
        return 0
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


def explain(sql):
    cur = conn.execute("EXPLAIN QUERY PLAN " + sql)
    return "\n".join(f"    {row}" for row in cur.fetchall())


def time_sql(sql, repeats=REPEATS):
    # warm the page cache first (uncounted), then time.
    rows = conn.execute(sql).fetchall()
    times = []
    for _ in range(repeats):
        t0 = time.perf_counter()
        rows = conn.execute(sql).fetchall()
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


def time_empty():
    return {"min_ms": 0.0, "median_ms": 0.0, "max_ms": 0.0, "row_count": 0}


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

    print(f"  guard-ON accepted terms (fed to the join, after the reach query runs): {accepted}")
    print(f"  guard-OFF: no reach query at all; the join runs over all terms:        {terms}")

    rc_reach = rows_considered(terms)             # reach query's own posting-list walk, guard-ON only
    rc_join_accepted = rows_considered(accepted)   # join's posting-list walk, guard-ON
    rc_join_all = rows_considered(terms)           # join's posting-list walk, guard-OFF (same WHERE as reach, different query)
    print(f"  rows considered by the reach query (guard-ON only):      {rc_reach}")
    print(f"  rows considered by the join over accepted terms (guard-ON): {rc_join_accepted}")
    print(f"  rows considered by the join over all terms (guard-OFF):     {rc_join_all}")
    print(f"  guard-ON total rows considered (reach + join):  {rc_reach + rc_join_accepted}")
    print(f"  guard-OFF total rows considered (join only):    {rc_join_all}")

    print("  EXPLAIN QUERY PLAN, reach query (guard-ON's first query):")
    print(explain(reach_query_sql(terms)))
    print("  EXPLAIN QUERY PLAN, join over all terms (guard-OFF):")
    print(explain(join_query(terms, budget)))
    if accepted:
        print("  EXPLAIN QUERY PLAN, join over accepted terms (guard-ON's second query):")
        print(explain(join_query(accepted, budget)))
    else:
        print("  EXPLAIN QUERY PLAN, join over accepted terms (guard-ON): n/a (accepted list empty -> segmentCandidates short-circuits, zero join issued)")

    timing_reach = time_sql(reach_query_sql(terms))
    timing_join_accepted = time_sql(join_query(accepted, budget)) if accepted else time_empty()
    timing_join_all = time_sql(join_query(terms, budget))

    guard_on_total_median = timing_reach["median_ms"] + timing_join_accepted["median_ms"]
    guard_off_total_median = timing_join_all["median_ms"]

    print(f"  wall-clock reach query          (n={REPEATS} reps): median={timing_reach['median_ms']:.3f} ms  min={timing_reach['min_ms']:.3f}  max={timing_reach['max_ms']:.3f}")
    print(f"  wall-clock join, accepted terms (n={REPEATS} reps): median={timing_join_accepted['median_ms']:.3f} ms  min={timing_join_accepted['min_ms']:.3f}  max={timing_join_accepted['max_ms']:.3f}  rows_returned={timing_join_accepted['row_count']}")
    print(f"  wall-clock join, all terms      (n={REPEATS} reps): median={timing_join_all['median_ms']:.3f} ms  min={timing_join_all['min_ms']:.3f}  max={timing_join_all['max_ms']:.3f}  rows_returned={timing_join_all['row_count']}")
    print(f"  ==> GUARD-ON  total (reach + join-accepted): {guard_on_total_median:.3f} ms")
    print(f"  ==> GUARD-OFF total (join-all only):         {guard_off_total_median:.3f} ms")
    if guard_off_total_median > 0:
        ratio = guard_on_total_median / guard_off_total_median
        verdict = "GUARD-ON CHEAPER" if ratio < 1 else ("GUARD-OFF CHEAPER" if ratio > 1 else "TIE")
        print(f"  ==> ratio (guard-on / guard-off): {ratio:.3f}x  ({verdict})")


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
