package com.makar.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MinecraftLaunchServiceTest {
    @Test
    void producesExactlyOneXmsAndXmxAndReservesFourGb() {
        MinecraftLaunchService service = new MinecraftLaunchService();
        List<String> arguments = new ArrayList<>(List.of("-Xms4G", "-Xmx30G", "-Dexample=true"));

        service.applyMemoryArguments(arguments, 30);

        assertEquals(1, arguments.stream().filter(value -> value.startsWith("-Xms")).count());
        assertEquals(1, arguments.stream().filter(value -> value.startsWith("-Xmx")).count());
        assertEquals("-Xms512M", arguments.stream().filter(value -> value.startsWith("-Xms")).findFirst().orElseThrow());
        assertEquals(4, MinecraftLaunchService.capMemoryForSystem(12, 8L * 1024 * 1024 * 1024));
        assertEquals(12, MinecraftLaunchService.capMemoryForSystem(12, 16L * 1024 * 1024 * 1024));
        assertEquals(20, MinecraftLaunchService.capMemoryForSystem(30, 24L * 1024 * 1024 * 1024));
        assertEquals(28, MinecraftLaunchService.capMemoryForSystem(30, 32L * 1024 * 1024 * 1024));
        service.close();
    }
}
