import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * Compiles every compute shader (*.comp) under a directory to SPIR-V for Vulkan 1.2, with the shaderc
 * that Minecraft ships. Usage: java -cp <lwjgl + lwjgl-shaderc jars> tools/ShaderCompiler.java src out
 * Writes out/<name>.spv; exits 1 on the first compile error.
 */
public class ShaderCompiler {
    public static void main(String[] args) throws Exception {
        Path src = Path.of(args[0]), out = Path.of(args[1]);
        Files.createDirectories(out);
        long compiler = shaderc_compiler_initialize(), options = shaderc_compile_options_initialize();
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
        shaderc_compile_options_set_warnings_as_errors(options);
        try (Stream<Path> files = Files.list(src)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".comp")).sorted().toList()) {
                String name = file.getFileName().toString();
                long result = shaderc_compile_into_spv(compiler, Files.readString(file), shaderc_compute_shader, name, "main", options);
                if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                    System.err.println(shaderc_result_get_error_message(result));
                    System.exit(1);
                }
                ByteBuffer spirv = shaderc_result_get_bytes(result);
                Path target = out.resolve(name.substring(0, name.length() - ".comp".length()) + ".spv");
                try (var channel = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    channel.write(spirv);
                }
                shaderc_result_release(result);
                System.out.println("compiled " + name + " -> " + target.getFileName());
            }
        }
        shaderc_compile_options_release(options);
        shaderc_compiler_release(compiler);
    }
}
