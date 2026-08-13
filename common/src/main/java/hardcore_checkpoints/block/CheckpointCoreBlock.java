package hardcore_checkpoints.block;

import com.mojang.serialization.MapCodec;
import hardcore_checkpoints.block.entity.CheckpointCoreBlockEntity;
import hardcore_checkpoints.server.CheckpointCorePermissions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public final class CheckpointCoreBlock extends BaseEntityBlock {
	public static final MapCodec<CheckpointCoreBlock> CODEC = simpleCodec(CheckpointCoreBlock::new);
	public static final BooleanProperty LIT = BlockStateProperties.LIT;
	private static final DustParticleOptions ACTIVE_PARTICLE = new DustParticleOptions(
			new Vector3f(0.0F, 1.0F, 1.0F),
			1.0F
	);

	public CheckpointCoreBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(LIT, false));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
		builder.add(LIT);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new CheckpointCoreBlockEntity(pos, state);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	protected InteractionResult useWithoutItem(
			BlockState state,
			Level level,
			BlockPos pos,
			Player player,
			BlockHitResult hitResult
	) {
		if (!player.getMainHandItem().isEmpty()) {
			return InteractionResult.PASS;
		}

		if (level.isClientSide) {
			return InteractionResult.SUCCESS;
		}

		if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
			return InteractionResult.PASS;
		}

		CheckpointCorePermissions.Decision decision = CheckpointCorePermissions.evaluate(
				serverPlayer,
				serverLevel,
				pos,
				state.getValue(LIT)
		);
		if (!decision.allowed()) {
			serverPlayer.displayClientMessage(decision.denialReason(), true);
			return InteractionResult.CONSUME;
		}

		boolean lit = !state.getValue(LIT);
		level.setBlock(pos, state.setValue(LIT, lit), UPDATE_ALL);
		level.playSound(
				null,
				pos,
				lit ? SoundEvents.COPPER_BULB_TURN_ON : SoundEvents.COPPER_BULB_TURN_OFF,
				SoundSource.BLOCKS,
				0.7F,
				lit ? 1.15F : 0.9F
		);
		return InteractionResult.CONSUME;
	}

	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		if (!state.getValue(LIT) || random.nextInt(5) != 0) {
			return;
		}
		spawnAbsorbedParticle(level, pos, random, 0.035);
	}

	private static void spawnAbsorbedParticle(Level level, BlockPos pos, RandomSource random, double speed) {
		Vec3 center = Vec3.atCenterOf(pos);
		Vec3 offset = new Vec3(
				random.nextDouble() * 2.0 - 1.0,
				random.nextDouble() * 2.0 - 1.0,
				random.nextDouble() * 2.0 - 1.0
		);
		if (offset.lengthSqr() < 1.0E-4) {
			offset = new Vec3(1.0, 0.0, 0.0);
		}
		offset = offset.normalize().scale(0.8 + random.nextDouble() * 0.7);
		Vec3 start = center.add(offset);
		Vec3 velocity = center.subtract(start).normalize().scale(speed);
		level.addParticle(ACTIVE_PARTICLE, start.x, start.y, start.z, velocity.x, velocity.y, velocity.z);
	}

}
