import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

import static java.lang.System.getenv;
import static java.nio.file.Files.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class Nanocode {
/**
 * nanocode - minimal claude code alternative. Original:
 * https://github.com/1rgs/nanocode
 */

static final ObjectMapper JSON = new ObjectMapper();

static final String OPENROUTER_KEY = getenv("OPENROUTER_API_KEY");
static final String GEMINI_KEY = getenv("GEMINI_API_KEY");
static final String API_URL = OPENROUTER_KEY != null
        ? "https://openrouter.ai/api/v1/messages"
        : "https://api.anthropic.com/v1/messages";
static final String MODEL = Optional.ofNullable(getenv("MODEL"))
        .orElse(OPENROUTER_KEY != null ? "anthropic/claude-opus-4.5" : GEMINI_KEY != null ? "gemini-flash-latest" : "claude-opus-4-5");
static final String PROVIDER = OPENROUTER_KEY != null ? "OpenRouter" : GEMINI_KEY != null ? "Gemini" : "Anthropic";
static String geminiLastId;

static final String RESET = "\033[0m", BOLD = "\033[1m", DIM = "\033[2m";
static final String BLUE = "\033[34m", CYAN = "\033[36m", GREEN = "\033[32m", RED = "\033[31m";

// --- Tools ---

static String toolRead(JsonNode args) throws IOException {
    var lines = readAllLines(Path.of(args.get("path").asText()));
    int offset = args.path("offset").asInt(0), limit = args.path("limit").asInt(lines.size());
    var sb = new StringBuilder();
    for (int i = offset; i < Math.min(offset + limit, lines.size()); i++)
        sb.append("%4d| %s%n".formatted(i + 1, lines.get(i)));
    return sb.toString();
}

static String toolWrite(JsonNode args) throws IOException {
    writeString(Path.of(args.get("path").asText()), args.get("content").asText());
    return "ok";
}

static String toolEdit(JsonNode args) throws IOException {
    var path = Path.of(args.get("path").asText());
    var text = readString(path);
    var old = args.get("old").asText();
    var repl = args.get("new").asText();
    if (!text.contains(old))
        return "error: old_string not found";
    int count = (text.length() - text.replace(old, "").length()) / old.length();
    if (!args.path("all").asBoolean() && count > 1)
        return "error: old_string appears " + count + " times, must be unique (use all=true)";
    writeString(path, args.path("all").asBoolean()
            ? text.replace(old, repl)
            : text.replaceFirst(Pattern.quote(old), Matcher.quoteReplacement(repl)));
    return "ok";
}

static String toolGlob(JsonNode args) throws IOException {
    var base = Path.of(args.path("path").asText("."));
    var matcher = FileSystems.getDefault().getPathMatcher("glob:" + base + "/" + args.get("pat").asText());
    if (!exists(base))
        return "none";
    try (var walk = walk(base)) {
        var files = walk.filter(Files::isRegularFile).filter(matcher::matches)
                .sorted((a, b) -> {
                    try {
                        return getLastModifiedTime(b).compareTo(getLastModifiedTime(a));
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .map(Path::toString).toList();
        return files.isEmpty() ? "none" : String.join("\n", files);
    }
}

static String toolGrep(JsonNode args) throws IOException {
    var pattern = Pattern.compile(args.get("pat").asText());
    var base = Path.of(args.path("path").asText("."));
    var hits = new ArrayList<String>();
    try (var walk = walk(base)) {
        walk.filter(Files::isRegularFile).takeWhile(path -> hits.size() < 50).forEach(file -> {
            try {
                var lines = readAllLines(file);
                for (int i = 0; i < lines.size() && hits.size() < 50; i++)
                    if (pattern.matcher(lines.get(i)).find())
                        hits.add(file + ":" + (i + 1) + ":" + lines.get(i));
            } catch (Exception e) {
                /* skip */ }
        });
    }
    return hits.isEmpty() ? "none" : String.join("\n", hits);
}

static String toolBash(JsonNode args) throws Exception {
    var proc = new ProcessBuilder("sh", "-c", args.get("cmd").asText()).redirectErrorStream(true).start();
    var out = new ArrayList<String>();
    try (var r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
        String line;
        while ((line = r.readLine()) != null) {
            System.out.println("  " + DIM + "│ " + line + RESET);
            out.add(line);
        }
    }
    if (!proc.waitFor(30, TimeUnit.SECONDS)) {
        proc.destroyForcibly();
        out.add("(timed out after 30s)");
    }
    return out.isEmpty() ? "(empty)" : String.join("\n", out);
}

static String runTool(String name, JsonNode args) {
    try {
        return switch (name) {
            case "read" -> toolRead(args);
            case "write" -> toolWrite(args);
            case "edit" -> toolEdit(args);
            case "glob" -> toolGlob(args);
            case "grep" -> toolGrep(args);
            case "bash" -> toolBash(args);
            default -> "error: unknown tool " + name;
        };
    } catch (Exception e) {
        return "error: " + e.getMessage();
    }
}

// --- Schema ---

static final String SCHEMA = """
        [{"name":"read","description":"Read file with line numbers (file path, not directory)","input_schema":{"type":"object","properties":{"path":{"type":"string"},"offset":{"type":"integer"},"limit":{"type":"integer"}},"required":["path"]}},
        {"name":"write","description":"Write content to file","input_schema":{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}},
        {"name":"edit","description":"Replace old with new in file (old must be unique unless all=true)","input_schema":{"type":"object","properties":{"path":{"type":"string"},"old":{"type":"string"},"new":{"type":"string"},"all":{"type":"boolean"}},"required":["path","old","new"]}},
        {"name":"glob","description":"Find files by pattern, sorted by mtime","input_schema":{"type":"object","properties":{"pat":{"type":"string"},"path":{"type":"string"}},"required":["pat"]}},
        {"name":"grep","description":"Search files for regex pattern","input_schema":{"type":"object","properties":{"pat":{"type":"string"},"path":{"type":"string"}},"required":["pat"]}},
        {"name":"bash","description":"Run shell command","input_schema":{"type":"object","properties":{"cmd":{"type":"string"}},"required":["cmd"]}}]""";

// --- API ---

static JsonNode callApi(ArrayNode messages, String systemPrompt) throws IOException {
    var body = JSON.createObjectNode().put("model", MODEL).put("max_tokens", 8192).put("system", systemPrompt);
    body.set("messages", messages);
    body.set("tools", JSON.readTree(SCHEMA));

    var conn = (HttpURLConnection) URI.create(API_URL).toURL().openConnection();
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json");
    conn.setRequestProperty("anthropic-version", "2023-06-01");
    conn.setRequestProperty(OPENROUTER_KEY != null ? "Authorization" : "x-api-key",
            OPENROUTER_KEY != null ? "Bearer " + OPENROUTER_KEY
                    : Optional.ofNullable(getenv("ANTHROPIC_API_KEY")).orElse(""));

    try (var os = conn.getOutputStream()) {
        os.write(JSON.writeValueAsBytes(body));
    }
    int status = conn.getResponseCode();
    var response = JSON.readTree(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
    if (status >= 400)
        throw new IOException("API error " + status + ": " + response);
    return response;
}

static ArrayNode geminiTools() throws IOException {
    var tools = JSON.createArrayNode();
    for (var tool : JSON.readTree(SCHEMA)) {
        tools.add(JSON.createObjectNode().put("type", "function")
                .put("name", tool.get("name").asText())
                .put("description", tool.get("description").asText())
                .<ObjectNode>set("parameters", tool.get("input_schema")));
    }
    return tools;
}

static JsonNode callGemini(JsonNode input, String systemPrompt, String previousInteractionId) throws IOException {
    var body = JSON.createObjectNode().put("model", MODEL);
    if (previousInteractionId == null)
        body.put("system_instruction", systemPrompt);
    else
        body.put("previous_interaction_id", previousInteractionId);
    if (input.isTextual())
        body.put("input", input.asText());
    else
        body.set("input", input);
    body.set("tools", geminiTools());

    var conn = (HttpURLConnection) URI.create("https://generativelanguage.googleapis.com/v1beta/interactions").toURL().openConnection();
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json");
    conn.setRequestProperty("x-goog-api-key", GEMINI_KEY);

    try (var os = conn.getOutputStream()) {
        os.write(JSON.writeValueAsBytes(body));
    }
    int status = conn.getResponseCode();
    var response = JSON.readTree(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
    if (status >= 400) {
        var error = response.path("error");
        var message = error.path("message").asText(response.toString());
        var code = error.path("code").asText();
        throw new IOException("API error " + status + (code.isEmpty() ? "" : " " + code) + ": " + message);
    }
    return response;
}

static void runGeminiTurn(String input, String systemPrompt) throws IOException {
    var response = callGemini(JSON.getNodeFactory().textNode(input), systemPrompt, geminiLastId);

    while (true) {
        var responseId = response.get("id").asText();
        geminiLastId = responseId;
        var toolResults = JSON.createArrayNode();

        for (var step : response.path("steps")) {
            if ("model_output".equals(step.path("type").asText()))
                for (var content : step.path("content"))
                    if ("text".equals(content.path("type").asText()))
                        System.out.println("\n" + CYAN + "⏺" + RESET + " "
                                + content.get("text").asText().replaceAll("\\*\\*(.+?)\\*\\*", BOLD + "$1" + RESET));

            if ("function_call".equals(step.path("type").asText())) {
                var name = step.get("name").asText();
                var toolArgs = step.get("arguments");
                var argPreview = toolArgs.fields().hasNext() ? toolArgs.fields().next().getValue().asText()
                        : "";
                System.out
                        .println("\n" + GREEN + "⏺ " + Character.toUpperCase(name.charAt(0)) + name.substring(1)
                                + RESET + "(" + DIM + argPreview.substring(0, Math.min(50, argPreview.length()))
                                + RESET + ")");

                var result = runTool(name, toolArgs);
                System.out.println("  " + DIM + "⎿  " + preview(result, 60) + RESET);

                toolResults.add(JSON.createObjectNode().put("type", "function_result")
                        .put("call_id", step.get("id").asText()).put("name", name).put("result", result));
            }
        }

        if (!"requires_action".equals(response.path("status").asText()))
            break;
        response = callGemini(toolResults, systemPrompt, responseId);
    }
}

// --- UI ---

static String sep() {
    try {
        var p = new ProcessBuilder("tput", "cols").redirectErrorStream(true).start();
        return DIM + "─".repeat(Math.min(Integer.parseInt(new String(p.getInputStream().readAllBytes()).trim()), 80))
                + RESET;
    } catch (Exception e) {
        return DIM + "─".repeat(80) + RESET;
    }
}

static String preview(String s, int max) {
    var lines = s.split("\n");
    var p = lines[0].substring(0, Math.min(lines[0].length(), max));
    return lines.length > 1 ? p + " ... +" + (lines.length - 1) + " lines" : (lines[0].length() > max ? p + "..." : p);
}

// --- Main ---

public static void main(String[] args) throws Exception {
    var cwd = System.getProperty("user.dir");
    System.out.println(BOLD + "nanocode" + RESET + " | " + DIM + MODEL + " ("
            + PROVIDER + ") | " + cwd + RESET + "\n");

    var messages = JSON.createArrayNode();
    var systemPrompt = "Concise coding assistant. cwd: " + cwd;
    var stdin = new BufferedReader(new InputStreamReader(System.in));

    while (true) {
        try {
            System.out.println(sep());
            System.out.print(BOLD + BLUE + "❯" + RESET + " ");
            System.out.flush();
            var input = stdin.readLine();
            if (input == null)
                break;
            input = input.strip();
            System.out.println(sep());
            if (input.isEmpty())
                continue;
            if (input.equals("/q") || input.equals("exit"))
                break;
            if (input.equals("/c")) {
                messages = JSON.createArrayNode();
                geminiLastId = null;
                System.out.println(GREEN + "⏺ Cleared" + RESET);
                continue;
            }

            if (GEMINI_KEY != null && OPENROUTER_KEY == null) {
                runGeminiTurn(input, systemPrompt);
            } else {
                messages.add(JSON.createObjectNode().put("role", "user").put("content", input));

                while (true) {
                    var response = callApi(messages, systemPrompt);
                    var content = response.get("content");
                    var toolResults = JSON.createArrayNode();

                    for (var block : content) {
                        if ("text".equals(block.get("type").asText()))
                            System.out.println("\n" + CYAN + "⏺" + RESET + " "
                                    + block.get("text").asText().replaceAll("\\*\\*(.+?)\\*\\*", BOLD + "$1" + RESET));

                        if ("tool_use".equals(block.get("type").asText())) {
                            var name = block.get("name").asText();
                            var toolArgs = block.get("input");
                            var argPreview = toolArgs.fields().hasNext() ? toolArgs.fields().next().getValue().asText()
                                    : "";
                            System.out
                                    .println("\n" + GREEN + "⏺ " + Character.toUpperCase(name.charAt(0)) + name.substring(1)
                                            + RESET + "(" + DIM + argPreview.substring(0, Math.min(50, argPreview.length()))
                                            + RESET + ")");

                            var result = runTool(name, toolArgs);
                            System.out.println("  " + DIM + "⎿  " + preview(result, 60) + RESET);

                            toolResults.add(JSON.createObjectNode().put("type", "tool_result")
                                    .put("tool_use_id", block.get("id").asText()).put("content", result));
                        }
                    }

                    messages.add(JSON.createObjectNode().put("role", "assistant").<ObjectNode>set("content", content));
                    if (toolResults.isEmpty())
                        break;
                    messages.add(JSON.createObjectNode().put("role", "user").<ObjectNode>set("content", toolResults));
                }
            }
            System.out.println();
        } catch (Exception e) {
            if (e instanceof EOFException)
                break;
            System.out.println(RED + "⏺ Error: " + e.getMessage() + RESET);
        }
    }
}
}
