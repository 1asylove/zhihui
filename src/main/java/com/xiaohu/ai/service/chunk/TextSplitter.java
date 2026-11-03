package com.xiaohu.ai.service.chunk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 滑动窗口 + 自然边界回退切片。
 * 优先在段落边界断开，其次句子，找不到才硬切。
 */
@Component
public class TextSplitter {

    @Value("${chunk.size:500}")
    private int chunkSize;

    @Value("${chunk.overlap:100}")
    private int overlap;

    public List<String> split(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }

        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        int len = text.length();
        int start = 0;

        while (start < len) {
            int end = Math.min(start + chunkSize, len);
            if (end < len) {
                end = findBoundary(text, start, end);
            }

            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) {
                result.add(piece);
            }

            if (end >= len) {
                break;
            }
            // 下一片起点回退 overlap；至少前进 1，防止死循环
            start = Math.max(end - overlap, start + 1);
        }
        return result;
    }

    /**
     * 在 [lower, end) 内向前找最近的边界：
     * 先段落（\n\n），再句子（。！？\n），都没有就硬切。
     * lower = start + chunkSize/2，保证片长不小于一半，避免碎片化。
     */
    private int findBoundary(String text, int start, int end) {
        int lower = start + chunkSize / 2;

        // 段落边界：从 end-2 往回找，避免命中越过 end 的 \n\n
        int para = text.lastIndexOf("\n\n", end - 2);
        if (para >= lower) {
            return para + 2;
        }

        for (int i = end - 1; i >= lower; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n') {
                return i + 1;
            }
        }
        return end;
    }
}
