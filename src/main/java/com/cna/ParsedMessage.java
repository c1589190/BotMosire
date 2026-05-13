package com.cna;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 消息解析結果封裝。
 * 包含：解析後的文字內容 + 被引用回覆的 message_id（0 表示無引用）
 */
public class ParsedMessage {
    public final String content;
    /** Napcat message_id，0 表示這條訊息不是引用回覆 */
    public final long quotedMessageId;

    public ParsedMessage(String content, long quotedMessageId) {
        this.content = content;
        this.quotedMessageId = quotedMessageId;
    }

    public static ParsedMessage of(String content) {
        return new ParsedMessage(content, 0);
    }

    public static ParsedMessage of(JsonNode messageNode) {
        return parseMessageArray(messageNode);
    }

    /**
     * 解析 Napcat 消息段陣列。
     * 同時提取文字內容與引用回覆的 message_id。
     */
    public static ParsedMessage parseMessageArray(JsonNode messageNode) {
        if (messageNode == null || messageNode.isMissingNode()) return of("");
        if (messageNode.isTextual()) return of(messageNode.asText().trim());
        if (!messageNode.isArray()) return of("");

        StringBuilder parsedContent = new StringBuilder();
        long quotedId = 0;

        for (JsonNode segment : messageNode) {
            String type = segment.path("type").asText("");
            JsonNode data = segment.path("data");

            switch (type) {
                case "text":
                    parsedContent.append(data.path("text").asText(""));
                    break;
                case "image": {
                    String imageUrl = data.path("url").asText("");
                    String fileId = data.path("file").asText("");
                    String cacheKey = fileId.isBlank() ? imageUrl.split("\\?")[0] : fileId;
                    // 圖片解析在 handleMessageEvent 統一處理，這裡只做標記
                    parsedContent.append("[圖片]");
                    break;
                }
                case "at":
                    parsedContent.append("[@").append(data.path("qq").asText("")).append("]");
                    break;
                case "face":
                    parsedContent.append("[表情]");
                    break;
                case "forward": {
                    // 合併轉發在 handleMessageEvent 統一處理，這裡只做標記
                    parsedContent.append("[合併轉發]");
                    break;
                }
                case "reply": {
                    // 引用回覆：記錄被引用訊息的 ID
                    String replyIdStr = data.path("id").asText("");
                    if (!replyIdStr.isBlank()) {
                        try {
                            quotedId = Long.parseLong(replyIdStr);
                            parsedContent.append("[引用回覆: ").append(replyIdStr).append("]");
                        } catch (NumberFormatException ignored) {}
                    }
                    break;
                }
            }
        }
        return new ParsedMessage(parsedContent.toString().trim(), quotedId);
    }
}