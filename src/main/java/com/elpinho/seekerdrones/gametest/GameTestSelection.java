package com.elpinho.seekerdrones.gametest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;

import com.elpinho.seekerdrones.SeekerDrones;
import com.mojang.logging.LogUtils;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Lets the GameTest server run a subset of the tests, several times over. Dev tooling only; it does nothing unless
 * one of these system properties is set (the {@code gameTestServer} run passes them through from Gradle):
 * <ul>
 * <li>{@code seekerdrones.gametest.filter} ({@code -PgametestFilter=...}): a case-insensitive regex, searched for in
 * {@code <class>.<method>} of each test, e.g.
 * {@code ImprovementGameTests.xrayDroneDoesNotAcquireInvisibleUnequippedZombie}. The registered test name is only
 * the method name in lower case ({@code @PrefixGameTestTemplate(false)}), so the class comes from the holders.</li>
 * <li>{@code seekerdrones.gametest.repeat} ({@code -PgametestRepeat=N}): runs every selected test N times. Each
 * extra round gets its own batches, so a round runs with the same concurrency as a normal run, and a test that
 * changes config in its own batch never overlaps its copies.</li>
 * </ul>
 *
 * {@code GameTestServer} copies the registered tests in its constructor, but only turns them into batches after
 * firing {@link ServerAboutToStartEvent}, so this edits its private list in that event.
 */
public final class GameTestSelection {
    public static final String FILTER_PROPERTY = "seekerdrones.gametest.filter";
    public static final String REPEAT_PROPERTY = "seekerdrones.gametest.repeat";
    private static final Logger LOGGER = LogUtils.getLogger();

    private GameTestSelection() {
    }

    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        String filter = System.getProperty(FILTER_PROPERTY, "").trim();
        int repeat = Integer.getInteger(REPEAT_PROPERTY, 1);
        if (!(event.getServer() instanceof GameTestServer server) || (filter.isEmpty() && repeat <= 1)) {
            return;
        }
        List<TestFunction> tests = testFunctions(server);
        if (!filter.isEmpty()) {
            Pattern pattern = Pattern.compile(filter, Pattern.CASE_INSENSITIVE);
            Map<String, String> qualifiedNames = qualifiedTestNames();
            tests.removeIf(test -> !pattern.matcher(qualifiedNames.getOrDefault(test.testName(), test.testName())).find());
            if (tests.isEmpty()) {
                throw new IllegalStateException("No GameTest matches the filter '" + filter + "'");
            }
        }
        List<TestFunction> selected = List.copyOf(tests);
        for (int round = 2; round <= repeat; round++) {
            for (TestFunction test : selected) {
                tests.add(copy(test, round));
            }
        }
        LOGGER.info("GameTest selection: {} test(s) matching '{}', {} round(s), {} test run(s) in total",
                selected.size(), filter, Math.max(repeat, 1), tests.size());
    }

    private static TestFunction copy(TestFunction test, int round) {
        String suffix = "_repeat" + round;
        return new TestFunction(test.batchName() + suffix, test.testName() + suffix, test.structureName(), test.rotation(), test.maxTicks(),
                test.setupTicks(), test.required(), test.manualOnly(), test.maxAttempts(), test.requiredSuccesses(), test.skyAccess(),
                test.function());
    }

    /** Test name (lower-case method name) to {@code <Class>.<method>}, for this mod's {@code @GameTestHolder} classes. */
    private static Map<String, String> qualifiedTestNames() {
        Map<String, String> names = new HashMap<>();
        String holder = GameTestHolder.class.getName();
        ModList.get().getModFileById(SeekerDrones.MODID).getFile().getScanResult().getAnnotations().stream()
                .filter(annotation -> annotation.annotationType().getClassName().equals(holder))
                .forEach(annotation -> {
                    try {
                        Class<?> type = Class.forName(annotation.clazz().getClassName(), false, GameTestSelection.class.getClassLoader());
                        for (Method method : type.getDeclaredMethods()) {
                            if (method.isAnnotationPresent(GameTest.class)) {
                                names.put(method.getName().toLowerCase(Locale.ROOT), type.getSimpleName() + "." + method.getName());
                            }
                        }
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                });
        return names;
    }

    @SuppressWarnings("unchecked")
    private static List<TestFunction> testFunctions(GameTestServer server) {
        try {
            Field field = GameTestServer.class.getDeclaredField("testFunctions");
            field.setAccessible(true);
            return (List<TestFunction>) field.get(server);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Can't reach GameTestServer.testFunctions to filter or repeat tests", e);
        }
    }
}
