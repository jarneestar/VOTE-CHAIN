import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class VotingApi {

    public static void main(String[] args) throws Exception {
        VotingSystem.initDatabase();       // ensures both tables exist
        VotingSystem.initCandidates();
        VotingSystem.createGenesisBlock();
        VotingSystem.loadBlockchainFromDatabase();

        int port = Integer.parseInt(
            System.getenv().getOrDefault("PORT", "4567")
        );
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", exchange -> {
            try {
                handle(exchange);
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    sendJson(exchange, 500, jsonObject("error", "Server error: " + e.getMessage()));
                } catch (Exception ignored) {}
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("VotingApi running on http://localhost:" + port);
    }

    private static void handle(com.sun.net.httpserver.HttpExchange exchange) throws Exception {
        String method = exchange.getRequestMethod();
        String path   = exchange.getRequestURI().getPath();

        // CORS
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");

        if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        // ── GET /candidates ─────────────────────────────────────────────────────
        if ("GET".equalsIgnoreCase(method) && "/candidates".equals(path)) {
            sendJson(exchange, 200, jsonArrayStrings(VotingSystem.getCandidatesApi()));
            return;
        }

        // ── POST /vote ───────────────────────────────────────────────────────────
        if ("POST".equalsIgnoreCase(method) && "/vote".equals(path)) {
            String body      = readBody(exchange);
            String voterId   = extractJsonString(body, "voterId");
            String candidate = extractJsonString(body, "candidate");

            String result = VotingSystem.castVoteApi(voterId, candidate);
            boolean ok    = "Vote recorded".equals(result);
            sendJson(exchange, 200, jsonObject(
                "status",  ok ? "ok" : "error",
                "message", result
            ));
            return;
        }

        // ── GET /results ─────────────────────────────────────────────────────────
        if ("GET".equalsIgnoreCase(method) && "/results".equals(path)) {
            var resp = new StringBuilder();
            resp.append("{");
            resp.append("\"results\":").append(jsonObjectNumberMap(VotingSystem.getResultsApi()));
            resp.append(",\"totalVotes\":").append(VotingSystem.getTotalVotesApi());
            resp.append("}");
            sendJson(exchange, 200, resp.toString());
            return;
        }

        // ── GET /blockchain ──────────────────────────────────────────────────────
        if ("GET".equalsIgnoreCase(method) && "/blockchain".equals(path)) {
            sendJson(exchange, 200, jsonBlocksArray(VotingSystem.getBlockchainApi()));
            return;
        }

        // ── GET /validate ────────────────────────────────────────────────────────
        if ("GET".equalsIgnoreCase(method) && "/validate".equals(path)) {
            boolean valid = VotingSystem.validateBlockchainApi();
            sendJson(exchange, 200, jsonObject("valid", valid));
            return;
        }

        // ── GET /tamper ──────────────────────────────────────────────────────────
        if ("GET".equalsIgnoreCase(method) && "/tamper".equals(path)) {
            VotingSystem.tamperBlock();
            sendJson(exchange, 200, jsonObject("status", "ok", "message", "Second block tampered (demo)"));
            return;
        }

        // ── POST /register-face ──────────────────────────────────────────────────
        if ("POST".equalsIgnoreCase(method) && "/register-face".equals(path)) {
            String body       = readBody(exchange);
            String voterId    = extractJsonString(body, "voterId");
            String descriptor = extractJsonArray(body, "descriptor");

            String result = VotingSystem.registerFace(voterId, descriptor);
            boolean ok    = "Face registered".equals(result);
            sendJson(exchange, 200, jsonObject(
                "status",  ok ? "ok" : "error",
                "message", result
            ));
            return;
        }

        // ── POST /verify-face ────────────────────────────────────────────────────
        if ("POST".equalsIgnoreCase(method) && "/verify-face".equals(path)) {
            String body       = readBody(exchange);
            String voterId    = extractJsonString(body, "voterId");
            String descriptor = extractJsonArray(body, "descriptor");

            boolean verified = VotingSystem.verifyFace(voterId, descriptor);
            sendJson(exchange, 200, jsonObject("verified", verified));
            return;
        }

        sendJson(exchange, 404, jsonObject("error", "Not found"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static String readBody(com.sun.net.httpserver.HttpExchange exchange) throws Exception {
        try (var in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(com.sun.net.httpserver.HttpExchange exchange, int status, String json)
            throws Exception {
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String jsonObject(String k1, Object v1) {
        return jsonObjectInternal(new String[]{k1}, new Object[]{v1});
    }

    private static String jsonObject(String k1, Object v1, String k2, Object v2) {
        return jsonObjectInternal(new String[]{k1, k2}, new Object[]{v1, v2});
    }

    private static String jsonObjectInternal(String[] keys, Object[] values) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(jsonEscape(keys[i])).append("\":");
            sb.append(toJsonValue(values[i]));
        }
        return sb.append("}").toString();
    }

    private static String toJsonValue(Object v) {
        if (v == null)          return "null";
        if (v instanceof Boolean) return ((Boolean) v) ? "true" : "false";
        if (v instanceof Number)  return String.valueOf(v);
        return "\"" + jsonEscape(String.valueOf(v)) + "\"";
    }

    private static String jsonArrayStrings(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(jsonEscape(items.get(i))).append("\"");
        }
        return sb.append("]").toString();
    }

    private static String jsonObjectNumberMap(java.util.Map<String, Integer> map) {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (var e : map.entrySet()) {
            if (i++ > 0) sb.append(",");
            sb.append("\"").append(jsonEscape(e.getKey())).append("\":").append(e.getValue());
        }
        return sb.append("}").toString();
    }

    private static String jsonBlocksArray(List<VotingSystem.Block> blocks) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) sb.append(",");
            VotingSystem.Block b = blocks.get(i);
            sb.append("{");
            sb.append("\"voterId\":\"").append(jsonEscape(b.voterId)).append("\",");
            sb.append("\"candidate\":\"").append(jsonEscape(b.candidate)).append("\",");
            sb.append("\"previousHash\":\"").append(jsonEscape(b.previousHash)).append("\",");
            sb.append("\"hash\":\"").append(jsonEscape(b.hash)).append("\",");
            sb.append("\"timeStamp\":").append(b.timeStamp);
            sb.append("}");
        }
        return sb.append("]").toString();
    }

    /** Extracts a quoted string value: "key" : "value" */
    private static String extractJsonString(String body, String key) {
        if (body == null || key == null) return null;
        String pattern = "\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"(.*?)\"";
        var m = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.DOTALL).matcher(body);
        if (!m.find()) return null;
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /** Extracts a JSON array value: "key" : [...] — handles nested brackets. */
    private static String extractJsonArray(String body, String key) {
        if (body == null || key == null) return null;
        String search = "\"" + key + "\"";
        int keyIdx = body.indexOf(search);
        if (keyIdx < 0) return null;
        int colonIdx = body.indexOf(':', keyIdx + search.length());
        if (colonIdx < 0) return null;
        int arrStart = body.indexOf('[', colonIdx);
        if (arrStart < 0) return null;
        int depth = 0;
        for (int i = arrStart; i < body.length(); i++) {
            char c = body.charAt(i);
            if      (c == '[') depth++;
            else if (c == ']') { if (--depth == 0) return body.substring(arrStart, i + 1); }
        }
        return null;
    }
}
