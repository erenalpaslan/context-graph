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

// AliasAnonymousStruct is a type alias to an anonymous struct -- legal Go, and the
// regression case for M5: `kind` here is "type_alias" (decided from spec.type before
// `underlying` is even inspected), and it must stay a bare TypeAlias node with no Field
// children -- an alias is not a struct declaration, even though its underlying shape is a
// struct_type.
type AliasAnonymousStruct = struct {
	A int
}

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
