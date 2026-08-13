package hardcore_checkpoints.control.transfer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import hardcore_checkpoints.control.ControlMetadata;
import hardcore_checkpoints.control.ControlRootLayout;
import hardcore_checkpoints.control.WorldBinding;
import hardcore_checkpoints.control.journal.ActivationSwitchJournal;
import hardcore_checkpoints.control.journal.TransactionJournal;
import hardcore_checkpoints.control.persistence.AtomicFileStore;
import hardcore_checkpoints.control.persistence.AtomicPathMoves;
import hardcore_checkpoints.control.persistence.PathSecurity;
import hardcore_checkpoints.control.repository.ControlStateRepository;
import hardcore_checkpoints.control.repository.WorldControlBootstrap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 停服状态下导出或导入世界及其外部检查点控制根。
 *
 * <p>归档包含世界目录、控制实例和带摘要的清单。导入会生成新的 worldInstanceId，
 * 因此复制品不会继承源实例所有权。所有归档路径都经过规范化和链接检查以阻止目录穿越。</p>
 *
 * <p>导入使用持久化日志记录阶段，可在进程中断后继续或回滚；最终目录发布只允许原子移动。</p>
 */
public final class WorldExportImportService {
	private static final String MANIFEST_ENTRY = "manifest.json";
	private static final long MAX_MANIFEST_SIZE = 8L * 1024L * 1024L;

	private final WorldActivityProbe activityProbe;
	private final ControlStateRepository controlRepository;
	private final WorldControlBootstrap bootstrap;
	private final AtomicFileStore fileStore;
	private final Gson gson;

	public WorldExportImportService(WorldActivityProbe activityProbe) {
		this(activityProbe, new ControlStateRepository());
	}

	public WorldExportImportService(
			WorldActivityProbe activityProbe,
			ControlStateRepository controlRepository
	) {
		this.activityProbe = Objects.requireNonNull(activityProbe, "activityProbe");
		this.controlRepository = Objects.requireNonNull(controlRepository, "controlRepository");
		this.bootstrap = new WorldControlBootstrap(controlRepository);
		this.fileStore = controlRepository.fileStore();
		this.gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
	}

	/**
	 * 导出已停止世界。归档同时包含世界数据和外部控制根，但不允许归档文件落在任一源根内部。
	 */
	public ExportManifest exportStopped(Path worldRoot, Path archivePath) throws IOException {
		Path safeWorldRoot = PathSecurity.requireSafeDirectory(worldRoot);
		if (activityProbe.isWorldRunning(safeWorldRoot)) {
			throw new IOException("World export requires a fully stopped source world: " + safeWorldRoot);
		}

		WorldControlBootstrap.BootstrapResult result = bootstrap.inspect(safeWorldRoot);
		if (result.status() == WorldControlBootstrap.BootstrapStatus.INCOMPLETE_CONTROL_DATA) {
			throw new IOException("Cannot export incomplete control data: " + String.join("; ", result.issues()));
		}
		ControlRootLayout layout = result.optionalLayout()
				.orElseThrow(() -> new IOException("World has no initialized external control root"));
		ControlMetadata metadata = result.optionalMetadata()
				.orElseThrow(() -> new IOException("World has no readable control metadata"));
		PathSecurity.requireSafeTree(safeWorldRoot);
		PathSecurity.requireSafeTree(layout.instanceRoot());

		Path targetArchive = PathSecurity.normalizeAbsolute(archivePath);
		Path archiveParent = Objects.requireNonNull(targetArchive.getParent(), "Archive path must have a parent");
		PathSecurity.requireSafeDirectory(archiveParent);
		if (targetArchive.startsWith(safeWorldRoot) || targetArchive.startsWith(layout.instanceRoot())) {
			throw new IOException("Export archive must be outside the world and control roots");
		}
		PathSecurity.requireSafeFileOrMissing(targetArchive);

		List<SourceFile> files = new ArrayList<>();
		files.addAll(describeTree(safeWorldRoot, "world"));
		files.addAll(describeTree(layout.instanceRoot(), "control"));
		files.sort(Comparator.comparing(source -> source.manifestFile().archivePath()));
		ExportManifest manifest = ExportManifest.create(
				String.valueOf(safeWorldRoot.getFileName()),
				metadata,
				files.stream().map(SourceFile::manifestFile).toList()
		);
		writeArchive(targetArchive, manifest, files);
		return manifest;
	}

	/**
	 * 把归档导入为独立世界实例。目标、暂存目录和控制实例均必须不存在或可安全恢复。
	 */
	public ImportedControl importStopped(Path archivePath, Path targetWorldRoot) throws IOException {
		Path archive = PathSecurity.normalizeAbsolute(archivePath);
		PathSecurity.requireSafeFileOrMissing(archive);
		if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Import archive is missing: " + archive);
		}

		Path targetWorld = PathSecurity.normalizeAbsolute(targetWorldRoot);
		Path worldParent = Objects.requireNonNull(targetWorld.getParent(), "Target world must have a parent directory");
		PathSecurity.requireSafeDirectory(worldParent);
		if (Files.exists(targetWorld, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Target world path already exists: " + targetWorld);
		}
		if (activityProbe.isWorldRunning(targetWorld)) {
			throw new IOException("World import requires a fully stopped target world: " + targetWorld);
		}

		recoverImports(worldParent);
		ExportManifest manifest = readManifest(archive);
		validateManifest(manifest);
		UUID newWorldInstanceId = UUID.randomUUID();
		ControlRootLayout finalLayout = ControlRootLayout.forWorld(targetWorld, newWorldInstanceId);
		Path importsRoot = finalLayout.ownershipRoot().resolve("imports");
		Path temporaryWorld = worldParent.resolve("." + targetWorld.getFileName() + ".importing." + newWorldInstanceId);
		Path temporaryControl = finalLayout.instancesRoot().resolve(".importing." + newWorldInstanceId);
		PathSecurity.createDirectoriesSecure(worldParent, finalLayout.ownershipRoot());
		PathSecurity.createDirectoriesSecure(finalLayout.ownershipRoot(), finalLayout.instancesRoot());
		PathSecurity.createDirectoriesSecure(finalLayout.ownershipRoot(), importsRoot);
		if (Files.exists(finalLayout.instanceRoot(), LinkOption.NOFOLLOW_LINKS)
				|| Files.exists(temporaryWorld, LinkOption.NOFOLLOW_LINKS)
				|| Files.exists(temporaryControl, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Import target or temporary path already exists");
		}
		PathSecurity.createDirectoriesSecure(worldParent, temporaryWorld);
		PathSecurity.createDirectoriesSecure(finalLayout.instancesRoot(), temporaryControl);

		ImportJournal journal = ImportJournal.create(
				newWorldInstanceId,
				temporaryWorld.toString(),
				targetWorld.toString(),
				temporaryControl.toString(),
				finalLayout.instanceRoot().toString()
		);
		Path journalPath = importsRoot.resolve(journal.importId() + ".json");
		fileStore.write(journalPath, journal);

		ControlMetadata importedMetadata;
		try {
			extractAndValidate(archive, manifest, temporaryWorld, temporaryControl);
			importedMetadata = rewriteImportedIdentity(
					manifest,
					temporaryWorld,
					temporaryControl,
					targetWorld,
					newWorldInstanceId
			);
			journal = journal.withPhase(ImportJournal.Phase.VALIDATED);
			fileStore.write(journalPath, journal);
		} catch (IOException | RuntimeException exception) {
			rollbackExtractingImport(journalPath, journal);
			throw exception;
		}

		continueImport(journalPath, journal);
		return new ImportedControl(targetWorld, finalLayout, importedMetadata);
	}

	/**
	 * 恢复中断导入。EXTRACTING 尚未验证，必须回滚；VALIDATED 以后可按日志幂等继续发布。
	 */
	public void recoverImports(Path worldParent) throws IOException {
		Path safeWorldParent = PathSecurity.requireSafeDirectory(worldParent);
		Path ownershipRoot = safeWorldParent.resolve(ControlRootLayout.OWNERSHIP_DIRECTORY);
		Path importsRoot = ownershipRoot.resolve("imports");
		if (!Files.isDirectory(importsRoot, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		PathSecurity.requireSafeDirectory(importsRoot);
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(importsRoot, "*.json")) {
			for (Path journalPath : stream) {
				ImportJournal journal = fileStore.readRequired(journalPath, ImportJournal.class).value();
				validateImportJournalPaths(safeWorldParent, ownershipRoot, journal);
				Path targetWorld = Path.of(journal.targetWorldPath());
				if (activityProbe.isWorldRunning(targetWorld)) {
					throw new IOException("Cannot recover import while target world is running: " + targetWorld);
				}
				if (journal.phase() == ImportJournal.Phase.EXTRACTING) {
					rollbackExtractingImport(journalPath, journal);
				} else {
					continueImport(journalPath, journal);
				}
			}
		}
	}

	/**
	 * 为导入副本生成新实例身份，并重写绑定、事务日志、激活日志和世界标记中的引用。
	 */
	private ControlMetadata rewriteImportedIdentity(
			ExportManifest manifest,
			Path temporaryWorld,
			Path temporaryControl,
			Path targetWorld,
			UUID newWorldInstanceId
	) throws IOException {
		Path controlMetadataPath = temporaryControl.resolve("control.json");
		ControlMetadata extractedMetadata = fileStore.readRequired(controlMetadataPath, ControlMetadata.class).value();
		if (!extractedMetadata.identity().equals(manifest.sourceControlMetadata().identity())) {
			throw new IOException("Export manifest identity does not match extracted control metadata");
		}
		ControlMetadata reboundMetadata = extractedMetadata.withWorldInstanceId(newWorldInstanceId);
		String metadataChecksum = fileStore.write(controlMetadataPath, reboundMetadata);
		fileStore.write(
				temporaryControl.resolve("binding.json"),
				WorldBinding.create(newWorldInstanceId, targetWorld.toAbsolutePath().normalize().toString())
		);

		Path transactionsRoot = temporaryControl.resolve("transactions");
		if (Files.isDirectory(transactionsRoot, LinkOption.NOFOLLOW_LINKS)) {
			PathSecurity.requireSafeDirectory(transactionsRoot);
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(transactionsRoot, "*.json")) {
				for (Path transactionPath : stream) {
					TransactionJournal transaction = fileStore.readRequired(transactionPath, TransactionJournal.class).value();
					if (!transaction.worldInstanceId().equals(extractedMetadata.identity().worldInstanceId())) {
						throw new IOException("Transaction belongs to another source world instance: " + transactionPath);
					}
					fileStore.write(transactionPath, transaction.rebind(newWorldInstanceId));
				}
			}
		}

		Path activationJournalPath = temporaryControl.resolve("activation-switch.json");
		var activationJournal = fileStore.readOptional(activationJournalPath, ActivationSwitchJournal.class);
		if (activationJournal.isPresent()) {
			fileStore.write(activationJournalPath, activationJournal.get().value().rebind(newWorldInstanceId));
		}
		controlRepository.markerRepository().write(temporaryWorld, reboundMetadata, metadataChecksum);
		return reboundMetadata;
	}

	/**
	 * 发布顺序固定为控制根后世界目录。这样世界一旦可见，其外部控制数据已经存在且可恢复。
	 */
	private void continueImport(Path journalPath, ImportJournal initialJournal) throws IOException {
		ImportJournal journal = initialJournal;
		Path temporaryControl = Path.of(journal.temporaryControlPath());
		Path targetControl = Path.of(journal.targetControlPath());
		Path temporaryWorld = Path.of(journal.temporaryWorldPath());
		Path targetWorld = Path.of(journal.targetWorldPath());

		if (journal.phase().ordinal() <= ImportJournal.Phase.VALIDATED.ordinal()) {
			publishDirectory(temporaryControl, targetControl);
			journal = journal.withPhase(ImportJournal.Phase.CONTROL_PUBLISHED);
			fileStore.write(journalPath, journal);
		}
		if (journal.phase().ordinal() <= ImportJournal.Phase.CONTROL_PUBLISHED.ordinal()) {
			publishDirectory(temporaryWorld, targetWorld);
			journal = journal.withPhase(ImportJournal.Phase.WORLD_PUBLISHED);
			fileStore.write(journalPath, journal);
		}
		Files.deleteIfExists(journalPath);
	}

	private void rollbackExtractingImport(Path journalPath, ImportJournal journal) throws IOException {
		deleteOwnedTree(Path.of(journal.temporaryWorldPath()));
		deleteOwnedTree(Path.of(journal.temporaryControlPath()));
		Files.deleteIfExists(journalPath);
	}

	/**
	 * 不信任日志中的绝对路径；恢复前重新约束到本次导入拥有的世界父目录和实例目录。
	 */
	private static void validateImportJournalPaths(
			Path worldParent,
			Path ownershipRoot,
			ImportJournal journal
	) throws IOException {
		Path temporaryWorld = PathSecurity.normalizeAbsolute(Path.of(journal.temporaryWorldPath()));
		Path targetWorld = PathSecurity.normalizeAbsolute(Path.of(journal.targetWorldPath()));
		Path temporaryControl = PathSecurity.normalizeAbsolute(Path.of(journal.temporaryControlPath()));
		Path targetControl = PathSecurity.normalizeAbsolute(Path.of(journal.targetControlPath()));
		Path instancesRoot = ownershipRoot.resolve("instances").normalize();
		String instanceId = journal.newWorldInstanceId().toString();
		if (!Objects.equals(temporaryWorld.getParent(), worldParent)
				|| !Objects.equals(targetWorld.getParent(), worldParent)
				|| temporaryWorld.startsWith(ownershipRoot)
				|| targetWorld.startsWith(ownershipRoot)
				|| !Objects.equals(temporaryControl.getParent(), instancesRoot)
				|| !Objects.equals(targetControl.getParent(), instancesRoot)
				|| !temporaryWorld.getFileName().toString().endsWith(".importing." + instanceId)
				|| !temporaryControl.getFileName().toString().equals(".importing." + instanceId)
				|| !targetControl.getFileName().toString().equals(instanceId)) {
			throw new IOException("Import journal contains a path outside the owned import locations");
		}
	}

	/**
	 * 严格按清单提取：拒绝目录项、重复项、额外项、缺失项、越界路径和摘要不匹配。
	 */
	private void extractAndValidate(
			Path archive,
			ExportManifest manifest,
			Path temporaryWorld,
			Path temporaryControl
	) throws IOException {
		Map<String, ExportManifest.ArchiveFile> expected = new LinkedHashMap<>();
		for (ExportManifest.ArchiveFile file : manifest.files()) {
			if (expected.put(file.archivePath(), file) != null) {
				throw new IOException("Duplicate file in export manifest: " + file.archivePath());
			}
		}
		Set<String> seen = new HashSet<>();
		boolean manifestSeen = false;
		try (ZipFile zipFile = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
			Enumeration<? extends ZipEntry> entries = zipFile.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String entryName = validateArchiveEntryName(entry.getName());
				if (MANIFEST_ENTRY.equals(entryName)) {
					if (manifestSeen) {
						throw new IOException("Duplicate manifest entry");
					}
					manifestSeen = true;
					continue;
				}
				if (entry.isDirectory()) {
					throw new IOException("Directory entries are not allowed in import archives: " + entryName);
				}
				ExportManifest.ArchiveFile expectedFile = expected.get(entryName);
				if (expectedFile == null || !seen.add(entryName)) {
					throw new IOException("Unexpected or duplicate archive entry: " + entryName);
				}

				Path destination;
				if (entryName.startsWith("world/")) {
					destination = resolveArchiveDestination(temporaryWorld, entryName.substring("world/".length()));
				} else if (entryName.startsWith("control/")) {
					destination = resolveArchiveDestination(temporaryControl, entryName.substring("control/".length()));
				} else {
					throw new IOException("Archive entry is outside world/control roots: " + entryName);
				}
				PathSecurity.createDirectoriesSecure(
						entryName.startsWith("world/") ? temporaryWorld : temporaryControl,
						Objects.requireNonNull(destination.getParent())
				);
				try (InputStream input = new BufferedInputStream(zipFile.getInputStream(entry))) {
					CopyDigest digest = writeExtractedFile(destination, input, expectedFile.size());
					if (digest.size() != expectedFile.size() || !digest.sha256().equals(expectedFile.sha256())) {
						throw new IOException("Archive entry checksum mismatch: " + entryName);
					}
				}
			}
		}
		if (!manifestSeen || seen.size() != expected.size()) {
			Set<String> missing = new HashSet<>(expected.keySet());
			missing.removeAll(seen);
			throw new IOException("Import archive is missing entries: " + missing);
		}
	}

	/** 清单大小有独立上限，避免在解析归档元数据时消耗无界内存。 */
	private ExportManifest readManifest(Path archive) throws IOException {
		try (ZipFile zipFile = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
			ZipEntry entry = zipFile.getEntry(MANIFEST_ENTRY);
			if (entry == null || entry.isDirectory() || entry.getSize() > MAX_MANIFEST_SIZE) {
				throw new IOException("Import archive has no valid manifest.json");
			}
			try (InputStream input = zipFile.getInputStream(entry)) {
				byte[] bytes = input.readNBytes((int) MAX_MANIFEST_SIZE + 1);
				if (bytes.length > MAX_MANIFEST_SIZE) {
					throw new IOException("Import manifest is too large");
				}
				try {
					ExportManifest manifest = gson.fromJson(new String(bytes, StandardCharsets.UTF_8), ExportManifest.class);
					if (manifest == null) {
						throw new IOException("Import manifest is empty");
					}
					return manifest;
				} catch (JsonParseException | IllegalArgumentException exception) {
					throw new IOException("Import manifest is invalid", exception);
				}
			}
		}
	}

	private static void validateManifest(ExportManifest manifest) throws IOException {
		Set<String> names = new HashSet<>();
		for (ExportManifest.ArchiveFile file : manifest.files()) {
			String name = validateArchiveEntryName(file.archivePath());
			if ((!name.startsWith("world/") && !name.startsWith("control/")) || !names.add(name)) {
				throw new IOException("Invalid or duplicate manifest path: " + name);
			}
			if (!file.sha256().matches("[0-9a-f]{64}")) {
				throw new IOException("Invalid manifest checksum for " + name);
			}
		}
	}

	private List<SourceFile> describeTree(Path root, String archivePrefix) throws IOException {
		List<SourceFile> files = new ArrayList<>();
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted().toList()) {
				if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
					continue;
				}
				String relative = root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
				String archivePath = archivePrefix + "/" + relative;
				files.add(new SourceFile(
						path,
						new ExportManifest.ArchiveFile(archivePath, Files.size(path), hashFile(path))
				));
			}
		}
		return files;
	}

	private void writeArchive(Path target, ExportManifest manifest, List<SourceFile> files) throws IOException {
		Path parent = Objects.requireNonNull(target.getParent());
		Path temporary = parent.resolve(target.getFileName() + ".tmp." + UUID.randomUUID());
		PathSecurity.requireSafeFileOrMissing(temporary);
		try {
			try (FileChannel channel = FileChannel.open(
					temporary,
					StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE
			);
				 ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel)), StandardCharsets.UTF_8)) {
				writeZipEntry(zip, MANIFEST_ENTRY, (gson.toJson(manifest) + "\n").getBytes(StandardCharsets.UTF_8));
				for (SourceFile file : files) {
					ZipEntry entry = new ZipEntry(file.manifestFile().archivePath());
					entry.setTime(0L);
					zip.putNextEntry(entry);
					try (InputStream input = new BufferedInputStream(Files.newInputStream(file.source()))) {
						input.transferTo(zip);
					}
					zip.closeEntry();
				}
				zip.finish();
				zip.flush();
				channel.force(true);
			}
			atomicMove(temporary, target, true);
			flushDirectory(parent);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static void writeZipEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
		ZipEntry entry = new ZipEntry(name);
		entry.setTime(0L);
		zip.putNextEntry(entry);
		zip.write(bytes);
		zip.closeEntry();
	}

	/** 将归档相对路径约束在指定提取根内，构成 Zip Slip 的第二层防护。 */
	private static Path resolveArchiveDestination(Path root, String relativeName) throws IOException {
		if (relativeName.isBlank()) {
			throw new IOException("Archive entry has an empty relative path");
		}
		Path destination = root.resolve(relativeName).normalize();
		if (!destination.startsWith(root)) {
			throw new IOException("Archive entry escapes extraction root: " + relativeName);
		}
		return destination;
	}

	/** 拒绝绝对路径、反斜杠、盘符、空段和父目录段。 */
	private static String validateArchiveEntryName(String entryName) throws IOException {
		if (entryName == null || entryName.isBlank() || entryName.startsWith("/") || entryName.contains("\\")) {
			throw new IOException("Invalid archive entry name: " + entryName);
		}
		String[] segments = entryName.split("/", -1);
		for (String segment : segments) {
			if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment) || segment.contains(":")) {
				throw new IOException("Unsafe archive entry name: " + entryName);
			}
		}
		return entryName;
	}

	private static CopyDigest writeExtractedFile(Path destination, InputStream input, long expectedSize) throws IOException {
		MessageDigest digest = sha256Digest();
		long size = 0L;
		try (FileChannel channel = FileChannel.open(
				destination,
				StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE
		)) {
			byte[] buffer = new byte[64 * 1024];
			int read;
			while ((read = input.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
				if (read > expectedSize - size) {
					throw new IOException("Archive entry exceeds its declared size: " + destination);
				}
				size += read;
				ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, read);
				while (byteBuffer.hasRemaining()) {
					channel.write(byteBuffer);
				}
			}
			channel.force(true);
		}
		return new CopyDigest(size, HexFormat.of().formatHex(digest.digest()));
	}

	private static String hashFile(Path path) throws IOException {
		MessageDigest digest = sha256Digest();
		try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
			byte[] buffer = new byte[64 * 1024];
			int read;
			while ((read = input.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	/**
	 * 幂等发布暂存目录。源和目标同时存在表示状态不明确，不能猜测哪一侧有效。
	 */
	private static void publishDirectory(Path source, Path target) throws IOException {
		boolean sourceExists = Files.exists(source, LinkOption.NOFOLLOW_LINKS);
		boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
		if (sourceExists && targetExists) {
			throw new IOException("Both import source and target exist: " + source + " / " + target);
		}
		if (!sourceExists && targetExists) {
			PathSecurity.requireSafeDirectory(target);
			return;
		}
		if (!sourceExists) {
			throw new IOException("Import source and target are both missing: " + source + " / " + target);
		}
		PathSecurity.requireSafeDirectory(source);
		atomicMove(source, target, false);
	}

	private static void atomicMove(Path source, Path target, boolean replaceExisting) throws IOException {
		AtomicPathMoves.move(
				source,
				target,
				replaceExisting,
				"Filesystem does not support required atomic move: " + target
		);
	}

	private static void deleteOwnedTree(Path root) throws IOException {
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		PathSecurity.requireSafeTree(root);
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
	}

	private static void flushDirectory(Path directory) throws IOException {
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
			channel.force(true);
		} catch (AccessDeniedException | UnsupportedOperationException ignored) {
			// Windows 常在原子移动已持久化后仍拒绝打开目录句柄；此处仅忽略目录级 flush 不支持。
		}
	}

	private record SourceFile(Path source, ExportManifest.ArchiveFile manifestFile) {
	}

	private record CopyDigest(long size, String sha256) {
	}

	public record ImportedControl(
			Path worldRoot,
			ControlRootLayout layout,
			ControlMetadata metadata
	) {
	}
}
