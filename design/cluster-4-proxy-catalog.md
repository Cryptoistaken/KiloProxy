# Cluster 4 — Proxy Catalog & Provider/Country Logic

Design document for deepening the proxy-provider + country cluster into one
data-driven module. **Design doc only — no repo changes.**

---

## 0. Status quo (what the cluster actually is)

`util/ProxyProviders.kt` is an `object` of seven public functions plus five
string "type" constants, every function a `when (type)` re-stating the same
per-provider regexes. The classification knowledge lives in four places, not
one:

| Knowledge | Lives in |
|---|---|
| Owl/Rapid/Clip/generic username regexes | `ProxyProviders.parseCountry/extractBase/buildUsername` (3 regexes × 3 functions) |
| Owl sticky suffix regex `_st__city_sid_\d+_time_\d+` | **3 copies**: `FloatingControlService.kt:870`, `CountriesScreen.kt:133`, `ProxiesScreen.kt:288` |
| "type is one of OWL/RAPID/CLIP/GENERIC" membership | **3 copies**: `ProxiesScreen.kt:329-333`, `ProxiesScreen.kt:576-580`, plus `isOwl(...)` at `ProxiesScreen.kt:220` |
| "country switch" `when(type)` block | **2 near-identical copies**: `FloatingControlService.kt:867-886` and `CountriesScreen.kt:130-147` (~20 lines each, one is a silent duplicate maintained by hand) |

Consumers (complete list, found by grep):
- `ProxiesScreen.kt` — 15 references (type state, live detection as the user
  types, sticky parse, membership lists, IP-mode/time widgets)
- `ProxyCard.kt` — `detectType` + `parseCountry` + `label` (`:62-66`)
- `FloatingControlService.kt` — `supportsCountrySwitch()` (`:849`) and the
  country-switch block (`:867-886`)
- `CountriesScreen.kt` — duplicate switch block (`:130-147`)
- `Utility.netshieldPolicy` (`:218-221`) — country for the NetShield decision

**Two honest findings that differ from the brief:**

1. **`ProfileFactory.kt` has no preset profiles.** It is a `WeakReference`
   cache of `Profile` objects (`ProfileFactory.kt:7-31`); the brief's
   `presetProfiles()` is *new* capability, not a refactor of existing code.
   ProfileFactory itself needs no change.
2. **`Countries.kt` is already data.** 200+ `Country(code,name,phone)` rows
   with O(1) `fromCode`/`fromName` maps and a pure-keyboard-math flag getter
   (`Utility.countryCodeToFlag` is pure Kotlin, no Android deps —
   `Utility.kt:427`). No conversion is needed; the catalog delegates to it.

The dominant smell is **scattered-what**: the same regexes and membership
lists copied across UI files because `ProxyProviders` returns a bare `String`
type and every caller re-branches on it.

---

## 1. The radical design

Replace the imperative per-provider branching (TYPE constants + `when(type)`
in the module *and in every caller*) with a **data-driven descriptor registry
behind one small class**: each provider's entire personality — id, label,
host suffixes, username schema (regex with named groups), country-case rule,
embed template, UI features, preset seed — is one immutable data record,
evaluated by a single generic engine.

Adding a provider = appending one data row. Closing duplicate code = deleting
the copies (they were only needed because the type string carried no
knowledge).

### 1.1 Designed twice — variant comparison

**Variant A — pure function table.** `object ProviderRegistry { val BUILTIN:
List<ProviderDef> }` plus top-level pure functions
`describe(registry, username, host)`, `embed(registry, def, ...)`. No
instance; the registry is threaded as the first argument.

**Variant B — object with injected registry.**
`class ProviderCatalog(private val registry: List<ProviderDef>)` with instance
methods; `companion default()` wires the built-in registry; tests construct
`ProviderCatalog(fixtureDefs)`.

**Winner: B.** Reasons:

1. **A and B expose the *same* seam, but A threads it by hand.** The registry
   is the seam in both. A forces every one of the six consumer files to know
   and pass the registry (or to invent a holder — which is B). B gives callers
   one object; when a second production registry appears (asset-JSON catalog,
   user-defined providers), B changes exactly one construction site, A changes
   every call site.
2. **B loses nothing in purity.** An instance with an immutable final registry
   and no mutable state is as pure as a function table; the "no module
   instance" of A is cosmetic.
3. **Testability is equal, ergonomics are not.** `ProviderCatalog(fixtures)`
   at one construction site vs `describe(fixtures, ...)` repeated per call:
   B keeps all fixture knowledge in one place.
4. **Kotlin idiom.** Companion default gives production the singleton feel of
   the current `object` without sacrificing the constructor seam.
5. **Depth.** B hides *both* the registry and the engine behind the object;
   A leaks the registry into the interface of every function.

Verdict: A is B with the parameter threaded by hand. Adopt B.

---

## 2. Module shape

New package `app/src/main/java/net/typeblog/socks/catalog/` — three files,
zero Android imports, pure JVM (unit-testable without Robolectric):

```
catalog/
  ProviderDef.kt       # data only: ProviderDef, ProviderFeature, CountryCase,
                       # EmbedMode, ProfileSeed, ProviderIdentity, sentinel defs
  ProviderRegistry.kt  # data only: BUILTIN: List<ProviderDef> (5 rows)
  ProviderCatalog.kt   # the module: engine + interface, no provider logic
```

- `catalog` depends on `util/Countries.kt` (already data — leave it in place;
  moving it would churn 6 UI files for zero gain).
- `util/ProxyProviders.kt` is **deleted** at the end of migration.
- `FloatingControlService`, `CountriesScreen`, `ProxiesScreen`, `ProxyCard`,
  `Utility` depend on `catalog` (directed inward).
- `BubbleMenuOverlay.kt` needs **no change**: it already consumes only
  `Countries` data and a `supportsCountrySwitch` boolean supplied by
  FloatingControlService.

---

## 3. Complete interface specification

### 3.1 Types

```kotlin
// catalog/ProviderDef.kt

enum class CountryCase { UPPER, LOWER, TOKEN }
// TOKEN = mirror the case of the country token currently embedded
// (used by the generic descriptor; Owl = LOWER, Rapid/Clip = UPPER)

enum class ProviderFeature { COUNTRY_PICKER, IP_MODE, STICKY_TIME }

enum class EmbedMode { PRESERVE, STRIP, NEW_SESSION(time: Int) }
// PRESERVE   – keep the parsed suffix verbatim (sticky sid/time survive a
//              country swap — today's FloatingControl behavior)
// STRIP      – rebuild without suffix (Owl "unique")
// NEW_SESSION(t) – regenerate sid, write `_time_<t>` (Owl "sticky")

data class ProviderDef(
    val id: String,                 // stable id: "owl", "rapid", "clip", "generic", "custom"
    val label: String,              // display: "OwlProxy", ..., "Custom"
    val hostSuffixes: List<String> = emptyList(),
    val pattern: Regex? = null,     // full-username regex; named groups:
                                    //   (?<base>...) required
                                    //   (?<country>[A-Za-z]{2}) required
                                    //   (?<suffix>...) optional
    val case: CountryCase = CountryCase.UPPER,
    val embed: String? = null,      // template, placeholders below; null ⇒
                                    //   not rebuildable (custom)
    val features: Set<ProviderFeature> = emptySet(),
    val stickyTime: Regex? = null,  // e.g. "_time_(\\d+)" — parses time out
                                    //   of a PRESERVEd suffix when STICKY_TIME present
    val seed: ProfileSeed? = null,  // preset-profile row, §3.4
)

data class ProfileSeed(
    val providerId: String,
    val name: String,               // default profile name
    val host: String, val port: Int, val password: String = "",
    val username: String,           // sample username demonstrating the schema
)

data class ProviderIdentity(
    val provider: ProviderDef,
    val country: String?,           // extracted country, normalized UPPERCASE ISO; null if none
    val base: String,               // base segment; "" if no match
    val sep: String,                // generic's separator ("-"/"_"), else "-"
    val suffix: String,             // leftover after base+country (Owl sticky tail)
    val isSticky: Boolean,          // STICKY_TIME feature && suffix contains a session marker
    val stickyTime: Int?,           // parsed from suffix via def.stickyTime when isSticky
)
```

**Embed template placeholders** (the only four): `{base}`, `{cc}`, `{sep}`,
`suffix}`. `{cc}` is case-transformed per `def.case`; `{sep}` and `{suffix}`
come from the parse (defaults: `"-"`, `""`).

Built-in registry rows (`ProviderRegistry.kt` — the whole file is data):

| id | label | hostSuffixes | pattern | case | embed | features |
|---|---|---|---|---|---|---|
| owl | OwlProxy | `["owlproxy.com"]` | `^(?<base>.+?)_custom_zone_(?<country>[A-Za-z]{2})(?<suffix>.*)$` | LOWER | `{base}_custom_zone_{cc}{suffix}` | COUNTRY_PICKER, IP_MODE, STICKY_TIME |
| rapid | RapidProxy | `["rapidproxy.io"]` | `^(?<base>.+)-residential-(?<country>[A-Za-z]{2})(?<suffix>.*)$` | UPPER | `{base}-residential-{cc}` | COUNTRY_PICKER |
| clip | ClipProxy | `["cliproxy.io"]` | `^(?<base>.+)-region-(?<country>[A-Za-z]{2})(?<suffix>.*)$` | UPPER | `{base}-region-{cc}` | COUNTRY_PICKER |
| generic | Custom | — | `^(?<base>.+)(?<sep>[-_])(?<country>[A-Za-z]{2})$` | TOKEN | `{base}{sep}{cc}` | COUNTRY_PICKER |
| custom | Custom | — | — | — | null | — |

Owl `stickyTime = Regex("_time_(\\d+)")`. Registry order *is* the match
priority (owl → rapid → clip → generic → custom).

### 3.2 Methods

```kotlin
// catalog/ProviderCatalog.kt
class ProviderCatalog(private val registry: List<ProviderDef>) {

    fun describe(username: String, host: String): ProviderIdentity
    fun embedCountry(username: String, countryCode: String,
                     mode: EmbedMode = EmbedMode.PRESERVE): String?
    fun countries(): List<Countries.Country>
    fun providers(): List<ProviderDef>
    fun presetProfiles(): List<ProfileSeed>

    companion object { fun default(): ProviderCatalog }   // over ProviderRegistry.BUILTIN
}
```

### 3.3 Invariants, ordering, error modes, config, perf

**Matching (describe)**
- Registry order defines priority; first def that matches wins. Each def
  matches by host suffix (case-insensitive, host == suffix or ends with
  `.suffix`, matching today's `hostEndsWith`) OR by username pattern. Host
  checked first *within* a def — reproduces status quo exactly (an owl
  username beats a rapid host because owl precedes rapid in the registry).
- `generic` and `custom` have no host suffixes. `generic` matches only when
  its pattern matches **and** the country token is a real ISO code
  (`Countries.fromCode != null`) — today's `genericParts` validation.
- `custom` (no pattern, no hosts) always matches as the last resort. The
  registry must contain exactly one such sentinel, last.
- `describe` never throws; every input maps to some identity.

**embedCountry**
- Returns `null` iff: descriptor has no embed template (custom), or country
  is not a 2-letter real ISO code, or `mode == PRESERVE` with no pattern
  match (parity with today's `?: return` bail-outs).
- `Preserve` must reproduce an existing suffix **byte-for-byte**; `STRIP`
  drops it; `NEW_SESSION(t)` regenerates it (8-digit random sid, `_time_<t>`)
  — only for defs with STICKY_TIME; other defs ignore the mode.
- Case per `def.case`; `TOKEN` mirrors the case of the token being replaced
  (generic "acct_de" stays lowercase, "user-US" stays uppercase).
- **Round-trip invariants** (golden-tested, §7):
  1. `parse(embedCountry(u, cc, PRESERVE)).country == cc.uppercase()`
  2. `parse(embedCountry(u, cc, PRESERVE)).base == parse(u).base` (base and
     suffix survive a country swap)
  3. `parse(embedCountry(u, cc, STRIP)).suffix == ""` and
     `parse(embedCountry(u, cc, NEW_SESSION(t))).stickyTime == t`

**Construction-time validation (fail fast in tests, debug builds)**
- ids unique; at most one sentinel def, last in list; patterns expose
  `base` and `country` groups; embed templates reference only the four known
  placeholders; `STICKY_TIME ⇒ stickyTime != null`. `IllegalArgumentException`
  otherwise.

**Config**: none today (type is derived, never persisted — `saveProfile`
stores only host/port/username/password, so there is **no stored-data
migration**). Future user-defined providers plug in via the registry
construction site only.

**Perf**: regexes compiled once in the data; `describe` = up to 5 regex
probes; country validation = O(1) map; embed = one small substitution. Called
on keystrokes and re-compositions — trivially within budget (status quo does
the same work, plus recompiles regexes and re-branches in the caller).

### 3.4 presetProfiles

New capability (nothing like it exists): returns `def.seed` rows from the
registry. Consumers (a future "Add OwlProxy" entry point) pre-fill the Add
sheet from a seed. Honest scope: today this returns at most the owl seed (or
empty); the seed is a schema demonstration, not a host directory — no
provider gateway host database exists in the app and none is invented here.

---

## 4. Seam placement

- **The seam is the registry** — data (provider personalities) on one side,
  engine + consumers on the other. It is expressed as the `ProviderCatalog`
  constructor parameter.
- **Internal seam, not a public port.** Per seam discipline: consumers see
  the five methods; the constructor is the injection point. Today there is
  exactly **one production adapter** (the built-in Kotlin registry) plus the
  test adapter (fixture registries). Two adapters exist, but the second is
  tests, so the seam stays internal — do not promote it to a port until a
  second *production* source exists (asset/remote descriptor JSON, user-
  defined provider records). The single construction site (`default()`) is
  where that promotion will happen; no consumer change required then.
- **Two-adapters test on family question**: is there a second provider family
  today? **Not as a module.** `generic` is a distinct *rule family* (own
  regex, case rule, validation), but the whole point of the data model is
  that families collapse into *rows of one table* — generic is row 4, custom
  row 5. There is no competing implementation of "provider knowledge" that
  would deserve an interface; the port would today be pure indirection.

---

## 5. Depth analysis

Five entry points; leverage per entry:

| Entry | Replaces (status quo sites) | Leverage |
|---|---|---|
| `describe()` | `detectType`+`parseCountry`+`extractBase`+`genericParts`+`label`+`isOwl`, plus the 3 scattered regex copies and 3 membership lists; ~15 call sites across 6 files | **Highest.** One call yields label, country, base, sep, suffix, sticky state, time — everything every consumer derived by re-branching on a string |
| `embedCountry()` | `buildUsername` + `extractBase` + the two 20-line duplicated country-switch blocks (`FloatingControlService:867-886`, `CountriesScreen:130-147`) | **Highest.** Two duplicated switch statements plus four branch functions collapse to one call; a mode parameter covers both caller intents (swap-preserving vs explicit rebuild) |
| `countries()` | direct `Countries.ALL` fetches in 3 screens + delegation-free lookups | Medium — mostly delegation, but it makes "which countries are swappable" a single UI-facing source and leaves room for per-provider allowlists later |
| `providers()` | the `"owl"` string special-cases + three hardcoded type-membership lists in ProxiesScreen | Medium — the provider picker and feature-gating become enumerations of data |
| `presetProfiles()` | nothing (new) | Low today — the seed for future "add from provider" UX |

Depth verdict: the module holds *all* provider knowledge as data in one file
(`ProviderRegistry.kt`), the engine is one small generic brain
(`ProviderCatalog.kt`), and callers stop being mini-classifiers. **Locality**:
adding a provider today touches 6–10 code sites (4 functions + UI lists +
2 switch blocks); after — exactly 1 row in `ProviderRegistry.kt` (optionally
+1 seed). A teammate adding "ZipProxy" reads one file, copies the rapid row,
changes the strings.

---

## 6. Deletion test

After migration, delete without replacement:

- `util/ProxyProviders.kt` — entire file.
- `FloatingControlService.kt:849` (`supportsCountrySwitch`) → becomes
  `describe(...).provider.features.contains(COUNTRY_PICKER)`.
- `FloatingControlService.kt:867-886` switch block → one `embedCountry` call.
- `CountriesScreen.kt:130-147` switch block (the silent duplicate) → same
  one call; the two copies become *one* implementation by construction.
- `ProxiesScreen.kt` — `isOwl` call (`:220`), forced-owl `provider` param
  (`:268`), inline owl regex (`:287-299`), both membership lists
  (`:329-333`, `:576-580`), `TYPE_OWL` weight/IP-mode/time gates
  (`:602`, `:635`, `:681`) → identity.features + embedCountry.
- `ProxyCard.kt:62-66` three `remember` branches → one `describe`.
- `Utility.kt:218-221` → `describe(username, server).country`.

Every grep hit on `ProxyProviders`, `TYPE_*` and the inline regexes resolves
to a catalog call; nothing else in the repo references `util.ProxyProviders`.

---

## 7. Testability — descriptor-driven tests, golden pairs

Pure JVM unit tests, no Android deps in `catalog` (Countries is pure data,
flags are keyboard math) → plain `testDebugUnitTest`, no Robolectric; CI gets
a new step (none exists today — build.yml runs only `assemble`) appended
after `assembleDebug`.

**Descriptor-driven**: the fixture registry *is* the test fixture —
`ProviderCatalog(listOf(owlDef, genericDef))` tests engine behavior
independently of the built-in rows; a test can add a 6th throwaway descriptor
to prove "new provider = 1 data row, 0 engine changes" (the core claim of the
design, asserted as a test).

**Golden pairs** (behavior locked byte-for-byte against today's outputs —
the status quo is the oracle):

| username | host | expect id | expect country | base | suffix / notes |
|---|---|---|---|---|---|
| `user_custom_zone_us` | `x.owlproxy.com` | owl | US | `user` | unique |
| `user_custom_zone_us_st__city_sid_12345678_time_10` | `x.owlproxy.com` | owl | US | `user` | isSticky, stickyTime=10 |
| `a-residential-de` | `p.rapidproxy.io` | rapid | DE | `a` | |
| `b-region-jp` | `p.clipproxy.io` | clip | JP | `b` | |
| `user_custom_zone_fr` | `p.rapidproxy.io` | **rapid** (host wins over owl-username? no — owl first in registry, matches username) | FR | | ordering locked |
| `acct_de` | anything | generic | DE | `acct` | sep `_`, TOKEN lowercase |
| `user-US` | anything | generic | US | `user` | sep `-`, TOKEN uppercase |
| `user-XX` | anything | custom | null | | invalid ISO rejected |
| `plainuser` | anything | custom | null | | |

Embed pairs (round-trips per §3.3): sticky swap DE→GB keeps
`_st__city_sid_12345678_time_10`; STRIP returns unique form; NEW_SESSION(15)
has fresh 8-digit sid and `_time_15`; generic `acct_de`→`acct_gb` keeps
lowercase and `_`; invalid code → null; custom → null.

Rejected-input table: empty username, hostile regex input (`a_custom_zone_us_custom_zone_de` — greedy/lazy base ambiguity), overlong username — all return stable identities, no throw.

---

## 8. Internal seams (private to the module, not exposed)

- `matchFirst(registry, username, host): ProviderDef?` — ordering engine
- `parse(def, username): ParseResult?` — named-group extraction
- `resolveSuffix(def, parse, mode): String` — PRESERVE/STRIP/NEW_SESSION
- `applyTemplate(def, base, cc, sep, suffix): String` — substitution
- `validateRegistry(registry)` — construction-time checks

All tested only through the public five entries.

---

## 9. Migration sketch (behavior-preserving; the existing code is the oracle)

1. **Add** `catalog/` (types, built-in registry, engine) — new code, dead
   until wired; `validateRegistry` runs in unit tests immediately.
2. **Port consumers one commit at a time**, smallest first:
   `ProxyCard` → `describe`; `Utility.netshieldPolicy` → `describe`;
   `FloatingControlService.supportsCountrySwitch` → features check;
   `FloatingControlService.onBubbleCountrySelected` → `embedCountry`;
   `CountriesScreen.onCountryTap` → same; `ProxiesScreen` sheet (state =
   identity fields, `owlMode/owlTime` initialized from `isSticky/stickyTime`,
   sync = `embedCountry(…, mode)`).
3. **Golden-pair tests** written against the catalog while `ProxyProviders`
   still exists; the old object's outputs are recorded as expectations (they
   *are* the golden pairs). Any mismatch = migration bug, not test change.
4. **Delete** `util/ProxyProviders.kt` and every inline copy (§6). Orange
   crosses red; grep for `ProxyProviders|TYPE_` returns zero.
5. **CI**: add `testDebugUnitTest` step to `.github/workflows/build.yml`;
   keep `ProviderRegistry.kt` as the "add a provider" reference.

No stored-data migration: provider type is derived, never persisted.

---

## 10. Honest drawbacks

1. **Engine machinery vs 5 branches.** A template engine with named groups
   is more moving parts than the five `when` arms — provably worth it only if
   providers keep being added or the duplicates keep being maintained. The
   duplicates *already* exist (3 regex copies, 2 switch blocks), so the
   centralization earns its keep even with zero new providers.
2. **Templates are strings; typos surface at runtime.** Mitigated by
   `validateRegistry` (placeholder whitelist, group-name checks) and the
   golden pairs, which pin every row's behavior. Risk concentrated in one
   file, unlike today's risk spread across four.
3. **Convention burden.** Every future pattern must expose `base`/`country`
   named groups and embed templates must use the four-placeholder vocabulary.
   A provider with a fundamentally different rebuild shape (e.g., country in
   the *host*, or a second embedded field) does not fit — the documented
   escape hatch (a `rebuild: (EmbedContext) -> String?` override on
   `ProviderDef`) is deliberately not built now; it would keep the engine
   honest while letting the rare case be explicit code.
4. **Suffix-preservation is Owl-shaped knowledge wearing a generic costume.**
   `{suffix}` + `PRESERVE` exists for one provider; it is harmless for others
   (they never produce suffixes) but is a smell if the second STICKY provider
   differs.
5. **Interface creep over the brief's sketch**: `providers()` was added
   (the picker and feature gates genuinely enumerate providers) and
   `embedCountry` gained the `EmbedMode` parameter (two real caller intents,
   one entry point). Five entries, not four — each justified by a live caller.
6. **Registry injection is a test-only seam today.** Per seam discipline it
   stays an internal constructor, not a port; the discipline says don't make
   a port out of one production adapter — noted, and the doc names the event
   (second production registry source) that promotes it.
7. **`custom` returns null from `embedCountry`** — the sheet must keep its
   silent-return behavior for custom profiles (identical to today's `else ->
   return`; no new UI states).

---

## 11. Summary

- Cluster 4 is shallow-wide: `ProxyProviders` returns bare type strings and
  six consumer files re-implement the provider logic — 3 copies of the Owl
  regex, 3 membership lists, 2 near-identical 20-line country-switch blocks.
- Radical design: one `ProviderCatalog` class over an immutable
  `List<ProviderDef>` registry; every provider is pure data (id, label, host
  suffixes, named-group regex, case rule, embed template, features, seed),
  evaluated by one generic engine.
- Two sub-variants compared: pure function table (A) vs object with injected
  registry (B). **B wins** — same seam, but A threads the registry by hand
  through every call site while B changes one construction site.
- Interface: `describe` (rich identity incl. country/base/sticky),
  `embedCountry` (with PRESERVE/STRIP/NEW_SESSION modes), `countries`,
  `providers`, `presetProfiles`; never-throwing `describe`, null on
  non-rebuildable embed, round-trip invariants golden-tested.
- Registry is an **internal seam** (tests = second adapter); generic/custom
  collapse into registry rows, so no port is justified until a second
  production registry source lands.
- Deletion test passes: `ProxyProviders.kt`, both switch blocks, all inline
  regexes and membership lists die; BubbleMenuOverlay needs no change.
- Testability: pure JVM unit tests, descriptor-driven (throwaway 6th
  provider proves "add provider = add data"), golden pairs lock current
  behavior byte-for-byte.
- Migration: 5 commits, old code as oracle, then delete; no stored-data
  migration — the type is derived, never persisted; add a `testDebugUnitTest`
  CI step (none exists).
- Drawbacks: template engine is machinery for 5 rows, string-typo risk
  (validated at construction), named-group convention, suffix-preserve is
  Owl-shaped — all accepted against today's four-way duplication.

**File:** `C:\Users\Ratul\AppData\Local\Temp\opencode\kiloproxy-design\cluster-4-proxy-catalog.md`