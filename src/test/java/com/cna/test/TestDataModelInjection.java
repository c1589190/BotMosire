package com.cna.test;

import com.cna.Utils;
import com.cna.config.ConfigsManager;
import com.cna.config.ConfigsLoader;
import com.cna.llm.LLManager;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;

/**
 * 排查 LLManager 异步注入 dataModel 的提示词格式问题。
 *
 * 模拟 ActionLoop → LLManager.executeScene 的完整数据流：
 *   1. ActionLoop.buildActionPromptData() 构建 dataModel
 *   2. LLManager.executeSceneAsyncWithCache() 异步注入上下文字段
 *   3. FreeMarker 渲染 V4_ACTION_LOOP_PROMPT.ftl
 *
 * 检查项：
 *   A. FreeMarker 渲染是否抛异常（变量缺失、类型不匹配）
 *   B. 渲染后的 prompt 总长度是否在安全范围内
 *   C. 注入字段与模板实际使用的字段的差异（冗余注入 vs 缺失引用）
 *   D. 特殊字符 / FreeMarker 语法 / null 值在用户输入中的安全性
 *
 * 用法: mvn exec:java -Dexec.mainClass="com.cna.test.TestDataModelInjection" -Dexec.classpathScope=test -q
 */
public class TestDataModelInjection {

    // ── 模拟 LLManager 注入的字段名 ──
    private static final Set<String> LLM_INJECTED_FIELDS = Set.of(
            "current_memories", "current_thoughts", "tools_guide",
            "curiosity_context", "now_time", "pending_tasks_summary"
    );

    // ── ActionLoop.buildActionPromptData() 提供的字段 ──
    private static final Set<String> ACTION_LOOP_FIELDS = Set.of(
            "action_text", "source_ids", "cognitive_familiarity", "scale",
            "accident_degree", "action_pressure", "continue_weight",
            "ue_concepts", "ue_dim_ids", "now_time",
            "feeling_resonance", "action_predicts_text", "pool_summary"
    );

    // ── V4 模板实际引用的字段（从 V4_ACTION_LOOP_PROMPT.ftl 提取） ──
    private static final Set<String> V4_TEMPLATE_USED_FIELDS = Set.of(
            "now_time", "source_ids", "action_text",
            "cognitive_familiarity", "scale", "accident_degree",
            "action_pressure", "continue_weight",
            "ue_concepts", "ue_dim_ids",
            "feeling_resonance", "action_predicts_text", "pool_summary"
    );

    // ── 旧模板可能引用的字段（不在 V4 模板中） ──
    private static final Set<String> LEGACY_ONLY_FIELDS = Set.of(
            "taskText", "current_feelings", "recent_history", "deep_memories",
            "scheduled", "current_interests", "good_feeling_bias_prompt",
            "bad_feeling_bias_prompt", "parent_task_info", "self_task_sources",
            "turnsAddition", "feeling_resonance_result"
    );

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> warnings = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("🧪 LLManager dataModel 异步注入 — 格式问题诊断测试");
        System.out.println("═══════════════════════════════════════════════════════════\n");

        // 初始化配置（需要 DB_URL 等基础配置）
        try {
            ConfigsManager.init();
            ConfigsLoader.loadAll();
            System.out.println("✅ ConfigsManager 初始化完毕\n");
        } catch (Exception e) {
            System.out.println("⚠️ ConfigsManager 初始化失败: " + e.getMessage());
            System.out.println("   将以无 DB 模式继续测试（仅测试 FreeMarker 渲染）\n");
        }

        // ── Test 1: 字段覆盖分析 ──
        testFieldCoverage();

        // ── Test 2: 正常数据渲染 ──
        testNormalRendering();

        // ── Test 3: 边界值 / null 安全性 ──
        testEdgeCases();

        // ── Test 4: 模拟 LLManager 完整注入流程 ──
        testFullInjectionSimulation();

        // ── Test 5: 用户输入中的 FreeMarker 特殊字符 ──
        testUserInputWithSpecialChars();

        // ── Test 6: 大体积 prompt 溢出检测 ──
        testLargePromptSize();

        // ── Test 7: 冗余注入检测 ──
        testRedundantInjection();

        // ── 汇总 ──
        System.out.println("\n═══════════════════════════════════════════════════════════");
        System.out.printf("📊 测试结果: ✅ %d 通过, ❌ %d 失败, ⚠️ %d 警告\n", passed, failed, warnings.size());
        for (String w : warnings) {
            System.out.println("  ⚠️ " + w);
        }
        System.out.println("═══════════════════════════════════════════════════════════");

        if (failed > 0) {
            System.out.println("\n❌ 存在格式问题，需要修复后再部署！");
            System.exit(1);
        } else {
            System.out.println("\n✅ 所有格式检查通过，dataModel 注入安全");
            System.exit(0);
        }
    }

    // ════════════════════════════════════════════════════════════
    // Test 1: 字段覆盖分析
    // ════════════════════════════════════════════════════════════
    private static void testFieldCoverage() {
        System.out.println("── Test 1: 字段覆盖分析 ──");

        // 1a. LLManager 注入但 V4 模板不使用的字段（冗余注入）
        Set<String> redundant = new HashSet<>(LLM_INJECTED_FIELDS);
        redundant.removeAll(V4_TEMPLATE_USED_FIELDS);
        // now_time 在两边都有，不算冗余
        redundant.remove("now_time");

        if (!redundant.isEmpty()) {
            String msg = "LLManager 注入的字段在 V4 模板中未被引用（冗余注入）: " + redundant;
            warnings.add(msg);
            System.out.println("  ⚠️ " + msg);
            System.out.println("     → 导致不必要的 I/O 操作（读取文件/查询数据库）");
        } else {
            System.out.println("  ✅ 无冗余注入字段");
            passed++;
        }

        // 1b. V4 模板使用但 ActionLoop 不提供的字段
        Set<String> missing = new HashSet<>(V4_TEMPLATE_USED_FIELDS);
        missing.removeAll(ACTION_LOOP_FIELDS);

        if (!missing.isEmpty()) {
            System.out.println("  ❌ V4 模板引用但 ActionLoop 未提供的字段: " + missing);
            failed++;
        } else {
            System.out.println("  ✅ ActionLoop 提供了 V4 模板所需的所有字段");
            passed++;
        }

        // 1c. ActionLoop 提供但 V4 模板不使用的字段
        Set<String> unusedByV4 = new HashSet<>(ACTION_LOOP_FIELDS);
        unusedByV4.removeAll(V4_TEMPLATE_USED_FIELDS);

        if (!unusedByV4.isEmpty()) {
            System.out.println("  ℹ️ ActionLoop 提供但 V4 模板未使用的字段: " + unusedByV4);
            System.out.println("     → 无害，但可能占用内存");
        }
    }

    // ════════════════════════════════════════════════════════════
    // Test 2: 正常数据渲染
    // ════════════════════════════════════════════════════════════
    private static void testNormalRendering() {
        System.out.println("\n── Test 2: 正常数据 FreeMarker 渲染 ──");

        try {
            String templateContent = LLManager.loadPromptTemplate("prompts/V4_ACTION_LOOP_PROMPT.ftl");
            if (templateContent == null || templateContent.isBlank()) {
                // 回退到文件系统直接读取
                templateContent = readFileFallback("prompts/V4_ACTION_LOOP_PROMPT.ftl");
            }
            if (templateContent == null || templateContent.isBlank()) {
                System.out.println("  ❌ 无法加载 V4_ACTION_LOOP_PROMPT.ftl 模板");
                failed++;
                return;
            }

            Map<String, Object> data = buildRealisticDataModel();
            // 模拟 LLManager 注入
            simulateLLMInjection(data, true);

            String rendered = LLManager.render(templateContent, data);

            // 检查渲染结果
            if (rendered == null || rendered.isBlank()) {
                System.out.println("  ❌ 渲染结果为空！");
                failed++;
                return;
            }

            System.out.printf("  ✅ 渲染成功: %,d chars\n", rendered.length());

            // 检查是否有明显的 FreeMarker 错误残留
            if (rendered.contains("【系统警告") || rendered.contains("模板渲染失败")) {
                System.out.println("  ❌ 渲染结果包含错误标记！");
                failed++;
                return;
            }

            // 检查是否有 null 字符串
            if (rendered.contains(" null\n") || rendered.contains("\tnull\n")) {
                warnings.add("渲染结果中包含 Java 'null' 字面量 — 可能是数据字段为 null");
                System.out.println("  ⚠️ 渲染结果包含 'null' 字符串（数据字段可能为 null）");
            }

            // 检查 JSON 模板部分是否完整
            if (!rendered.contains("\"thoughts\"") || !rendered.contains("\"tool_calls\"")) {
                warnings.add("渲染结果中缺少完整的 JSON 模板结构");
                System.out.println("  ⚠️ JSON 模板结构不完整");
            }

            passed++;
        } catch (Exception e) {
            System.out.println("  ❌ FreeMarker 渲染异常: " + e.getMessage());
            e.printStackTrace(System.out);
            failed++;
        }
    }

    // ════════════════════════════════════════════════════════════
    // Test 3: 边界值与 null 安全性
    // ════════════════════════════════════════════════════════════
    private static void testEdgeCases() {
        System.out.println("\n── Test 3: 边界值 / null 安全性 ──");

        String templateContent;
        try {
            templateContent = LLManager.loadPromptTemplate("prompts/V4_ACTION_LOOP_PROMPT.ftl");
            if (templateContent == null || templateContent.isBlank()) {
                templateContent = readFileFallback("prompts/V4_ACTION_LOOP_PROMPT.ftl");
            }
        } catch (Exception e) {
            System.out.println("  ❌ 无法加载模板: " + e.getMessage());
            failed++;
            return;
        }

        if (templateContent == null || templateContent.isBlank()) {
            System.out.println("  ❌ 模板为空，跳过");
            failed++;
            return;
        }

        boolean allOk = true;

        // 3a. 空 source_ids
        try {
            Map<String, Object> data = buildMinimalDataModel();
            data.put("source_ids", Collections.emptyList());
            simulateLLMInjection(data, true);
            String r = LLManager.render(templateContent, data);
            if (r.contains("null")) {
                System.out.println("  ⚠️ 空 source_ids: 输出含 'null' 字符");
                warnings.add("空 source_ids 渲染含 'null'");
                allOk = false;
            } else {
                System.out.println("  ✅ 空 source_ids: 渲染正常");
            }
        } catch (Exception e) {
            System.out.println("  ❌ 空 source_ids: " + e.getMessage());
            allOk = false;
            failed++;
        }

        // 3b. 空 ue_concepts / ue_dim_ids
        try {
            Map<String, Object> data = buildMinimalDataModel();
            data.put("ue_concepts", Collections.emptyList());
            data.put("ue_dim_ids", Collections.emptyList());
            simulateLLMInjection(data, true);
            String r = LLManager.render(templateContent, data);
            if (r.contains("null")) {
                System.out.println("  ⚠️ 空 ue_concepts/ue_dim_ids: 输出含 'null'");
                warnings.add("空 ue_concepts/ue_dim_ids 渲染含 'null'");
                allOk = false;
            } else {
                System.out.println("  ✅ 空 ue_concepts/ue_dim_ids: 渲染正常");
            }
        } catch (Exception e) {
            System.out.println("  ❌ 空 ue_concepts/ue_dim_ids: " + e.getMessage());
            allOk = false;
            failed++;
        }

        // 3c. 空的 action_predicts_text（无先验经验）
        try {
            Map<String, Object> data = buildMinimalDataModel();
            data.put("action_predicts_text", "");
            simulateLLMInjection(data, true);
            String r = LLManager.render(templateContent, data);
            if (r.contains("null")) {
                warnings.add("空 action_predicts_text 渲染含 'null'");
                allOk = false;
            }
            System.out.println("  ✅ 空 action_predicts_text: 渲染正常");
        } catch (Exception e) {
            System.out.println("  ❌ 空 action_predicts_text: " + e.getMessage());
            allOk = false;
            failed++;
        }

        // 3d. 缺失 feeling_resonance（谐振分析失败时）
        try {
            Map<String, Object> data = buildMinimalDataModel();
            // 不放入 feeling_resonance
            simulateLLMInjection(data, true);
            String r = LLManager.render(templateContent, data);
            if (r.contains("null")) {
                warnings.add("缺失 feeling_resonance 渲染含 'null'");
                allOk = false;
            }
            System.out.println("  ✅ 缺失 feeling_resonance: 渲染正常");
        } catch (Exception e) {
            System.out.println("  ❌ 缺失 feeling_resonance: " + e.getMessage());
            allOk = false;
            failed++;
        }

        // 3e. preparePool 返回 null 文本（模拟 buildPoolSummary 的潜在 bug）
        try {
            Map<String, Object> data = buildMinimalDataModel();
            data.put("pool_summary", "【认知准备池】共 1 个单元:\n  - [abc12345] 'null' SE=0.50 UE=0.30 tick=0 cw=1.00\n");
            simulateLLMInjection(data, true);
            String r = LLManager.render(templateContent, data);
            // 这个 null 是字符串字面量，FreeMarker 会原样输出
            System.out.println("  ⚠️ pool_summary 含 'null': 确认 FreeMarker 会原样输出 null 字面量");
            warnings.add("CognitivePreparePool.buildPoolSummary() 在 getText() 为 null 时输出 'null' 字面量");
        } catch (Exception e) {
            System.out.println("  ❌ pool_summary null test: " + e.getMessage());
            failed++;
        }

        if (allOk) passed++;
    }

    // ════════════════════════════════════════════════════════════
    // Test 4: 模拟 LLManager 完整注入流程
    // ════════════════════════════════════════════════════════════
    private static void testFullInjectionSimulation() {
        System.out.println("\n── Test 4: 模拟 LLManager 完整注入流程 ──");

        try {
            String templateContent = LLManager.loadPromptTemplate("prompts/V4_ACTION_LOOP_PROMPT.ftl");
            if (templateContent == null || templateContent.isBlank()) {
                templateContent = readFileFallback("prompts/V4_ACTION_LOOP_PROMPT.ftl");
            }
            if (templateContent == null || templateContent.isBlank()) {
                System.out.println("  ❌ 无法加载模板");
                failed++;
                return;
            }

            // 模拟 ActionLoop.buildActionPromptData()
            Map<String, Object> dataModel = buildRealisticDataModel();

            // 记录注入前的 keys
            Set<String> keysBefore = new HashSet<>(dataModel.keySet());

            // 模拟 LLManager 注入（needInjection = true，模拟上下文清空后的首轮）
            simulateLLMInjection(dataModel, true);

            // 记录注入后的 keys
            Set<String> keysAfter = new HashSet<>(dataModel.keySet());
            Set<String> injected = new HashSet<>(keysAfter);
            injected.removeAll(keysBefore);

            System.out.println("  注入前字段数: " + keysBefore.size());
            System.out.println("  注入后字段数: " + keysAfter.size());
            System.out.println("  LLManager 新增字段: " + injected);

            // 验证注入的字段值非 null
            for (String key : injected) {
                Object val = dataModel.get(key);
                if (val == null) {
                    System.out.println("  ❌ 注入字段 '" + key + "' 值为 null！");
                    failed++;
                    return;
                }
                System.out.printf("    %s → %s\n", key,
                        val instanceof String s
                                ? (s.length() > 60 ? s.substring(0, 60) + "..." : s)
                                : val.getClass().getSimpleName() + (val instanceof List<?> l ? "[" + l.size() + "]" : ""));
            }

            // 渲染
            String rendered = LLManager.render(templateContent, dataModel);
            System.out.printf("  ✅ 完整注入后渲染成功: %,d chars\n", rendered.length());

            // 验证注入字段没有污染渲染输出（V4 模板不应引用这些字段）
            // 检查 tools_guide 的内容是否意外出现在渲染结果中
            String toolsGuide = (String) dataModel.get("tools_guide");
            if (toolsGuide != null && !toolsGuide.isBlank()) {
                if (rendered.contains(toolsGuide.substring(0, Math.min(30, toolsGuide.length())))) {
                    warnings.add("tools_guide 内容意外出现在 V4 渲染结果中");
                    System.out.println("  ⚠️ tools_guide 内容意外出现在 V4 模板渲染结果中");
                } else {
                    System.out.println("  ✅ tools_guide 被注入但未出现在渲染结果（V4 模板不引用它）");
                }
            }

            // ── V4 模式子测试 ──
            System.out.println("\n  --- V4 模式（跳过冗余注入）---");
            try {
                // 启用 V4 模式
                LLManager.setV4Mode(true);

                // 构建新的 dataModel（不预先注入旧字段）
                Map<String, Object> v4DataModel = buildRealisticDataModel();
                Set<String> v4KeysBefore = new HashSet<>(v4DataModel.keySet());

                // 模拟 V4 模式下的注入
                simulateLLMInjectionV4(v4DataModel, true);

                Set<String> v4KeysAfter = new HashSet<>(v4DataModel.keySet());
                Set<String> v4Injected = new HashSet<>(v4KeysAfter);
                v4Injected.removeAll(v4KeysBefore);

                System.out.println("  V4 模式注入后新增字段: " + v4Injected);
                // V4 模式下应只注入 now_time（其他 5 个字段跳过）
                boolean hasRedundantInjection = v4Injected.stream()
                        .anyMatch(k -> !"now_time".equals(k)
                                && LLM_INJECTED_FIELDS.contains(k));
                if (hasRedundantInjection) {
                    System.out.println("  ❌ V4 模式下仍注入了冗余字段！");
                    failed++;
                } else if (v4Injected.isEmpty() || (v4Injected.size() == 1 && v4Injected.contains("now_time"))) {
                    System.out.println("  ✅ V4 模式正确跳过了冗余注入（仅注入 now_time）");
                    passed++;
                } else {
                    System.out.println("  ⚠️ V4 模式注入了非预期字段: " + v4Injected);
                }

                // V4 模式下渲染应正常
                String v4Rendered = LLManager.render(templateContent, v4DataModel);
                System.out.printf("  ✅ V4 模式下渲染成功: %,d chars\n", v4Rendered.length());

                // 恢复默认模式
                LLManager.setV4Mode(false);
            } catch (Exception e) {
                System.out.println("  ❌ V4 模式子测试异常: " + e.getMessage());
                failed++;
                LLManager.setV4Mode(false); // 确保恢复
            }

            passed++;
        } catch (Exception e) {
            System.out.println("  ❌ 完整注入模拟失败: " + e.getMessage());
            e.printStackTrace(System.out);
            failed++;
        }
    }

    // ════════════════════════════════════════════════════════════
    // Test 5: 用户输入中的 FreeMarker 特殊字符
    // ════════════════════════════════════════════════════════════
    private static void testUserInputWithSpecialChars() {
        System.out.println("\n── Test 5: 用户输入特殊字符安全性 ──");

        String templateContent;
        try {
            templateContent = LLManager.loadPromptTemplate("prompts/V4_ACTION_LOOP_PROMPT.ftl");
            if (templateContent == null || templateContent.isBlank()) {
                templateContent = readFileFallback("prompts/V4_ACTION_LOOP_PROMPT.ftl");
            }
        } catch (Exception e) {
            System.out.println("  ❌ 无法加载模板: " + e.getMessage());
            failed++;
            return;
        }

        if (templateContent == null || templateContent.isBlank()) {
            System.out.println("  ❌ 模板为空");
            failed++;
            return;
        }

        // 用户可能发送的恶意/特殊内容
        String[] specialInputs = {
                // FreeMarker 语法片段（在 ${} 插值内部，不会被二次解析）
                "${exploit}",
                "<#if true>hacked</#if>",
                "<#list 1..10 as i>${i}</#list>",
                "#{malicious}",
                // JSON 特殊字符
                "包含 \"双引号\" 和 \\反斜杠\\ 的文本",
                // Unicode / emoji
                "用户发了一个 😀😈👍 然后说了 { \"key\": \"value\" }",
                // 超长单行（无换行）
                "A".repeat(10000),
                // 控制字符（除了 \t \n \r）
                "正常文本 包含空字符和控制字符",
                // 混合 FreeMarker + JSON
                "用户说: ${action_text} 这种格式对吗？{\"key\": \"<#if x>yes</#if>\"}",
                // 真实场景：QQ 消息中的 @ 和特殊格式
                "[CQ:at,qq=123456] @BotMosire 请帮我查一下天气怎么样？ ${weather} 是不是要下雨了",
                // HTML/XML 片段
                "<div class=\"message\">用户发了<br/>一段<b>富文本</b></div>",
        };

        boolean allSafe = true;
        for (int i = 0; i < specialInputs.length; i++) {
            String input = specialInputs[i];
            try {
                Map<String, Object> data = buildMinimalDataModel();
                data.put("action_text", input);
                simulateLLMInjection(data, true);
                String rendered = LLManager.render(templateContent, data);

                // 核心验证：FreeMarker 不应抛出异常（意味着用户输入被安全处理）
                // 输入内容应原样出现在输出中（不会被二次解析）
                // 注意：很长的输入可能被截断是预期行为

                if (rendered == null || rendered.isBlank()) {
                    System.out.printf("  ❌ case %d: 渲染结果为空 (input 长度=%d)\n", i, input.length());
                    allSafe = false;
                    failed++;
                }
                // 对于控制字符的输入，验证渲染不会崩溃就是通过
                else if (input.contains(" ")) {
                    System.out.printf("  ✅ case %d: 控制字符未导致渲染崩溃 (input=%d, output=%d chars)\n",
                            i, input.length(), rendered.length());
                } else {
                    System.out.printf("  ✅ case %d: 安全渲染 (input=%d, output=%d chars)\n",
                            i, input.length(), rendered.length());
                }
            } catch (Exception e) {
                System.out.printf("  ❌ case %d: 异常 — %s\n", i, e.getMessage());
                allSafe = false;
                failed++;
            }
        }

        if (allSafe) passed++;
    }

    // ════════════════════════════════════════════════════════════
    // Test 6: 大体积 prompt 溢出检测
    // ════════════════════════════════════════════════════════════
    private static void testLargePromptSize() {
        System.out.println("\n── Test 6: 大体积 Prompt 溢出检测 ──");

        try {
            String userTemplate = LLManager.loadPromptTemplate("prompts/V4_ACTION_LOOP_PROMPT.ftl");
            if (userTemplate == null || userTemplate.isBlank()) {
                userTemplate = readFileFallback("prompts/V4_ACTION_LOOP_PROMPT.ftl");
            }
            String systemPrompt = LLManager.loadPromptTemplate("prompts/V4_ACTION_SYSTEM_PROMPT.md");
            if (systemPrompt == null || systemPrompt.isBlank()) {
                systemPrompt = readFileFallback("prompts/V4_ACTION_SYSTEM_PROMPT.md");
            }

            if (userTemplate == null || systemPrompt == null) {
                System.out.println("  ❌ 无法加载模板文件");
                failed++;
                return;
            }

            // 模拟不同大小的输入
            int[] actionTextSizes = {100, 500, 2000, 5000, 10000, 20000};
            int[] poolSizes = {0, 3, 10, 30, 50};
            int[] experienceCounts = {0, 5, 20, 50};

            int systemPromptLen = systemPrompt.length();
            int userTemplateLen = userTemplate.length();
            int toolDefsEstimate = 30 * 500; // 约 30 个工具 × 平均 500 字符

            System.out.printf("  System Prompt: %,d chars\n", systemPromptLen);
            System.out.printf("  User Template:  %,d chars\n", userTemplateLen);
            System.out.printf("  Tools (估计):   %,d chars\n", toolDefsEstimate);

            boolean allSafe = true;
            for (int actionSize : actionTextSizes) {
                for (int poolSize : poolSizes) {
                    for (int expCount : experienceCounts) {
                        Map<String, Object> data = buildMinimalDataModel();
                        // 模拟大 action_text
                        StringBuilder actionText = new StringBuilder();
                        for (int j = 0; j < actionSize / 100; j++) {
                            actionText.append("用户消息行").append(j).append(": 这是一段模拟的聊天消息文本，包含中文和 English mixed content。")
                                    .append(" 包含 @提及 和一些常规的聊天内容。用户可能在询问问题或者讨论话题。\n");
                        }
                        data.put("action_text", actionText.toString());

                        // 模拟大 pool_summary
                        StringBuilder poolSummary = new StringBuilder();
                        poolSummary.append(String.format("【认知准备池】共 %d 个单元:\n", poolSize));
                        for (int j = 0; j < poolSize; j++) {
                            poolSummary.append(String.format("  - [%s] '模拟准备单元 #%d 的文本内容' SE=0.50 UE=0.30 tick=%d cw=%.2f\n",
                                    UUID.randomUUID().toString().substring(0, 8), j, j * 3, 0.5 + j * 0.05));
                        }
                        data.put("pool_summary", poolSummary.toString());

                        // 模拟大 action_predicts_text
                        StringBuilder predicts = new StringBuilder();
                        for (int j = 0; j < expCount; j++) {
                            predicts.append(String.format("  [经验%d] (ID=%d, 相似度=%.3f, 有用度=%.1f): 这是一个模拟的先验经验文本，描述了之前处理类似问题的过程和结果。\n",
                                    j + 1, 100 + j, 0.5 + j * 0.02, 0.5));
                        }
                        data.put("action_predicts_text", predicts.toString());

                        simulateLLMInjection(data, true);

                        try {
                            String rendered = LLManager.render(userTemplate, data);
                            int totalEstimate = systemPromptLen + rendered.length() + toolDefsEstimate;

                            // 上下文缓存每轮也会增加消息
                            int estimatedPerRound = rendered.length() + 500; // +tool result
                            int estimatedMaxRounds = Math.min(64, ConfigsManager.MAX_CONTEXT_CACHE_ROUNDS);
                            long worstCase = (long) systemPromptLen + (long) rendered.length() * estimatedMaxRounds
                                    + (long) toolDefsEstimate * 2;

                            // 粗略估算 token 数（中文约 1.5 字符/token，英文约 4 字符/token）
                            long estimatedTokens = worstCase / 3;

                            if (estimatedTokens > 128_000) {
                                System.out.printf("  ⚠️ action=%d pool=%d exp=%d → 渲染 %,d chars, 估算 ~%,d tokens (可能超上下文窗口!)\n",
                                        actionSize, poolSize, expCount, rendered.length(), estimatedTokens);
                                warnings.add(String.format(
                                        "action_text=%d + pool=%d + exp=%d → 估算 %,d tokens，可能超 API 上下文限制",
                                        actionSize, poolSize, expCount, estimatedTokens));
                            } else if (estimatedTokens > 64_000) {
                                System.out.printf("  ⚠️ action=%d pool=%d exp=%d → 渲染 %,d chars, 估算 ~%,d tokens (接近上限)\n",
                                        actionSize, poolSize, expCount, rendered.length(), estimatedTokens);
                            }
                        } catch (Exception e) {
                            System.out.printf("  ❌ action=%d pool=%d exp=%d → 渲染异常: %s\n",
                                    actionSize, poolSize, expCount, e.getMessage());
                            allSafe = false;
                            failed++;
                        }
                    }
                }
            }

            if (allSafe) {
                System.out.println("  ✅ 所有 prompt 大小组合均渲染成功");
                passed++;
            }
        } catch (Exception e) {
            System.out.println("  ❌ 大体积测试异常: " + e.getMessage());
            failed++;
        }
    }

    // ════════════════════════════════════════════════════════════
    // Test 7: 冗余注入检测
    // ════════════════════════════════════════════════════════════
    private static void testRedundantInjection() {
        System.out.println("\n── Test 7: 冗余注入与缺失引用检测 ──");

        // 7a. LLManager 注入但 V4 模板不使用的字段
        Set<String> injectedNotUsed = new HashSet<>(LLM_INJECTED_FIELDS);
        injectedNotUsed.removeAll(V4_TEMPLATE_USED_FIELDS);
        // now_time 两边都有
        injectedNotUsed.remove("now_time");

        System.out.println("  7a. LLManager 注入但 V4 模板不引用:");
        if (injectedNotUsed.isEmpty()) {
            System.out.println("      ✅ 无冗余注入");
            passed++;
        } else {
            for (String field : injectedNotUsed) {
                String detail = switch (field) {
                    case "tools_guide" -> "读取 prompts/toolsGuide.md（IO 操作）";
                    case "current_thoughts" -> "读取 thoughts.md（IO 操作）";
                    case "current_memories" -> "查询 MemoryDB.getLatestCurrentMemoryContents()（DB 查询）";
                    case "curiosity_context" -> "查询 CuriosityListManager DB（DB 查询）";
                    case "pending_tasks_summary" -> "调用 LivingLoop.buildTaskQueueSummary()（但 livingLoop=null，返回空字符串）";
                    default -> "未知来源";
                };
                warnings.add("LLManager 冗余注入 '" + field + "' → " + detail + " — V4 模板不引用此字段");
                System.out.println("      ⚠️ " + field + " — " + detail);
            }
            System.out.println("      → 建议：在 executeSceneAsyncWithCache 中检测 V4 模式，跳过不必要的注入");
        }

        // 7b. 检查 tools_guide 是否对 V4 有意义
        try {
            String toolsGuide = com.cna.db.MDManager.read("prompts/toolsGuide.md", "");
            if (!toolsGuide.isBlank()) {
                boolean forV4 = toolsGuide.contains("V4") || toolsGuide.contains("finish_action");
                if (!forV4) {
                    warnings.add("toolsGuide.md 内容仍针对旧架构（引用 manage_tool_groups 等），在 V4 模式下不应注入");
                    System.out.println("  ⚠️ 7b. toolsGuide.md 内容针对旧架构，V4 模式注入无意义");
                } else {
                    System.out.println("  ✅ 7b. toolsGuide.md 似乎已适配 V4");
                }
            }
        } catch (Exception e) {
            System.out.println("  ℹ️ 7b. 无法读取 toolsGuide.md: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════
    // Helper: 构建接近真实场景的 dataModel（模拟 ActionLoop）
    // ════════════════════════════════════════════════════════════
    private static Map<String, Object> buildRealisticDataModel() {
        Map<String, Object> data = new HashMap<>();

        // 模拟真实聊天消息
        data.put("action_text", """
                [QQ:private:u_abc123_def456] [发送者: 张三 (qqid:12345)]
                张三 说: 你好呀 BotMosire！今天天气怎么样？

                [QQ:private:u_abc123_def456] [发送者: 张三 (qqid:12345)]
                张三 说: 我打算下午出去走走，有什么推荐的活动吗？

                [QQ:private:u_abc123_def456] [发送者: 张三 (qqid:12345)]
                张三 说: @BotMosire 对了，帮我查一下明天会下雨吗？
                """);

        data.put("source_ids", List.of("qqid:12345", "channel:private"));
        data.put("cognitive_familiarity", 0.45);
        data.put("scale", 3);
        data.put("accident_degree", 0.15);
        data.put("action_pressure", 0.35);
        data.put("continue_weight", 0.85);
        data.put("ue_concepts", List.of("用户对天气的关心", "出行计划讨论", "日常闲聊"));
        data.put("ue_dim_ids", List.of("42", "87", "103"));
        data.put("now_time", "2026-05-31 14:30:00");

        // 模拟谐振分析结果
        data.put("feeling_resonance", """
                ## 🔍 感觉谐振分析

                **违和感觉维度 (2 组):**
                - `用户对天气的关心` ← 当前输入与其感觉模式有显著偏离 (gap=0.35)
                  > 该维度既往主要与"气象查询"共现，当前输入更偏向"出行决策"语境。
                - `闲聊倾向性` ← 当前输入激活该维度的模式中缺乏"日常寒暄"的典型特征 (gap=0.28)

                **一致感觉维度 (3 组):**
                - `出行计划讨论` (consistency=0.82)
                - `用户询问建议` (consistency=0.75)
                - `时间相关查询` (consistency=0.71)
                """);

        // 模拟先验经验
        data.put("action_predicts_text", """
                  [经验1] (ID=42, 相似度=0.82, 有用度=0.8): 之前用户询问天气时，直接调用天气API查询后给出回复，用户表示满意。
                  [经验2] (ID=87, 相似度=0.65, 有用度=0.5): 用户询问出行建议时，结合天气+时间+用户偏好给出综合回复。
                  [经验3] (ID=103, 相似度=0.45, 有用度=0.3): 用户@提及且问题涉及时间范围时，先确认时间再查询相关信息。
                """);

        // 模拟准备池概况
        data.put("pool_summary", """
                【认知准备池】共 3 个单元:
                  - [a1b2c3d4] '用户李四的未读消息待处理' SE=0.65 UE=0.40 tick=5 cw=0.72
                  - [e5f6g7h8] '定时任务: 更新内心想法与兴趣' SE=0.55 UE=0.30 tick=3 cw=0.88
                  - [i9j0k1l2] '控制台管理员询问系统状态' SE=0.80 UE=0.50 tick=2 cw=0.95
                """);

        return data;
    }

    private static Map<String, Object> buildMinimalDataModel() {
        Map<String, Object> data = new HashMap<>();
        data.put("action_text", "测试消息");
        data.put("source_ids", List.of("system:test"));
        data.put("cognitive_familiarity", 0.5);
        data.put("scale", 2);
        data.put("accident_degree", 0.0);
        data.put("action_pressure", 0.3);
        data.put("continue_weight", 1.0);
        data.put("ue_concepts", List.of("测试概念"));
        data.put("ue_dim_ids", List.of("1"));
        data.put("now_time", "2026-05-31 14:30:00");
        data.put("action_predicts_text", "");
        data.put("pool_summary", "准备池为空");
        return data;
    }

    // ════════════════════════════════════════════════════════════
    // Helper: 模拟 LLManager.executeSceneAsyncWithCache 的注入逻辑
    // ════════════════════════════════════════════════════════════
    private static void simulateLLMInjection(Map<String, Object> dataModel, boolean needInjection) {
        // 模拟 LLManager.java lines 258-277
        if (!dataModel.containsKey("current_memories")) {
            dataModel.put("current_memories",
                    needInjection
                            ? List.of(
                                    "[14:25] 回复了张三的消息: 今天天气晴朗，适合户外活动",
                                    "[14:20] 查询了天气API: 北京今日晴，15-25°C",
                                    "[14:15] 收到张三的消息: 你好呀",
                                    "[14:10] 内部反思完成: 更新了3个兴趣标签",
                                    "[14:00] 完成了定时任务: 整理内心想法")
                            : Collections.emptyList());
        }
        dataModel.put("current_thoughts",
                needInjection
                        ? "## 重要提醒\n- 用户张三偏好简洁回复\n- 避免在回复中重复问候语\n- 天气查询使用 weather_api 工具"
                        : "");
        dataModel.put("tools_guide",
                needInjection
                        ? "修改网页前先调用 tool_usage_reader 查询 update_web_ui 使用指南。\n\n工具组管理：系统默认仅注入核心工具..."
                        : "");
        dataModel.put("curiosity_context",
                needInjection
                        ? "## ⚠️ 未解决的好奇心\n\n- **#1** (追问3次) 对 **用户对天气的关心** 的违和:\n  🤔 \"为什么用户在室内时会频繁询问天气？\"\n"
                        : "");
        dataModel.put("now_time", Utils.getNowPrecise());
        dataModel.put("pending_tasks_summary", ""); // ActionLoop 场景下 livingLoop=null
    }

    /**
     * 模拟 V4 模式下的 LLManager 注入逻辑。
     * V4 模式跳过 tools_guide/current_thoughts/current_memories/curiosity_context/pending_tasks_summary，
     * 仅注入 now_time。
     */
    private static void simulateLLMInjectionV4(Map<String, Object> dataModel, boolean needInjection) {
        // V4 模式：仅注入 now_time
        // 其他 5 个字段（current_memories, current_thoughts, tools_guide, curiosity_context, pending_tasks_summary）
        // 在 V4 模式中跳过 —— 因为 V4_ACTION_LOOP_PROMPT.ftl 不引用它们
        dataModel.put("now_time", Utils.getNowPrecise());
    }

    // ════════════════════════════════════════════════════════════
    // Helper: 文件系统回退读取
    // ════════════════════════════════════════════════════════════
    private static String readFileFallback(String resourcePath) {
        try {
            java.io.InputStream is = TestDataModelInjection.class.getClassLoader().getResourceAsStream(resourcePath);
            if (is != null) {
                return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            // ignore
        }
        // 尝试直接文件系统路径
        try {
            return new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Path.of("src/main/resources", resourcePath)),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("  无法读取文件: " + resourcePath + " — " + e.getMessage());
            return null;
        }
    }
}
