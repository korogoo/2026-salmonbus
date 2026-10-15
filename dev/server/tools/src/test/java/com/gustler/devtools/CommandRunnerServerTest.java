package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@Tag("linux-root")
class CommandRunnerServerTest {
    @TempDir Path base;

    @Test
    void timeoutKillsChildrenIgnoringTermWithoutWaitingForTheirOutput() throws Exception {
        var script = base.resolve("child.sh");
        var written = base.resolve("late-write");
        Files.writeString(script, "trap '' TERM\nsleep 2\necho unexpected > \"$1\"\nsleep 10\n");
        final long started = System.nanoTime();
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new CommandRunner()
                                        .run(
                                                base,
                                                null,
                                                Duration.ofMillis(300),
                                                List.of(
                                                        "/bin/sh",
                                                        "-c",
                                                        "/bin/sh \"$1\" \"$2\" & wait",
                                                        "test",
                                                        script.toString(),
                                                        written.toString())));
        assertEquals("DEV_COMMAND_TIMEOUT", error.getMessage());
        assertTrue(
                Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
        Thread.sleep(2200);
        assertFalse(Files.exists(written));
    }

    @Test
    void 부모가_종료되면_자식의_출력을_기다리느라_제한_시간을_넘기지_않는다() throws Exception {
        // given
        final long started = System.nanoTime();

        // when
        try {
            var result = new CommandRunner().run(
                    base, null, Duration.ofMillis(300),
                    List.of("/bin/sh", "-c", "(trap '' TERM; sleep 12) & exit 0"));
            // Java가 부모 종료 시 출력 스트림을 먼저 닫으면 정상 반환할 수 있다.
            assertEquals(0, result.exit());
            assertEquals("", result.stdout());
            assertEquals("", result.stderr());
        } catch (IllegalArgumentException error) {
            // 출력 읽기가 먼저 시작됐다면 같은 제한 시간으로 중단해야 한다.
            assertEquals("DEV_COMMAND_TIMEOUT", error.getMessage());
        }

        // then
        assertTrue(
                Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
    }

    @Test
    void successfulCommandsReturnBothStreamsAndExitStatus() throws Exception {
        var result =
                new CommandRunner()
                        .run(
                                base,
                                null,
                                Duration.ofSeconds(3),
                                List.of(
                                        "/bin/sh",
                                        "-c",
                                        "printf output; printf diagnostic >&2; exit 7"));
        assertEquals(7, result.exit());
        assertEquals("output", result.stdout());
        assertEquals("diagnostic", result.stderr());
    }
}
