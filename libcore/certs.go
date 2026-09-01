package libcore

import (
	"crypto/x509"
	"log"
	"sync"
	_ "unsafe" // for go:linkname
)

//go:linkname systemRoots crypto/x509.systemRoots
var systemRoots *x509.CertPool

// crypto/x509 readers hold systemRootsMu while reading systemRoots; only
// systemRoots itself is marked linkname-able upstream, so this reference
// needs -checklinkname=0 at link time (see build.sh).
//
//go:linkname systemRootsMu crypto/x509.systemRootsMu
var systemRootsMu sync.RWMutex

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
	systemRootsMu.Lock()
	systemRoots = roots
	systemRootsMu.Unlock()
	log.Println("external ca.pem was loaded")
}
