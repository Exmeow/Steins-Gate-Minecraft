package hardcore_checkpoints.block.entity;

import hardcore_checkpoints.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

public final class CheckpointCoreBlockEntity extends BlockEntity {
	private static final Set<CheckpointCoreBlockEntity> LOADED =
			Collections.newSetFromMap(new WeakHashMap<>());

	public CheckpointCoreBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.checkpointCore(), pos, state);
		synchronized (LOADED) {
			LOADED.add(this);
		}
	}


	@Override
	public void setRemoved() {
		synchronized (LOADED) {
			LOADED.remove(this);
		}
		super.setRemoved();
	}

	public static List<CheckpointCoreBlockEntity> loadedSnapshot() {
		synchronized (LOADED) {
			return List.copyOf(new ArrayList<>(LOADED));
		}
	}
}
