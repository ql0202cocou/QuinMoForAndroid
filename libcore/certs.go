package libcore

import (
	"crypto/x509"
	"log"
	_ "unsafe" // for go:linkname
)

//go:linkname systemRoots crypto/x509.systemRoots
var systemRoots *x509.CertPool

func updateRootCACerts(pem []byte) {
	// Append to the system roots instead of replacing them, so public
	// TLS verification keeps working alongside the custom CA.
	roots, err := x509.SystemCertPool()
	if err != nil {
		roots = x509.NewCertPool()
	}
	if !roots.AppendCertsFromPEM(pem) {
		log.Println("failed to append certificates from pem")
		return
	}
	systemRoots = roots
	log.Println("external ca.pem was loaded")
}

//go:linkname initSystemRoots crypto/x509.initSystemRoots
func initSystemRoots()
