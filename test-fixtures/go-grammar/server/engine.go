package server

import (
	"net/http"

	"github.com/example/app/binding"
)

const EnvMode = "APP_MODE"

const (
	DebugMode   = "debug"
	ReleaseMode = "release"
)

var default404Body = []byte("404 page not found")

// Engine embeds RouterGroup, so RouterGroup's methods are promoted onto Engine.
type Engine struct {
	RouterGroup
	Handlers []HandlerFunc
	binder   binding.Binding
	pool     *Context
}

type HandlerFunc func(*Context)

func New() *Engine {
	engine := &Engine{}
	engine.RouterGroup.engine = engine
	return engine
}

func (engine *Engine) ServeHTTP(w http.ResponseWriter, req *http.Request) {
	var c *Context
	engine.handleHTTPRequest(c)
}

func (engine *Engine) handleHTTPRequest(c *Context) {
	c.handlers = engine.Handlers
	c.Next()
	serveError(c, default404Body)
}

// Use is one of three methods named Use in this fixture -- the other two are on
// RouterGroup and Context, both in router.go.
func (engine *Engine) Use(middleware ...HandlerFunc) {
	engine.RouterGroup.Use(middleware...)
	engine.rebuild404Handlers()
}

func (engine *Engine) rebuild404Handlers() {
	engine.Handlers = engine.combineHandlers(engine.Handlers)
}

func serveError(c *Context, body []byte) {
	c.Next()
}
