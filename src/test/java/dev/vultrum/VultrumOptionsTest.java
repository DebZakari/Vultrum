package dev.vultrum;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class VultrumOptionsTest {
    @Test
    void everySettingHasANameAndTooltip() throws IOException {
        String lang;
        try (InputStream in = VultrumOptionsTest.class.getResourceAsStream("/assets/vultrum/lang/en_us.json")) {
            assertNotNull(in);
            lang = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // Minecraft drops a language file it can't parse, and every name with it.
        assertDoesNotThrow(() -> JsonParser.parseString(lang).getAsJsonObject());
        for (String property : VultrumConfig.KEYS) {
            assertTrue(lang.contains('"' + VultrumOptions.langKey(property) + "\":"), property);
            assertTrue(lang.contains('"' + VultrumOptions.langKey(property) + ".tooltip\":"), property);
        }
        assertEquals("vultrum.options.sunless_shadow", VultrumOptions.langKey(VultrumConfig.SUNLESS_SHADOW));
    }
}
