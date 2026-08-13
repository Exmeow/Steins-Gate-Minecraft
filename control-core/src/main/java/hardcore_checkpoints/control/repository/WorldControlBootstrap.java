package hardcore_checkpoints.control.repository;

import hardcore_checkpoints.control.ControlMetadata;
import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.WorldIdentityMarker;
import hardcore_checkpoints.control.persistence.AtomicFileStore;
import hardcore_checkpoints.control.persistence.PathSecurity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class WorldControlBootstrap {
	private final ControlStateRepository controlRepository;

	public WorldControlBootstrap() {
		this(new ControlStateRepository());
	}

	public WorldControlBootstrap(ControlStateRepository controlRepository) {
		this.controlRepository = controlRepository;
	}

	public BootstrapResult inspect(Path worldRoot) {
		List<String> issues = new ArrayList<>();
		try {
			Path safeWorldRoot = PathSecurity.requireSafeDirectory(worldRoot);
			var storedMarker = controlRepository.markerRepository().read(safeWorldRoot);
			if (storedMarker.isEmpty()) {
				return new BootstrapResult(BootstrapStatus.UNINITIALIZED_DISABLED, null, null, List.of());
			}

			WorldIdentityMarker marker = storedMarker.get().value();
			ControlRootLayout layout = ControlRootLayout.forWorld(
					safeWorldRoot,
					marker.identity().worldInstanceId()
			);
			if (!Files.isDirectory(layout.instanceRoot(), LinkOption.NOFOLLOW_LINKS)) {
				if (!marker.featureEnabled()) {
					return new BootstrapResult(BootstrapStatus.FEATURE_DISABLED, null, null, List.of());
				}
				issues.add("Expected external control root is missing: " + layout.instanceRoot());
				return incomplete(layout, issues);
			}

			PathSecurity.requireSafeTree(layout.instanceRoot());
			controlRepository.recoverActivationSwitch(layout);
			AtomicFileStore.StoredValue<ControlMetadata> storedMetadata = controlRepository.readMetadata(layout);
			ControlMetadata metadata = storedMetadata.value();
			storedMarker = controlRepository.markerRepository().read(safeWorldRoot);
			if (storedMarker.isEmpty()) {
				issues.add("World identity marker disappeared during bootstrap");
				return incomplete(layout, issues);
			}
			marker = storedMarker.get().value();

			if (!metadata.identity().equals(marker.identity())) {
				issues.add("World identity marker does not match external control metadata");
			}
			if (metadata.featureEnabled() != marker.featureEnabled()) {
				issues.add("World feature flag does not match external control metadata");
			}
			if (!storedMetadata.sha256().equals(marker.controlMetadataSha256())) {
				issues.add("World identity marker references a different control metadata checksum");
			}

			WorldBindingRepository.BindingResult binding = controlRepository.bindingRepository()
					.validateOrRebind(layout, metadata);
			if (binding.status() == WorldBindingRepository.BindingStatus.CONFLICT) {
				issues.add(binding.issue());
			}
			if (!issues.isEmpty()) {
				return incomplete(layout, issues);
			}

			BootstrapStatus status = metadata.featureEnabled()
					? BootstrapStatus.CONTROL_READY
					: BootstrapStatus.FEATURE_DISABLED;
			return new BootstrapResult(status, layout, metadata, List.of());
		} catch (IOException | IllegalArgumentException exception) {
			issues.add(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
			return new BootstrapResult(BootstrapStatus.INCOMPLETE_CONTROL_DATA, null, null, issues);
		}
	}

	private static BootstrapResult incomplete(ControlRootLayout layout, List<String> issues) {
		return new BootstrapResult(BootstrapStatus.INCOMPLETE_CONTROL_DATA, layout, null, List.copyOf(issues));
	}

	public enum BootstrapStatus {
		UNINITIALIZED_DISABLED,
		FEATURE_DISABLED,
		CONTROL_READY,
		INCOMPLETE_CONTROL_DATA
	}

	public record BootstrapResult(
			BootstrapStatus status,
			ControlRootLayout layout,
			ControlMetadata metadata,
			List<String> issues
	) {
		public BootstrapResult {
			issues = List.copyOf(issues);
		}

		public Optional<ControlRootLayout> optionalLayout() {
			return Optional.ofNullable(layout);
		}

		public Optional<ControlMetadata> optionalMetadata() {
			return Optional.ofNullable(metadata);
		}
	}
}
