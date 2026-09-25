package com.github.claudecodegui.provider.pi;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PiHistoryReaderTest {

    @Test
    public void normalizePathConvertsBackslashesAndDriveCase() {
        assertEquals("c:/Users/83429/project",
                PiHistoryReader.normalizePath("C:\\Users\\83429\\project"));
        assertEquals("c:/Users/83429/project",
                PiHistoryReader.normalizePath("C:/Users/83429/project/"));
    }

    @Test
    public void pathsMatchIsCaseInsensitiveOnWindowsStylePaths() {
        assertTrue(PiHistoryReader.pathsMatch(
                "C:\\Users\\83429\\AppData\\project",
                "c:/Users/83429/AppData/project"));
        assertFalse(PiHistoryReader.pathsMatch(
                "C:\\Users\\83429\\project-a",
                "C:\\Users\\83429\\project-b"));
    }

    @Test
    public void listsAndLoadsSessionFromJsonl() throws Exception {
        Path root = Files.createTempDirectory("pi-history-test");
        Path cwdDir = root.resolve("--C-Users-83429-project--");
        Files.createDirectories(cwdDir);

        String sessionId = "019fe705-27fd-712e-a1be-f972ef3773f3";
        Path file = cwdDir.resolve("2026-08-09T14-55-02-653Z_" + sessionId + ".jsonl");
        String jsonl = ""
                + "{\"type\":\"session\",\"version\":3,\"id\":\"" + sessionId
                + "\",\"timestamp\":\"2026-08-09T14:55:02.653Z\",\"cwd\":\"C:\\\\Users\\\\83429\\\\project\"}\n"
                + "{\"type\":\"message\",\"id\":\"m1\",\"parentId\":null,\"timestamp\":\"2026-08-09T14:55:02.745Z\","
                + "\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hello from windows\"}]}}\n"
                + "{\"type\":\"message\",\"id\":\"m2\",\"parentId\":\"m1\",\"timestamp\":\"2026-08-09T14:55:03.000Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"hi\"},"
                + "{\"type\":\"text\",\"text\":\"你好\"},"
                + "{\"type\":\"toolCall\",\"id\":\"tool_1\",\"name\":\"bash\",\"arguments\":{\"command\":\"dir\"}}"
                + "]}}\n"
                + "{\"type\":\"message\",\"id\":\"m3\",\"parentId\":\"m2\",\"timestamp\":\"2026-08-09T14:55:04.000Z\","
                + "\"message\":{\"role\":\"toolResult\",\"toolCallId\":\"tool_1\",\"toolName\":\"bash\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"file1.txt\"}],\"isError\":false}}\n";
        Files.writeString(file, jsonl, StandardCharsets.UTF_8);

        PiHistoryReader reader = new PiHistoryReader(root, new Gson());

        List<PiHistoryReader.SessionInfo> listed =
                reader.listSessionsForProject("C:/Users/83429/project");
        assertEquals(1, listed.size());
        assertEquals(sessionId, listed.get(0).sessionId);
        assertTrue(listed.get(0).title.contains("hello from windows"));
        assertTrue(listed.get(0).messageCount >= 2);

        List<JsonObject> messages = reader.getSessionMessages(sessionId, "C:\\Users\\83429\\project");
        assertFalse(messages.isEmpty());
        assertEquals("user", messages.get(0).get("type").getAsString());
        // assistant text + thinking + tool_use + tool_result
        assertTrue(messages.size() >= 3);

        boolean hasToolUse = messages.stream().anyMatch(m -> {
            if (!m.has("message") || !m.get("message").isJsonObject()) {
                return false;
            }
            JsonObject msg = m.getAsJsonObject("message");
            if (!msg.has("content") || !msg.get("content").isJsonArray()) {
                return false;
            }
            return msg.getAsJsonArray("content").toString().contains("tool_use");
        });
        assertTrue(hasToolUse);

        assertTrue(reader.deleteSession(sessionId, "C:/Users/83429/project"));
        assertTrue(reader.listSessionsForProject("C:/Users/83429/project").isEmpty());
    }

    @Test
    public void rejectsPathLikeSessionIds() {
        assertFalse(PiHistoryReader.isSafeSessionId("C:\\evil\\path"));
        assertFalse(PiHistoryReader.isSafeSessionId("../escape"));
        assertTrue(PiHistoryReader.isSafeSessionId("019fe705-27fd-712e-a1be-f972ef3773f3"));
    }

    @Test
    public void truncatesSessionAtUserMessageAndKeepsHeader() throws Exception {
        Path root = Files.createTempDirectory("pi-truncate-test");
        Path file = writeSession(root, "019fe705-27fd-712e-a1be-f972ef3773f3");
        PiHistoryReader reader = new PiHistoryReader(root, new Gson());

        // m3 is the second user turn: it and everything after it are discarded.
        assertTrue(reader.truncateAfterUserMessage(
                "019fe705-27fd-712e-a1be-f972ef3773f3", "C:/Users/83429/project",
                "m3", "second question"));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("\"type\":\"session\""));
        assertTrue(lines.get(1).contains("\"id\":\"m1\""));
        assertTrue(lines.get(2).contains("\"id\":\"m2\""));

        // The truncated file is still loadable — PI needs the header to keep the id.
        assertFalse(reader.getSessionMessages(
                "019fe705-27fd-712e-a1be-f972ef3773f3", "C:/Users/83429/project").isEmpty());
    }

    @Test
    public void truncatingAtFirstUserMessageKeepsOnlyHeader() throws Exception {
        Path root = Files.createTempDirectory("pi-truncate-first-test");
        Path file = writeSession(root, "019fe705-27fd-712e-a1be-f972ef3773f3");
        PiHistoryReader reader = new PiHistoryReader(root, new Gson());

        assertTrue(reader.truncateAfterUserMessage(
                "019fe705-27fd-712e-a1be-f972ef3773f3", "C:/Users/83429/project",
                "m1", "first question"));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"type\":\"session\""));
    }

    @Test
    public void truncatesByTextWhenEntryIdIsUnknown() throws Exception {
        Path root = Files.createTempDirectory("pi-truncate-text-test");
        Path file = writeSession(root, "019fe705-27fd-712e-a1be-f972ef3773f3");
        PiHistoryReader reader = new PiHistoryReader(root, new Gson());

        // The uuid is only back-filled once the CLI echoes the message, so a
        // just-sent message has none — text matching has to carry it.
        assertTrue(reader.truncateAfterUserMessage(
                "019fe705-27fd-712e-a1be-f972ef3773f3", "C:/Users/83429/project",
                null, "second question"));

        assertEquals(3, Files.readAllLines(file, StandardCharsets.UTF_8).size());
    }

    @Test
    public void leavesFileUntouchedWhenTargetMessageIsAbsent() throws Exception {
        Path root = Files.createTempDirectory("pi-truncate-miss-test");
        Path file = writeSession(root, "019fe705-27fd-712e-a1be-f972ef3773f3");
        PiHistoryReader reader = new PiHistoryReader(root, new Gson());

        assertFalse(reader.truncateAfterUserMessage(
                "019fe705-27fd-712e-a1be-f972ef3773f3", "C:/Users/83429/project",
                "missing", "not in this session"));

        assertEquals(4, Files.readAllLines(file, StandardCharsets.UTF_8).size());
    }

    /** Writes a four-line session (header + user/assistant/user) and returns its path. */
    private static Path writeSession(Path root, String sessionId) throws Exception {
        Path cwdDir = root.resolve("--C-Users-83429-project--");
        Files.createDirectories(cwdDir);
        Path file = cwdDir.resolve("2026-08-09T14-55-02-653Z_" + sessionId + ".jsonl");
        Files.writeString(file, ""
                + "{\"type\":\"session\",\"version\":3,\"id\":\"" + sessionId
                + "\",\"timestamp\":\"2026-08-09T14:55:02.653Z\",\"cwd\":\"C:\\\\Users\\\\83429\\\\project\"}\n"
                + "{\"type\":\"message\",\"id\":\"m1\",\"parentId\":null,\"timestamp\":\"2026-08-09T14:55:02.745Z\","
                + "\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"first question\"}]}}\n"
                + "{\"type\":\"message\",\"id\":\"m2\",\"parentId\":\"m1\",\"timestamp\":\"2026-08-09T14:55:03.000Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"answer\"}]}}\n"
                + "{\"type\":\"message\",\"id\":\"m3\",\"parentId\":\"m2\",\"timestamp\":\"2026-08-09T14:55:04.000Z\","
                + "\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"second question\"}]}}\n",
                StandardCharsets.UTF_8);
        return file;
    }
}
