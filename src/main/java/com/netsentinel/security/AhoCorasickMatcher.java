package com.netsentinel.security;

import io.netty.buffer.ByteBuf;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;

public final class AhoCorasickMatcher {
    private final Node root = new Node();

    public AhoCorasickMatcher(List<String> patterns) {
        Objects.requireNonNull(patterns, "patterns");
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            addPattern(pattern.toLowerCase(Locale.ROOT));
        }
        buildFailureLinks();
    }

    public Optional<String> findIn(CharSequence value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        Node cursor = root;
        for (int i = 0; i < value.length(); i++) {
            char next = normalize(value.charAt(i));
            while (cursor != root && !cursor.children.containsKey(next)) {
                cursor = cursor.failure;
            }
            cursor = cursor.children.getOrDefault(next, root);
            if (cursor.pattern != null) {
                return Optional.of(cursor.pattern);
            }
        }
        return Optional.empty();
    }

    public Optional<String> findIn(ByteBuf buffer) {
        if (buffer == null || !buffer.isReadable()) {
            return Optional.empty();
        }
        Node cursor = root;
        for (int i = buffer.readerIndex(); i < buffer.writerIndex(); i++) {
            char next = normalize((char) buffer.getUnsignedByte(i));
            while (cursor != root && !cursor.children.containsKey(next)) {
                cursor = cursor.failure;
            }
            cursor = cursor.children.getOrDefault(next, root);
            if (cursor.pattern != null) {
                return Optional.of(cursor.pattern);
            }
        }
        return Optional.empty();
    }

    private void addPattern(String pattern) {
        Node cursor = root;
        for (int i = 0; i < pattern.length(); i++) {
            char value = normalize(pattern.charAt(i));
            cursor = cursor.children.computeIfAbsent(value, ignored -> new Node());
        }
        cursor.pattern = pattern;
    }

    private void buildFailureLinks() {
        Queue<Node> queue = new ArrayDeque<>();
        root.failure = root;
        for (Node child : root.children.values()) {
            child.failure = root;
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            Node current = queue.remove();
            for (Map.Entry<Character, Node> transition : current.children.entrySet()) {
                char character = transition.getKey();
                Node child = transition.getValue();
                Node fallback = current.failure;
                while (fallback != root && !fallback.children.containsKey(character)) {
                    fallback = fallback.failure;
                }
                child.failure = fallback.children.getOrDefault(character, root);
                if (child.pattern == null && child.failure.pattern != null) {
                    child.pattern = child.failure.pattern;
                }
                queue.add(child);
            }
        }
    }

    private static char normalize(char value) {
        return value >= 'A' && value <= 'Z' ? (char) (value + 32) : value;
    }

    private static final class Node {
        private final Map<Character, Node> children = new HashMap<>();
        private Node failure;
        private String pattern;
    }
}
