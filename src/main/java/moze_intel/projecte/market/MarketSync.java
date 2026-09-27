package moze_intel.projecte.market;

import java.util.LinkedHashMap;
import java.util.Map;
import moze_intel.projecte.PECore;
import moze_intel.projecte.network.packets.IPEPacket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MarketSync(boolean reset, boolean enabled, double liquidity, int maxOrder, double scale, String fraction,
                         Map<String, MarketSavedData.Entry> entries) implements IPEPacket {
    public static final Type<MarketSync> TYPE = new Type<>(PECore.rl("market_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MarketSync> STREAM_CODEC = new StreamCodec<>() {
        public MarketSync decode(RegistryFriendlyByteBuf b) {
            boolean reset = b.readBoolean(), enabled = b.readBoolean(); double liquidity = b.readDouble(); int maxOrder = b.readVarInt();
            double scale = b.readDouble(); String fraction = b.readUtf(4096);
            int size = b.readVarInt(); if (size < 0 || size > 64) throw new IllegalArgumentException("Market batch size");
            Map<String,MarketSavedData.Entry> entries = new LinkedHashMap<>();
            for (int i=0;i<size;i++) {
                String id = b.readUtf(256); long q = b.readLong(); int n = b.readVarInt();
                if (n<1 || n>256) throw new IllegalArgumentException("Market prior size");
                double[] p = new double[n], w = new double[n];
                for (int j=0;j<n;j++) { p[j]=b.readDouble(); w[j]=b.readDouble(); }
                entries.put(id,new MarketSavedData.Entry(p,w,"server snapshot",q));
            }
            return new MarketSync(reset,enabled,liquidity,maxOrder,scale,fraction,entries);
        }
        public void encode(RegistryFriendlyByteBuf b, MarketSync p) {
            b.writeBoolean(p.reset); b.writeBoolean(p.enabled); b.writeDouble(p.liquidity); b.writeVarInt(p.maxOrder); b.writeDouble(p.scale); b.writeUtf(p.fraction,4096); b.writeVarInt(p.entries.size());
            p.entries.forEach((id,e)-> { b.writeUtf(id,256); b.writeLong(e.inventory); b.writeVarInt(e.prices.length);
                for (int j=0;j<e.prices.length;j++) { b.writeDouble(e.prices[j]); b.writeDouble(e.weights[j]); }
            });
        }
    };
    @Override public Type<MarketSync> type() { return TYPE; }
    @Override public void handle(IPayloadContext context) { MarketClient.receive(this); }
}
