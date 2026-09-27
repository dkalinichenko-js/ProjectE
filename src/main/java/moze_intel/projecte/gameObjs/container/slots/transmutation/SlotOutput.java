package moze_intel.projecte.gameObjs.container.slots.transmutation;

import java.math.BigDecimal;
import java.math.BigInteger;
import moze_intel.projecte.api.proxy.IEMCProxy;
import moze_intel.projecte.gameObjs.container.inventory.TransmutationInventory;
import moze_intel.projecte.gameObjs.container.slots.InventoryContainerSlot;
import moze_intel.projecte.market.MarketService;
import moze_intel.projecte.utils.ItemHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

public class SlotOutput extends InventoryContainerSlot {

	private final TransmutationInventory inv;

	public SlotOutput(TransmutationInventory inv, int index, int x, int y) {
		super(inv, index, x, y);
		this.inv = inv;
	}

	@Override
	protected void onSwapCraft(int amount) {
		remove(amount);
	}

	@NotNull
	@Override
	public ItemStack remove(int amount) {
		if (amount == 0) {
			return ItemStack.EMPTY;
		}
		if (MarketService.enabled(inv.player)) {
			ItemStack selected = getItem();
			if (selected.isEmpty() || !inv.provider.hasExplicitKnowledge(selected)) return ItemStack.EMPTY;
			int count = Math.min(amount, selected.getMaxStackSize());
			BigDecimal ask = MarketService.quote(inv.player, selected, count, true);
			if (ask == null || ask.signum() <= 0 || ask.compareTo(inv.getMarketAvailableEmc()) > 0) return ItemStack.EMPTY;
			if (inv.isServer()) {
				BigDecimal paid = MarketService.trade(inv.player, selected, count, true);
				if (paid == null || paid.compareTo(inv.getMarketAvailableEmc()) > 0) return ItemStack.EMPTY;
				inv.removeMarketEmc(paid);
			}
			return ItemHelper.size(selected, count);
		}
		ItemStack stack = ItemHelper.size(getItem(), amount);
		long emcValue = IEMCProxy.INSTANCE.getValue(stack);
		BigInteger bigEmcValue = BigInteger.valueOf(emcValue);
		if (amount > 1) {
			bigEmcValue = bigEmcValue.multiply(BigInteger.valueOf(amount));
			if (bigEmcValue.compareTo(inv.getAvailableEmc()) > 0) {
				//Requesting more emc than available
				//Container expects stacksize=0-Itemstack for 'nothing'
				return ItemStack.EMPTY;
			}
		} else if (emcValue > inv.getAvailableEmcAsLong()) {
			//Requesting more emc than available
			//Container expects stacksize=0-Itemstack for 'nothing'
			return ItemStack.EMPTY;
		}
		if (inv.isServer()) {
			inv.removeEmc(bigEmcValue);
		}
		return stack;
	}

	@Override
	public void initialize(@NotNull ItemStack stack) {
	}

	@Override
	public void set(@NotNull ItemStack stack) {
	}

	@Override
	public boolean mayPlace(@NotNull ItemStack stack) {
		return false;
	}

	@Override
	public boolean mayPickup(@NotNull Player player) {
		if (MarketService.enabled(player)) {
			if (!hasItem()) return true;
			BigDecimal ask = MarketService.quote(player, getItem(), 1, true);
			return inv.provider.hasExplicitKnowledge(getItem()) && ask != null && ask.signum() > 0 && ask.compareTo(inv.getMarketAvailableEmc()) <= 0;
		}
		return !hasItem() || IEMCProxy.INSTANCE.getValue(getItem()) <= inv.getAvailableEmcAsLong();
	}
}
