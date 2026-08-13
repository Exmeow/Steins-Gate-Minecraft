package hardcore_checkpoints.registry;

import hardcore_checkpoints.HardcoreCheckpoints;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;

public final class ModSounds {
	public static final SoundEvent CHECKPOINT_SUCCESS = SoundEvent.createVariableRangeEvent(
			HardcoreCheckpoints.id("checkpoint_success")
	);
	public static final SoundEvent ROLLBACK_SUCCESS = SoundEvent.createVariableRangeEvent(
			HardcoreCheckpoints.id("rollback_success")
	);
	public static final SoundEvent DEDICATED_RECOVERY_MUSIC = SoundEvent.createVariableRangeEvent(
			HardcoreCheckpoints.id("dedicated_recovery_music")
	);

	private ModSounds() {
	}

	public static void register() {
		Registry.register(BuiltInRegistries.SOUND_EVENT, CHECKPOINT_SUCCESS.getLocation(), CHECKPOINT_SUCCESS);
		Registry.register(BuiltInRegistries.SOUND_EVENT, ROLLBACK_SUCCESS.getLocation(), ROLLBACK_SUCCESS);
		Registry.register(BuiltInRegistries.SOUND_EVENT, DEDICATED_RECOVERY_MUSIC.getLocation(), DEDICATED_RECOVERY_MUSIC);
	}
}
