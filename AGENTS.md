# AGENTS.md — NekoBox for Android

> Guidance for AI coding agents working in this repository. Assumes no prior knowledge of the project.

## Project overview

**NekoBox for Android** is an Android universal proxy client built on the [sing-box](https://github.com/SagerNet/sing-box) core. This repository is an independently maintained fork of [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid) (upstream is unmaintained; the fork relationship is documented only in `README.md`).

- License: GPL-3.0. Package name: `moe.nb4a` (namespace `io.nekohasekai.sagernet`).
- Supported protocols: SOCKS(4/4a/5), HTTP(S), SSH, Shadowsocks, VMess, Trojan, VLESS, AnyTLS, ShadowTLS, TUIC, Hysteria 1/2, WireGuard — plus external-plugin protocols Trojan-Go, NaïveProxy and Mieru, which run as separate plugin APKs discovered via the `io.nekohasekai.sagernet.plugin.ACTION_NATIVE_PLUGIN` intent.
- Documentation/README is bilingual (Chinese/English); code comments are in English.

## Technology stack

Two-stage hybrid build — a **Go core** bound into the **Kotlin Android app**:

| Layer | Tech |
|---|---|
| Android app (`app/`) | Kotlin + Java, Android Gradle Plugin 8.8.1, Kotlin 2.0.21, Gradle 8.10.2 (wrapper), KSP 2.0.21-1.0.27, Java 8 source/target, compileSdk/targetSdk 35, minSdk 21, NDK 25.0.8775105 |
| Proxy core (`libcore/`) | Go (go.mod targets 1.23.x; CI uses Go ^1.25), wraps sing-box, compiled with gomobile (vendored MatsuriDayo fork, binaries named `gomobile-matsuri`/`gobind-matsuri`) into `app/libs/libcore.aar` |
| Build glue | `buildSrc/` (shared Gradle helper `setupApp()`), `buildScript/` (bash build scripts) |

Key app dependencies: AndroidX (appcompat, navigation, preference, work, Room 2.6.1 via KSP), Material Components, kotlinx-coroutines, Gson, OkHttp, snakeyaml, Kryo, Roomigrant (generates Room migrations from `app/schemas/`), zxing-lite (QR scan), editorkit (config editor). ViewBinding, BuildConfig and AIDL are enabled.

## Repository layout

- `app/` — the only Gradle module (`settings.gradle.kts` includes just `:app`).
  - `src/main/java/io/nekohasekai/sagernet/` — main app package:
    - `bg/` — service layer: `VpnService`, `ProxyService`, `TileService`, `GuardedProcessPool` (runs plugin binaries), `SagerConnection`. All run in the `:bg` process (see `AndroidManifest.xml`).
    - `database/` — Room database (`SagerDatabase`), `ProxyEntity`/`RuleEntity`/`ProxyGroup`, `DataStore` (shared prefs singleton), `ProfileManager`/`GroupManager`.
    - `fmt/` — per-protocol config beans and parsers (`shadowsocks/`, `v2ray/`, `trojan/`, `tuic/`, `hysteria/`, `wireguard/`, …) and `ConfigBuilder.kt`, which renders the sing-box JSON config.
    - `group/` — subscription/group updaters (`RawUpdater`, `GroupUpdater`).
    - `ui/` — activities/fragments, incl. one `*SettingsActivity` per protocol; `plugin/` — plugin APK management; `ktx/`, `utils/`, `widget/`, `aidl/`.
  - `src/main/java/moe/matsuri/nb4a/` — NekoBox-specific layer: `NativeInterface.kt` (implements the gomobile-generated `BoxPlatformInterface`/`NB4AInterface` from libcore, bridges to `VpnService`), `proxy/` (newer protocols: anytls, shadowtls, neko, custom config), `SingBoxOptions.java` (generated structs mirroring sing-box options).
  - `src/main/java/com/github/shadowsocks/` — code vendored from shadowsocks-android.
  - `libs/` — gitignored; `libcore.aar` is placed here by the core build and consumed via `implementation(fileTree("libs"))`.
  - `executableSo/` — extra jniLibs source dir. `schemas/` — Room schema JSON (checked in; keep in sync when entities change).
- `libcore/` — Go module wrapping sing-box for Android. `box.go` exposes `BoxInstance`; `platform_java.go`/`platform_box.go` implement the platform interface; `assets*.go` load geoip/geosite (xz-compressed); subpackages: `device/`, `ech/`, `procfs/`, `stun/`.
  - All core sources are **vendored in-tree** (nested Go modules, committed as plain directories): `libcore/sing-box/` (upstream SagerNet/sing-box v1.12.19 + neko patches — see `libcore/sing-box/NEKO.md` for the patch list, maintained by this fork since upstream NekoBox is unmaintained), `libcore/libneko/` (neko-specific Go code), `libcore/gomobile/` (MatsuriDayo/gomobile @ master2 build tool, installed by `libcore/init.sh`). `go.mod` `replace`s point at `./sing-box` and `./libneko`; no external checkouts are needed.
  - Build tags (set in `build.sh`): `with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api`.
- `buildSrc/src/main/kotlin/Helpers.kt` — `setupApp()` / `setupCommon()`: SDK levels, ABI splits (armeabi-v7a/arm64-v8a/x86/x86_64, no universal APK), product flavors **`oss` / `fdroid` / `play` / `preview`** (dimension `vendor`), release signing, lint config, APK renaming (`NekoBox-<version>-<abi>.apk`).
- `buildScript/` — bash entry points invoked through the `./run` dispatcher (`./run a b c` resolves to `buildScript/a/b/c.sh`):
  - `./run lib core` → init gomobile, report available Go module updates (check-only), build `libcore.aar` into `app/libs/`.
  - `./run init action gradle` → CI prep (downloads geoip/geosite assets).
  - `buildScript/lib/assets.sh` → downloads `geoip.db`/`geosite.db` (xz) into `app/src/main/assets/sing-box/` (gitignored).
  - `buildScript/init/env.sh` / `env_ndk.sh` → locate Android SDK/NDK and set per-ABI clang env vars.
- `nb4a.properties` — release metadata: `PACKAGE_NAME`, `VERSION_NAME`, `PRE_VERSION_NAME`, `VERSION_CODE` (multiplied by 5 in `buildSrc` to leave room for ABI offsets). Bump versions here, not in Gradle files.
- `.github/workflows/` — `release.yml` and `preview.yml` (both manual `workflow_dispatch`; see Deployment).

## Build

Prerequisites: Android SDK 35 + NDK 25.0.8775105, JDK 17+, Go ≥1.23 (with `GOPATH/bin` usable). All Go sources (sing-box, libneko, gomobile) are vendored under `libcore/` — no external checkouts needed.

Full build from a clean checkout:

```bash
./run lib core                     # builds libcore.aar -> app/libs/ (Go + gomobile, slow first time)
./gradlew app:assembleOssDebug     # or app:assembleOssRelease
```

- `./run lib core` prints outdated Go modules (`go list -m -u`, check-only) before compiling. Upgrades are done **manually together with the sing-box base** — the sing-box ecosystem modules (sing, sing-quic, sing-tun, quic-go, sing-mux, …) are version-locked to each other, so a blind `go get -u` breaks the build.
- CI caches `app/libs/libcore.aar` keyed by hashes of `libcore/` contents (vendored sources included). A cache hit skips the core build entirely; clear the Actions cache to force a rebuild.

- If you did not touch `libcore/`, the Go core rebuild can be skipped as long as `app/libs/libcore.aar` exists.
- Gradle tasks follow `assemble<Flavor><BuildType>`, e.g. `assembleFdroidRelease`, `bundlePlayRelease`, `assemblePreviewRelease`. Helper tasks `assemble<Arm64|Arm|X64|X86>FdroidRelease` also exist.
- Release signing: the keystore `release.keystore` (alias `ql0202cocou`) is **not committed** — it lives locally and is gitignored. Passwords go in `local.properties` (`KEYSTORE_PASS`, `ALIAS_NAME`, `ALIAS_PASS`) or the base64-encoded `LOCAL_PROPERTIES` env var. In CI the workflows decode `secrets.KEYSTORE_BASE64` into `release.keystore` before building. Without credentials, release builds are unsigned.
- Env var `nkmr_minify=0` disables minify/resource-shrinking for release builds.

## Code style guidelines

- Kotlin follows the official code style (`kotlin.code.style=official` in `gradle.properties`). Match the surrounding file: heavy use of extension functions (`ktx/`), `DataStore` for preferences, coroutines for async.
- Package split is historical: base app in `io.nekohasekai.sagernet`, newer NekoBox features in `moe.matsuri.nb4a` — put new protocol support alongside the existing per-protocol dirs, don't merge the trees.
- `android.nonTransitiveRClass=false` and `android.nonFinalResIds=false` are set; the codebase relies on legacy (transitive) R-class behavior.
- Lint is strict: `warningsAsErrors = true`, `checkReleaseBuilds = true` (config in `buildSrc/Helpers.kt`), with `lint.xml` overrides at repo root. A release build fails on lint warnings — run `./gradlew app:lintOssRelease` before finalizing non-trivial app changes.
- Go side: standard `gofmt` style; Android-only files use the `//go:build android` convention via the `_android.go` suffix pattern (e.g. `assets_android.go` / `assets_other.go`).

## Testing instructions

There is **no test suite** — no `src/test`/`src/androidTest`, no Go `_test.go` files, no CI test job. Verification is by compilation and lint:

```bash
./gradlew app:assembleOssDebug        # compiles app + vendored AAR
./gradlew app:lintOssRelease          # lint gate (warnings are errors)
cd libcore && go build ./...          # type-checks the Go core
```

Manual/device verification is the norm for behavior changes (proxy configs, VPN service, subscriptions).

## Deployment process

All releases are manual via GitHub Actions (`workflow_dispatch`):

- `.github/workflows/release.yml` — jobs: (1) `libcore` builds `libcore.aar` on ubuntu-latest with Go ^1.25, cached in `actions/cache` keyed by hashes of the workflows + build scripts + `libcore/` contents (changing any of those busts the cache); (2) `build` runs `./run init action gradle` then `./gradlew app:assembleOssRelease` and uploads APKs; (3) `publish` pushes them to a GitHub release with `ghr` (skipped when input `publish=y`); (4) `play` builds `bundlePlayRelease` (skipped when input `play=y`).
- `.github/workflows/preview.yml` — same libcore caching, builds `app:assemblePreviewRelease` (uses `PRE_VERSION_NAME` from `nb4a.properties`, APKs named `NekoBox-pre-*.apk`).
- F-Droid builds use `buildScript/fdroid/prebuild.sh` (just builds the core) plus the `fdroid` flavor.
- The Google Play version has been controlled by a third party since May 2024 and is not open source — do not treat it as a distribution target (see `README.md`).

## Security considerations

- `release.keystore` (alias `ql0202cocou`) is gitignored and never committed — the public repo must not contain the private signing key. CI receives it via the `KEYSTORE_BASE64` secret; passwords come from `local.properties` / `LOCAL_PROPERTIES` / env vars — never hard-code them.
- Never commit `local.properties`, `release.keystore`, `app/libs/`, `app/src/main/assets/sing-box/`, or `/nkmr` (all gitignored).
- The app defines a signature-level permission `${applicationId}.SERVICE` guarding its IPC/AIDL surface — keep `protectionLevel="signature"` when touching the manifest.
- `VpnService` traffic, plugin binaries (`GuardedProcessPool`) and user-supplied configs/subscriptions are trust boundaries: validate parsed input in `fmt/` parsers and don't log credentials/keys.
- geoip/geosite databases are downloaded from GitHub at build time; builds are therefore network-dependent and sensitive to upstream release changes. The gomobile toolchain and all core Go sources are vendored under `libcore/` and no longer fetched at build time.
