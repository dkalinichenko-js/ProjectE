package moze_intel.projecte.market;

import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.impl.capability.KnowledgeImpl;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketKnowledgeTest {
    @Test void fullKnowledgeDoesNotBypassExplicitDiscovery() {
        var provider = KnowledgeImpl.wrapAttachment(new KnowledgeImpl.KnowledgeAttachment());
        var diamond = ItemInfo.fromStack(Items.DIAMOND.getDefaultInstance());
        provider.setFullKnowledge(true);
        assertTrue(provider.hasKnowledge(diamond));
        assertFalse(provider.hasExplicitKnowledge(diamond));
        assertTrue(provider.getExplicitKnowledge().isEmpty());
        assertTrue(provider.addExplicitKnowledge(diamond));
        assertTrue(provider.hasExplicitKnowledge(diamond));
        assertEquals(1, provider.getExplicitKnowledge().size());
        assertTrue(provider.removeExplicitKnowledge(diamond));
        assertFalse(provider.hasExplicitKnowledge(diamond));
        assertTrue(provider.hasFullKnowledge(), "Market discovery must not erase legacy Tome state");
    }
}
