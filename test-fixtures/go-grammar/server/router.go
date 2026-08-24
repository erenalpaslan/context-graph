package server

// RouterGroup is declared here, but Engine (in engine.go) embeds it -- so
// Engine.rebuild404Handlers can call combineHandlers without declaring it.
type RouterGroup struct {
	Handlers HandlersChain
	basePath string
	engine   *Engine
}

type Context struct {
	handlers HandlersChain
	index    int8
}

// A named type whose underlying type is neither a struct nor an interface.
type HandlersChain []HandlerFunc

func (group *RouterGroup) Use(middleware ...HandlerFunc) {
	group.Handlers = append(group.Handlers, middleware...)
}

func (group *RouterGroup) combineHandlers(handlers HandlersChain) HandlersChain {
	merged := make(HandlersChain, 0)
	copy(merged, group.Handlers)
	return merged
}

func (c *Context) Next() {
	c.index++
}

func (c *Context) Use(handler HandlerFunc) {
	c.handlers = append(c.handlers, handler)
}
