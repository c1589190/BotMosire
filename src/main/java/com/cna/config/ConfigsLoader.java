package com.cna.config;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class ConfigsLoader {

    private static final Map<String, String> FILE_MAP = new LinkedHashMap<>();

    static {
        // 1. 基础配置文件 (模板 -> 实际配置)
        FILE_MAP.put("/application-template.properties", "application.properties");
        FILE_MAP.put("/PromptScenesPath.properties", "PromptScenesPath.properties");

        // 2. 自动扫描 classpath 下的 /prompts/ 目录，不再手动维护白名单
        List<String> promptFiles = discoverPromptFiles("/prompts/");
        for (String fileName : promptFiles) {
            FILE_MAP.put("/prompts/" + fileName, "prompts/" + fileName);
        }
        log.info("[ConfigsLoader] 从 classpath 扫描到 {} 个 prompt 资源文件: {}",
                promptFiles.size(),
                promptFiles.stream().sorted().collect(Collectors.joining(", ")));

        // 3. MCP 服务器配置模板
        FILE_MAP.put("/mcp-servers-template.json", "mcp-servers.json");
    }

    // =====================================================
    // Classpath 目录扫描
    // =====================================================

    /**
     * 扫描 classpath 下指定目录中的所有文件（仅文件名，不含子目录）。
     * 支持 exploded classpath（IDE/开发环境）和 JAR 内 classpath（生产部署）。
     *
     * @param classpathDir 以 / 开头的 classpath 目录路径，如 "/prompts/"
     * @return 文件名列表，扫描失败返回空列表
     */
    private static List<String> discoverPromptFiles(String classpathDir) {
        try {
            URL dirURL = ConfigsLoader.class.getResource(classpathDir);
            if (dirURL == null) {
                log.warn("[ConfigsLoader] 无法找到 classpath 目录: {}", classpathDir);
                return List.of();
            }

            if ("file".equals(dirURL.getProtocol())) {
                // exploded classpath — 直接遍历文件系统目录
                try (Stream<Path> files = Files.list(Paths.get(dirURL.toURI()))) {
                    return files.filter(Files::isRegularFile)
                            .map(p -> p.getFileName().toString())
                            .collect(Collectors.toList());
                }
            } else if ("jar".equals(dirURL.getProtocol())) {
                // JAR 内 classpath — 解析 jar 条目
                String path = dirURL.getPath();
                String jarPath = path.substring(5, path.indexOf("!"));
                String prefix = classpathDir.substring(1); // 去掉前导 /，如 "prompts/"
                try (var jar = new java.util.jar.JarFile(
                        URLDecoder.decode(jarPath, StandardCharsets.UTF_8))) {
                    return jar.stream()
                            .filter(e -> !e.isDirectory())
                            .filter(e -> e.getName().startsWith(prefix)
                                    && e.getName().length() > prefix.length())
                            .map(e -> {
                                String name = e.getName();
                                return name.substring(name.lastIndexOf('/') + 1);
                            })
                            .collect(Collectors.toList());
                }
            }

            log.warn("[ConfigsLoader] 不支持的 classpath 协议 [{}]，跳过目录扫描", dirURL.getProtocol());
            return List.of();
        } catch (Exception e) {
            log.error("[ConfigsLoader] 扫描 classpath 目录失败: {}", classpathDir, e);
            return List.of();
        }
    }

    // =====================================================
    // 资源释放
    // =====================================================

    /**
     * 释放所有必要的资源文件到程序运行目录。
     * 特殊处理：application.properties 在已存在时会合并模板中的新条目而非跳过。
     */
    public static void loadAll() {
        log.info("[ConfigsLoader] 正在检查 BotMosire 外部运行环境...");
        int releaseCount = 0;

        for (Map.Entry<String, String> entry : FILE_MAP.entrySet()) {
            String resourcePath = entry.getKey();
            Path targetPath = Paths.get(entry.getValue());

            // application.properties 特殊处理：不存在时全量释放，已存在时合并新条目
            if ("/application-template.properties".equals(resourcePath)) {
                if (Files.notExists(targetPath)) {
                    if (releaseResource(resourcePath, targetPath)) releaseCount++;
                } else {
                    mergePropertiesTemplate(resourcePath, targetPath);
                }
                continue;
            }

            // 其他文件：仅在不存在时释放，避免覆盖用户的本地修改
            if (Files.notExists(targetPath)) {
                if (releaseResource(resourcePath, targetPath)) {
                    releaseCount++;
                }
            }
        }

        if (releaseCount > 0) {
            log.info("[ConfigsLoader] 环境初始化完毕，新增了 {} 个资源文件。", releaseCount);
        } else {
            log.info("[ConfigsLoader] 运行环境检查完毕，所有配置已就绪。");
        }
    }

    private static boolean releaseResource(String resourcePath, Path targetPath) {
        try (InputStream in = ConfigsLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                log.error("[ConfigsLoader] ❌ 资源遗失: {}", resourcePath);
                return false;
            }

            // 自动创建 prompts 文件夹等父级目录
            if (targetPath.getParent() != null) {
                Files.createDirectories(targetPath.getParent());
            }

            Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("[ConfigsLoader] 已释放: {}", targetPath.getFileName());
            return true;
        } catch (IOException e) {
            log.error("[ConfigsLoader] 释放资源 {} 失败: {}", resourcePath, e.getMessage());
            return false;
        }
    }

    // =====================================================
    // Properties 增量合并
    // =====================================================

    /**
     * 将模板中"现有文件里不存在的 key"追加到目标文件末尾。
     * 保留原有配置不变，保留模板中的注释块。
     */
    private static void mergePropertiesTemplate(String templatePath, Path targetPath) {
        try {
            // 1. 加载模板 properties，找出所有 key
            Properties templateProps = new Properties();
            try (InputStream in = ConfigsLoader.class.getResourceAsStream(templatePath)) {
                if (in == null) {
                    log.error("[ConfigsLoader] 无法加载 application 模板: {}", templatePath);
                    return;
                }
                templateProps.load(in);
            }

            // 2. 加载现有 properties
            Properties existingProps = new Properties();
            try (InputStream in = Files.newInputStream(targetPath)) {
                existingProps.load(in);
            }

            // 3. 找出模板中有而现有文件中没有的 key
            Set<String> missingKeys = new LinkedHashSet<>();
            for (String key : templateProps.stringPropertyNames()) {
                if (!existingProps.containsKey(key)) {
                    missingKeys.add(key);
                }
            }

            if (missingKeys.isEmpty()) {
                log.debug("[ConfigsLoader] application.properties 已包含所有模板条目，无需补全");
                return;
            }

            // 4. 从模板文本中提取缺失 key 对应的行块（含注释）
            List<String> newBlocks = extractMissingBlocks(templatePath, missingKeys);

            if (newBlocks.isEmpty()) {
                log.warn("[ConfigsLoader] 未从模板中找到 {} 个缺失 key 的文本块", missingKeys.size());
                return;
            }

            // 5. 追加到现有文件末尾
            List<String> existingLines = Files.readAllLines(targetPath, StandardCharsets.UTF_8);

            // 确保末尾有空行分隔
            if (!existingLines.isEmpty() && !existingLines.get(existingLines.size() - 1).isBlank()) {
                existingLines.add("");
            }
            existingLines.add("# ===== " + LocalDate.now() + " 自动补全 " + missingKeys.size() + " 个新条目 =====");
            existingLines.addAll(newBlocks);

            Files.write(targetPath, existingLines, StandardCharsets.UTF_8);
            log.info("[ConfigsLoader] 已向 application.properties 追加 {} 个新条目: {}",
                    missingKeys.size(), String.join(", ", missingKeys));
        } catch (Exception e) {
            log.error("[ConfigsLoader] 合并 application.properties 失败", e);
        }
    }

    /**
     * 从模板文本中提取指定 key 的行块（前置注释行 + 键值行）。
     * 注释归属规则：紧邻在 key=value 上方、中间没有空行分隔的 # 行视为该 key 的注释。
     */
    private static List<String> extractMissingBlocks(String templatePath, Set<String> targetKeys) throws IOException {
        // 先读全部模板行
        List<String> allLines;
        try (InputStream in = ConfigsLoader.class.getResourceAsStream(templatePath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            allLines = reader.lines().collect(Collectors.toList());
        }

        List<String> result = new ArrayList<>();
        List<String> pendingComments = new ArrayList<>();

        for (String line : allLines) {
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                // 空行重置待定注释缓存
                pendingComments.clear();
            } else if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
                pendingComments.add(line);
            } else {
                // 键值行
                int eqIdx = line.indexOf('=');
                if (eqIdx > 0) {
                    String key = line.substring(0, eqIdx).trim();
                    if (targetKeys.contains(key)) {
                        if (!pendingComments.isEmpty()) {
                            if (!result.isEmpty()) result.add(""); // 跟上一个块之间空一行
                            result.addAll(pendingComments);
                        }
                        result.add(line);
                        pendingComments.clear();
                    } else {
                        pendingComments.clear();
                    }
                } else {
                    pendingComments.clear();
                }
            }
        }

        return result;
    }
}
