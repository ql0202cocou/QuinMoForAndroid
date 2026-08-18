# AGENTS.md — NekoBox for Android

> Guidance for AI coding agents working in this repository. Assumes no prior knowledge of the project.

## Project overview

**NekoBox for Android** is an Android universal proxy client built on the [sing-box](https://github.com/SagerNet/sing-box) core. This repository is an independently maintained fork of [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid) (upstream is unmaintained; the fork relationship is documented only in `README.md`).

- License: GPL-3.0. Package name: `moe.nb4a` (namespace `io.nekohasekai.sagernet`).
- Supported protocols: SOCKS(4/4a/5), HTTP(S), SSH, Shadowsocks, VMess, Trojan, VLESS, AnyTLS, ShadowTLS, TUIC, Hysteria 1/2, WireGuard — plus external-plugin protocols Trojan-Go, NaïveProxy and Mieru, which run as separate plugin APKs discovered via the `io.nekohasekai.sagernet.plugin.ACTION_NATIVE_PLUGIN` intent. Additionally the app bundles two proxy cores as built-in plugins (see `app/executableSo/`): **Xray** (default core for VLESS profiles, for newer REALITY compatibility) and **mihomo** (default core for AnyTLS profiles); the core is selectable per profile in the profile editor.
- Documentation/README is bilingual (Chinese/English); code comments are in English.

## Technology stack

Two-stage hybrid build — a **Go core** bound into the **Kotlin Android app**:

| Layer | Tech |
|---|---|
| Android app (`app/`) | Kotlin + Java, Android Gradle Plugin 8.9.3, Kotlin 2.0.21, Gradle 8.11.1 (wrapper), KSP 2.0.21-1.0.27, Java 8 source/target, compileSdk/targetSdk 36 (build-tools 36.0.0), minSdk 21, NDK 25.0.8775105 |
| Proxy core (`libcore/`) | Go (`libcore/go.mod` requires go 1.25.0; CI uses Go ^1.25), wraps sing-box, compiled with gomobile (vendored MatsuriDayo fork, binaries named `gomobile-matsuri`/`gobind-matsuri`) into `app/libs/libcore.aar` |
| Build glue | `buildSrc/` (shared Gradle helper `setupApp()`), `buildScript/` (bash build scripts) |

Key app dependencies: AndroidX (appcompat, navigation, preference, work, Room 2.6.1 via KSP), Material Components, kotlinx-coroutines, Gson, OkHttp, snakeyaml, Kryo, Roomigrant (generates Room migrations from `app/schemas/`), zxing-lite (QR scan), editorkit (config editor). ViewBinding, BuildConfig and AIDL are enabled.

Deliberately pinned versions (do not bump in isolation):

- `activity-ktx` 1.10.1, `okhttp` 4.12.0, `room` 2.6.1 — okhttp 5.x needs Kotlin 2.2 metadata and Room 2.8 needs a matching KSP; bump them only together with a Kotlin 2.2.x upgrade (the AGP/compileSdk half is already done). `Roomigrant` 0.3.4 is unmaintained and untested against Room 2.8/KSP2 — if it breaks, either keep Room at 2.6.1 or replace the generated migrations with handwritten ones.
- `gson` 2.13.2 — 2.13 previously broke `SingBoxOptions` serialization twice (infinite recursion, then nested `_hack_config_map` merges being dropped); the current fix is `singBoxOptionFactory` in `SingBoxOptions.java` (official `getDelegateAdapter` pattern). Before any gson bump, re-run the nested-outbound merge repro and verify on-device that external-core profiles still start.
- okhttp 5 note: `ConfigurationFragment.kt` uses the internal `okhttp3.internal.closeQuietly`, which may be removed/moved in okhttp 5 — replace with an explicit close when upgrading.

## Repository layout

- `app/` — the only Gradle module (`settings.gradle.kts` includes just `:app`).
  - `src/main/java/io/nekohasekai/sagernet/` — main app package:
    - `bg/` — service layer: `VpnService`, `ProxyService`, `TileService`, `GuardedProcessPool` (runs plugin binaries), `SagerConnection`. All run in the `:bg` process (see `AndroidManifest.xml`). Runtime wiring for the bundled external cores (Xray/mihomo): their generated configs dial `127.0.0.1:<mappingPort>` as the server address; sing-box hosts one `direct` inbound per external profile (tag `c-<chain>-mapping-<profileId>`) whose `override_address`/`override_port` restores the real server. So routing and **server-domain DNS resolution for external-core profiles happen on the sing-box side** (and are affected by the group's `proxyServerNameserver`, see `fmt/`).
    - `database/` — Room database (`SagerDatabase`), `ProxyEntity`/`RuleEntity`/`ProxyGroup`, `DataStore` (shared prefs singleton), `ProfileManager`/`GroupManager`.
    - `fmt/` — per-protocol config beans and parsers (`shadowsocks/`, `v2ray/`, `trojan/`, `tuic/`, `hysteria/`, `wireguard/`, …) and `ConfigBuilder.kt`, which renders the sing-box JSON config. When the group sets `proxyServerNameserver` (one DNS address per line, editable in the group settings UI), `ConfigBuilder` emits one `dns-group-N` DNS server per address plus top-priority DNS rules covering the group's node server domains; the rules carry `fallback: true` so the addresses are tried top-to-bottom in order. These servers use `dns-local` as `address_resolver`, so any DoH/DoT hostname in them must itself be publicly resolvable.
    - `group/` — subscription/group updaters (`RawUpdater`, `GroupUpdater`). On update, `RawUpdater` also mirrors `dns.proxy-server-nameserver` (or a top-level key of the same name) from mihomo/clash YAML subscriptions into the group's `proxyServerNameserver` field. Only the plain-text YAML body is inspected for this — a base64-wrapped subscription body is not decoded, and share-link-style subscriptions have no such key, so the field stays empty in both cases. `GroupUpdater.forceResolve` also honors the group nameserver (first usable address, system DNS as fallback).
    - `ui/` — activities/fragments, incl. one `*SettingsActivity` per protocol; `plugin/` — plugin APK management; `ktx/`, `utils/`, `widget/`, `aidl/`.
  - `src/main/java/moe/matsuri/nb4a/` — NekoBox-specific layer: `NativeInterface.kt` (implements the gomobile-generated `BoxPlatformInterface`/`NB4AInterface` from libcore, bridges to `VpnService`), `proxy/` (newer protocols: anytls, shadowtls, neko, custom config), `SingBoxOptions.java` (generated structs mirroring sing-box options).
  - `src/main/java/com/github/shadowsocks/` — code vendored from shadowsocks-android.
  - `libs/` — gitignored; `libcore.aar` is placed here by the core build and consumed via `implementation(fileTree("libs"))`.
  - `executableSo/` — extra jniLibs source dir hosting the built-in plugin cores (`libxray.so` / `libmihomo.so` per ABI, gitignored, downloaded by `buildScript/lib/plugins.sh`; resolved via `PluginManager.initNativeInternal`). `schemas/` — Room schema JSON (checked in; keep in sync when entities change).
- `libcore/` — Go module wrapping sing-box for Android. `box.go` exposes `BoxInstance`; `platform_java.go`/`platform_box.go` implement the platform interface; `assets*.go` load geoip/geosite (xz-compressed); subpackages: `device/`, `ech/`, `procfs/`, `stun/`.
  - All core sources are **vendored in-tree** (nested Go modules, committed as plain directories): `libcore/sing-box/` (upstream SagerNet/sing-box v1.13.18 + neko patches — see `libcore/sing-box/NEKO.md` for the patch list, maintained by this fork since upstream NekoBox is unmaintained), `libcore/libneko/` (neko-specific Go code), `libcore/gomobile/` (MatsuriDayo/gomobile @ master2 build tool, installed by `libcore/init.sh`). `go.mod` `replace`s point at `./sing-box` and `./libneko`; no external checkouts are needed.
  - Build tags (set in `build.sh`): `with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api`.
- `buildSrc/src/main/kotlin/Helpers.kt` — `setupApp()` / `setupCommon()`: SDK levels, ABI splits (arm64-v8a/x86_64 plus a universal all-ABI APK), product flavors **`oss` / `fdroid` / `preview`** (dimension `vendor`), release signing, lint config, APK renaming (`NekoBox-<version>-<abi>.apk`).
- `buildScript/` — bash entry points invoked through the `./run` dispatcher (`./run a b c` resolves to `buildScript/a/b/c.sh`):
  - `./run lib core` → init gomobile, report available Go module updates (check-only), build `libcore.aar` into `app/libs/`.
  - `./run lib plugins` → download the pinned Xray/mihomo Android binaries (versions pinned in `buildScript/lib/plugins.sh`) into `app/executableSo/`.
  - `./run init action gradle` → CI prep (geoip/geosite assets + Xray/mihomo plugin cores). Also run by
    `buildScript/fdroid/prebuild.sh`, so keep it free of CI-runner-specific work. Note that everything
    under `buildScript/**` is in the libcore cache key, so edits here force a full Go core rebuild.
  - `buildScript/lib/assets.sh` → downloads `geoip.db`/`geosite.db` (xz) into `app/src/main/assets/sing-box/` (gitignored).
  - `buildScript/init/env.sh` / `env_ndk.sh` → locate Android SDK/NDK and set per-ABI clang env vars.
- `nb4a.properties` — release metadata: `PACKAGE_NAME`, `VERSION_NAME`, `PRE_VERSION_NAME`, `VERSION_CODE` (multiplied by 5 in `buildSrc` to leave room for ABI offsets). Bump versions here, not in Gradle files. The version name for the "project complete" milestone is planned to be **2.0.0**, so the 1.6.x line continues until then.
- `.github/workflows/` — `release.yml` and `preview.yml` (both manual `workflow_dispatch`) are thin callers; the work lives in the reusable `libcore.yml` and `build-apk.yml` they both call. See Deployment.

## Build

Prerequisites: Android SDK 36 (platform + build-tools 36.0.0) + NDK 25.0.8775105, JDK 17+, Go ≥1.25 (with `GOPATH/bin` usable). All Go sources (sing-box, libneko, gomobile) are vendored under `libcore/` — no external checkouts needed.

Full build from a clean checkout:

```bash
./run lib core                     # builds libcore.aar -> app/libs/ (Go + gomobile, slow first time)
./run lib plugins                  # downloads xray/mihomo binaries -> app/executableSo/
./gradlew app:assembleOssDebug     # or app:assembleOssRelease
```

- `./run lib core` prints outdated Go modules (`go list -m -u`, check-only) before compiling. Upgrades are done **manually together with the sing-box base** — the sing-box ecosystem modules (sing, sing-quic, sing-tun, quic-go, sing-mux, …) are version-locked to each other, so a blind `go get -u` breaks the build.
- 16 KB page alignment for `libgojni.so` — see the comment on the `-extldflags` flag in
  `libcore/build.sh`; verify the result with `llvm-readelf -l`.
- CI caches `app/libs/libcore.aar` keyed by the core build inputs (see `.github/workflows/libcore.yml`). A cache hit skips the core build entirely; clear the Actions cache to force a rebuild.

- If you did not touch `libcore/`, the Go core rebuild can be skipped as long as `app/libs/libcore.aar` exists.
- Gradle tasks follow `assemble<Flavor><BuildType>`, e.g. `assembleFdroidRelease`, `assemblePreviewRelease`. Helper tasks `assemble<Arm64|Arm|X64|X86>FdroidRelease` also exist.
- Release signing: the keystore `release.keystore` (alias `ql0202cocou`) is **not committed** — it lives locally and is gitignored. Passwords go in `local.properties` (`KEYSTORE_PASS`, `ALIAS_NAME`, `ALIAS_PASS`) or the base64-encoded `LOCAL_PROPERTIES` env var. In CI the workflows decode `secrets.KEYSTORE_BASE64` into `release.keystore` before building. Without credentials, release builds are unsigned.
- Env var `nkmr_minify=0` disables minify/resource-shrinking for release builds.

## External core config mapping

Each bundled external core has a hand-written config builder, and **any bean field it does not
explicitly map is silently dropped** — the profile still starts, the option just never applies. This
is the most common source of "works on sing-box, broken on the plugin" reports.

| Core | Builder | Covers |
|---|---|---|
| Xray | `fmt/v2ray/XrayConfig.kt` | VMess / VLESS |
| mihomo | `moe/matsuri/nb4a/proxy/anytls/MihomoConfig.kt` | AnyTLS only — it never sees VLESS/REALITY |

When adding a bean field, map it in the sing-box builder **and** the relevant external-core builder,
or record the gap. Known gaps as of 1.6.6: `certificates` is unmapped in `MihomoConfig`; ECH is
unmapped in `XrayConfig`.

Core capability differs and is checkable without hunting docs — read it out of the shipped binary:

```bash
strings -a app/executableSo/arm64-v8a/libxray.so  | grep -i mldsa65
strings -a app/executableSo/arm64-v8a/libmihomo.so | grep -oE 'proxy:"[a-z0-9-]+,omitempty"' | sort -u
```

mihomo option keys surface as Go struct tags (`proxy:"ech-opts,omitempty"`). This is how the
`mldsa65Verify` support matrix was established: present in `libxray.so`, absent from vendored
sing-box 1.13.18 — hence Xray-only, and labelled as such in the profile editor.

The external core dials `127.0.0.1` (the sing-box mapping inbound), so `bean.finalAddress` is not the
real host. Both builders already fall back to `bean.serverAddress` when `bean.sni` is blank — preserve
that when adding TLS options, or the core will send `127.0.0.1` as the SNI.

## Code style guidelines

- Kotlin follows the official code style (`kotlin.code.style=official` in `gradle.properties`). Match the surrounding file: heavy use of extension functions (`ktx/`), `DataStore` for preferences, coroutines for async.
- Package split is historical: base app in `io.nekohasekai.sagernet`, newer NekoBox features in `moe.matsuri.nb4a` — put new protocol support alongside the existing per-protocol dirs, don't merge the trees.
- `android.nonTransitiveRClass=false` and `android.nonFinalResIds=false` are set; the codebase relies on legacy (transitive) R-class behavior.
- Lint is strict: `warningsAsErrors = true`, `checkReleaseBuilds = true` (config in `buildSrc/Helpers.kt`), with `lint.xml` overrides at repo root. A release build fails on lint warnings — run `./gradlew app:lintOssRelease` before finalizing non-trivial app changes.
- Never `override fun onBackPressed()` — predictive back (default from targetSdk 35) bypasses it. Screens
  with unsaved edits call `ThemedActivity.guardUnsavedChanges()`, which registers on the dispatcher; the
  KDoc there explains why. A regression here silently discards user input.
- Use `@RequiresApi`, not `@TargetApi` (lint `UseRequiresApi`), and prefer the `androidx.core` KTX extensions
  over their platform equivalents (lint `UseKtx`) — both are errors under `warningsAsErrors`.
- Beans hand-roll Kryo serialization with a leading version int (`StandardV2RayBean.serialize`). To add
  a field: bump that int, append the write at the **end** of the stream, and guard the read with
  `if (version >= N)`. Old records then skip it and get their default from `initializeDefaultValues()`,
  which `KryoConverters.deserialize` calls outside its try/catch — so the field is `""`, never null.
  Downgrades are safe too: each bean deserializes from its own `byte[]`, so trailing bytes are ignored.
  Getting this wrong loses every profile of that type, and there is no test to catch it.
- Go side: standard `gofmt` style; Android-only files use the `//go:build android` convention via the `_android.go` suffix pattern (e.g. `assets_android.go` / `assets_other.go`).

## Testing instructions

There is **no test suite** — no `src/test`/`src/androidTest`, no Go `_test.go` files, no CI test job. Verification is by compilation and lint:

```bash
./gradlew app:assembleOssDebug        # compiles app + vendored AAR
./gradlew app:lintOssRelease          # lint gate (warnings are errors)
cd libcore && go build ./...          # type-checks the Go core
```

Manual/device verification is the norm for behavior changes (proxy configs, VPN service, subscriptions).

## Debugging

- Device logs the user shares land in `log/` (gitignored). They contain node domains/IPs and can contain credentials — analyze them, never commit or echo their contents elsewhere.
- The dev Mac runs a local proxy in fake-ip mode (`198.18.0.0/15`, hijacks port 53), so `dig` output on this machine is unreliable. Verify DNS over DoH instead: `https://dns.google/resolve` or `https://dns.alidns.com/resolve` (JSON) for the public view, or an RFC 8484 wire query (`application/dns-message`) against the concrete DoH endpoint when split-horizon DNS is suspected.
- Reading a device log for external-core (Xray/mihomo) profile failures: find the sing-box config dump (`[ProxyInstance]`), check the `c-*-mapping-*` direct inbound's `override_address`/`override_port` (the real server), then follow the `dns: match[N] ... => route(<server>)` line for the server domain's lookup. If the domain resolves only via the group's own DNS (split-horizon), public resolvers return NXDOMAIN — that points to an empty group `proxyServerNameserver`, not a dead domain.
- The subscription request honors the mixed port when the service is running (`trySocks5(DataStore.mixedPort)`), so subscription updates can traverse the proxy itself; keep this in mind when a subscription behaves differently with the VPN on vs off.

## Deployment process

All releases are manual via GitHub Actions (`workflow_dispatch`):

- `.github/workflows/libcore.yml` — reusable (`workflow_call`) job shared by both workflows below: builds `libcore.aar` once, caches it keyed on the core build inputs (the workflow itself, `run`, `buildScript/`, `libcore/`), and hands it to the consumer jobs as the short-lived `libcore-aar` artifact.
- `.github/workflows/build-apk.yml` — reusable (`workflow_call`) APK build, parameterised by `gradle-task`. Holds the runner setup both release and preview need: caches, the SDK 36 install, keystore decode, `./run init action gradle`, and the APK upload. Callers pass `secrets: inherit`.
- `.github/workflows/release.yml` — jobs: (1) `libcore`; (2) `build` calls `build-apk.yml` with `app:assembleOssRelease`; (3) `publish` pushes the APKs to a GitHub release with `ghr` (skipped when input `publish=y`).
- `.github/workflows/preview.yml` — same shape, calls `build-apk.yml` with `app:assemblePreviewRelease` (uses `PRE_VERSION_NAME` from `nb4a.properties`, APKs named `NekoBox-pre-*.apk`).
- F-Droid builds use `buildScript/fdroid/prebuild.sh` plus the `fdroid` flavor. That script runs
  `./run init action gradle` **and** `buildScript/lib/core.sh`, so anything added to the CI prep script
  also runs on F-Droid's builders — keep runner-specific setup in the workflows instead.
- The Google Play version has been controlled by a third party since May 2024 and is not open source — do not treat it as a distribution target (see `README.md`).

Cutting a release:

1. Bump `nb4a.properties` only (`VERSION_NAME`, `PRE_VERSION_NAME`, `VERSION_CODE`); commit as
   `chore: 发布 X.Y.Z` touching nothing else, then `git tag X.Y.Z` — lightweight tag, bare version
   number, on that commit. Push both.
2. **No workflow has a push or pull_request trigger**, so pushing validates nothing. Run
   `gh workflow run "Preview Build"` to exercise `main` through the same `build-apk.yml`, or
   `gh workflow run "Release Build" -f tag=X.Y.Z -f publish=y` to run the release pipeline without
   creating a GitHub Release. Omit `publish` when you actually want to publish.
3. Verify the artifact, not just the green check:
   `aapt2 dump badging <apk>` (versionName / versionCode == `VERSION_CODE` × 5),
   `apksigner verify --print-certs <apk>` (SHA-256 must match previous releases or updates are
   rejected as a signature mismatch), and `llvm-readelf -l` on the bundled `.so` (LOAD align `0x4000`).

CI green means it compiled and packaged. It says nothing about runtime behaviour — external-core
config mapping, bean deserialization of existing profiles, and anything touching the VPN need a
device.

## Security considerations

- `release.keystore` (alias `ql0202cocou`) is gitignored and never committed — the public repo must not contain the private signing key. CI receives it via the `KEYSTORE_BASE64` secret; passwords come from `local.properties` / `LOCAL_PROPERTIES` / env vars — never hard-code them.
- Never commit `local.properties`, `release.keystore`, `app/libs/`, `app/src/main/assets/sing-box/`, or `/nkmr` (all gitignored).
- The app defines a signature-level permission `${applicationId}.SERVICE` guarding its IPC/AIDL surface — keep `protectionLevel="signature"` when touching the manifest.
- `VpnService` traffic, plugin binaries (`GuardedProcessPool`) and user-supplied configs/subscriptions are trust boundaries: validate parsed input in `fmt/` parsers and don't log credentials/keys.
- geoip/geosite databases are downloaded from GitHub at build time; builds are therefore network-dependent and sensitive to upstream release changes. The gomobile toolchain and all core Go sources are vendored under `libcore/` and no longer fetched at build time.

## Open issues (as of 1.6.6, 2026-08-18)

Carried into the next session; none of these are fixed.

- **Xray plugin fails VLESS+REALITY where sing-box succeeds** — server closes the connection during the
  handshake. Undiagnosed. An SNI-fallback theory was investigated and **disproved** (`XrayConfig.kt`
  already falls back to `bean.serverAddress`). Next step needs the device log's Xray handshake error,
  or whether that profile's SNI / Fingerprint / ShortId fields are empty.
- **`pqv` share-link parameter is a guess.** The REALITY `mldsa65Verify` field is exported as `pqv`,
  inferred from the `pbk`/`sid`/`fp` abbreviation convention with no authoritative source. The parser
  also accepts the full `mldsa65Verify` name. Confirm against a real post-quantum REALITY link.
- **`certificates` unmapped for mihomo** — see External core config mapping. `libmihomo.so` exposes
  `proxy:"certificate"` / `proxy:"cert"` but no `ca-str`, so it may only accept a file path while the
  bean stores inline PEM. It also carries a runtime guard string,
  `disallow using AnyTLS without certificates/shadow-tls/res-tls/jls/allow-insecure config`, which may
  reject startup outright rather than failing the handshake.
- **`libcore.yml` cache key is over-broad** — it hashes all of `buildScript/**`, but only `run`,
  `buildScript/lib/core*` and `buildScript/init/env*.sh` affect the core. A routine `plugins.sh`
  version bump therefore forces a full 10–15 min gomobile rebuild.
- **`Router.Exchange` ignores `fallback` on NXDOMAIN.** The neko `fallback` DNS-rule patch only
  continues on transport errors. `Router.Lookup` is unaffected because `client.Lookup` converts any
  non-success Rcode into an error, and node dialing goes through `Lookup` — so this is latent, and
  only reachable by app DNS queries proxied through the tunnel.
- **1.6.5 and 1.6.6 shipped without device verification** — 16 KB page alignment, the predictive-back
  migration, targetSdk 36 behaviour, and both external-core field mappings are verified by build, lint
  and binary inspection only.
