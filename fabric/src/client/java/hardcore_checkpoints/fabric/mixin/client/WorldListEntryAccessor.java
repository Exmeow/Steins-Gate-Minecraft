package hardcore_checkpoints.fabric.mixin.client;

import net.minecraft.world.level.storage.LevelSummary;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WorldSelectionList.WorldListEntry.class)
public interface WorldListEntryAccessor {
	@Accessor("summary")
	LevelSummary hardcoreCheckpoints$summary();
}
