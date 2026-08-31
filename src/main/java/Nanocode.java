import java.io.*;
import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

import com.sun.net.httpserver.HttpServer;

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
static final String ANTHROPIC_KEY = getenv("ANTHROPIC_API_KEY");
static String PROVIDER, MODEL, API_URL;
static String geminiLastId;

static final String AGY_CLIENT_ID = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com";
static final String AGY_CLIENT_SECRET = "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"; // public installed-app secret, same as agy CLI
static final String AGY_SCOPES = "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile https://www.googleapis.com/auth/cclog https://www.googleapis.com/auth/experimentsandconfigs";
static final Path AGY_CREDS_PATH = Path.of(System.getProperty("user.home"), ".gemini", "oauth_creds_antigravity.json");
static final String AGY_URL = "https://daily-cloudcode-pa.googleapis.com/v1internal";
static final String OS_NAME = System.getProperty("os.name").toLowerCase(Locale.ROOT);
static final String AGY_UA = "antigravity/hub/2.9.1 "
        + (OS_NAME.contains("win") ? "windows" : OS_NAME.contains("mac") ? "darwin" : "linux")
        + "/" + (System.getProperty("os.arch").matches("aarch64|arm64") ? "arm64" : "amd64");
static final String SESSION_ID = "-" + Math.abs(new SecureRandom().nextLong());
static ObjectNode oauthCreds;
static String codeAssistProject;
static ArrayNode oauthHistory = JSON.createArrayNode();

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
    var shell = OS_NAME.contains("win") ? new String[] { "cmd.exe", "/c", args.get("cmd").asText() }
            : new String[] { "sh", "-c", args.get("cmd").asText() };
    var proc = new ProcessBuilder(shell).redirectErrorStream(true).start();
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
    conn.setRequestProperty("openrouter".equals(PROVIDER) ? "Authorization" : "x-api-key",
            "openrouter".equals(PROVIDER) ? "Bearer " + OPENROUTER_KEY : ANTHROPIC_KEY);

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

// --- Antigravity (Code Assist) ---

static String accessToken() throws Exception {
    if (oauthCreds == null && exists(AGY_CREDS_PATH))
        oauthCreds = (ObjectNode) JSON.readTree(AGY_CREDS_PATH.toFile());

    var now = System.currentTimeMillis();
    if (oauthCreds != null && oauthCreds.path("expiry_date").asLong() > now + 60_000
            && !oauthCreds.path("access_token").asText().isEmpty())
        return oauthCreds.get("access_token").asText();

    if (oauthCreds != null && !oauthCreds.path("refresh_token").asText().isEmpty())
        try {
            var response = tokenRequest("grant_type=refresh_token&refresh_token="
                    + enc(oauthCreds.get("refresh_token").asText()) + "&client_id=" + enc(AGY_CLIENT_ID)
                    + "&client_secret=" + enc(AGY_CLIENT_SECRET));
            oauthCreds.put("access_token", response.get("access_token").asText());
            oauthCreds.put("expiry_date", now + response.path("expires_in").asLong() * 1000);
            if (response.has("scope"))
                oauthCreds.put("scope", response.get("scope").asText());
            if (response.has("token_type"))
                oauthCreds.put("token_type", response.get("token_type").asText());
            if (response.has("id_token"))
                oauthCreds.put("id_token", response.get("id_token").asText());
            saveCreds();
            return oauthCreds.get("access_token").asText();
        } catch (Exception e) {
            oauthCreds = null;
        }

    return oauthLogin();
}

static String oauthLogin() throws Exception {
    var state = randomB64(16);
    var queue = new ArrayBlockingQueue<String>(1);
    HttpServer server;
    try {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 51121), 0);
    } catch (BindException e) {
        throw new IOException("OAuth callback port 51121 is already in use", e);
    }
    // agy registers http://localhost:51121/oauth-callback; exact match required, no PKCE
    var redirect = "http://localhost:" + server.getAddress().getPort() + "/oauth-callback";

    server.createContext("/oauth-callback", exchange -> {
        var params = queryParams(exchange.getRequestURI().getRawQuery());
        var ok = state.equals(params.get("state")) && params.containsKey("code");
        var page = ok ? "<html><body>You can close this tab.</body></html>"
                : "<html><body>Invalid OAuth response.</body></html>";
        var bytes = page.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(ok ? 200 : 400, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        queue.offer(ok ? params.get("code") : "");
    });
    server.start();

    try {
        var authUrl = "https://accounts.google.com/o/oauth2/v2/auth?response_type=code&access_type=offline"
                + "&prompt=consent&client_id=" + enc(AGY_CLIENT_ID) + "&redirect_uri=" + enc(redirect)
                + "&scope=" + enc(AGY_SCOPES) + "&state=" + enc(state);
        System.out.println("Login with " + providerName() + ": " + authUrl);
        try {
            new ProcessBuilder(OS_NAME.contains("mac") ? new String[] { "open", authUrl }
                    : OS_NAME.contains("win") ? new String[] { "rundll32", "url.dll,FileProtocolHandler", authUrl }
                    : new String[] { "xdg-open", authUrl }).start();
        } catch (Exception e) {
            /* best effort */
        }

        var code = queue.take();
        if (code.isEmpty())
            throw new IOException("OAuth login failed or was denied");
        var response = tokenRequest("grant_type=authorization_code&code=" + enc(code)
                + "&redirect_uri=" + enc(redirect) + "&client_id=" + enc(AGY_CLIENT_ID)
                + "&client_secret=" + enc(AGY_CLIENT_SECRET));
        oauthCreds = JSON.createObjectNode()
                .put("access_token", response.get("access_token").asText())
                .put("refresh_token", response.path("refresh_token").asText())
                .put("scope", response.path("scope").asText(AGY_SCOPES))
                .put("token_type", response.path("token_type").asText("Bearer"))
                .put("id_token", response.path("id_token").asText())
                .put("expiry_date", System.currentTimeMillis() + response.path("expires_in").asLong() * 1000);
        saveCreds();
        return oauthCreds.get("access_token").asText();
    } finally {
        server.stop(0);
    }
}

static JsonNode callCodeAssist(String method, JsonNode body) throws Exception {
    var conn = (HttpURLConnection) URI.create(AGY_URL + ":" + method).toURL().openConnection();
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json");
    conn.setRequestProperty("Authorization", "Bearer " + accessToken());
    conn.setRequestProperty("User-Agent", AGY_UA);
    if ("onboardUser".equals(method))
        conn.setRequestProperty("X-Goog-Api-Client", "gl-node/22.21.1");

    try (var os = conn.getOutputStream()) {
        os.write(JSON.writeValueAsBytes(body));
    }
    int status = conn.getResponseCode();
    var response = JSON.readTree(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
    if (status >= 400) {
        var message = response.path("error").path("message").asText(response.toString());
        throw new IOException("API error " + status + ": " + message);
    }
    return response;
}

static String codeAssistProject() throws Exception {
    if (codeAssistProject != null)
        return codeAssistProject;

    var envProject = Optional.ofNullable(getenv("GOOGLE_CLOUD_PROJECT")).filter(s -> !s.isBlank());
    var body = JSON.createObjectNode().<ObjectNode>set("metadata", JSON.createObjectNode().put("ideType", "ANTIGRAVITY"));
    if (envProject.isPresent())
        body.put("cloudaicompanionProject", envProject.get());
    var response = callCodeAssist("loadCodeAssist", body);
    var project = projectId(response.path("cloudaicompanionProject"));
    if (!project.isEmpty())
        return codeAssistProject = project;

    var tierId = "free-tier";
    var needsProject = false;
    for (var tier : response.path("allowedTiers"))
        if (tier.path("isDefault").asBoolean()) {
            tierId = tier.path("id").asText(tier.path("tierId").asText(tierId));
            needsProject = tier.path("userDefinedCloudaicompanionProject").asBoolean();
            break;
        }
    if (needsProject && envProject.isEmpty()) {
        var reason = response.path("ineligibleTiers").path(0).path("reasonMessage").asText("");
        throw new IOException("tier '" + tierId + "' requires $GOOGLE_CLOUD_PROJECT"
                + (reason.isEmpty() ? "" : " (" + reason + ")"));
    }

    while (true) {
        body = JSON.createObjectNode().put("tier_id", tierId).<ObjectNode>set("metadata", JSON.createObjectNode()
                .put("ide_type", "ANTIGRAVITY").put("ide_version", "2.9.1").put("ide_name", "antigravity"));
        if (envProject.isPresent() && needsProject)
            body.put("cloudaicompanionProject", envProject.get());
        response = callCodeAssist("onboardUser", body);
        if (response.path("done").asBoolean())
            break;
        Thread.sleep(5000);
    }

    project = projectId(response.path("cloudaicompanionProject"));
    if (project.isEmpty())
        project = projectId(response.path("response").path("cloudaicompanionProject"));
    if (project.isEmpty())
        throw new IOException("Code Assist onboarding did not return a project");
    return codeAssistProject = project;
}

static void runOauthTurn(String input, String systemPrompt) throws Exception {
    oauthHistory.add(JSON.createObjectNode().put("role", "user")
            .<ObjectNode>set("parts", JSON.createArrayNode().add(JSON.createObjectNode().put("text", input))));

    while (true) {
        var request = JSON.createObjectNode()
                .<ObjectNode>set("contents", oauthHistory)
                .<ObjectNode>set("systemInstruction", JSON.createObjectNode().<ObjectNode>set("parts",
                        JSON.createArrayNode().add(JSON.createObjectNode().put("text", systemPrompt))))
                .<ObjectNode>set("tools", codeAssistTools())
                .put("sessionId", SESSION_ID);
        var body = JSON.createObjectNode().put("model", MODEL).put("project", codeAssistProject())
                .put("userAgent", "antigravity").put("requestType", "agent")
                .put("requestId", "agent-" + UUID.randomUUID())
                .<ObjectNode>set("enabledCreditTypes", JSON.createArrayNode().add("GOOGLE_ONE_AI"))
                .<ObjectNode>set("request", request);
        var response = callCodeAssist("generateContent", body);
        var content = response.path("response").path("candidates").path(0).path("content");
        oauthHistory.add(content);
        var functionResponses = JSON.createArrayNode();

        for (var part : content.path("parts")) {
            if (part.has("text") && !part.path("thought").asBoolean())
                System.out.println("\n" + CYAN + "⏺" + RESET + " "
                        + part.get("text").asText().replaceAll("\\*\\*(.+?)\\*\\*", BOLD + "$1" + RESET));

            if (part.has("functionCall")) {
                var call = part.get("functionCall");
                var name = call.get("name").asText();
                var toolArgs = call.get("args");
                var argPreview = toolArgs.fields().hasNext() ? toolArgs.fields().next().getValue().asText() : "";
                System.out
                        .println("\n" + GREEN + "⏺ " + Character.toUpperCase(name.charAt(0)) + name.substring(1)
                                + RESET + "(" + DIM + argPreview.substring(0, Math.min(50, argPreview.length()))
                                + RESET + ")");

                var result = runTool(name, toolArgs);
                System.out.println("  " + DIM + "⎿  " + preview(result, 60) + RESET);

                functionResponses.add(JSON.createObjectNode().<ObjectNode>set("functionResponse",
                        JSON.createObjectNode().put("name", name).<ObjectNode>set("response",
                                JSON.createObjectNode().put("output", result))));
            }
        }

        if (functionResponses.isEmpty())
            break;
        oauthHistory.add(JSON.createObjectNode().put("role", "user").<ObjectNode>set("parts", functionResponses));
    }
}

static ArrayNode codeAssistTools() throws IOException {
    var declarations = JSON.createArrayNode();
    for (var tool : JSON.readTree(SCHEMA))
        declarations.add(JSON.createObjectNode().put("name", tool.get("name").asText())
                .put("description", tool.get("description").asText())
                .<ObjectNode>set("parameters", tool.get("input_schema")));
    return JSON.createArrayNode().add(JSON.createObjectNode().<ObjectNode>set("functionDeclarations", declarations));
}

static String projectId(JsonNode project) {
    if (project.isMissingNode() || project.isNull())
        return "";
    return project.isTextual() ? project.asText() : project.path("id").asText();
}

static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
}

static JsonNode tokenRequest(String params) throws IOException {
    var conn = (HttpURLConnection) URI.create("https://oauth2.googleapis.com/token").toURL().openConnection();
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
    try (var os = conn.getOutputStream()) {
        os.write(params.getBytes(StandardCharsets.UTF_8));
    }
    int status = conn.getResponseCode();
    var response = JSON.readTree(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
    if (status >= 400) {
        var message = response.path("error_description").asText(response.path("error").path("message").asText(response.toString()));
        throw new IOException("OAuth error " + status + ": " + message);
    }
    return response;
}

static void saveCreds() throws IOException {
    createDirectories(AGY_CREDS_PATH.getParent());
    var perms = PosixFilePermissions.fromString("rw-------");
    try {
        if (!exists(AGY_CREDS_PATH))
            createFile(AGY_CREDS_PATH, PosixFilePermissions.asFileAttribute(perms));
        setPosixFilePermissions(AGY_CREDS_PATH, perms);
    } catch (UnsupportedOperationException e) {
        if (!exists(AGY_CREDS_PATH))
            createFile(AGY_CREDS_PATH);
    }
    writeString(AGY_CREDS_PATH, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(oauthCreds),
            StandardOpenOption.TRUNCATE_EXISTING);
    try {
        setPosixFilePermissions(AGY_CREDS_PATH, perms);
    } catch (UnsupportedOperationException e) {
        /* ignore */
    }
}

static String randomB64(int bytes) {
    var data = new byte[bytes];
    new SecureRandom().nextBytes(data);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
}

static Map<String, String> queryParams(String query) {
    var params = new HashMap<String, String>();
    if (query == null)
        return params;
    for (var pair : query.split("&")) {
        var i = pair.indexOf('=');
        var key = i < 0 ? pair : pair.substring(0, i);
        var value = i < 0 ? "" : pair.substring(i + 1);
        params.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
    }
    return params;
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

static boolean selectProvider(String[] args) {
    PROVIDER = "antigravity";
    for (var arg : args)
        if (arg.startsWith("--provider="))
            PROVIDER = arg.substring("--provider=".length()).strip().toLowerCase(Locale.ROOT);

    if (!List.of("antigravity", "openrouter", "gemini", "anthropic").contains(PROVIDER)) {
        System.out.println(RED + "⏺ Error: unknown provider '" + PROVIDER
                + "' (valid: antigravity, openrouter, gemini, anthropic)" + RESET);
        return false;
    }

    MODEL = Optional.ofNullable(getenv("MODEL")).orElse(switch (PROVIDER) {
        case "openrouter" -> "anthropic/claude-opus-4.5";
        case "gemini" -> "gemini-flash-latest";
        case "anthropic" -> "claude-opus-4-5";
        default -> "gemini-3.7-flash-medium"; // antigravity wire IDs carry an effort suffix
    });
    API_URL = switch (PROVIDER) {
        case "openrouter" -> "https://openrouter.ai/api/v1/messages";
        case "anthropic" -> "https://api.anthropic.com/v1/messages";
        default -> "";
    };

    var missing = switch (PROVIDER) {
        case "openrouter" -> OPENROUTER_KEY == null ? "OPENROUTER_API_KEY" : null;
        case "gemini" -> GEMINI_KEY == null ? "GEMINI_API_KEY" : null;
        case "anthropic" -> ANTHROPIC_KEY == null ? "ANTHROPIC_API_KEY" : null;
        default -> null;
    };
    if (missing != null) {
        System.out.println(RED + "⏺ Error: --provider=" + PROVIDER + " requires $" + missing + RESET);
        return false;
    }
    return true;
}

static String providerName() {
    return switch (PROVIDER) {
        case "openrouter" -> "OpenRouter";
        case "gemini" -> "Gemini";
        case "anthropic" -> "Anthropic";
        default -> "Antigravity";
    };
}

public static void main(String[] args) throws Exception {
    if (!selectProvider(args))
        return;

    var cwd = System.getProperty("user.dir");
    System.out.println(BOLD + "nanocode" + RESET + " | " + DIM + MODEL + " ("
            + providerName() + ") | " + cwd + RESET + "\n");

    var messages = JSON.createArrayNode();
    var systemPrompt = "Concise coding assistant. cwd: " + cwd + ". os: " + System.getProperty("os.name")
            + (OS_NAME.contains("win") ? " (bash tool runs cmd.exe)" : "");
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
                oauthHistory = JSON.createArrayNode();
                System.out.println(GREEN + "⏺ Cleared" + RESET);
                continue;
            }

            if ("antigravity".equals(PROVIDER)) {
                runOauthTurn(input, systemPrompt);
            } else if ("gemini".equals(PROVIDER)) {
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
