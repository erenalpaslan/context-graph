package server

// Abort is declared in a different file from the Context type it extends -- legal Go,
// and common in large packages. Its identity still leads with the receiver type; only
// the containment edge falls back to the file, because this file has no node for Context.
func (c *Context) Abort() {
	c.index = 63
	c.Next()
}
