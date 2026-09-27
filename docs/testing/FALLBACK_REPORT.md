# Legacy EMC fallback verification

Market.3 adds missing market priors for positive static-EMC items, including existing initialized worlds. The default support is `[0.75v, v, 1.25v]`, with equal weights, zero initial inventory, and explicit fallback provenance. Existing priors, inventory, denomination basket, fractional balances, and discovery remain intact. Whitelist, component, EMC-holder, and explicit-discovery checks still apply.

## Build and regression tests

Java 21 `./gradlew test build` passed all 101 tests with zero failures, errors, or skips. Added tests exercise persisted-world backfill, non-destructive/idempotent behavior, default spread, nonprofitable round trips, large static values, invalid spreads, and defaults when older JSON omits the new settings.

Runtime artifact: `projecte-1.1.0-market.3.jar`, SHA-256 `f5fc4a05224ea0b18b037ed3b80e174777639d8b2c78f72055e9096be4ac272c`. A subsequent build after adding the configuration test produced a different ZIP hash, but comparison of every archive entry confirmed identical contents; the exact runtime-tested archive is used for installation.

## ATM10 integration

The isolated ATM10 8.1 copy uses Minecraft 1.21.1, NeoForge 21.1.249, and the ProjectExpansion 1.0.6 market compatibility fork. Its already initialized world received **42,966 fallback priors** after legacy EMC mapping. The existing configuration deliberately omitted both new fallback settings to test migration defaults.

The first five fixture attempts encountered KubeJS-only array and overloaded-method interoperability restrictions before a trade; they are not successful trade tests. The corrected fixture is retained alongside the final result. Runtime checks use real server inventory/slot methods, not visual UI inspection.

The final fixture **passed at 17:03:47 EDT on 2026-09-27**. It found 42,966 persisted fallback entries and selected `actuallyadditions:advanced_coil` (static EMC 2,084). The real bid was **1,563** and ask **2,605**. Actual `SlotConsume` credited exactly the bid and moved inventory from 0 to 1; actual `SlotOutput` produced the item, debited exactly the ask, and restored inventory to 0. Existing diamond bid, ask, inventory, and provenance remained unchanged. See [the fixture](fallback-test.js) and [recorded result](fallback-test-result.json). Discovery-specific Tome behavior remains covered by the earlier market integration suite; this fixture verifies the fallback transaction path.

## Main-instance installation

Installed the exact runtime archive in the closed main ATM10 instance on 2026-09-27 at 16:59 EDT, replacing the active market.2 jar while retaining it disabled. `market.json` now explicitly enables `legacyEmcFallback` with `fallbackSpread: 0.25`. The ProjectExpansion compatibility jar remains active and its checksum was verified unchanged. The update applies when Minecraft next launches; no world reset is required.

Before installation, saves, ProjectE configuration, and the previous jar were backed up under the instance's `market-install-backups/fallback-20260927-165922`. The installer verifies the main client is stopped and checks the installed artifact hash. The main world itself has not been launched for this update's verification; runtime evidence comes from the isolated copy.
