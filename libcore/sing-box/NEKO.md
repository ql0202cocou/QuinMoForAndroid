# Neko patches on sing-box

This directory is a vendored copy of `MatsuriDayo/sing-box` at commit
`aed32ee3066cdbc7d471e3e0415c5134088962df` (`1.12.19-neko-1`), i.e. upstream
[SagerNet/sing-box](https://github.com/SagerNet/sing-box) **v1.12.19** plus the
NekoBox patch set. Upstream NekoBox is unmaintained; this fork maintains the
patches itself. When rebasing onto a newer upstream sing-box release, re-apply
or drop each patch below after checking whether upstream has fixed the issue.

Functional neko commits (on top of the 1.12.x upstream base):

| Commit | Title | Notes |
|---|---|---|
| `a863df9b` | add boxapi | v2ray stats API used by the Android app (`boxapi/`) |
| `6b4fdc8c` | nekoutils: add geoip geosite | `nekoutils/srs.go` helpers for libcore assets |
| `7cf44f37` | nekoutils: add selector callback | `nekoutils/callback.go`, group selector callback |
| `721602a8` | dialer: add DoNotSelectInterface | dialer option to skip VPN interface selection |
| `0bc13363` | temp fix gvisor close | `protocol/tun/fix_gvisor.go`; check upstream gVisor updates before keeping |
| `d294d39b` | outbound/vless: disable flow when mux is enable | check if upstream still needs this |
| `44169a9b` | fix needCacheFile | cache file handling fix |

The `1.12.x-neko-1` commits only bump `constant/version.go` and carry no code
changes.

How to upgrade the base: clone upstream SagerNet/sing-box, merge or rebase the
patches onto the new tag, resolve conflicts, replace this directory with the
result (without `.git`), and update this file.
