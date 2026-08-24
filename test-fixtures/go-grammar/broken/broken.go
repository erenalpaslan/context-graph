package broken

// Deliberately unparseable: the parameter list is never closed. Tree-sitter recovers,
// so the file still yields a file node and a diagnostic rather than throwing.
func Oops( {
	return
}

func Fine() int {
	return 1
}
