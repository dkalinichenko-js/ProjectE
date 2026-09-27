# Dynamic EMC market implementation

This experimental fork starts from upstream `mc1.21.1` commit `f432b0c66837759fb0731c9144dc53176b949c5d` and targets Minecraft 1.21.1 / NeoForge and replaces the base ProjectE table/tablet exchange with a shared inventory market. Three Sol workstreams implemented and reviewed the economic core, valuation, transmutation integration, and ATM10 test harness.

## Implemented

- Root distributions from natural-resource estimates, common ore/raw-material tags, and loaded ore-feature opportunity data; bounded recursive crafting/cooking/stonecutting valuations with recipe provenance, alternative routes, cycle rejection and conservative handling of remainders/components.
- Global per-item net inventory and frozen priors persisted in world SavedData. Posterior weights and bid/ask depend on current inventory, never gross trade volume or elapsed time.
- Exact decimal finite-order settlement along deterministic unit edges. Purchases and sales have distinct quotes; splitting an order preserves its total; reversal cannot profit through quote rounding.
- Explicit discovery separate from Tome full-knowledge flags; configurable whitelist/blacklist; default-component items only; server-authoritative buy, sell, shift-click and hotbar-swap paths.
- Fixed-weight commodity index and one global display scale. Real balances are invariant under redenomination; fractional real EMC is saved separately from legacy whole-EMC storage. Restoring the complete inventory vector restores the scale. There is no history-dependent smoothing.
- Client state synchronization, indexed unit/stack quotes and balances, and `market inspect`, `market balance`, and `market dump` debug commands.

Artifact: `projecte-1.1.0-market.2.jar`, SHA-256 `c3e473ef2b6ffa7aa2900f620ce1d8c1afd25228488f33b538fe95f112eb0ea1`.

See [design and configuration](MARKET_DESIGN.md) for formulas, configuration, and supported scope.

## Build verification

`./gradlew test build` passes with Java 21. The suite contains 97 tests, with zero failures, errors or skips. It covers existing ProjectE behavior plus market path/round-trip properties, fractional quotes, extreme inputs, recipe alternatives/output counts/cycles/remainders, explicit Tome discovery, persisted inventory/priors/fractions/index settings, index ratios and restored denomination.

## ATM10 integration

The test profile is an isolated copy of the user’s ATM10 8.1 instance (Minecraft 1.21.1, NeoForge 21.1.249), with the final fork jar and a disposable copied world. Initialization produced **7,463 item valuations**, including an AllTheOres raw-osmium root with placed-feature provenance and recipe-derived iron ingots.

The final jar passed the two-phase runtime harness, including a full client/server restart from saved market state:

- A one-item sale credited exactly **0.016 real EMC**, saved a **0.016** fractional remainder, recorded discovery, and moved inventory from 0 to 1.
- The commodity scale changed from **4000.000000000001** to **4691.015270891784**. Inventory, discovery, and fraction survived restart; buying the unit back for **0.256** restored inventory 0 and the original scale.
- A Tome full-knowledge flag did not permit a purchase without explicit discovery. The configured whitelist excluded derived diamond blocks and rejected dirt while leaving their valuation/debug data inspectable where available.
- Shift-buy produced **64 physical diamonds** and charged **16.384** real EMC. Shift-sell consumed exactly those 64 items, credited **13.648**, and restored inventory 0. Ordinary pickup and hotbar swap each charged **0.256** and their sale reversals also restored inventory 0.
- The test reached **PASS** at 15:48:25 EDT on 2026-09-27. Earlier harness-only Rhino interop/scoping failures were corrected; the final run uses the artifact hash above.

See [runtime fixtures and results](testing/README.md). The test configuration deliberately uses a diamond-only whitelist and fractional prices to stress accounting; it is not a recommended gameplay balance configuration.

The existing ProjectExpansion addon is disabled only in the copy because its independent fixed-price exchange bypasses this market. An unrelated local `mct-client-mod` is also disabled only in the copy after its client-world initialization exception prevented joining. Runtime checks invoke actual server container and slot methods through KubeJS; they do not constitute visual inspection of the tooltip layout.

The final protected live-instance comparison found **zero changes across 2,392 files**: 2,388 configuration files, two ProjectE-related jars, and the two launcher instance metadata files. Worlds are read/copied for testing, not edited in the live instance; this baseline is not a full-world checksum audit. The isolated client is stopped and the test autorun script is disabled after verification.

## Limits

- Stock ProjectExpansion implements independent static-price exchange paths and is unsupported. It was disabled for the original market tests described above. A subsequent [compatibility fork](https://github.com/dkalinichenko-js/ProjectExpansion/tree/dynamic-market-compat) retains the addon with selective exchange guards; its own report records that validation. Base ProjectE condensers pause in market mode to prevent their fixed-price route bypassing the market; their contents are retained.
- Arbitrary mod-machine recipes need adapters or root overrides. Worldgen estimates use nominal vein size/count, not measured extraction yield or progression detection.
- The index stabilizes its basket, not a minimum price for every item. Tiny positive amounts retain decimal accounting and engineering notation; no per-item floor distorts their ratios.
- Legacy integer EMC APIs remain in real units. Addons must use the market API for exchange and explicitly convert display units; unchanged third-party interfaces are not implicitly supported.
- Trade settlement runs serially on the server thread. It does not provide a database-style rollback if an external EMC-holder capability throws during settlement.
- This is an experimental fork, not a claim of complete economic balance across all ATM10 automation or addon paths.
