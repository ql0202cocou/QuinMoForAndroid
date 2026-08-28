package device

import (
	"fmt"
	"runtime/debug"
)

func DeferPanicToError(name string, onError func(error)) {
	if r := recover(); r != nil {
		if onError != nil {
			s := fmt.Errorf("%s panic: %s\n%s", name, r, string(debug.Stack()))
			onError(s)
		}
	}
}
