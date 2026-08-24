package broken

// Deliberately unparseable: the parameter list is never closed. Tree-sitter recovers,
// so the file still yields a file node and a diagnostic rather than throwing.
func Oops( {
	return
}

func Fine() int {
	return 1
}

// Deliberately malformed: `1` is not a valid receiver -- the receiver clause carries no
// name and no reducible type, an error tree-sitter recovers from without losing the
// method's own name. This is the fixture for H3: a method_declaration whose receiver
// cannot be reduced to a type must still be emitted, scoped to the file, rather than
// vanish with no node, no fqn, and no attribution to this file at all.
func (1) BrokenReceiver() {
	return
}
