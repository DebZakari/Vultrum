package dev.vultrum.cull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reads std430 buffer blocks out of Vultrum's GLSL sources, for layout contract tests. */
final class ShaderBlocks {
    private ShaderBlocks() { }

    /** Member name to std430 offset in the shader's {@code buffer <name> { ... };} block. */
    static Map<String, Integer> offsets(String shader, String block) throws IOException {
        var matcher = Pattern.compile("buffer " + block + " \\{(.*?)};", Pattern.DOTALL).matcher(source(shader));
        assertTrue(matcher.find(), "no " + block + " block in " + shader);
        var members = new LinkedHashMap<String, Integer>();
        int offset = 0;
        var member = Pattern.compile("^\\s*(\\w+)\\s+(\\w+)(\\[])?;", Pattern.MULTILINE).matcher(matcher.group(1));
        while (member.find()) {
            int[] layout = std430(member.group(1)); // alignment, size
            offset = Math.ceilDiv(offset, layout[0]) * layout[0];
            members.put(member.group(2), offset);
            offset += layout[1];
        }
        return members;
    }

    static String source(String shader) throws IOException {
        return Files.readString(Path.of(System.getProperty("vultrum.glsl"), shader));
    }

    /** Alignment and size of a std430 member. */
    static int[] std430(String type) {
        return switch (type) {
            case "uint", "int", "float" -> new int[] {4, 4};
            case "uvec2", "ivec2", "vec2" -> new int[] {8, 8};
            case "uvec3", "ivec3", "vec3" -> new int[] {16, 12};
            case "uvec4", "ivec4", "vec4" -> new int[] {16, 16};
            case "mat4" -> new int[] {16, 64};
            default -> throw new AssertionError("add std430 layout for " + type);
        };
    }
}
