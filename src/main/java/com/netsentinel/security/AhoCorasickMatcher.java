package com.netsentinel.security;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
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
        return findIn(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    public Optional<String> findIn(ByteBuf buffer) {
        if (buffer == null || !buffer.isReadable()) {
            return Optional.empty();
        }
        State state = newState();
        for (int i = buffer.readerIndex(); i < buffer.writerIndex(); i++) {
            Optional<String> match = state.accept(buffer.getByte(i));
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    public State newState() {
        return new State(root);
    }

    private Optional<String> findIn(byte[] buffer) {
        if (buffer == null || buffer.length == 0) {
            return Optional.empty();
        }
        Node cursor = root;
        for (int i = 0; i < buffer.length; i++) {
            int next = normalize(buffer[i]) & 0xFF;
            while (cursor != root && cursor.children[next] == null) {
                cursor = cursor.failure;
            }
            if (cursor.children[next] != null) {
                cursor = cursor.children[next];
            } else {
                cursor = root;
            }
            if (cursor.pattern != null) {
                return Optional.of(cursor.pattern);
            }
        }
        return Optional.empty();
    }

    private void addPattern(String pattern) {
        Node cursor = root;
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            int val = normalize(bytes[i]) & 0xFF;
            if (cursor.children[val] == null) {
                cursor.children[val] = new Node();
            }
            cursor = cursor.children[val];
        }
        cursor.pattern = pattern;
    }

    private void buildFailureLinks() {
        Queue<Node> queue = new ArrayDeque<>();
        root.failure = root;
        for (int i = 0; i < 256; i++) {
            Node child = root.children[i];
            if (child != null) {
                child.failure = root;
                queue.add(child);
            }
        }
        while (!queue.isEmpty()) {
            Node current = queue.remove();
            for (int i = 0; i < 256; i++) {
                Node child = current.children[i];
                if (child != null) {
                    Node fallback = current.failure;
                    while (fallback != root && fallback.children[i] == null) {
                        fallback = fallback.failure;
                    }
                    if (fallback.children[i] != null) {
                        child.failure = fallback.children[i];
                    } else {
                        child.failure = root;
                    }
                    if (child.pattern == null && child.failure.pattern != null) {
                        child.pattern = child.failure.pattern;
                    }
                    queue.add(child);
                }
            }
        }
    }

    private static byte normalize(byte value) {
        return value >= 'A' && value <= 'Z' ? (byte) (value + 32) : value;
    }

    public static final class State {
        private final Node root;
        private Node cursor;

        private State(Node root) {
            this.root = root;
            this.cursor = root;
        }

        public Optional<String> accept(byte value) {
            int next = normalize(value) & 0xFF;
            while (cursor != root && cursor.children[next] == null) {
                cursor = cursor.failure;
            }
            if (cursor.children[next] != null) {
                cursor = cursor.children[next];
            } else {
                cursor = root;
            }
            return cursor.pattern == null ? Optional.empty() : Optional.of(cursor.pattern);
        }
    }

    private static final class Node {
        private final Node[] children = new Node[256];
        private Node failure;
        private String pattern;
    }
}
