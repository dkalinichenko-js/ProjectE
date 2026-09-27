// Runs only in the isolated ATM10 Market Test instance and its fresh test world.
var $MarketService = Java.loadClass('moze_intel.projecte.market.MarketService');
var $PECapabilities = Java.loadClass('moze_intel.projecte.api.capabilities.PECapabilities');
var $ItemInfo = Java.loadClass('moze_intel.projecte.api.ItemInfo');
var $ItemStack = Java.loadClass('net.minecraft.world.item.ItemStack');
var $Items = Java.loadClass('net.minecraft.world.item.Items');
var $BigDecimal = Java.loadClass('java.math.BigDecimal');
var $TransmutationInventory = Java.loadClass('moze_intel.projecte.gameObjs.container.inventory.TransmutationInventory');
var $TransmutationContainer = Java.loadClass('moze_intel.projecte.gameObjs.container.TransmutationContainer');
var $SlotConsume = Java.loadClass('moze_intel.projecte.gameObjs.container.slots.transmutation.SlotConsume');
var $SlotOutput = Java.loadClass('moze_intel.projecte.gameObjs.container.slots.transmutation.SlotOutput');
var $DynamicMarketMath = Java.loadClass('moze_intel.projecte.market.math.DynamicMarketMath');
var $ClickType = Java.loadClass('net.minecraft.world.inventory.ClickType');
var $Registries = Java.loadClass('net.minecraft.core.registries.BuiltInRegistries');

function marketTestIsDiamond(stack) {
  return !stack.isEmpty() && String(stack.getItem()) === 'minecraft:diamond';
}

function marketTestCheck(condition, message) {
  if (!condition) {
    JsonIO.write('kubejs/market-test-result.json', {status: 'FAIL', message: message});
    throw new Error('ATM10 market test: ' + message);
  }
}

function marketInventory(server) {
  return Number($MarketService.inspect(server, 'minecraft:diamond').get('inventory'));
}

ServerEvents.loaded(event => {
  var server = event.server;
  var diamond = $MarketService.inspect(server, 'minecraft:diamond');
  var block = $MarketService.inspect(server, 'minecraft:diamond_block');
  marketTestCheck($MarketService.enabled(server), 'market disabled');
  marketTestCheck(String(diamond.get('exchangeable')) === 'true', 'diamond should be whitelisted');
  marketTestCheck(String(block.get('exchangeable')) === 'false', 'derived diamond block should be outside whitelist');
  marketTestCheck(block.get('prices') != null, 'diamond block recipe valuation missing');
  marketTestCheck(diamond.get('posteriorWeights') != null, 'posterior debug weights missing');
  var osmium = $MarketService.inspect(server, 'alltheores:raw_osmium');
  var iron = $MarketService.inspect(server, 'minecraft:iron_ingot');
  marketTestCheck(osmium.get('prices') != null, 'mod raw osmium valuation missing');
  marketTestCheck(iron.get('prices') != null, 'iron ingot recipe valuation missing');
  var scale = Number(diamond.get('scale'));
  marketTestCheck(Number.isFinite(scale) && scale > 0, 'commodity index scale invalid');
  marketTestCheck(Number(diamond.get('indexTarget')) === 256, 'wrong index target');
  marketTestCheck(diamond.get('indexBasket').containsKey('minecraft:diamond'), 'diamond missing from index basket');
  marketTestCheck(Math.abs(Number(diamond.get('bid')) / Number(diamond.get('realBid')) - scale) < 1e-9,
    'nominal bid does not use the index scale');
  console.info('[ATM10 market test] valuations=' + $MarketService.dump(server).size()
    + ', osmium provenance=' + String(osmium.get('provenance'))
    + ', iron provenance=' + String(iron.get('provenance')));
  console.info('[ATM10 market test] world loaded, diamond q=' + marketInventory(server));
});

PlayerEvents.loggedIn(event => {
  try {
  var player = event.player;
  var server = player.getServer();
  var diamond = new $ItemStack($Items.DIAMOND);
  var dirt = new $ItemStack($Items.DIRT);
  marketTestCheck($MarketService.canExchange(player, diamond), 'diamond cannot exchange');
  marketTestCheck(!$MarketService.canExchange(player, dirt), 'dirt blacklist or whitelist failed');
  var phase = JsonIO.read('kubejs/market-test-phase.json');

  if (phase == null) {
    marketTestCheck(marketInventory(server) === 0, 'new world should start at q=0');
    var initialScale = Number($MarketService.inspect(server, 'minecraft:diamond').get('scale'));
    var one = $MarketService.quote(player, diamond, 1, false);
    var two = $MarketService.quote(player, diamond, 2, false);
    marketTestCheck(one != null && one.signum() > 0 && two.compareTo(one) >= 0, 'invalid sell quotes');
    marketTestCheck(one.compareTo($BigDecimal.valueOf(0.016)) === 0, 'subunit root bid was rounded');
    var inventory = new $TransmutationInventory(player);
    var beforeEmc = inventory.getMarketAvailableEmc();
    var consume = new $SlotConsume(inventory, 9, 0, 0);
    marketTestCheck(consume.mayPlace(diamond), 'SlotConsume refused diamond');
    consume.set(diamond);
    marketTestCheck(marketInventory(server) === 1, 'SlotConsume sale did not update q');
    marketTestCheck(inventory.getMarketAvailableEmc().subtract(beforeEmc).compareTo(one) === 0,
      'SlotConsume sale credited wrong fractional EMC');
    marketTestCheck($MarketService.fraction(player).compareTo($BigDecimal.valueOf(0.016)) === 0,
      'fractional EMC remainder missing after subunit sale');
    marketTestCheck(inventory.provider.hasExplicitKnowledge(diamond), 'sale did not discover diamond');
    var reverseAsk = $MarketService.quote(player, diamond, 1, true);
    marketTestCheck(reverseAsk.compareTo(one) >= 0, 'immediate reversal would profit');
    var shiftedScale = Number($MarketService.inspect(server, 'minecraft:diamond').get('scale'));
    marketTestCheck(shiftedScale !== initialScale, 'commodity index did not react to diamond inventory');
    JsonIO.write('kubejs/market-test-phase.json', {phase: 1, sell: String(one), ask: String(reverseAsk), initialScale: initialScale});
    JsonIO.write('kubejs/market-test-result.json', {status: 'PHASE1_PASS', q: 1, sell: String(one),
      ask: String(reverseAsk), initialScale: initialScale, shiftedScale: shiftedScale});
    console.info('[ATM10 market test] phase 1 passed; save and fully reload the world for persistence test');
    return;
  }

  if (Number(phase.phase) === 1) {
    marketTestCheck(marketInventory(server) === 1, 'q did not persist across world reload');
    var inventory = new $TransmutationInventory(player);
    marketTestCheck(inventory.provider.hasExplicitKnowledge(diamond), 'discovery did not persist');
    marketTestCheck($MarketService.fraction(player).compareTo($BigDecimal.valueOf(0.016)) === 0,
      'fractional EMC remainder did not persist');
    var hadFullKnowledge = inventory.provider.hasFullKnowledge();
    inventory.provider.removeExplicitKnowledge($ItemInfo.fromStack(diamond));
    inventory.provider.setFullKnowledge(true);
    marketTestCheck(inventory.provider.hasKnowledge(diamond), 'Tome knowledge flag did not apply');
    marketTestCheck(!inventory.provider.hasExplicitKnowledge(diamond), 'Tome flag bypassed explicit discovery');
    marketTestCheck($MarketService.trade(player, diamond, 1, true) == null,
      'Tome flag improperly permitted purchase without discovery');
    marketTestCheck(marketInventory(server) === 1, 'rejected purchase changed q');
    inventory.provider.addExplicitKnowledge($ItemInfo.fromStack(diamond));
    inventory.provider.setFullKnowledge(hadFullKnowledge);
    inventory.addMarketEmc($BigDecimal.valueOf(1000));
    inventory.outputs.setStackInSlot(0, diamond);
    var beforeEmc = inventory.getMarketAvailableEmc();
    var expectedAsk = $MarketService.quote(player, diamond, 1, true);
    var output = new $SlotOutput(inventory, 11, 0, 0);
    marketTestCheck(output.mayPickup(player), 'SlotOutput refused affordable diamond');
    var taken = output.remove(1);
    marketTestCheck(taken.getCount() === 1, 'SlotOutput did not return one diamond');
    marketTestCheck(marketInventory(server) === 0, 'SlotOutput purchase did not restore q=0');
    marketTestCheck(beforeEmc.subtract(inventory.getMarketAvailableEmc()).compareTo(expectedAsk) === 0,
      'SlotOutput charged wrong fractional EMC');
    marketTestCheck(Math.abs(Number($MarketService.inspect(server, 'minecraft:diamond').get('scale'))
      - Number(phase.initialScale)) < 1e-9, 'index scale did not restore at q=0');

    for (var i = 0; i < 36; i++) {
      if (marketTestIsDiamond(player.getInventory().getItem(i))) {
        player.getInventory().setItem(i, $ItemStack.EMPTY);
      }
    }
    player.getInventory().setItem(9, $ItemStack.EMPTY);
    var container = new $TransmutationContainer(2, player.getInventory());
    container.transmutationInventory.addMarketEmc($BigDecimal.valueOf(2000));
    container.transmutationInventory.outputs.setStackInSlot(0, diamond);
    var preMoveEmc = container.transmutationInventory.getMarketAvailableEmc();
    var preMoveQ = marketInventory(server);
    container.quickMoveStack(player, 11);
    var moved = preMoveQ - marketInventory(server);
    marketTestCheck(moved > 0, 'quickMoveStack bought no diamonds');
    var boughtSlots = [];
    for (var i = 0; i < 36; i++) {
      var held = player.getInventory().getItem(i);
      if (!held.isEmpty()) {
        var registryId;
        try { registryId = String($Registries.ITEM.getKey(held.getItem())); }
        catch (error) { registryId = 'lookup-error'; }
        boughtSlots.push(i + ':item=' + String(held.getItem()) + ':registry=' + registryId
          + ':count=' + held.getCount());
      }
    }
    console.info('[ATM10 market test] shift-buy moved=' + moved + ', inventory=' + boughtSlots.join(','));
    var state = $MarketService.inspect(server, 'minecraft:diamond');
    var model = new $DynamicMarketMath(state.get('prices'), state.get('priorWeights'), Number(state.get('liquidity')));
    var expectedMoveCost = model.quoteBuyReal(preMoveQ, moved);
    marketTestCheck(preMoveEmc.subtract(container.transmutationInventory.getMarketAvailableEmc()).compareTo(expectedMoveCost) === 0,
      'quickMoveStack charged the wrong slippage total');

    var beforeShiftSellEmc = container.transmutationInventory.getMarketAvailableEmc();
    var expectedShiftSell = model.quoteSellReal(marketInventory(server), moved);
    var shiftedSold = 0;
    for (var i = 0; i < 36; i++) {
      var stack = player.getInventory().getItem(i);
      if (marketTestIsDiamond(stack)) {
        var count = stack.getCount();
        var menuSlot = i < 9 ? 54 + i : 27 + i - 9;
        container.quickMoveStack(player, menuSlot);
        shiftedSold += count;
        marketTestCheck(player.getInventory().getItem(i).isEmpty(), 'shift-sell left sold items in inventory');
      }
    }
    marketTestCheck(shiftedSold === moved,
      'shift-sell did not consume purchased quantity: moved=' + moved + ', sold=' + shiftedSold
      + ', q=' + marketInventory(server) + ', inventory=' + boughtSlots.join(','));
    marketTestCheck(marketInventory(server) === 0, 'shift-sell did not restore q=0');
    marketTestCheck(container.transmutationInventory.getMarketAvailableEmc().subtract(beforeShiftSellEmc).compareTo(expectedShiftSell) === 0,
      'shift-sell credited the wrong EMC');

    container.transmutationInventory.addMarketEmc($BigDecimal.valueOf(1000));
    var beforeClickEmc = container.transmutationInventory.getMarketAvailableEmc();
    var clickAsk = $MarketService.quote(player, diamond, 1, true);
    container.clicked(11, 0, $ClickType.PICKUP, player);
    var carried = container.getCarried().copy();
    marketTestCheck(marketTestIsDiamond(carried) && carried.getCount() === 1, 'manual click did not carry one diamond');
    marketTestCheck(marketInventory(server) === -1, 'manual click did not buy one unit');
    marketTestCheck(beforeClickEmc.subtract(container.transmutationInventory.getMarketAvailableEmc()).compareTo(clickAsk) === 0,
      'manual click charged wrong EMC');
    container.setCarried($ItemStack.EMPTY);
    new $SlotConsume(container.transmutationInventory, 9, 0, 0).set(carried);
    marketTestCheck(marketInventory(server) === 0, 'manual click reversal did not restore q');

    player.getInventory().setItem(0, $ItemStack.EMPTY);
    container.transmutationInventory.addMarketEmc($BigDecimal.valueOf(1000));
    var beforeSwapEmc = container.transmutationInventory.getMarketAvailableEmc();
    var swapAsk = $MarketService.quote(player, diamond, 1, true);
    container.clicked(11, 0, $ClickType.SWAP, player);
    marketTestCheck(marketTestIsDiamond(player.getInventory().getItem(0)), 'SWAP did not place diamond in hotbar');
    marketTestCheck(marketInventory(server) === -1, 'SWAP did not buy one unit');
    marketTestCheck(beforeSwapEmc.subtract(container.transmutationInventory.getMarketAvailableEmc()).compareTo(swapAsk) === 0,
      'SWAP charged wrong EMC');
    container.quickMoveStack(player, 54);
    marketTestCheck(player.getInventory().getItem(0).isEmpty() && marketInventory(server) === 0,
      'SWAP reversal did not consume item or restore q');
    JsonIO.write('kubejs/market-test-phase.json', {phase: 2});
    JsonIO.write('kubejs/market-test-result.json', {status: 'PASS', slotOutputAsk: String(expectedAsk),
      quickMoveUnits: moved, quickMoveAsk: String(expectedMoveCost), shiftSellBid: String(expectedShiftSell),
      manualClickAsk: String(clickAsk), swapAsk: String(swapAsk)});
    console.info('[ATM10 market test] all phases passed');
  }
  } catch (error) {
    JsonIO.write('kubejs/market-test-result.json', {status: 'FAIL', message: String(error)});
    console.error('[ATM10 market test] ' + String(error));
    throw error;
  }
});
