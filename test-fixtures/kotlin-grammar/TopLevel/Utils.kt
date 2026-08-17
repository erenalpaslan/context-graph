package com.example.util

fun square(n: Int): Int = n * n

val defaultRetries: Int = 3

typealias Handler = (String) -> Unit

// Regression coverage for a byte/char-offset bug in tree-sitter symbol extraction: a
// non-ASCII comment like this one — with an em-dash and an arrow (→) — used to throw
// StringIndexOutOfBoundsException on every declaration that followed it, once the
// accumulated byte/char drift pushed a later node's tree-sitter byte offset past the
// file's actual character length. préparer below is itself a non-ASCII (accented)
// identifier, so this also proves declaration names round-trip byte-correctly, not just
// comments.
fun préparer(a: Int, b: Int): Int = a + b

val café: String = "espresso"
