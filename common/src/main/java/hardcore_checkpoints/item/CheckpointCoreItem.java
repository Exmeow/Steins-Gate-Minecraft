package hardcore_checkpoints.item;

import hardcore_checkpoints.block.CheckpointCoreBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class CheckpointCoreItem extends BlockItem {
	public CheckpointCoreItem(Block block, Item.Properties properties) {
		super(block, properties);
	}

	@Override
	protected @Nullable BlockState getPlacementState(BlockPlaceContext context) {
		BlockState state = super.getPlacementState(context);
		return state == null ? null : state.setValue(CheckpointCoreBlock.LIT, false);
	}

	@Override
	protected boolean updateCustomBlockEntityTag(
			BlockPos pos,
			Level level,
			@Nullable net.minecraft.world.entity.player.Player player,
			ItemStack stack,
			BlockState state
	) {
		return false;
	}

	@Override
	public void appendHoverText(
			ItemStack stack,
			Item.TooltipContext context,
			List<Component> tooltip,
			TooltipFlag flag
	) {
		super.appendHoverText(stack, context, tooltip, flag);
		tooltip.add(Component.translatable("block.hardcore_checkpoints.checkpoint_core.tooltip")
				.withStyle(ChatFormatting.GRAY));
	}
}
