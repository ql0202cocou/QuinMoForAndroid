package libcore

import (
	"fmt"
	"os"
	"path/filepath"

	geosites "github.com/sagernet/sing-box/common/geosite"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

type geosite struct {
	geositeReader *geosites.Reader
	file          *os.File
}

func (g *geosite) Open(path string) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	geositeReader, _, err := geosites.NewReader(file)
	if err != nil {
		file.Close()
		return err
	}
	g.geositeReader = geositeReader
	g.file = file
	return nil
}

func (g *geosite) Close() error {
	return g.file.Close()
}

func (g *geosite) Rules(code string) ([]option.HeadlessRule, error) {
	sourceSet, err := g.geositeReader.Read(code)
	if err != nil {
		return nil, fmt.Errorf("failed to read geosite code %s :%w", code, err)
	}

	var headlessRule option.DefaultHeadlessRule

	defaultRule := geosites.Compile(sourceSet)

	headlessRule.Domain = defaultRule.Domain
	headlessRule.DomainSuffix = defaultRule.DomainSuffix
	headlessRule.DomainKeyword = defaultRule.DomainKeyword
	headlessRule.DomainRegex = defaultRule.DomainRegex

	return []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}, nil
}

func init() {
	nekoutils.GetGeoSiteHeadlessRules = func(name string) ([]option.HeadlessRule, error) {
		g := new(geosite)
		if err := g.Open(filepath.Join(externalAssetsPath, "geosite.db")); err != nil {
			return nil, err
		}
		// Rules reads all items into memory eagerly, so the file can be
		// closed as soon as it returns.
		defer g.Close()
		return g.Rules(name)
	}
}
