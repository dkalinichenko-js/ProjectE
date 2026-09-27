// Run only in the disposable ATM10-Market clone with a copied test player.
// The clone config must enable legacyEmcFallback and use an empty whitelist.
var $Market = Java.loadClass('moze_intel.projecte.market.MarketService');
var $EMC = Java.loadClass('moze_intel.projecte.api.proxy.IEMCProxy');
var $Registry = Java.loadClass('net.minecraft.core.registries.BuiltInRegistries');
var $ResourceLocation = Java.loadClass('net.minecraft.resources.ResourceLocation');
var $ItemStack = Java.loadClass('net.minecraft.world.item.ItemStack');
var $ItemInfo = Java.loadClass('moze_intel.projecte.api.ItemInfo');
var $TransmutationInventory = Java.loadClass('moze_intel.projecte.gameObjs.container.inventory.TransmutationInventory');
var $SlotConsume = Java.loadClass('moze_intel.projecte.gameObjs.container.slots.transmutation.SlotConsume');
var $SlotOutput = Java.loadClass('moze_intel.projecte.gameObjs.container.slots.transmutation.SlotOutput');
var $BigDecimal = Java.loadClass('java.math.BigDecimal');

function fallbackCheck(condition, message) {
  if (!condition) {
    JsonIO.write('kubejs/fallback-test-result.json', {status: 'FAIL', message: message});
    throw new Error('ATM10 fallback test: ' + message);
  }
}

function fallbackNear(actual, expected) {
  return Number.isFinite(actual) && Math.abs(actual - expected) <= Math.max(1e-8, Math.abs(expected) * 1e-9);
}

PlayerEvents.loggedIn(event => {
  JsonIO.write('kubejs/fallback-test-result.json', {status: 'RUNNING'});
  var player = event.player;
  var server = player.getServer();
  fallbackCheck($Market.enabled(server), 'market disabled');
  var diamondBefore = $Market.inspect(server, 'minecraft:diamond');
  var diamondQ = Number(diamondBefore.get('inventory'));
  var diamondBid = Number(diamondBefore.get('realBid'));
  var diamondAsk = Number(diamondBefore.get('realAsk'));
  var diamondProvenance = String(diamondBefore.get('provenance'));
  fallbackCheck(diamondProvenance.includes('config override') && diamondBid > 0 && diamondAsk >= diamondBid,
    'saved diamond prior changed');

  var all = $Market.dump(server);
  var iterator = all.entrySet().iterator();
  var fallbackCount = 0;
  var sampleId = null;
  var sampleStack = null;
  var staticEmc = 0;
  while (iterator.hasNext()) {
    var entry = iterator.next();
    var id = String(entry.getKey());
    var state = entry.getValue();
    if (!String(state.get('provenance')).startsWith('legacy EMC fallback')) continue;
    fallbackCount++;
    if (sampleId != null || Number(state.get('inventory')) !== 0 || id === 'minecraft:diamond') continue;
    var item = $Registry.ITEM.get($ResourceLocation.parse(id));
    var stack = new $ItemStack(item);
    var emc = Number($EMC.INSTANCE.applyAsLong($ItemInfo.fromStack(stack)));
    if (emc <= 0 || emc > 10000 || !$Market.canExchange(player, stack)) continue;
    sampleId = id;
    sampleStack = stack;
    staticEmc = emc;
  }
  fallbackCheck(fallbackCount > 0, 'no fallback entries after EMC mapping');
  fallbackCheck(sampleId != null, 'no exchangeable fallback sample; empty whitelist is required');
  var sample = $Market.inspect(server, sampleId);
  fallbackCheck(Number(sample.get('inventory')) === 0, 'new fallback must begin at q=0');
  fallbackCheck(fallbackNear(Number(sample.get('realBid')), 0.75 * staticEmc)
    && fallbackNear(Number(sample.get('realAsk')), 1.25 * staticEmc), 'fallback q=0 bid/ask wrong');

  var inventory = new $TransmutationInventory(player);
  var sellBid = $Market.quote(player, sampleStack, 1, false);
  var emcBeforeSale = inventory.getMarketAvailableEmc();
  var consume = new $SlotConsume(inventory, 9, 0, 0);
  fallbackCheck(consume.mayPlace(sampleStack), 'SlotConsume refused fallback item');
  consume.set(sampleStack);
  fallbackCheck(Number($Market.inspect(server, sampleId).get('inventory')) === 1,
    'SlotConsume did not move fallback q');
  fallbackCheck(inventory.getMarketAvailableEmc().subtract(emcBeforeSale).compareTo(sellBid) === 0,
    'SlotConsume credited wrong bid');
  var buyAsk = $Market.quote(player, sampleStack, 1, true);
  fallbackCheck(buyAsk.compareTo(sellBid) >= 0, 'fallback round trip profits');
  inventory.addMarketEmc(buyAsk.add($BigDecimal.ONE));
  inventory.outputs.setStackInSlot(0, sampleStack);
  var emcBeforeBuy = inventory.getMarketAvailableEmc();
  var output = new $SlotOutput(inventory, 11, 0, 0);
  fallbackCheck(output.mayPickup(player), 'SlotOutput refused discovered funded item');
  var bought = output.remove(1);
  fallbackCheck(!bought.isEmpty() && bought.getCount() === 1, 'SlotOutput returned no item');
  fallbackCheck(Number($Market.inspect(server, sampleId).get('inventory')) === 0,
    'SlotOutput did not restore fallback q');
  fallbackCheck(emcBeforeBuy.subtract(inventory.getMarketAvailableEmc()).compareTo(buyAsk) === 0,
    'SlotOutput debited wrong ask');

  var diamondAfter = $Market.inspect(server, 'minecraft:diamond');
  fallbackCheck(Number(diamondAfter.get('inventory')) === diamondQ
    && Number(diamondAfter.get('realBid')) === diamondBid
    && Number(diamondAfter.get('realAsk')) === diamondAsk
    && String(diamondAfter.get('provenance')) === diamondProvenance,
    'existing diamond prior or q changed');
  JsonIO.write('kubejs/fallback-test-result.json', {
    status: 'PASS', fallbackCount: fallbackCount, sampleId: sampleId,
    staticEmc: String(staticEmc), bid: String(sellBid), ask: String(buyAsk),
    qRestored: true, diamondPriorUnchanged: true
  });
  console.info('[ATM10 fallback test] PASS sample=' + sampleId + ', fallbacks=' + fallbackCount);
});
