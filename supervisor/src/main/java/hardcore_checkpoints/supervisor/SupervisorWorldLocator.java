package hardcore_checkpoints.supervisor;

import hardcore_checkpoints.control.ControlMetadata;
import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.WorldBinding;
import hardcore_checkpoints.control.persistence.AtomicFileStore;
import hardcore_checkpoints.control.repository.ControlStateRepository;
import hardcore_checkpoints.control.repository.WorldControlBootstrap;
import hardcore_checkpoints.control.transfer.FileLockWorldActivityProbe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * 根据请求的世界路径定位其外部控制根。
 *
 * <p>世界目录存在时直接检查绑定；目录因回档暂时缺失时，才扫描父目录下的实例控制根。
 * 扫描结果必须唯一且同时匹配规范化路径和 worldInstanceId，禁止监督器误接管其他世界。</p>
 */
final class SupervisorWorldLocator {
	private final AtomicFileStore fileStore = new AtomicFileStore();
	private final ControlStateRepository controls = new ControlStateRepository(fileStore);
	private final FileLockWorldActivityProbe activityProbe = new FileLockWorldActivityProbe();

	/**
	 * 定位世界并返回已验证身份。启用监督前世界必须停止，避免初始化控制根时与 Minecraft 竞争。
	 */
	LocatedWorld locate(Path requestedWorldRoot) throws IOException {
		Path worldRoot = requestedWorldRoot.toAbsolutePath().normalize();
		if (Files.isDirectory(worldRoot, LinkOption.NOFOLLOW_LINKS)) {
			var result = new WorldControlBootstrap(controls).inspect(worldRoot);
			if (result.status() == WorldControlBootstrap.BootstrapStatus.UNINITIALIZED_DISABLED) {
				if (!Files.isRegularFile(worldRoot.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS)) {
					throw new IOException("World is not initialized: missing level.dat at " + worldRoot);
				}
				if (activityProbe.isWorldRunning(worldRoot)) {
					throw new IOException("World is currently running and cannot be initialized for supervision: " + worldRoot);
				}
				var initialized = controls.initializeDisabled(worldRoot);
				return new LocatedWorld(initialized.layout(), initialized.metadata());
			}
			if (result.status() == WorldControlBootstrap.BootstrapStatus.INCOMPLETE_CONTROL_DATA) {
				throw new IOException("Checkpoint control data is incomplete: " + String.join("; ", result.issues()));
			}
			if (result.optionalLayout().isEmpty() || result.optionalMetadata().isEmpty()) {
				throw new IOException("World is not initialized for Hardcore Checkpoints supervision");
			}
			return new LocatedWorld(
					result.optionalLayout().orElseThrow(),
					result.optionalMetadata().orElseThrow()
			);
		}

		// 世界目录缺失是离线替换期间的合法状态，此时只能通过外部绑定反向定位。
		Path parent = worldRoot.getParent();
		if (parent == null) {
			throw new IOException("World path has no parent directory");
		}
		Path instancesRoot = parent.resolve(ControlRootLayout.OWNERSHIP_DIRECTORY).resolve("instances");
		if (!Files.isDirectory(instancesRoot, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("World is missing and no checkpoint instance root exists: " + worldRoot);
		}
		// 只接受唯一绑定；零个或多个候选都意味着控制根不明确，必须拒绝启动。
		var matches = new ArrayList<LocatedWorld>();
		try (var instances = Files.newDirectoryStream(instancesRoot)) {
			for (Path instanceRoot : instances) {
				if (!Files.isDirectory(instanceRoot, LinkOption.NOFOLLOW_LINKS)) {
					continue;
				}
				try {
					ControlMetadata metadata = fileStore.readRequired(
							instanceRoot.resolve("control.json"),
							ControlMetadata.class
					).value();
					WorldBinding binding = fileStore.readRequired(
							instanceRoot.resolve("binding.json"),
							WorldBinding.class
					).value();
					Path boundWorld = Path.of(binding.normalizedWorldPath()).toAbsolutePath().normalize();
					if (!boundWorld.equals(worldRoot)
							|| !binding.worldInstanceId().equals(metadata.identity().worldInstanceId())) {
						continue;
					}
					ControlRootLayout layout = ControlRootLayout.forWorld(
							worldRoot,
							metadata.identity().worldInstanceId()
					);
					if (layout.instanceRoot().equals(instanceRoot.toAbsolutePath().normalize())) {
						matches.add(new LocatedWorld(layout, metadata));
					}
				} catch (IOException | IllegalArgumentException ignored) {
					// 其他实例的损坏数据不能被当前世界误认领；最终唯一性检查会报告真正的绑定问题。
				}
			}
		}
		if (matches.size() != 1) {
			throw new IOException("Expected exactly one control binding for missing world " + worldRoot
					+ ", found " + matches.size());
		}
		return matches.getFirst();
	}

	/** 已完成路径与实例身份交叉验证的监督目标。 */
	record LocatedWorld(ControlRootLayout layout, ControlMetadata metadata) {
	}
}
