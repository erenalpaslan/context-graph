package io.contextgraph.benchmark.retrieval

import io.contextgraph.benchmark.model.QuestionCategory
import io.contextgraph.benchmark.runner.GraphTool
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.Instant

/**
 * The two-sided run the earliest assertions here were written against: no CodeGraph side and no bash
 * side, so the report has to render both as absent rather than as zero.
 *
 * Neither question encodes a win for this project. `gin-q1` scores the two present sides identically;
 * `gin-q8` is a negative control, where the graph side finding nothing and text search finding
 * everything is the category doing exactly what it was defined to do.
 */
private fun fixtureRun(): RetrievalRun {
    val results = listOf(
        RetrievalRunResult(
            questionId = "gin-q1",
            repoId = "gin",
            category = QuestionCategory.GRAPH_HEAVY,
            expectedFiles = listOf("gin.go"),
            ripgrepQueryTokens = listOf("ServeHTTP"),
            contextGraph = SideResult(listOf("gin.go"), mapOf(5 to 0.2), mapOf(5 to 1.0), 1.0),
            ripgrep = SideResult(listOf("gin.go"), mapOf(5 to 0.2), mapOf(5 to 1.0), 1.0)
        ),
        RetrievalRunResult(
            questionId = "gin-q8",
            repoId = "gin",
            category = QuestionCategory.NEGATIVE_CONTROL,
            expectedFiles = listOf("gin.go"),
            ripgrepQueryTokens = listOf("404", "405"),
            contextGraph = SideResult(emptyList(), mapOf(5 to 0.0), mapOf(5 to 0.0), 0.0),
            ripgrep = SideResult(listOf("gin.go"), mapOf(5 to 0.2), mapOf(5 to 1.0), 1.0)
        )
    )
    return RetrievalRun(
        runId = "retrieval-test-fixture",
        generatedAt = Instant.parse("2026-08-17T00:00:00Z"),
        kValues = listOf(5),
        results = results,
        skippedRepos = listOf(SkippedRepo("keycloak", "WITHOUT working copy not found")),
        summary = RetrievalStats.summarize(results, listOf(5))
    )
}

class RetrievalReportGeneratorTest : FunSpec({

    test("deterministic: the same run renders to the same string every time") {
        val run = fixtureRun()
        RetrievalReportGenerator.generate(run) shouldBe RetrievalReportGenerator.generate(run)
    }

    test("the section is bounded by start/end markers") {
        val section = RetrievalReportGenerator.generate(fixtureRun())
        section shouldContain "<!-- retrieval-axis:start -->"
        section shouldContain "<!-- retrieval-axis:end -->"
    }

    test("negative controls are reported separately from the headline") {
        val section = RetrievalReportGenerator.generate(fixtureRun())
        section shouldContain "### Headline (GRAPH_HEAVY + NEUTRAL)"
        section shouldContain "### Negative Controls"
        section shouldContain "gin-q8"
    }

    test("category breakdown covers all three categories") {
        val section = RetrievalReportGenerator.generate(fixtureRun())
        section shouldContain "GRAPH_HEAVY"
        section shouldContain "NEUTRAL"
        section shouldContain "NEGATIVE_CONTROL"
    }

    test("skipped repos are reported explicitly, not silently omitted") {
        val section = RetrievalReportGenerator.generate(fixtureRun())
        section shouldContain "### Skipped"
        section shouldContain "keycloak"
    }

    test("upsert appends the section (with markers) when the target file has no existing section") {
        val existing = "# BENCHMARKS\n\nsome pre-existing agent-A/B content\n"
        val section = RetrievalReportGenerator.generate(fixtureRun())

        val merged = RetrievalReportGenerator.upsert(existing, section)

        merged shouldContain "some pre-existing agent-A/B content"
        merged shouldContain "<!-- retrieval-axis:start -->"
        merged shouldContain "gin-q8"
    }

    test("upsert replaces only the marked section, leaving surrounding content untouched") {
        val existing = "# BENCHMARKS\n\nbefore\n\n<!-- retrieval-axis:start -->\nSTALE CONTENT\n<!-- retrieval-axis:end -->\n\nafter\n"
        val section = RetrievalReportGenerator.generate(fixtureRun())

        val merged = RetrievalReportGenerator.upsert(existing, section)

        merged shouldContain "before"
        merged shouldContain "after"
        merged shouldContain "gin-q8"
        (merged.contains("STALE CONTENT")) shouldBe false
    }

    test("upsert is idempotent: running it twice with the same section yields the same file") {
        val existing = "# BENCHMARKS\n\nagent axis content\n"
        val section = RetrievalReportGenerator.generate(fixtureRun())

        val once = RetrievalReportGenerator.upsert(existing, section)
        val twice = RetrievalReportGenerator.upsert(once, section)

        once shouldBe twice
    }

    // ------------------------------------------------- the three-way comparison

    test("every metric table carries all four sides, with their qualifiers") {
        // A three-sided run still renders four columns: the side that was not in it reads `n/a`,
        // never `0.0%`, and a table that dropped the column entirely would hide the absence.
        val section = RetrievalReportGenerator.generate(threeWayRun())

        section shouldContain "| Metric | ContextGraph (this project) | CodeGraph (third-party) | " +
            "bash (base-system shell only) | ripgrep (baseline) |"
        section shouldContain "ContextGraph (this project)"
        section shouldContain "CodeGraph (third-party)"
        section shouldContain "bash (base-system shell only) not in this run"
        section shouldContain "ripgrep (baseline)"
    }

    test("neither graph tool is ever named bare in a table header or a verdict") {
        // The two names differ by two letters, and a reader skimming a table is exactly who gets
        // that wrong. Any occurrence of either bare word inside a table row is a regression.
        val section = RetrievalReportGenerator.generate(threeWayRun())

        val offending = section.lines()
            .filter { it.trimStart().startsWith("|") }
            .filter { line ->
                Regex("ContextGraph(?! \\(this project\\))").containsMatchIn(line) ||
                    Regex("CodeGraph(?! \\(third-party\\))").containsMatchIn(line)
            }
            // `codegraph explore` / `codegraph_explore` in a code span is a command name, not a label.
            .filterNot { it.contains("`codegraph") || it.contains("codegraph_") }

        offending shouldBe emptyList()
    }

    test("each side reports how many questions it actually measured, so a short denominator is visible") {
        val section = RetrievalReportGenerator.generate(threeWayRun())
        section shouldContain "Measured:"
        section shouldContain "excluded from that column's mean, not counted as zero"
    }

    test("gold-file coverage is published for all three sides, and an unknown renders as one") {
        val section = RetrievalReportGenerator.generate(threeWayRun())

        section shouldContain "### Gold-file coverage"
        section shouldContain "queried the index, file by file"
        section shouldContain "100% by construction"
        section shouldContain "_not determinable_"
        // The unknown must not claim a cause it cannot distinguish: this state is reached both
        // when no index exists and when one exists without a per-file read surface.
        section shouldContain "no index, or no readable per-file index surface"
        // The asymmetry it exists to answer, and the part of it that remains.
        section shouldContain "was deliberately **not** loosened"
        section shouldContain "still absent from the ContextGraph (this project)"
    }

    test("ingest cost is reported per tool, and a missing index reads as not built rather than free") {
        val section = RetrievalReportGenerator.generate(threeWayRun())

        section shouldContain "### Ingest cost"
        section shouldContain "1.10 GB"
        section shouldContain "_not built_"
        section shouldContain "binary did not resolve"
    }

    test("a run with no ingest manifest says 'not recorded' rather than showing zeros") {
        val section = RetrievalReportGenerator.generate(fixtureRun())
        section shouldContain "Not recorded"
    }

    test("the report states that the instrument is not self-contained and names what to install") {
        val section = RetrievalReportGenerator.generate(threeWayRun())

        section shouldContain "not self-contained"
        section shouldContain "@colbymchenry/codegraph"
        section shouldContain "ripgrep"
    }

    test("the two parsing decisions that move CodeGraph's numbers are both stated") {
        val section = RetrievalReportGenerator.generate(threeWayRun())

        section shouldContain "blast-radius"
        section shouldContain "Low-confidence match"
        section shouldContain "no `--json` flag"
    }

    test("a run measuring no CodeGraph side renders n/a, never 0.0%") {
        val section = RetrievalReportGenerator.generate(fixtureRun())

        section shouldContain "CodeGraph (third-party) not in this run"
        // The MRR row must show n/a for the absent side rather than a score.
        section.lines().filter { it.startsWith("| MRR ") }.forEach { it shouldContain "n/a" }
    }

    // -------------------------------------------------- the four-way comparison

    test("the side table names four sides, and says which are third-party installs") {
        val section = RetrievalReportGenerator.generate(fourWayRun())

        section shouldContain "Four sides are compared"
        section shouldContain "| **ContextGraph (this project)** |"
        section shouldContain "| **CodeGraph (third-party)** |"
        section shouldContain "| **bash (base-system shell only)** |"
        section shouldContain "| **ripgrep (baseline)** |"
        // The fourth side's whole point: no third-party install anywhere in it.
        section shouldContain "no third-party tools"
    }

    test("every table that carries a ripgrep column carries a bash column too") {
        val section = RetrievalReportGenerator.generate(fourWayRun())

        val metricHeaders = section.lines().filter { it.startsWith("| Metric |") }
        metricHeaders.shouldNotBeEmpty()
        metricHeaders.forEach {
            it shouldBe "| Metric | ContextGraph (this project) | CodeGraph (third-party) | " +
                "bash (base-system shell only) | ripgrep (baseline) |"
        }
        // Headline, GRAPH_HEAVY, NEGATIVE_CONTROL, by-repo `gin`, negative control. NEUTRAL has
        // no questions in this fixture and renders as prose rather than an unfillable table.
        metricHeaders.size shouldBe 5

        // The gold-file coverage table is a row-per-side table, so bash appears as a row.
        section shouldContain "| `gin` | bash (base-system shell only) |"
        // The per-question negative-control table carries the fourth column too.
        section shouldContain "| Question | Repo | derived query tokens | ContextGraph (this project) | " +
            "CodeGraph (third-party) | bash (base-system shell only) | ripgrep (baseline) | Verdict |"
    }

    test("each side's measured count is printed, bash included, so a short denominator is visible") {
        val section = RetrievalReportGenerator.generate(fourWayRun())

        section shouldContain "bash (base-system shell only) 2/2"
        section shouldContain "excluded from that column's mean, not counted as zero"
    }

    test("the methodology states the exact grep invocation and every flag's reason") {
        val section = RetrievalReportGenerator.generate(fourWayRun())

        // Read from BashBaselineFlags rather than retyped, so the report cannot describe a search
        // that did not run.
        section shouldContain BashBaselineFlags.describeArgv()
        BashBaselineFlags.RATIONALE.forEach { (flag, why) ->
            section shouldContain "| `$flag` | $why |"
        }
        // Why these and not others -- including the flags deliberately absent.
        section shouldContain "no emulation of `rg`'s `.gitignore`"
    }

    test("a result favouring bash or CodeGraph reads as a legitimate outcome, not an error") {
        val section = RetrievalReportGenerator.generate(fourWayRun())

        section shouldContain "A result favouring CodeGraph is a legitimate outcome of this measurement, not an error in it."
        section shouldContain "So is a result favouring bash"
        // The per-question verdict names the winning side in full, bash included.
        section shouldContain "bash (base-system shell only) leads"
    }

    test("neither graph tool is named bare in a four-sided report either") {
        val section = RetrievalReportGenerator.generate(fourWayRun())

        val offending = section.lines()
            .filter { it.trimStart().startsWith("|") }
            .filter { line ->
                Regex("ContextGraph(?! \\(this project\\))").containsMatchIn(line) ||
                    Regex("CodeGraph(?! \\(third-party\\))").containsMatchIn(line)
            }
            .filterNot { it.contains("`codegraph") || it.contains("codegraph_") }

        offending shouldBe emptyList()
    }

    // ------------------------------- the extraction disclosure (AC-15), data-driven

    test("a repo whose index holds files but no declarations is disclosed before the tables") {
        val section = RetrievalReportGenerator.generate(fourWayRun())

        val disclosure = section.substringBefore("### Ingest cost")
        disclosure shouldContain "yields no code declarations at all"
        disclosure shouldContain "`gin`"
        // Its extracted-node counts, so the claim is shown rather than asserted.
        disclosure shouldContain "0 `Function`"
        disclosure shouldContain "847 `Document`"
        // ...and its gold-file coverage, so the row is never a bare retrieval score.
        disclosure shouldContain "10 / 10 gold-cited files"
        disclosure shouldContain "extraction coverage, not retrieval quality"
    }

    test("the affected repo's own table carries the caveat, not just the section above it") {
        val section = RetrievalReportGenerator.generate(fourWayRun())

        val ginSection = section.substringAfter("#### `gin`").substringBefore("### Negative Controls")
        ginSection shouldContain "extraction coverage, not retrieval quality"
        // Above the numbers, not below them: a caveat a reader meets after the figure has already
        // been read is a caveat that arrived too late.
        val caveatAt = ginSection.indexOf("extraction coverage, not retrieval quality")
        val tableAt = ginSection.indexOf("| Metric |")
        (caveatAt in 0 until tableAt) shouldBe true
    }

    test("the same report generated from a run where every repo yields declarations says so") {
        // Slice 08 may land Go support, at which point the disclosure above becomes false. It is
        // driven by the counts in the result document, so it changes with them.
        val section = RetrievalReportGenerator.generate(goSupportedRun())

        section shouldContain "Every repo measured here yielded code declarations"
        section.contains("yields no code declarations at all") shouldBe false
        // The counts are still published -- that is the evidence for the claim.
        section shouldContain "| `gin` | 1208 | 604 `Method`, 412 `Function`, 192 `Class`, 128 `CodeFile` |"
    }

    test("a run that recorded no extraction counts says so rather than reading as zero") {
        val section = RetrievalReportGenerator.generate(threeWayRun())

        section shouldContain "Extracted-declaration counts were not recorded"
        section.contains("yields no code declarations at all") shouldBe false
    }

    // ------------------------- the headline is a pooled mean, not a per-repo verdict

    test("the headline table is qualified as a pooled mean, naming each repo's share of the pool") {
        val section = RetrievalReportGenerator.generate(pooledLeadRun())

        val headline = section.substringAfter("### Headline").substringBefore("### By Category")
        headline shouldContain "pooled mean over 3 question(s) drawn from 2 repo(s)"
        headline shouldContain "It is not a verdict, and it is not a per-repo result."
        // Shares are of the *headline* pool, so the negative control on `beta` is not counted in.
        headline shouldContain "`alpha` 2 of 3 (66.7%)"
        headline shouldContain "`beta` 1 of 3 (33.3%)"
        // And because that is a different slice from the "By Repo" tables -- which do include the
        // negative controls -- the two will print different figures for the same repo. Said out
        // loud, or the document contradicts itself sixty lines apart with no explanation.
        headline shouldContain "the same aggregation as the per-repo tables under \"By Repo\", " +
            "which also include each repo's negative controls"
        section.substringAfter("### By Repo") shouldContain "#### `beta`"
        // Under the table it qualifies, where a headline-only reader cannot miss it.
        val tableAt = headline.indexOf("| MRR |")
        val caveatAt = headline.indexOf("pooled mean over 3 question(s)")
        (tableAt in 0 until caveatAt) shouldBe true
    }

    test("a metric whose pooled leader is beaten on a repo names that repo and the side that leads there") {
        val section = RetrievalReportGenerator.generate(pooledLeadRun())

        // ContextGraph leads recall@5 and MRR in the pool (66.7% and 0.833) while scoring 0.0%
        // and 0.500 on `beta`, where both text-search sides beat it. That is precisely the swing
        // a pooled row hides, so it is named: repo, side, and both figures.
        section shouldContain "| `recall@5` | ContextGraph (this project) | `beta` -- " +
            "bash (base-system shell only), ripgrep (baseline) lead there, 100.0% against 0.0% " +
            "for ContextGraph (this project) |"
        section shouldContain "| `MRR` | ContextGraph (this project) | `beta` -- " +
            "bash (base-system shell only), ripgrep (baseline) lead there, 1.000 against 0.500 " +
            "for ContextGraph (this project) |"
    }

    test("a metric the pooled leader also leads on every repo says so rather than implying a loss") {
        val section = RetrievalReportGenerator.generate(pooledLeadRun())

        // Same fixture, same side, different metric: ContextGraph leads precision@5 on both repos,
        // and the row must not manufacture a caveat where the data does not support one.
        section shouldContain "| `precision@5` | ContextGraph (this project) | _leads on every repo measured_ |"
    }

    test("the pooled-mean caveat's rows never collide with the metric tables' rows") {
        // The two tables sit next to each other and both have a metric in the first cell. Anything
        // filtering rows by that cell -- this file's own `| MRR ` assertion above included -- would
        // silently start reading one table's claims as the other's if they ever rendered alike.
        val section = RetrievalReportGenerator.generate(pooledLeadRun())

        val metricRows = section.lines().filter { it.startsWith("| MRR ") }
        val caveatRows = section.lines().filter { it.startsWith("| `MRR` ") }
        metricRows.shouldNotBeEmpty()
        caveatRows.shouldNotBeEmpty()
        metricRows.intersect(caveatRows.toSet()) shouldBe emptySet()
    }

    test("the pooled-mean caveat is computed, so a single-repo run reports a single-repo pool") {
        // The generator names no repo, side or metric in its source; drive it with an entirely
        // different run and the same code prints an entirely different sentence.
        val section = RetrievalReportGenerator.generate(fourWayRun())

        section shouldContain "pooled mean over 1 question(s) drawn from 1 repo(s)"
        section shouldContain "`gin` 1 of 1 (100.0%)"
        section.contains("`beta`") shouldBe false
    }

    test("the ingest table explains a reused-index row rather than leaving it to read as free") {
        val section = RetrievalReportGenerator.generate(reusedIndexRun())

        section shouldContain "| `gin` | CodeGraph (third-party) | reused existing index |"
        section shouldContain "A row reading _reused existing index_ is one whose index this run found already built"
        section shouldContain "not a claim that the index was free"
        // Only when a row carries the sentinel: a note that fires on every run explains nothing.
        RetrievalReportGenerator.generate(fourWayRun())
            .contains("is one whose index this run found already built") shouldBe false
    }

    // ------------------- the two text-search columns, and why they read alike

    test("a run whose baselines diverge only past k says so, and says where the difference lives") {
        val section = RetrievalReportGenerator.generate(tailDivergenceRun())
        val subsection = baselineSubsection(section)

        // Counted, not asserted: one of the two questions differs, and it differs past rank 5.
        subsection shouldContain "1 of 2 question(s) produce ranked lists that are not equal"
        subsection shouldContain "**every** question's first-5 prefix is identical"
        subsection shouldContain "never returns fewer files than the ripgrep (baseline) side, and at most 1 more"
        // The strongest available conclusion, printed only because the lists support it.
        subsection shouldContain "**All of it lives below the ranks that are scored.**"
        subsection shouldContain "Reciprocal rank -- the one metric here that is *not* capped at `k`"
        // Both causes, never just the tidy one.
        subsection shouldContain "two causes, not one"
        subsection shouldContain "`rg` never opens files that `grep` reads"
        subsection shouldContain "do not mean the same thing by `-w`"
    }

    test("a run whose baselines diverge inside k never claims the difference lives below it") {
        val section = RetrievalReportGenerator.generate(prefixDivergenceRun())
        val subsection = baselineSubsection(section)

        // The question whose top-k order differs is named, and the count is computed.
        subsection shouldContain "1 of 2 have an identical first-5 prefix, and the 1 that do not are `q1`"
        subsection shouldContain "**None of it reaches a scored position.**"
        subsection shouldContain "including on the 1 question(s) whose first-5 order does differ, " +
            "where the entries that differ are not gold-cited files at all"
        // The claim the data does not support must not appear.
        subsection.contains("**All of it lives below the ranks that are scored.**") shouldBe false
        // Reciprocal rank is uncapped, so it is the one place the tail difference shows.
        subsection shouldContain "it differs on 1 of 2 question(s) -- `q1` -- where the first " +
            "gold-cited file sits at rank 98 or deeper"
        subsection shouldContain "not one of them survives rounding to the precision the tables print"
    }

    test("a prefix difference that does involve a gold file is never described as gold-free") {
        // Two different gold files, one in each side's prefix: precision@k and recall@k come out
        // equal, so the metrics cannot tell -- but "the entries that differ are not gold-cited
        // files" would be false, and is checked against the expected set rather than inferred
        // from the metrics agreeing.
        val subsection = baselineSubsection(RetrievalReportGenerator.generate(goldSwapRun()))

        subsection shouldContain "**None of it reaches a scored position.**"
        subsection shouldContain "where the gold-cited files fall inside the same scored ranks on both sides"
        subsection.contains("are not gold-cited files at all") shouldBe false
    }

    test("a run whose baselines return identical lists says that, and explains nothing further") {
        // Every question's bash and ripgrep results are the same object here, so there is no
        // divergence to attribute -- and a causes list printed anyway would be explaining a
        // difference the run does not have.
        val subsection = baselineSubsection(RetrievalReportGenerator.generate(pooledLeadRun()))

        // 7 groupings (headline, 3 categories, 2 repos, negative control) x 3 metrics at k=5.
        subsection shouldContain "All 21 aggregate figure(s) this document computes for the two"
        subsection shouldContain "**On this run they also returned the same thing.** All 4 question(s)"
        subsection.contains("two causes, not one") shouldBe false
        subsection.contains("**None of it reaches a scored position.**") shouldBe false
    }

    test("a run whose baselines score differently says so instead of claiming agreement") {
        // `gin-q8`'s two text-search sides return different files inside k and score differently
        // for it. The section must report that as the measurement, not reach for the fairness
        // conclusion that only an agreeing run earns.
        val subsection = baselineSubsection(RetrievalReportGenerator.generate(fourWayRun()))

        subsection shouldContain "**Some of it does reach a scored position.**"
        subsection shouldContain "1 of 2 question(s) score differently on at least one precision@k " +
            "or recall@k: `gin-q8`"
        subsection shouldContain "Where the two columns differ below, that difference is the measurement"
        subsection.contains("that is the finding, not a footnote to one") shouldBe false
    }

    test("the agreement is reported as a finding, and the fairness invariant it discharges") {
        val subsection = baselineSubsection(RetrievalReportGenerator.generate(tailDivergenceRun()))

        subsection shouldContain "**And that is the finding, not a footnote to one.**"
        subsection shouldContain "At k=5 on this corpus"
        subsection shouldContain "was not starved to produce them"
        subsection shouldContain "Baseline'ı zayıflatarak kazanılan bir sayı, kazanılmamış bir sayıdır."
        // Independence is stated as a mechanism a reader can check, not as reassurance.
        subsection shouldContain "**The two are computed independently.**"
        subsection shouldContain "`BashBaselineRunner`"
        subsection shouldContain "`RipgrepBaselineRunner`"
    }

    test("a run with no bash side has no such subsection at all") {
        // Nothing to reconcile when only one text-search column exists; a section explaining an
        // agreement between a column and an absent one would be explaining nothing.
        val section = RetrievalReportGenerator.generate(threeWayRun())
        section.contains("### The two text-search columns") shouldBe false
    }

    // ---------------- how long each side's ranked list is, and what that alone allows

    test("the ranked-list shapes are published per side, every figure read off the run's own lists") {
        val subsection = listLengthSubsection(RetrievalReportGenerator.generate(listLengthRun()))

        subsection shouldContain "| Side | Median files returned | Longest | Returned 5 files or fewer | " +
            "Gold-cited file at ranks 6-10 |"
        subsection shouldContain "| ContextGraph (this project) | 8 | 12 | 0 of 3 | 1 of 3 |"
        subsection shouldContain "| CodeGraph (third-party) | 3 | 4 | 3 of 3 | 0 of 3 |"
        subsection shouldContain "| bash (base-system shell only) | 14 | 20 | 1 of 3 | 1 of 3 |"
        // The shortest side is identified by comparing medians, not by being named in the source.
        subsection shouldContain "**The shortest lists are CodeGraph (third-party)'s** -- a median of 3 " +
            "against bash (base-system shell only)'s 14"
        subsection shouldContain "caps precision@10 at 30.0% before retrieval quality is considered at all"
    }

    test("a side whose recall does not move between the two k values is told apart from a truncated one") {
        // The two look identical in the table and mean opposite things: "its list ran out" versus
        // "there was nothing further down to find". The ranked lists are asked directly, so only
        // the side that genuinely has no gold file in the band is named.
        val subsection = listLengthSubsection(RetrievalReportGenerator.generate(listLengthRun()))

        subsection shouldContain "recall@5 and recall@10 read the same figure for CodeGraph (third-party) (50.0%)"
        subsection shouldContain "not one question in this pool places a gold-cited file at ranks 6-10 for that side"
        subsection shouldContain "precision@10 is precision@5 scaled by exactly 5/10"
        // ...and the sides that do put gold files in that band are named as the ones whose recall moves.
        subsection shouldContain "ContextGraph (this project) on 1 of 3, bash (base-system shell only) on 1 of 3"
    }

    test("the ceiling half ships with the length half, and never after the tables it qualifies") {
        // The disclosure alone reads as an excuse manufactured on some side's behalf; the ceiling
        // alone answers a question nobody was asked. Neither is honest without the other, so the
        // one thing worth pinning is that they cannot come apart.
        val section = RetrievalReportGenerator.generate(listLengthRun())
        val subsection = listLengthSubsection(section)

        val lengths = subsection.indexOf("| Side | Median files returned |")
        val ceiling = subsection.indexOf("| Side | Highest precision@10 its lists allowed |")
        (lengths >= 0) shouldBe true
        (ceiling > lengths) shouldBe true
        (section.indexOf("### How long each side's ranked list is") < section.indexOf("### Headline")) shouldBe true
    }

    test("each side's own ceiling is published beside what it actually scored") {
        val subsection = listLengthSubsection(RetrievalReportGenerator.generate(listLengthRun()))

        subsection shouldContain "| ContextGraph (this project) | 13.3% | 10.0% | 75.0% |"
        subsection shouldContain "| CodeGraph (third-party) | 13.3% | 6.7% | 50.0% |"
        subsection shouldContain "| bash (base-system shell only) | 13.3% | 6.7% | 50.0% |"
        // Spans are computed from the values as printed, so subtracting the figures above gives
        // the same answer the sentence does.
        subsection shouldContain "The ceilings span 0.0 percentage points, from 13.3% to 13.3%; " +
            "the measured figures span 3.3 percentage points, from 6.7% to 10.0%"
        subsection shouldContain "**Scoring each side against its own ceiling, rather than against " +
            "a flat k=10, leaves them in the same order.**"
        subsection shouldContain "50.0% of its own ceiling, the lowest share of any side here"
        subsection shouldContain "explains part of the spread and not the result"
    }

    test("a run where normalising by the ceiling reorders the sides prints the reordering instead") {
        // The conclusion is gated on the data, not on which side it flatters: here this project's
        // side leads the measured row and is beaten once each side is scored against what its own
        // lists allowed, and the same generator says so.
        val subsection = listLengthSubsection(RetrievalReportGenerator.generate(ceilingReorderRun()))

        subsection shouldContain "**Scoring each side against its own ceiling, rather than against " +
            "a flat k=10, reorders them**: CodeGraph (third-party) 100.0%, " +
            "ContextGraph (this project) 75.0%, ripgrep (baseline) 25.0%."
        subsection shouldContain "neither is presented as the real one"
        subsection.contains("leaves them in the same order") shouldBe false
        subsection.contains("explains part of the spread and not the result") shouldBe false
    }

    test("neither graph tool is named bare anywhere in the length subsection either") {
        val offending = listOf(listLengthRun(), ceilingReorderRun())
            .map { RetrievalReportGenerator.generate(it) }
            .flatMap { listLengthSubsection(it).lines() }
            .filter { it.trimStart().startsWith("|") }
            .filter { line ->
                Regex("ContextGraph(?! \\(this project\\))").containsMatchIn(line) ||
                    Regex("CodeGraph(?! \\(third-party\\))").containsMatchIn(line)
            }

        offending shouldBe emptyList()
    }

    // ------------------------------------------- the "Skipped" section, either way

    test("a run that skipped nothing prints the Skipped section anyway, saying so") {
        val section = RetrievalReportGenerator.generate(pooledLeadRun())

        section shouldContain "### Skipped"
        section shouldContain "**Nothing was skipped in this run.**"
        section shouldContain "Every side measured every question it was given: across 2 repo(s) and 4 question(s)"
        // An empty list must not render as an empty table with a dangling header.
        section.contains("| Repo | Reason |") shouldBe false
    }

    test("a run that skipped something still prints the table, and the reason with it") {
        val section = RetrievalReportGenerator.generate(fourWayRun())

        section shouldContain "### Skipped"
        section shouldContain "| Repo | Reason |"
        section shouldContain "| calcom | WITHOUT working copy not found |"
        section.contains("**Nothing was skipped in this run.**") shouldBe false
    }

    test("a column that measured fewer questions than its table's n is named under Skipped") {
        // The skip list is about repos; a side can be short on one question of a repo that was
        // otherwise measured, and that shortfall is computed from the counts rather than trusted
        // to have made it into the list.
        val section = RetrievalReportGenerator.generate(threeWayRun())

        section shouldContain "Columns whose denominator is short of their table's `n`"
        section shouldContain "`keycloak` -- CodeGraph (third-party) measured 1 of 2"
    }

    test("regenerating from the same result JSON is byte for byte identical") {
        // AC-11: the published file is generated, never hand-edited, and the way that claim is
        // kept honest is that anyone can regenerate it and diff. Round-tripped through JSON on
        // both sides, because that is the path a regeneration actually takes -- reading the
        // committed result document, not re-using an in-memory object.
        val json = fourWayRun().toJson()

        val once = RetrievalReportGenerator.generate(RetrievalRun.fromJson(json))
        val twice = RetrievalReportGenerator.generate(RetrievalRun.fromJson(json))

        once shouldBe twice
        once.toByteArray(Charsets.UTF_8).toList() shouldBe twice.toByteArray(Charsets.UTF_8).toList()
    }

    test("upserting a regenerated section into the file it already produced changes nothing") {
        // The merge step is part of "generated, not hand-edited" too: a second run over an
        // unchanged result must leave BENCHMARKS.md byte-identical, or every regeneration would
        // show up as a diff and the no-hand-edits claim would be unverifiable in practice.
        val section = RetrievalReportGenerator.generate(RetrievalRun.fromJson(fourWayRun().toJson()))
        val file = RetrievalReportGenerator.upsert("# BENCHMARKS\n\nagent axis content\n", section)

        RetrievalReportGenerator.upsert(file, section) shouldBe file
    }
})

/**
 * Just the subsection reconciling the two text-search columns, so an assertion cannot drift into a
 * neighbour. Bounded by the *next* `###` heading rather than by a named one: a section added
 * between this one and the headline would otherwise be silently swallowed into every assertion
 * below, including the `shouldBe false` ones, which fail open.
 */
private fun baselineSubsection(section: String): String =
    section.substringAfter("### The two text-search columns").substringBefore("\n### ")

/** Just the subsection about ranked-list length and the ceiling it imposes, bounded the same way. */
private fun listLengthSubsection(section: String): String =
    section.substringAfter("### How long each side's ranked list is").substringBefore("\n### ")

/** The `k` values the length fixtures use: two of them, so there is a band between them to inspect. */
private val LENGTH_KS = listOf(5, 10)

/**
 * One side's measurement, scored from its own ranked list by the **real** metrics rather than by
 * hand. A fixture whose precision@k was typed in could state a figure its own list contradicts, and
 * the section under test reads both -- the lists for the shapes, the aggregates for the scores -- so
 * that contradiction would be invisible in exactly the place it mattered.
 */
private fun scoredSide(ranked: List<String>, expected: List<String>): SideResult {
    val set = expected.toSet()
    return SideResult(
        rankedFiles = ranked,
        precisionAtK = LENGTH_KS.associateWith { RetrievalMetrics.precisionAtK(ranked, set, it) },
        recallAtK = LENGTH_KS.associateWith { RetrievalMetrics.recallAtK(ranked, set, it) },
        reciprocalRank = RetrievalMetrics.reciprocalRank(ranked, set)
    )
}

/** [count] distinct non-gold paths, so a list's length is stated by the call rather than typed out. */
private fun filler(tag: String, count: Int): List<String> = (1..count).map { "$tag/Filler$it.kt" }

/**
 * Four sides returning ranked lists of deliberately different lengths, with one short-list side that
 * places no gold file between the two `k` values and three that do.
 *
 * No side is arranged to win: the short-list side is beaten on precision@10 *and* reaches the
 * smallest share of its own ceiling, which is the shape the real measurement has and the one the
 * section's conclusion is gated on. [ceilingReorderRun] is the same generator driven to the opposite
 * conclusion, where this project's side is the one that loses under normalisation.
 */
private fun listLengthRun(): RetrievalRun {
    fun question(
        id: String,
        expected: List<String>,
        contextGraph: List<String>,
        codeGraph: List<String>,
        textSearch: List<String>
    ) = RetrievalRunResult(
        questionId = id,
        repoId = "repo",
        category = QuestionCategory.GRAPH_HEAVY,
        expectedFiles = expected,
        ripgrepQueryTokens = listOf("resolve"),
        contextGraph = scoredSide(contextGraph, expected),
        ripgrep = scoredSide(textSearch, expected),
        codeGraph = scoredSide(codeGraph, expected),
        bash = scoredSide(textSearch, expected)
    )

    val results = listOf(
        question(
            "q1", listOf("g1", "g2"),
            contextGraph = listOf("g1") + filler("cgA", 4) + listOf("g2") + filler("cgB", 2),
            codeGraph = listOf("g1") + filler("cdA", 1),
            textSearch = filler("tA", 7) + listOf("g2") + filler("tB", 6)
        ),
        question(
            "q2", listOf("h1"),
            contextGraph = filler("cgC", 2) + listOf("h1") + filler("cgD", 9),
            codeGraph = filler("cdB", 3),
            textSearch = filler("tC", 1) + listOf("h1") + filler("tD", 18)
        ),
        question(
            "q3", listOf("k1"),
            contextGraph = filler("cgE", 6),
            codeGraph = listOf("k1") + filler("cdC", 3),
            textSearch = filler("tE", 4)
        )
    )
    return RetrievalRun(
        runId = "retrieval-list-length-fixture",
        generatedAt = Instant.parse("2026-08-24T00:00:00Z"),
        kValues = LENGTH_KS,
        results = results,
        summary = RetrievalStats.summarize(results, LENGTH_KS)
    )
}

/**
 * A run where scoring each side against its own ceiling **changes** the order the measured row puts
 * them in -- and changes it against this project, which leads the measured precision@10 and comes
 * second once list length is normalised out. The conclusion the section prints is gated on the data
 * rather than on who it favours, and this is the fixture that proves it is.
 */
private fun ceilingReorderRun(): RetrievalRun {
    fun question(
        id: String,
        expected: List<String>,
        contextGraph: List<String>,
        codeGraph: List<String>,
        ripgrep: List<String>
    ) = RetrievalRunResult(
        questionId = id,
        repoId = "repo",
        category = QuestionCategory.GRAPH_HEAVY,
        expectedFiles = expected,
        ripgrepQueryTokens = listOf("resolve"),
        contextGraph = scoredSide(contextGraph, expected),
        ripgrep = scoredSide(ripgrep, expected),
        codeGraph = scoredSide(codeGraph, expected)
    )

    val results = listOf(
        question(
            "q1", listOf("g1", "g2"),
            contextGraph = listOf("g1", "g2") + filler("rA", 10),
            codeGraph = listOf("g1"),
            ripgrep = listOf("g1") + filler("rB", 9)
        ),
        question(
            "q2", listOf("h1", "h2"),
            contextGraph = listOf("h1") + filler("rC", 11),
            codeGraph = listOf("h1"),
            ripgrep = filler("rD", 5)
        )
    )
    return RetrievalRun(
        runId = "retrieval-ceiling-reorder-fixture",
        generatedAt = Instant.parse("2026-08-24T00:00:00Z"),
        kValues = LENGTH_KS,
        results = results,
        summary = RetrievalStats.summarize(results, LENGTH_KS)
    )
}

/**
 * The two graph sides for a fixture that is not about either of them: identical, mid-range, and
 * shared by reference so they cannot drift apart.
 *
 * **A test fixture is precisely where a thumb on the scale would hide if there were one**, and a
 * reader scanning a diff cannot tell filler somebody typed without thinking from the shape an
 * author wanted to see. The fixtures below that reached for `1.0` on this project's side and `0.0`
 * on the third party's were the former, and it changed no published number -- but the burden is on
 * this file to make that distinction unnecessary rather than to ask for the benefit of the doubt.
 *
 * So: where a fixture needs the graph columns populated without making any claim about them, it
 * uses this for both. Neither a perfect score nor a zero, so nothing here can be read as a result;
 * where a fixture genuinely *is* about an asymmetry between two sides, it says so in a comment and
 * spells the values out at the point of use.
 */
private val NEUTRAL_GRAPH_SIDE =
    SideResult(listOf("placeholder/One.kt", "placeholder/Two.kt"), mapOf(5 to 0.1), mapOf(5 to 0.5), 0.5)

/**
 * A run whose two text-search sides return different ranked lists that agree on everything the
 * metrics look at: same first-`k` prefix, same precision@k/recall@k, same reciprocal rank, one
 * extra file on the bash side past the scored ranks. The shape the real four-way measurement has,
 * reduced to the two questions it takes to exercise it.
 */
private fun tailDivergenceRun(): RetrievalRun = baselineRun(
    listOf(
        textPair(
            bashFiles = listOf("a", "b", "c", "d", "e", "tail-only"),
            ripgrepFiles = listOf("a", "b", "c", "d", "e")
        ),
        textPair(bashFiles = listOf("a", "b"), ripgrepFiles = listOf("a", "b"))
    )
)

/**
 * A run whose two sides differ *inside* the scored prefix -- on a non-gold entry, so no
 * precision@k or recall@k moves -- and whose first gold hit sits at a different depth in each
 * list, so the uncapped reciprocal rank differs while still rounding to the same printed score.
 * The case where "the difference lives below k" is false and must not be printed.
 */
private fun prefixDivergenceRun(): RetrievalRun = baselineRun(
    listOf(
        textPair(
            bashFiles = listOf("a", "non-gold", "b", "c", "d", "e"),
            ripgrepFiles = listOf("a", "b", "c", "d", "e"),
            bashReciprocalRank = 0.0100,
            ripgrepReciprocalRank = 0.0102
        ),
        textPair(bashFiles = listOf("a", "b"), ripgrepFiles = listOf("a", "b"))
    )
)

/**
 * A run where each side's scored prefix contains a *different* gold-cited file. precision@k and
 * recall@k come out equal, so no metric can see it -- but the prefixes do differ over gold, and
 * the section must not say otherwise.
 */
private fun goldSwapRun(): RetrievalRun = baselineRun(
    listOf(
        textPair(
            bashFiles = listOf("g1", "b", "c", "d", "e"),
            ripgrepFiles = listOf("g2", "b", "c", "d", "e")
        ),
        textPair(bashFiles = listOf("b", "c"), ripgrepFiles = listOf("b", "c"))
    ),
    expectedFiles = listOf("g1", "g2")
)

/** One question's two text-search sides, scored identically at `k` unless told otherwise. */
private fun textPair(
    bashFiles: List<String>,
    ripgrepFiles: List<String>,
    bashReciprocalRank: Double = 0.5,
    ripgrepReciprocalRank: Double = 0.5
): Pair<SideResult, SideResult> =
    SideResult(bashFiles, mapOf(5 to 0.2), mapOf(5 to 1.0), bashReciprocalRank) to
        SideResult(ripgrepFiles, mapOf(5 to 0.2), mapOf(5 to 1.0), ripgrepReciprocalRank)

/**
 * A single-repo run built from [pairs], one question each, for exercising the baseline subsection.
 *
 * The two graph sides are irrelevant to every assertion these fixtures carry -- that subsection
 * reads only the two text-search columns -- so both get [NEUTRAL_GRAPH_SIDE] and neither makes a
 * claim. They used to read `1.0` for this project and `0.0` for the third party, which measured
 * nothing and looked like everything.
 */
private fun baselineRun(
    pairs: List<Pair<SideResult, SideResult>>,
    expectedFiles: List<String> = listOf("a")
): RetrievalRun {
    val results = pairs.mapIndexed { index, (bash, ripgrep) ->
        RetrievalRunResult(
            questionId = "q${index + 1}",
            repoId = "repo",
            category = QuestionCategory.GRAPH_HEAVY,
            expectedFiles = expectedFiles,
            ripgrepQueryTokens = listOf("resolve"),
            contextGraph = NEUTRAL_GRAPH_SIDE,
            ripgrep = ripgrep,
            codeGraph = NEUTRAL_GRAPH_SIDE,
            bash = bash
        )
    }
    return RetrievalRun(
        runId = "retrieval-baseline-fixture",
        generatedAt = Instant.parse("2026-08-24T00:00:00Z"),
        kValues = listOf(5),
        results = results,
        summary = RetrievalStats.summarize(results, listOf(5))
    )
}

/** gin's real shape before Go support: files and prose indexed, not one declaration parsed. */
private val NO_DECLARATIONS = mapOf("Document" to 847, "Concept" to 206, "CodeFile" to 128)

/** The same repo once a grammar exists for its language. */
private val WITH_DECLARATIONS = mapOf("CodeFile" to 128, "Function" to 412, "Method" to 604, "Class" to 192)

/**
 * The CodeGraph side here is [NEUTRAL_GRAPH_SIDE] and says nothing: no assertion in this file reads
 * it, and the fixture it belongs to is about an *extraction* gap on this project's own side (below)
 * plus a plain-`grep` win on the negative control. It previously returned a non-gold file and
 * scored zero on everything, which is a loss no test was measuring.
 */
private fun fourSidedResult(
    questionId: String,
    repoId: String,
    category: QuestionCategory,
    contextGraph: SideResult?,
    bash: SideResult?,
    ripgrep: SideResult = SideResult(listOf("$repoId/A.go"), mapOf(5 to 0.2), mapOf(5 to 1.0), 1.0)
) = RetrievalRunResult(
    questionId = questionId,
    repoId = repoId,
    category = category,
    expectedFiles = listOf("$repoId/A.go"),
    ripgrepQueryTokens = listOf("ServeHTTP"),
    contextGraph = contextGraph,
    ripgrep = ripgrep,
    codeGraph = NEUTRAL_GRAPH_SIDE,
    bash = bash
)

/**
 * A run with all four sides measured, and one repo whose indexer parsed none of its language.
 *
 * This project's own side scores zero on both questions, deliberately and with a comment because it
 * is an encoded loss: that is the *point* of the fixture. It is the shape AC-15 exists for -- an
 * index holding every gold-cited file and not one declaration -- and the report must print it as an
 * extractor's floor rather than as a retrieval verdict. The bash side outright beats every other
 * side on the negative control, which is the second thing the fixture is for.
 */
private fun fourWayRun(ginExtraction: Map<String, Int>? = NO_DECLARATIONS): RetrievalRun {
    val found = SideResult(listOf("gin/A.go"), mapOf(5 to 0.2), mapOf(5 to 1.0), 1.0)
    val foundNothing = SideResult(emptyList(), mapOf(5 to 0.0), mapOf(5 to 0.0), 0.0)
    val results = listOf(
        fourSidedResult("gin-q1", "gin", QuestionCategory.GRAPH_HEAVY, contextGraph = foundNothing, bash = found),
        // The negative control the fourth side exists for: plain `grep` outright beats every
        // other side, including the third-party one, and the verdict column says so by name.
        fourSidedResult(
            "gin-q8", "gin", QuestionCategory.NEGATIVE_CONTROL,
            contextGraph = foundNothing,
            bash = found,
            ripgrep = SideResult(listOf("gin/B.go", "gin/A.go"), mapOf(5 to 0.1), mapOf(5 to 0.5), 0.5)
        )
    )
    return RetrievalRun(
        runId = "retrieval-four-way-fixture",
        generatedAt = Instant.parse("2026-08-24T00:00:00Z"),
        kValues = listOf(5),
        results = results,
        skippedRepos = listOf(SkippedRepo("calcom", "WITHOUT working copy not found")),
        summary = RetrievalStats.summarize(results, listOf(5)),
        goldFileCoverage = listOf(
            GoldFileCoverage("gin", RetrievalSide.CONTEXT_GRAPH, 10, 10, CoverageBasis.INDEX_QUERY, ginExtraction),
            GoldFileCoverage("gin", RetrievalSide.CODE_GRAPH, 10, 8, CoverageBasis.INDEX_QUERY),
            GoldFileCoverage("gin", RetrievalSide.BASH, 10, 10, CoverageBasis.READS_WORKING_TREE),
            GoldFileCoverage("gin", RetrievalSide.RIPGREP, 10, 10, CoverageBasis.READS_WORKING_TREE)
        ),
        ingestCosts = listOf(
            ToolIngestCost("gin", GraphTool.CONTEXTGRAPH, durationMillis = 12_000, indexSizeBytes = 4_000_000)
        )
    )
}

/** The same run as [fourWayRun], measured after a grammar for the repo's language landed. */
private fun goSupportedRun(): RetrievalRun = fourWayRun(ginExtraction = WITH_DECLARATIONS)

/** [fourWayRun] whose CodeGraph index was found already built, so its cost is a sentinel. */
private fun reusedIndexRun(): RetrievalRun = fourWayRun().let { run ->
    run.copy(
        ingestCosts = run.ingestCosts + ToolIngestCost(
            "gin", GraphTool.CODEGRAPH, durationMillis = 0, indexSizeBytes = 7_905_509
        )
    )
}

/**
 * Two repos with unequal shares of the headline pool, where one side leads the pooled row on
 * every metric but is beaten on one repo on some of them -- the shape a pooled mean hides, and
 * the reason the caveat under the headline table exists.
 *
 * `alpha` contributes 2 of the 3 headline questions and `beta` 1, so `beta`'s column moving is
 * worth a third of every pooled row on its own. On `beta`, ContextGraph scores 0.0% recall@5 and
 * 0.500 MRR against both text-search sides' 100.0% and 1.000 -- yet still leads both pooled rows
 * on `alpha`'s strength. It leads precision@5 on both repos, so that row has nothing to disclose:
 * the same generator must print the difference and the absence of one.
 *
 * `beta-q9` is a negative control, present so the shares are demonstrably computed over the
 * headline pool rather than over every question the run scored.
 *
 * The asymmetry between this project's side and the two text-search sides is therefore the fixture's
 * whole subject and is spelled out above. The CodeGraph side is *not* part of that subject: it takes
 * [NEUTRAL_GRAPH_SIDE], which is enough to keep it out of every pooled and per-repo lead this file
 * asserts on without encoding a loss the test never measures.
 */
private fun pooledLeadRun(): RetrievalRun {
    fun result(
        questionId: String,
        repoId: String,
        category: QuestionCategory,
        contextGraph: SideResult,
        textSearch: SideResult
    ) = RetrievalRunResult(
        questionId = questionId,
        repoId = repoId,
        category = category,
        expectedFiles = listOf("$repoId/A.kt"),
        ripgrepQueryTokens = listOf("resolve"),
        contextGraph = contextGraph,
        ripgrep = textSearch,
        codeGraph = NEUTRAL_GRAPH_SIDE,
        bash = textSearch
    )

    val graphFound = SideResult(listOf("alpha/A.kt"), mapOf(5 to 0.2), mapOf(5 to 1.0), 1.0)
    val textFoundNothing = SideResult(emptyList(), mapOf(5 to 0.0), mapOf(5 to 0.0), 0.0)
    val graphPrecise = SideResult(listOf("beta/B.kt"), mapOf(5 to 0.2), mapOf(5 to 0.0), 0.5)
    val textFound = SideResult(listOf("beta/A.kt"), mapOf(5 to 0.1), mapOf(5 to 1.0), 1.0)

    val results = listOf(
        result("alpha-q1", "alpha", QuestionCategory.GRAPH_HEAVY, graphFound, textFoundNothing),
        result("alpha-q2", "alpha", QuestionCategory.NEUTRAL, graphFound, textFoundNothing),
        result("beta-q1", "beta", QuestionCategory.GRAPH_HEAVY, graphPrecise, textFound),
        result("beta-q9", "beta", QuestionCategory.NEGATIVE_CONTROL, graphPrecise, textFound)
    )
    return RetrievalRun(
        runId = "retrieval-pooled-lead-fixture",
        generatedAt = Instant.parse("2026-08-24T00:00:00Z"),
        kValues = listOf(5),
        results = results,
        summary = RetrievalStats.summarize(results, listOf(5))
    )
}

/**
 * A run with all three sides measured, plus coverage and ingest cost, as a real three-way run has.
 *
 * `kc-q1` scores all three sides identically -- the subject there is that a third column exists and
 * carries its qualifier, not that anybody wins. `kc-q2` is the one encoded asymmetry, and it is
 * encoded on *this project's* side: a negative control is by definition a question where plain text
 * search is expected to beat a graph, so the graph finding nothing there is the category behaving as
 * designed rather than a fixture leaning either way.
 */
private fun threeWayRun(): RetrievalRun {
    val results = listOf(
        RetrievalRunResult(
            questionId = "kc-q1",
            repoId = "keycloak",
            category = QuestionCategory.GRAPH_HEAVY,
            expectedFiles = listOf("services/Auth.java"),
            ripgrepQueryTokens = listOf("Auth"),
            contextGraph = SideResult(listOf("services/Auth.java"), mapOf(5 to 0.2), mapOf(5 to 1.0), 1.0),
            ripgrep = SideResult(listOf("services/Auth.java"), mapOf(5 to 0.2), mapOf(5 to 1.0), 1.0),
            codeGraph = SideResult(listOf("services/Auth.java"), mapOf(5 to 0.2), mapOf(5 to 1.0), 1.0)
        ),
        RetrievalRunResult(
            questionId = "kc-q2",
            repoId = "keycloak",
            category = QuestionCategory.NEGATIVE_CONTROL,
            expectedFiles = listOf("services/Auth.java"),
            ripgrepQueryTokens = listOf("404"),
            contextGraph = SideResult(emptyList(), mapOf(5 to 0.0), mapOf(5 to 0.0), 0.0),
            ripgrep = SideResult(listOf("services/Auth.java"), mapOf(5 to 0.2), mapOf(5 to 1.0), 1.0),
            // Unmeasured on this question -- excluded from the mean, listed under Skipped.
            codeGraph = null
        )
    )
    return RetrievalRun(
        runId = "retrieval-three-way-fixture",
        generatedAt = Instant.parse("2026-08-22T00:00:00Z"),
        kValues = listOf(5),
        results = results,
        skippedRepos = listOf(
            SkippedRepo("keycloak", "${RetrievalSide.CODE_GRAPH.label} side unmeasured for question kc-q2: timed out")
        ),
        summary = RetrievalStats.summarize(results, listOf(5)),
        goldFileCoverage = listOf(
            GoldFileCoverage("keycloak", RetrievalSide.CONTEXT_GRAPH, 22, 16, CoverageBasis.INDEX_QUERY),
            GoldFileCoverage("keycloak", RetrievalSide.CODE_GRAPH, 22, null, CoverageBasis.NOT_DETERMINABLE),
            GoldFileCoverage("keycloak", RetrievalSide.RIPGREP, 22, 22, CoverageBasis.READS_WORKING_TREE)
        ),
        ingestCosts = listOf(
            ToolIngestCost("keycloak", GraphTool.CONTEXTGRAPH, durationMillis = 3_000_000, indexSizeBytes = 1_100_000_000),
            ToolIngestCost("keycloak", GraphTool.CODEGRAPH, absentReason = "${RetrievalSide.CODE_GRAPH.label} binary did not resolve")
        )
    )
}
