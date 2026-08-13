package hardcore_checkpoints.server.admin;

import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.persistence.AtomicFileStore;

import java.io.IOException;
import java.util.UUID;

public final class AdministratorRepository {
	private final AtomicFileStore fileStore;

	public AdministratorRepository(AtomicFileStore fileStore) {
		this.fileStore = fileStore;
	}

	public AdministratorState load(ControlRootLayout layout) throws IOException {
		return fileStore.readOptional(layout.instanceRoot().resolve("administrators.json"), AdministratorState.class)
				.map(AtomicFileStore.StoredValue::value)
				.orElseGet(AdministratorState::empty);
	}

	public AdministratorState add(ControlRootLayout layout, UUID playerId) throws IOException {
		AdministratorState updated = load(layout).add(playerId);
		fileStore.write(layout.instanceRoot().resolve("administrators.json"), updated);
		return updated;
	}

	public AdministratorState remove(ControlRootLayout layout, UUID playerId) throws IOException {
		AdministratorState updated = load(layout).remove(playerId);
		fileStore.write(layout.instanceRoot().resolve("administrators.json"), updated);
		return updated;
	}

	public boolean contains(ControlRootLayout layout, UUID playerId) throws IOException {
		return load(layout).members().contains(playerId);
	}
}
