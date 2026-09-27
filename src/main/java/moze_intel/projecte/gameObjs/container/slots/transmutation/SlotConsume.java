package moze_intel.projecte.gameObjs.container.slots.transmutation;

import java.math.BigDecimal;
import java.math.BigInteger;
import moze_intel.projecte.api.proxy.IEMCProxy;
import moze_intel.projecte.gameObjs.container.inventory.TransmutationInventory;
import moze_intel.projecte.gameObjs.container.slots.InventoryContainerSlot;
import moze_intel.projecte.gameObjs.registries.PEItems;
import moze_intel.projecte.market.MarketService;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

public class SlotConsume extends InventoryContainerSlot {

	private final TransmutationInventory inv;

	public SlotConsume(TransmutationInventory inv, int index, int x, int y) {
		super(inv, index, x, y);
		this.inv = inv;
	}

	@Override
	public void initialize(@NotNull ItemStack stack) {
		//Note: We don't need to copy any of the logic from set as initialize is only ever called on the client
	}

	@Override
	public void set(@NotNull ItemStack stack) {
		if (inv.isServer() && !stack.isEmpty()) {
			if (MarketService.enabled(inv.player)) {
				BigDecimal proceeds = MarketService.trade(inv.player, stack, stack.getCount(), false);
				if (proceeds != null && proceeds.signum() > 0) {
					inv.handleKnowledge(stack);
					inv.addMarketEmc(proceeds);
					this.setChanged();
				}
				return;
			}
			inv.handleKnowledge(stack);
			inv.addEmc(BigInteger.valueOf(IEMCProxy.INSTANCE.getSellValue(stack)).multiply(BigInteger.valueOf(stack.getCount())));
			this.setChanged();
		}
	}

	@Override
	public boolean mayPlace(@NotNull ItemStack stack) {
		if (MarketService.enabled(inv.player)) {
			BigDecimal bid = MarketService.quote(inv.player, stack, 1, false);
			return MarketService.canExchange(inv.player, stack)
					&& bid != null && bid.signum() > 0;
		}
		return IEMCProxy.INSTANCE.hasValue(stack) || stack.is(PEItems.TOME_OF_KNOWLEDGE);
	}

	@Override
	public int getMaxStackSize(@NotNull ItemStack stack) {
		int max = super.getMaxStackSize(stack);
		if (!MarketService.enabled(inv.player)) return max;
		int low = 0;
		int high = max;
		while (low < high) {
			int mid = low + (high - low + 1) / 2;
			BigDecimal bid = MarketService.quote(inv.player, stack, mid, false);
			if (bid != null && bid.signum() > 0) low = mid;
			else high = mid - 1;
		}
		return low;
	}
}
