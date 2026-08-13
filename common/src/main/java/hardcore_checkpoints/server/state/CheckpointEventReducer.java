package hardcore_checkpoints.server.state;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 检查点服务端状态机的纯事件归约器。
 *
 * <p>该类不执行磁盘或网络操作；每次调用仅根据旧状态和单个事件产生新状态。
 * 调用方负责在服务器线程串行提交并持久化已接受的转换。</p>
 *
 * <p>核心不变量：除死亡倒计时外，名单成员未全部实际进入 Play 时不得保持运行状态。</p>
 */
public final class CheckpointEventReducer {
	private static final long READY_STABILITY_NANOS = Duration.ofSeconds(2).toNanos();
	private static final long RESUME_COUNTDOWN_NANOS = Duration.ofSeconds(5).toNanos();

	private final ServerMutationGate mutationGate = new ServerMutationGate();

	/**
	 * 将协议、生命周期和定时事件分派到对应转换规则。
	 * 未知事件必须显式拒绝，避免新增事件在旧归约器中被静默接受。
	 */
	public Transition reduce(CheckpointServerState state, CheckpointEvent event) {
		if (event instanceof CheckpointEvent.EnableCycle) {
			if (state.featureEnabled()) {
				return reject(state, "already_enabled");
			}
			RosterState roster = RosterState.empty();
			return accept(state.update(
					CheckpointPhase.WAITING_FOR_ROSTER,
					true,
					false,
					roster,
					state.epoch().next(roster, Set.of(), Set.of())
			));
		}
		if (!state.featureEnabled()) {
			return reject(state, "feature_disabled");
		}
		if (event instanceof CheckpointEvent.DisableCommitted) {
			if (!mutationGate.allowsDisable(state)) {
				return reject(state, "unsafe_disable_phase");
			}
			return accept(state.update(
					CheckpointPhase.FEATURE_DISABLED,
					false,
					state.hasValidCheckpoint(),
					state.roster(),
					state.epoch().clearReady()
			));
		}
		if (event instanceof CheckpointEvent.AddRosterMember add) {
			return addRosterMember(state, add);
		}
		if (event instanceof CheckpointEvent.RemoveRosterMember remove) {
			return removeRosterMember(state, remove);
		}
		if (event instanceof CheckpointEvent.PlayConnected connected) {
			ReadyState ready = state.epoch().ready().markPlayConnected(connected.playerId());
			CheckpointPhase phase = state.roster().contains(connected.playerId()) && state.phase() == CheckpointPhase.ADMISSION_FROZEN
					? CheckpointPhase.WAITING_FOR_PLAYERS
					: state.phase();
			return accept(state.update(phase, true, state.hasValidCheckpoint(), state.roster(), state.epoch().withReady(ready)));
		}
		if (event instanceof CheckpointEvent.Disconnected disconnected) {
			return disconnect(state, disconnected.playerId());
		}
		if (event instanceof CheckpointEvent.PlayPresenceObserved observed) {
			return observePlayPresence(state, observed.playerIds());
		}
		if (event instanceof CheckpointEvent.MarkReady ready) {
			return markReady(state, ready);
		}
		if (event instanceof CheckpointEvent.WithdrawReady withdraw) {
			if (!state.epoch().epochId().equals(withdraw.expectedEpochId())) {
				return reject(state, "stale_epoch");
			}
			ReadyState ready = state.epoch().ready().withdraw(withdraw.playerId());
			CheckpointPhase phase = state.phase() == CheckpointPhase.RUNNING ? CheckpointPhase.PAUSED : state.phase();
			return accept(state.update(phase, true, state.hasValidCheckpoint(), state.roster(), state.epoch().withReady(ready)));
		}
		if (event instanceof CheckpointEvent.Tick tick) {
			return tick(state, tick.nowNanos());
		}
		if (event instanceof CheckpointEvent.CheckpointRequested) {
			return state.phase() == CheckpointPhase.RUNNING
					? accept(state.update(CheckpointPhase.QUIESCING, true, state.hasValidCheckpoint(), state.roster(), state.epoch()))
					: reject(state, "checkpoint_not_allowed");
		}
		if (event instanceof CheckpointEvent.SavingStarted) {
			return state.phase() == CheckpointPhase.QUIESCING
					? accept(state.update(CheckpointPhase.SAVING, true, state.hasValidCheckpoint(), state.roster(), state.epoch()))
					: reject(state, "not_quiescing");
		}
		if (event instanceof CheckpointEvent.CheckpointSucceeded succeeded) {
			return checkpointSucceeded(state, succeeded.nowNanos(), succeeded.resumeImmediately());
		}
		if (event instanceof CheckpointEvent.CheckpointFailed) {
			return checkpointFailed(state);
		}
		if (event instanceof CheckpointEvent.DeathCountdownStarted) {
			return state.phase() == CheckpointPhase.RUNNING
					? accept(state.update(CheckpointPhase.DEATH_COUNTDOWN, true, state.hasValidCheckpoint(), state.roster(), state.epoch()))
					: reject(state, "death_not_allowed");
		}
		if (event instanceof CheckpointEvent.ServerActuallyStopping) {
			return state.phase() == CheckpointPhase.DEATH_COUNTDOWN
					? accept(state.update(CheckpointPhase.ROLLBACK_PENDING, true, state.hasValidCheckpoint(), state.roster(), state.epoch()))
					: accept(state);
		}
		if (event instanceof CheckpointEvent.RollbackSucceeded) {
			if (state.phase() != CheckpointPhase.RESTORING && state.phase() != CheckpointPhase.ROLLBACK_PENDING) {
				return reject(state, "not_restoring");
			}
			CheckpointPhase phase = state.roster().members().isEmpty()
					? CheckpointPhase.WAITING_FOR_ROSTER
					: CheckpointPhase.ADMISSION_FROZEN;
			return accept(state.update(phase, true, state.hasValidCheckpoint(), state.roster(), state.epoch().clearReady()));
		}
		if (event instanceof CheckpointEvent.RollbackFailed) {
			return state.phase() == CheckpointPhase.RESTORING || state.phase() == CheckpointPhase.ROLLBACK_PENDING
					? accept(state.update(CheckpointPhase.RECOVERY_FAILED, true, state.hasValidCheckpoint(), state.roster(), state.epoch().clearReady()))
					: reject(state, "not_restoring");
		}
		return reject(state, "unsupported_event");
	}

	/**
	 * 新成员在运行期或死亡事务中只能进入待提交集合，防止当前检查点的名单语义中途改变。
	 */
	private Transition addRosterMember(CheckpointServerState state, CheckpointEvent.AddRosterMember event) {
		if (!mutationGate.allowsRosterMutation(state)) {
			return reject(state, "roster_locked");
		}
		if (event.expectedRosterVersion() != state.roster().version()) {
			return reject(state, "stale_roster_version");
		}
		boolean pending = event.pending() || state.phase() == CheckpointPhase.RUNNING || state.phase() == CheckpointPhase.DEATH_COUNTDOWN;
		RosterState roster = pending ? state.roster().markPending(event.playerId()) : state.roster().add(event.playerId());
		if (roster == state.roster()) {
			return accept(state);
		}
		CheckpointPhase phase = state.phase();
		if (!pending && phase == CheckpointPhase.WAITING_FOR_ROSTER) {
			phase = CheckpointPhase.ADMISSION_FROZEN;
		} else if (phase == CheckpointPhase.RUNNING) {
			phase = CheckpointPhase.PAUSED;
		}
		EpochState epoch = phase == CheckpointPhase.DEATH_COUNTDOWN
				? state.epoch().withRoster(roster)
				: state.epoch().next(roster, state.epoch().ready().readyMembers(), state.epoch().ready().playConnectedMembers());
		return accept(state.update(phase, true, state.hasValidCheckpoint(), roster, epoch));
	}

	/**
	 * 名单版本是管理操作的乐观锁；客户端基于旧快照发出的修改不得覆盖新名单。
	 */
	private Transition removeRosterMember(CheckpointServerState state, CheckpointEvent.RemoveRosterMember event) {
		if (!mutationGate.allowsRosterMutation(state)) {
			return reject(state, "roster_locked");
		}
		if (event.expectedRosterVersion() != state.roster().version()) {
			return reject(state, "stale_roster_version");
		}
		RosterState roster = state.roster().remove(event.playerId());
		if (roster == state.roster()) {
			return accept(state);
		}
		CheckpointPhase phase = state.phase() == CheckpointPhase.DEATH_COUNTDOWN
				? CheckpointPhase.DEATH_COUNTDOWN
				: roster.members().isEmpty() ? CheckpointPhase.WAITING_FOR_ROSTER : state.phase();
		EpochState epoch = phase == CheckpointPhase.DEATH_COUNTDOWN
				? state.epoch().withRoster(roster)
				: state.epoch().next(roster, state.epoch().ready().readyMembers(), state.epoch().ready().playConnectedMembers());
		return accept(state.update(phase, true, state.hasValidCheckpoint(), roster, epoch));
	}

	/**
	 * 处理显式断线通知。
	 *
	 * <p>死亡倒计时和快照事务必须保持原阶段；其他阶段通过新 epoch 撤销旧 READY，
	 * 防止断线玩家重连后沿用过期准备结果。</p>
	 */
	private Transition disconnect(CheckpointServerState state, UUID playerId) {
		ReadyState disconnected = state.epoch().ready().disconnect(playerId);
		if (!state.roster().contains(playerId)) {
			return accept(state.update(state.phase(), true, state.hasValidCheckpoint(), state.roster(), state.epoch().withReady(disconnected)));
		}
		if (state.phase() == CheckpointPhase.DEATH_COUNTDOWN
				|| state.phase() == CheckpointPhase.QUIESCING
				|| state.phase() == CheckpointPhase.SAVING
				|| state.phase() == CheckpointPhase.CREATING_INITIAL_CHECKPOINT) {
			return accept(state.update(state.phase(), true, state.hasValidCheckpoint(), state.roster(), state.epoch().withReady(disconnected)));
		}
		Set<UUID> autoReady = new LinkedHashSet<>(disconnected.playConnectedMembers());
		autoReady.retainAll(state.roster().members());
		CheckpointPhase phase = state.phase() == CheckpointPhase.RUNNING ? CheckpointPhase.PAUSED : CheckpointPhase.WAITING_FOR_PLAYERS;
		EpochState epoch = state.epoch().next(state.roster(), autoReady, disconnected.playConnectedMembers());
		return accept(state.update(phase, true, state.hasValidCheckpoint(), state.roster(), epoch));
	}

	/**
	 * 使用 Minecraft 实际 PlayerList 校准缓存的 Play 在线集合。
	 *
	 * <p>连接回调可能因线程切换、协议重配置或异常断开而漏报，因此实际 Play 集合是最终权威。
	 * 即使缓存集合原本为空，本方法也会纠正错误残留的 RUNNING 阶段。</p>
	 */
	private Transition observePlayPresence(CheckpointServerState state, Set<UUID> actualPlayPlayers) {
		Set<UUID> rosterPlayPlayers = new LinkedHashSet<>(actualPlayPlayers);
		rosterPlayPlayers.retainAll(state.roster().members());
		ReadyState current = state.epoch().ready();
		boolean rosterComplete = rosterPlayPlayers.containsAll(state.roster().members());
		CheckpointPhase synchronizedPhase = presencePhase(state.phase(), rosterComplete);
		if (current.playConnectedMembers().equals(rosterPlayPlayers) && synchronizedPhase == state.phase()) {
			return accept(state);
		}

		ReadyState synchronizedReady = current.synchronizePlayConnected(rosterPlayPlayers);
		if (state.phase() == CheckpointPhase.DEATH_COUNTDOWN
				|| state.phase() == CheckpointPhase.QUIESCING
				|| state.phase() == CheckpointPhase.SAVING
				|| state.phase() == CheckpointPhase.CREATING_INITIAL_CHECKPOINT) {
			return accept(state.update(
					state.phase(),
					true,
					state.hasValidCheckpoint(),
					state.roster(),
					state.epoch().withReady(synchronizedReady)
			));
		}

		CheckpointPhase phase = synchronizedPhase;
		EpochState epoch = state.epoch().next(
				state.roster(),
				synchronizedReady.readyMembers(),
				synchronizedReady.playConnectedMembers()
		);
		return accept(state.update(phase, true, state.hasValidCheckpoint(), state.roster(), epoch));
	}

	/**
	 * 将缺员映射为冻结阶段。死亡倒计时是唯一豁免：其固定截止时间不得被断线改变。
	 */
	private static CheckpointPhase presencePhase(CheckpointPhase phase, boolean rosterComplete) {
		if (rosterComplete || phase == CheckpointPhase.DEATH_COUNTDOWN) {
			return phase;
		}
		return switch (phase) {
			case RUNNING -> CheckpointPhase.PAUSED;
			case ADMISSION_FROZEN, WAITING_FOR_PLAYERS, SYNCING, START_COUNTDOWN, PAUSED ->
					CheckpointPhase.WAITING_FOR_PLAYERS;
			default -> phase;
		};
	}

	/**
	 * READY 请求同时校验 epoch、名单版本和实际 Play 资格，拒绝跨重连重放的旧请求。
	 */
	private Transition markReady(CheckpointServerState state, CheckpointEvent.MarkReady event) {
		if (!state.epoch().epochId().equals(event.expectedEpochId())) {
			return reject(state, "stale_epoch");
		}
		if (state.roster().version() != event.expectedRosterVersion()) {
			return reject(state, "stale_roster_version");
		}
		if (!state.roster().contains(event.playerId()) || !state.epoch().ready().playConnectedMembers().contains(event.playerId())) {
			return reject(state, "player_not_ready_eligible");
		}
		ReadyState ready = state.epoch().ready().markReady(event.playerId());
		CheckpointPhase phase = state.phase();
		if (ready.allRosterMembersReady(state.roster())) {
			phase = state.hasValidCheckpoint() ? CheckpointPhase.START_COUNTDOWN : CheckpointPhase.CREATING_INITIAL_CHECKPOINT;
			ready = ready.withStability(event.nowNanos(), null);
		}
		return accept(state.update(phase, true, state.hasValidCheckpoint(), state.roster(), state.epoch().withReady(ready)));
	}

	/**
	 * 推进 READY 稳定窗口与五秒恢复倒计时。
	 * 名单在任一时刻失去全员 READY，倒计时立即失效并重新冻结。
	 */
	private Transition tick(CheckpointServerState state, long nowNanos) {
		if (state.phase() != CheckpointPhase.START_COUNTDOWN) {
			return accept(state);
		}
		ReadyState ready = state.epoch().ready();
		if (!ready.allRosterMembersReady(state.roster())) {
			return accept(state.update(CheckpointPhase.PAUSED, true, state.hasValidCheckpoint(), state.roster(), state.epoch().withReady(ready.withStability(0, null))));
		}
		Long stableSince = ready.stableSinceNanos();
		if (stableSince == null || stableSince == 0) {
			return accept(state.update(state.phase(), true, state.hasValidCheckpoint(), state.roster(), state.epoch().withReady(ready.withStability(nowNanos, null))));
		}
		Long deadline = ready.resumeDeadlineNanos();
		if (deadline == null && nowNanos - stableSince >= READY_STABILITY_NANOS) {
			ReadyState countdown = ready.withStability(stableSince, nowNanos + RESUME_COUNTDOWN_NANOS);
			return accept(state.update(state.phase(), true, state.hasValidCheckpoint(), state.roster(), state.epoch().withReady(countdown)));
		}
		if (deadline != null && nowNanos >= deadline) {
			return accept(state.update(CheckpointPhase.RUNNING, true, state.hasValidCheckpoint(), state.roster(), state.epoch().withReady(ready.withStability(stableSince, deadline))));
		}
		return accept(state);
	}

	/**
	 * 处理已原子发布的检查点。
	 *
	 * <p>待加入成员只在发布边界提交。发布完成不等于可以运行；名单或在线集合发生变化时，
	 * 必须进入新的等待 epoch。</p>
	 */
	private Transition checkpointSucceeded(CheckpointServerState state, long nowNanos, boolean resumeImmediately) {
		if (state.phase() != CheckpointPhase.SAVING && state.phase() != CheckpointPhase.CREATING_INITIAL_CHECKPOINT) {
			return reject(state, "no_checkpoint_transaction");
		}
		if (state.phase() == CheckpointPhase.CREATING_INITIAL_CHECKPOINT) {
			ReadyState ready = state.epoch().ready();
			// 集成服务器玩家已经完成入场；复制期间 READY 波动不能使已发布快照失效。
			if (resumeImmediately) {
				return accept(state.update(
						CheckpointPhase.RUNNING,
						true,
						true,
						state.roster(),
						state.epoch().withReady(ready)
				));
			}
			if (!ready.allRosterMembersReady(state.roster()) || !allRosterMembersConnected(state)) {
				EpochState epoch = state.epoch().next(
						state.roster(),
						ready.readyMembers(),
						ready.playConnectedMembers()
				);
				return accept(state.update(CheckpointPhase.WAITING_FOR_PLAYERS, true, true, state.roster(), epoch));
			}
			return accept(state.update(
					CheckpointPhase.START_COUNTDOWN,
					true,
					true,
					state.roster(),
					state.epoch().withReady(ready.withStability(nowNanos, null))
			));
		}
		RosterState roster = state.roster().commitPendingAdditions();
		if (roster.version() != state.roster().version() || !allRosterMembersConnected(state)) {
			EpochState epoch = state.epoch().next(
					roster,
					state.epoch().ready().readyMembers(),
					state.epoch().ready().playConnectedMembers()
			);
			return accept(state.update(CheckpointPhase.WAITING_FOR_PLAYERS, true, true, roster, epoch));
		}
		return accept(state.update(CheckpointPhase.RUNNING, true, true, roster, state.epoch()));
	}

	/**
	 * 首个检查点失败会阻止功能进入运行态；已有有效检查点时则保留旧快照并按在线情况恢复。
	 */
	private Transition checkpointFailed(CheckpointServerState state) {
		if (state.phase() != CheckpointPhase.SAVING && state.phase() != CheckpointPhase.CREATING_INITIAL_CHECKPOINT) {
			return reject(state, "no_checkpoint_transaction");
		}
		if (!state.hasValidCheckpoint()) {
			return accept(state.update(
					CheckpointPhase.INITIAL_CHECKPOINT_FAILED,
					true,
					false,
					state.roster(),
					state.epoch().clearReady()
			));
		}
		if (allRosterMembersConnected(state)) {
			return accept(state.update(CheckpointPhase.RUNNING, true, true, state.roster(), state.epoch()));
		}
		EpochState epoch = state.epoch().next(
				state.roster(),
				state.epoch().ready().readyMembers(),
				state.epoch().ready().playConnectedMembers()
		);
		return accept(state.update(CheckpointPhase.WAITING_FOR_PLAYERS, true, true, state.roster(), epoch));
	}

	private static boolean allRosterMembersConnected(CheckpointServerState state) {
		return state.epoch().ready().playConnectedMembers().containsAll(state.roster().members());
	}

	private static Transition accept(CheckpointServerState state) {
		return new Transition(state, true, "ok");
	}

	private static Transition reject(CheckpointServerState state, String code) {
		return new Transition(state, false, code);
	}

	/**
	 * 状态转换结果。拒绝结果始终保留输入状态，并携带稳定的协议错误码。
	 */
	public record Transition(CheckpointServerState state, boolean accepted, String code) {
	}
}
