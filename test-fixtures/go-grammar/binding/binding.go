package binding

import "net/http"

// Binding is the interface every concrete binder implements. A change to Bind's
// signature is what impact analysis has to be able to trace outwards from.
type Binding interface {
	Name() string
	Bind(*http.Request, any) error
}

// Validating embeds Binding, the interface equivalent of struct embedding.
type Validating interface {
	Binding
	Validate(any) error
}

type BindError = error

type jsonBinding struct{}

// A receiver with no name at all -- legal Go, and the shape that would bind an empty
// identifier into the type environment if the walker were careless.
func (jsonBinding) Name() string { return "json" }

func (jsonBinding) Bind(req *http.Request, obj any) error {
	return decodeJSON(req, obj)
}

var (
	JSON Binding = jsonBinding{}
	XML  Binding = jsonBinding{}
)

func decodeJSON(req *http.Request, obj any) error {
	return nil
}
