package com.cna.apcore.action;

import com.cna.apcore.model.CognitivePrepareUnit;
import com.cna.apcore.pool.CognitivePreparePool;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TickAction 管理器 — 统一调度所有系统级周期性检查。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>注册 TickAction 实现（内置 + 插件）</li>
 *   <li>每 tick 遍历已注册的 action，检查间隔和就绪条件</li>
 *   <li>保证池中同时最多只有一个 tick action 单元（新触发替换旧的）</li>
 *   <li>追踪每个 action 类型的 lastFireTick</li>
 * </ul>
 *
 * <h3>单例约束</h3>
 * 所有 tick action 共享 source ID "system:tick"。
 * 每次触发前先 pool.removeBySource("system:tick") 移除旧单元，再 push 新单元。
 * 即使同一 tick 内多个 action 触发，最终池中也只会保留最后一个。
 *
 * <h3>线程安全</h3>
 * tick() 仅在 ActionLoop.onTick() 的单线程 ScheduledExecutor 中调用，
 * 无并发问题。register() 可在启动阶段任意线程调用，ConcurrentHashMap 保证可见性。
 *
 * <h3>扩展方式</h3>
 * <pre>{@code
 * TickActionManager.getInstance().register(new MyTickAction());
 * }</pre>
 */
@Slf4j
public class TickActionManager {

    private static volatile TickActionManager INSTANCE;

    // ── 状态 ──

    /** 已注册的 tick action：actionType → action */
    private final Map<String, TickAction> actions = new ConcurrentHashMap<>();

    /** 每种 action 类型的最近触发 tick：actionType → tick */
    private final Map<String, Integer> lastFireTicks = new ConcurrentHashMap<>();

    /** 准备池引用（初始化时注入） */
    private volatile CognitivePreparePool pool;

    private TickActionManager() {
    }

    /** 获取单例 */
    public static TickActionManager getInstance() {
        if (INSTANCE == null) {
            synchronized (TickActionManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new TickActionManager();
                }
            }
        }
        return INSTANCE;
    }

    // ==========================================
    // 生命周期
    // ==========================================

    /**
     * 初始化管理器：注入池引用并注册内置 tick action。
     * 由 ActionLoop 构造时调用一次。
     */
    public void init(CognitivePreparePool pool) {
        this.pool = pool;

        // 注册内置 tick action
        register(new FileSurveyTickAction());
        register(new SelfCheckTickAction());

        log.info("[TickMgr] 🎛️ 初始化完成: {} 个 tick action 已注册 — {}",
                actions.size(),
                actions.keySet());
    }

    /**
     * 注册一个 tick action（内置或插件）。
     * 可在运行时动态注册。同 actionType 重复注册会覆盖。
     */
    public void register(TickAction action) {
        if (action == null) {
            log.warn("[TickMgr] 拒绝注册 null action");
            return;
        }
        String type = action.getActionType();
        if (type == null || type.isBlank()) {
            log.warn("[TickMgr] 拒绝注册 actionType 为空白的 action: {}", action.getClass().getName());
            return;
        }
        actions.put(type, action);
        lastFireTicks.putIfAbsent(type, 0);
        log.info("[TickMgr] 📌 注册 tick action: type={}, interval={} ticks, class={}",
                type, action.getIntervalTicks(), action.getClass().getSimpleName());
    }

    // ==========================================
    // 每 tick 调度
    // ==========================================

    /**
     * 每个认知 tick 调用一次。遍历已注册的 tick action，检查间隔和就绪条件，
     * 对满足条件的 action 执行单例约束 → 生成 → 推入池。
     *
     * @param currentTick 当前全局 tick 编号
     * @param poolSize    当前准备池大小
     * @return 本轮推入池的单元数
     */
    public int tick(int currentTick, int poolSize) {
        if (pool == null) {
            log.warn("[TickMgr] 池未初始化，跳过 tick");
            return 0;
        }

        int pushed = 0;
        for (TickAction action : actions.values()) {
            // 1. 间隔检查
            int lastFire = lastFireTicks.getOrDefault(action.getActionType(), 0);
            int elapsed = currentTick - lastFire;
            if (elapsed < action.getIntervalTicks()) {
                continue;
            }

            // 2. 就绪条件检查
            if (!action.isReady(poolSize, currentTick)) {
                continue;
            }

            // 3. 单例约束：移除池中已有的旧 tick 单元
            CognitivePrepareUnit old = pool.removeBySource(TickAction.TICK_SOURCE_ID);
            if (old != null) {
                log.debug("[TickMgr] 🔄 替换旧 tick 单元: type={}, oldUUID={}",
                        action.getActionType(),
                        old.getUuid().toString().substring(0, 8));
            }

            // 4. 生成新单元
            CognitivePrepareUnit unit;
            try {
                unit = action.generate(currentTick);
            } catch (Exception e) {
                log.error("[TickMgr] {} 生成单元异常: {}", action.getActionType(), e.getMessage(), e);
                continue;
            }

            if (unit == null) {
                log.debug("[TickMgr] {} 本轮不生成单元，跳过", action.getActionType());
                continue;
            }

            // 5. 推入池
            pool.push(unit);
            lastFireTicks.put(action.getActionType(), currentTick);
            pushed++;

            log.info("[TickMgr] ✅ tick action 触发: type={}, tick={}, interval={}, poolSize={}",
                    action.getActionType(), currentTick, elapsed, poolSize);
        }
        return pushed;
    }

    // ==========================================
    // 查询
    // ==========================================

    /** 获取每种 action 类型的最近触发 tick（只读） */
    public Map<String, Integer> getLastFireTicks() {
        return Collections.unmodifiableMap(lastFireTicks);
    }

    /** 获取已注册的 action 类型集合 */
    public Set<String> getRegisteredActionTypes() {
        return Collections.unmodifiableSet(actions.keySet());
    }

    /** 已注册的 action 数量 */
    public int getActionCount() {
        return actions.size();
    }
}
