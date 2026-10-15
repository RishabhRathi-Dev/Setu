package io.setu.storage.runtime;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Packaged copy of config/jvm.options, shared by all isolated benchmark workers. */
public final class JvmOptions {
    private JvmOptions() { }

    public static List<String> arguments() {
        var input = JvmOptions.class.getResourceAsStream("/jvm.options");
        if (input == null) throw new IllegalStateException("Missing packaged jvm.options");
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return reader.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot read JVM options", failure); }
    }
}
