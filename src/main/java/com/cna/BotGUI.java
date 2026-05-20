package com.cna;
import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.io.OutputStream;
import java.io.PrintStream;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

//测试Class
public class BotGUI {
    private final JTextArea logArea;
    private final JTextField commandInput;

    public BotGUI() {
        // 设置系统 UI 风格（Windows 11 原生感）
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}

        JFrame frame = new JFrame("BotMosire 控制台");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(900, 600);
        frame.setLayout(new BorderLayout());

        // 1. 日志显示区域 (JTextArea)
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(new Color(30, 30, 30)); // 黑色背景
        logArea.setForeground(new Color(220, 220, 220)); // 浅灰色文字
        logArea.setFont(new Font("Consolas", Font.PLAIN, 14));
        logArea.setLineWrap(true);

        // 自动滚动到最后一行
        ((DefaultCaret) logArea.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder(" 运行日志 "));
        frame.add(scrollPane, BorderLayout.CENTER);

        // 2. 指令输入区域 (TextField)
        commandInput = new JTextField();
        commandInput.setBackground(new Color(45, 45, 45));
        commandInput.setForeground(Color.GREEN);
        commandInput.setCaretColor(Color.WHITE);
        commandInput.setFont(new Font("Consolas", Font.BOLD, 14));

        // 绑定回车事件
        commandInput.addActionListener(e -> {
            String cmd = commandInput.getText().trim();
            if (!cmd.isEmpty()) {
                // 在这里对接你的指令逻辑，例如：ConsoleCommandSystem.execute(cmd);
                System.out.println("> 执行指令: " + cmd);
                commandInput.setText("");
            }
        });

        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.add(new JLabel(" 指令: "), BorderLayout.WEST);
        southPanel.add(commandInput, BorderLayout.CENTER);
        frame.add(southPanel, BorderLayout.SOUTH);

        // 3. 重定向系统流
        redirectStreams();

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void redirectStreams() {
        OutputStream guiOutputStream = new OutputStream() {
            @Override
            public void write(int b) {
                updateLog(String.valueOf((char) b));
            }

            @Override
            public void write(byte[] b, int off, int len) {
                // 解决 ANSI 乱码：过滤掉那些 [34m 之类的符号
                String text = new String(b, off, len, StandardCharsets.UTF_8);
                updateLog(text.replaceAll("\u001B\\[[;\\d]*m", ""));
            }
        };

        PrintStream ps = new PrintStream(guiOutputStream, true, StandardCharsets.UTF_8);
        System.setOut(ps);
        System.setErr(ps);
    }

    private void updateLog(String text) {
        // 确保在 Swing 的事件分发线程中更新 UI，防止并发崩溃
        SwingUtilities.invokeLater(() -> logArea.append(text));
    }
}