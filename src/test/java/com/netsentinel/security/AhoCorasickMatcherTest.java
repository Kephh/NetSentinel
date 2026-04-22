package com.netsentinel.security;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AhoCorasickMatcherTest {
    @Test
    void matchesTextCaseInsensitively() {
        AhoCorasickMatcher matcher = new AhoCorasickMatcher(List.of("union select", "<script"));

        assertEquals("union select", matcher.findIn("/search?q=UNION SELECT password").orElseThrow());
    }

    @Test
    void scansByteBufWithoutChangingReaderIndex() {
        AhoCorasickMatcher matcher = new AhoCorasickMatcher(List.of("javascript:"));
        var buffer = Unpooled.copiedBuffer("payload=javascript:alert(1)", StandardCharsets.UTF_8);
        int readerIndex = buffer.readerIndex();

        assertTrue(matcher.findIn(buffer).isPresent());
        assertEquals(readerIndex, buffer.readerIndex());
    }
}
