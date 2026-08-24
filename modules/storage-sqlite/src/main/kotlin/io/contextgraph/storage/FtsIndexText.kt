package io.contextgraph.storage

import io.contextgraph.core.IdentifierSplitter

/**
 * What actually goes into `nodes_fts`'s indexed text, for a node with a given label.
 *
 * A tiny object rather than a private function because two callers must agree exactly:
 * `SqliteStorageAdapter`'s write path, and the migration that rebuilds an already-built index
 * in place. If those two ever disagreed, a repaired database would be searchable differently
 * from a freshly indexed one -- the kind of divergence nothing would notice until a query
 * quietly stopped finding something. Sharing the rule makes them the same write by
 * construction rather than by two implementations that happen to agree today.
 */
internal object FtsIndexText {

    /**
     * `nodes_fts` is a search index, not a source of truth -- the real label lives in `nodes`,
     * untouched. FTS5's default tokenizer only splits on non-alphanumeric characters, so a
     * compound identifier with no separator (camelCase, acronym runs, digit-adjacent words --
     * e.g. "RungDistribution") is indexed as a single token and cannot be found by any of its
     * component words. Appending the split components alongside the original lets a search for
     * "rung" or "distribution" retrieve "RungDistribution" without changing what is displayed
     * or stored as truth.
     */
    fun labelFor(label: String): String {
        val words = IdentifierSplitter.split(label)
        return if (words.size > 1) (listOf(label) + words).joinToString(" ") else label
    }
}
