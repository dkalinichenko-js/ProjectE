# Dynamic EMC market

The market is enabled by default in this experimental fork (`enabled: false` restores legacy exchange). It replaces fixed EMC exchange prices in ProjectE's base transmutation table and tablet. It keeps a separate global inventory displacement for each priced item. A positive displacement means players have sold more of that item to the exchange than they have bought from it. The inventory is a pricing state, not a physical stock limit: the table can still make an item when its buy order is affordable.

## Initial valuations

On first initialization of a world, the market builds a finite price distribution for each supported item. Natural resources are roots. Vanilla natural resources have broad estimated ranges; items in `c:ores` and `c:raw_materials` receive material estimates where possible. Registered placed ore features supply a bounded scarcity adjustment from their configured vein size and count. This is an *opportunity signal*, not an observed yield: dimension, biome, height, exposure, replacement and processing methods affect actual acquisition cost. The initial figures set a relative EMC scale and are tunable estimates.

Crafting, smelting, blasting, smoking, campfire cooking and stonecutting recipes propagate these root valuations. Ingredient costs are summed and divided by output count. Alternative routes supply distinct price hypotheses, capped to the six cheapest supported routes to keep computation bounded. Cyclic routes, special recipes, ingredients with reusable containers, component-dependent outputs and unsupported processing types are omitted. An item with no supported root or recipe route has no market quote unless explicitly configured. Valuation provenance names root estimates, worldgen features and recipe IDs and is visible in `/projecte market inspect`.

`rootPriors` replaces a root distribution before recipe propagation for a newly initialized world. For example, this `config/ProjectE/market.json` supplies a narrower iron prior and restricts exchange to two items:

```json
{
  "enabled": true,
  "liquidityScale": 4096,
  "maxOrder": 4096,
  "indexTarget": 256,
  "indexBasket": {
    "minecraft:raw_iron": 1.0,
    "minecraft:raw_copper": 1.0,
    "minecraft:coal": 1.0,
    "minecraft:oak_log": 1.0,
    "minecraft:redstone": 1.0
  },
  "whitelist": ["minecraft:raw_iron", "minecraft:iron_ingot"],
  "blacklist": [],
  "rootPriors": {
    "minecraft:raw_iron": [130, 260, 520]
  }
}
```

An empty whitelist permits any item with a valuation unless blacklisted. A nonempty whitelist permits only listed IDs. The config is loaded when the server starts. Prior distributions, liquidity, index basket and index target are saved with the world when its market is first initialized; editing them later does not reprice an existing world or reset its inventory. Make a world backup before deliberately changing saved market state. Invalid config fails startup rather than silently changing the economy.

## Inventory pricing

For item-specific support prices \(p_r>0\), prior weights \(w_r\), current net inventory \(q\), and liquidity scale \(L\), the current weights are

\[
  w_r(q)=\frac{w_r p_r^{-q/L}}{\sum_s w_s p_s^{-q/L}}.
\]

Selling to the table increases \(q\), shifting weight toward lower price hypotheses. Buying decreases \(q\), shifting weight toward higher ones. Larger `liquidityScale` makes prices less sensitive to each unit. At each unit boundary, the sell price is the weighted 10th percentile (bid), and the buy price is the weighted 90th percentile (ask). The market evaluates both directions at the same half-unit boundary. A multi-item order crosses successive boundaries, so its real total is the sum of its per-unit prices and includes slippage. Real totals retain fractional EMC in a saved player remainder between 0 and 1; the existing whole-EMC balance and EMC holders remain usable. `maxOrder` caps a single trade.

The UI expresses all market quotes and balances in **indexed EMC**. Each basket commodity's central price is the geometric mean of its support prices under current posterior weights, \(c_i(q)=\exp[\sum_r w_{ir}(q)\log p_{ir}]\). For configured basket weights \(a_i\), the index is their weighted geometric mean \(I(q)=\exp[\sum_i a_i\log c_i(q)/\sum_i a_i]\). The common display scale is \(S(q)=\texttt{indexTarget}/I(q)\). A real quote or balance \(x\) appears as \(S(q)x\) indexed EMC. The basket has fixed weights and no moving-average history, so restoring the same inventory positions restores the same index and displayed quotes. The index sets a reference scale for the basket; it does not guarantee every item costs at least one indexed EMC. Tiny positive values use scientific or engineering notation rather than a per-item floor.

The index is a display denomination only. No scaled or rounded value settles a trade. ProjectE's client-side EMC text formatter also shows indexed values where it can, but `long`-valued EMC APIs and stored balances retain real units for compatibility. Addons that use those APIs for actual exchange logic need their own market integration; a changed tooltip alone does not make an exchange path dynamic.

The pricing state is just the saved prior and current \(q\). Therefore a reversible sequence such as selling 64 iron and buying 64 iron restores the *same posterior weights and real quotes* for that item. Restoring the complete inventory vector also restores the index, denomination scale, and nominal quotes; other basket commodities retaining changed positions can legitimately change the nominal scale. It does not restore the same real EMC balance: the bid/ask spread charges for the round trip. This difference makes reversible wash trading costly while preventing it from permanently moving price or confidence. The invariant is about market state and future quotes, not cash flow.

## Exchange rules and scope

Buying still requires that the player explicitly learned the item. Selling an eligible item records that discovery. Existing Tome full-knowledge flags do not authorize new market purchases; the market uses the stored explicit discoveries. Only default-component items can be exchanged. Items with modified data components and items holding EMC are excluded to prevent component and stored-value arbitrage. EMC holders in the table's input slots still charge and fund the player balance through ProjectE's existing mechanisms. Root ores and raw materials can have market quotes even if an installed EMC mapper gives them zero static EMC.

The base transmutation GUI shows a one-item quote and a full-stack or held-stack total. The server recomputes every quote before a click, shift-click or hotbar swap and performs the market inventory change followed by the EMC debit or credit on the server thread. The client receives market snapshots for display and search; the server owns the saved state and validates purchases against explicit knowledge and available EMC, including EMC stored in input-slot holders.

ProjectE condensers are paused while market mode is enabled, preserving their contents because their fixed-EMC conversion path would bypass inventory pricing. Other mods that perform EMC exchange through their own containers or blocks need separate integration. In particular, stock ProjectExpansion has fixed-EMC paths and is **unsupported in market mode**. The [ProjectExpansion compatibility fork](https://github.com/dkalinichenko-js/ProjectExpansion/tree/dynamic-market-compat) keeps its blocks/items registered, redirects Arcane tablets to the base market UI, and selectively pauses conflicting exchanges while retaining generation, storage and EMC intake. Use that fork when retaining ProjectExpansion; its compatibility report describes the exact scope.

## Inspection

- `/projecte market inspect` shows the item in the player's main hand.
- `/projecte market inspect "minecraft:raw_iron"` shows a named item. The quotes around IDs containing `:` are required by the command parser.
- `/projecte market balance` shows the player's real available EMC, indexed display balance and current denomination scale.
- `/projecte market dump` (operator permission) exports all valuations, current inventory positions, bids, asks, weights and provenance to `config/ProjectE/market-debug.json`.

The implementation entry points are `MarketConfig` for config validation, `MarketValuation` for root and recipe priors, `DynamicMarketMath` for posterior and finite-order quotes, `MarketService` for saved state and server trades, and the base transmutation inventory/container/slots for discovery and exchange enforcement.
