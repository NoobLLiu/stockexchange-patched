package com.github.exchange.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class SlimefunSearchTest {
    public static void main(String[] args) {
        String serialized = """
            item:
              components:
                minecraft:custom_data: '{PublicBukkitValues:{"slimefun:slimefun_item":"COAL_GENERATOR"}}'
            """;
        String base64 = Base64.getEncoder().encodeToString(
            serialized.getBytes(StandardCharsets.UTF_8)
        );
        assert "COAL_GENERATOR".equals(SlimefunSearch.serializedSlimefunItemId(base64));
        assert SlimefunSearch.serializedSlimefunItemId("not-base64") == null;
        assert SlimefunSearch.serializedSlimefunItemId(
            Base64.getEncoder().encodeToString("item: {}".getBytes(StandardCharsets.UTF_8))
        ) == null;
        System.out.println("PASSED SlimefunSearchTest");
    }
}
