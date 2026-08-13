package hardcore_checkpoints.fabric.client;

import hardcore_checkpoints.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

public final class DedicatedRecoveryMusic {
	private static SimpleSoundInstance active;

	private DedicatedRecoveryMusic() {
	}

	public static void start(Minecraft minecraft) {
		if (active != null) {
			return;
		}
		minecraft.getMusicManager().stopPlaying();
		active = new SimpleSoundInstance(
				ModSounds.DEDICATED_RECOVERY_MUSIC.getLocation(),
				SoundSource.MUSIC,
				1.0F,
				1.0F,
				SoundInstance.createUnseededRandom(),
				true,
				0,
				SoundInstance.Attenuation.NONE,
				0.0,
				0.0,
				0.0,
				true
		);
		minecraft.getSoundManager().play(active);
	}

	public static boolean isControllingMusic() {
		return active != null;
	}

	public static void stop(Minecraft minecraft) {
		if (active != null) {
			minecraft.getSoundManager().stop(active);
			active = null;
		}
	}
}
