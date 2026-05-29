import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * VoteChain REST API Server
 *
 * Runs on port 4567.  All responses are JSON.
 *
 * Endpoints:
 *   GET  /health              → system health check
 *   GET  /candidates          → list all candidates
 *   GET  /results             → live vote tallies
 *   GET  /chain               → full blockchain dump
 *   GET  /validate            → validate chain integrity
 *   GET  /voter?id=...        → voter info & status
 *   POST /register-voter      → register a new voter
 *   POST /mark-face           → mark face as registered
 *   POST /vote                → cast a vote (requires face auth via Python API)
 *   POST /verify-vote-status  → check if voter has voted
 */
public class VotingApi {

    // ──────────────────────────────────────────────────
    // Shared blockchain engine
    // ──────────────────────────────────────────────────
    private static final VotingSystem SYSTEM = new VotingSystem();

    // ──────────────────────────────────────────────────
    // Entry point
    // ──────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {

        if (!DatabaseConnection.testConnection()) {
            System.err.println("[API] Cannot connect to MySQL. Exiting.");
            System.exit(1);
        }

        int port = 4567;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 100);
        server.setExecutor(Executors.newFixedThreadPool(20));

        // Register all routes
        server.createContext("/health",             new HealthHandler());
        server.createContext("/candidates",         new CandidatesHandler());
        server.createContext("/results",            new ResultsHandler());
        server.createContext("/chain",              new ChainHandler());
        server.createContext("/validate",           new ValidateHandler());
        server.createContext("/voter",              new VoterInfoHandler());
        server.createContext("/register-voter",     new RegisterVoterHandler());
        server.createContext("/mark-face",          new MarkFaceHandler());
        server.createContext("/vote",               new VoteHandler());
        server.createContext("/verify-vote-status", new VoteStatusHandler());

        server.start();
        System.out.println("[API] VoteChain server running on http://localhost:" + port);
        System.out.println("[API] Chain length: " + SYSTEM.getChain().size() + " block(s)");
    }

    // ══════════════════════════════════════════════════
    // Shared utilities
    // ══════════════════════════════════════════════════

    private static void addCORS(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
    }

    private static boolean handlePreflight(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendOk(HttpExchange ex, String json) throws IOException {
        send(ex, 200, json);
    }

    private static void sendError(HttpExchange ex, int status, String message) throws IOException {
        send(ex, status, "{\"success\":false,\"message\":" + jsonString(message) + "}");
    }

    /** Minimal JSON string serializer (avoids pulling in a library). */
    private static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }

    /** Very small JSON object builder — only for maps with simple values. */
    private static String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(jsonString(e.getKey())).append(":");
            Object v = e.getValue();
            if (v == null)             sb.append("null");
            else if (v instanceof Boolean)  sb.append(v);
            else if (v instanceof Number)   sb.append(v);
            else                            sb.append(jsonString(v.toString()));
        }
        sb.append("}");
        return sb.toString();
    }

    private static String listOfMapsToJson(List<Map<String, Object>> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(mapToJson(list.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    /** Parses exactly the fields needed from simple flat JSON bodies. */
    private static String jsonField(String body, String key) {
        // Handles "key":"value" and "key":value (numbers/booleans)
        String search = "\"" + key + "\":";
        int idx = body.indexOf(search);
        if (idx < 0) return null;
        int start = idx + search.length();
        while (start < body.length() && body.charAt(start) == ' ') start++;
        if (body.charAt(start) == '"') {
            // String value
            int end = body.indexOf('"', start + 1);
            return end > start ? body.substring(start + 1, end) : null;
        }
        // Non-string (number / boolean)
        int end = start;
        while (end < body.length() && ",}\n\r ".indexOf(body.charAt(end)) < 0) end++;
        return body.substring(start, end);
    }

    // ══════════════════════════════════════════════════
    // Handlers
    // ══════════════════════════════════════════════════

    // ── GET /health ────────────────────────────────────
    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            addCORS(ex);
            if (handlePreflight(ex)) return;
            boolean dbOk    = DatabaseConnection.testConnection();
            boolean chainOk = SYSTEM.isChainValid();
            String json = String.format(
                "{\"status\":\"ok\",\"db_connected\":%b,\"chain_valid\":%b," +
                "\"chain_length\":%d,\"total_votes\":%d,\"total_voters\":%d}",
                dbOk, chainOk,
                SYSTEM.getChain().size(),
                SYSTEM.getTotalVotes(),
                SYSTEM.getTotalRegisteredVoters()
            );
            sendOk(ex, json);
        }
    }

    // ── GET /candidates ────────────────────────────────
    static class CandidatesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            addCORS(ex);
            if (handlePreflight(ex)) return;
            List<Map<String, Object>> candidates = SYSTEM.getCandidates();
            sendOk(ex, "{\"success\":true,\"candidates\":" +
                       listOfMapsToJson(candidates) + "}");
        }
    }

    // ── GET /results ───────────────────────────────────
    static class ResultsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            addCORS(ex);
            if (handlePreflight(ex)) return;
            List<Map<String, Object>> results = SYSTEM.getResults();
            boolean chainValid = SYSTEM.isChainValid();
            sendOk(ex, String.format(
                "{\"success\":true,\"chain_valid\":%b,\"total_votes\":%d," +
                "\"total_voters\":%d,\"results\":%s}",
                chainValid,
                SYSTEM.getTotalVotes(),
                SYSTEM.getTotalRegisteredVoters(),
                listOfMapsToJson(results)
            ));
        }
    }

    // ── GET /chain ─────────────────────────────────────
    static class ChainHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            addCORS(ex);
            if (handlePreflight(ex)) return;
            List<Block> chain = SYSTEM.getChain();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < chain.size(); i++) {
                if (i > 0) sb.append(",");
                Block b = chain.get(i);
                sb.append(String.format(
                    "{\"index\":%d,\"timestamp\":%d,\"voter_hash\":%s," +
                    "\"candidate\":%s,\"previous_hash\":%s,\"hash\":%s," +
                    "\"nonce\":%d,\"merkle_root\":%s}",
                    b.getIndex(), b.getTimestamp(),
                    jsonString(b.getVoterIdHash()),
                    jsonString(b.getCandidateId()),
                    jsonString(b.getPreviousHash()),
                    jsonString(b.getBlockHash()),
                    b.getNonce(),
                    jsonString(b.getMerkleRoot())
                ));
            }
            sb.append("]");
            sendOk(ex, "{\"success\":true,\"chain\":" + sb + "}");
        }
    }

    // ── GET /validate ──────────────────────────────────
    static class ValidateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            addCORS(ex);
            if (handlePreflight(ex)) return;
            boolean valid = SYSTEM.isChainValid();
            sendOk(ex, String.format(
                "{\"success\":true,\"chain_valid\":%b,\"message\":%s}",
                valid, jsonString(valid ? "Blockchain is intact." : "Tampering detected!")
            ));
        }
    }

    // ── GET /voter?id=VOTER_ID ─────────────────────────
    static class VoterInfoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            addCORS(ex);
            if (handlePreflight(ex)) return;
            String query = ex.getRequestURI().getQuery();
            String voterId = null;
            if (query != null) {
                for (String part : query.split("&")) {
                    if (part.startsWith("id=")) {
                        voterId = part.substring(3);
                        break;
                    }
                }
            }
            if (voterId == null || voterId.isBlank()) {
                sendError(ex, 400, "Missing 'id' query parameter"); return;
            }
            Map<String, Object> info = SYSTEM.getVoterInfo(voterId);
            if (info == null) {
                sendError(ex, 404, "Voter not found"); return;
            }
            sendOk(ex, "{\"success\":true,\"voter\":" + mapToJson(info) + "}");
        }
    }

    // ── POST /register-voter ───────────────────────────
    // Body: {"voter_id":"...","full_name":"...","email":"...","aadhar":"..."}
    static class RegisterVoterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            addCORS(ex);
            if (handlePreflight(ex)) return;
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendError(ex, 405, "POST required"); return;
            }
            String body = readBody(ex);
            String voterId   = jsonField(body, "voter_id");
            String fullName  = jsonField(body, "full_name");
            String email     = jsonField(body, "email");
            String aadhar    = jsonField(body, "aadhar");

            if (voterId == null || fullName == null || email == null || aadhar == null) {
                sendError(ex, 400, "Required: voter_id, full_name, email, aadhar"); return;
            }

            String aadharHash = SHA.hash(aadhar);
            boolean ok = SYSTEM.registerVoter(voterId, fullName, email, aadharHash);
            if (ok) {
                sendOk(ex, "{\"success\":true,\"message\":\"Voter registered successfully\"," +
                           "\"voter_id\":" + jsonString(voterId) + "}");
            } else {
                sendError(ex, 409, "Voter already exists or DB error");
            }
        }
    }

    // ── POST /mark-face ────────────────────────────────
    // Body: {"voter_id":"..."}
    static class MarkFaceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            addCORS(ex);
            if (handlePreflight(ex)) return;
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendError(ex, 405, "POST required"); return;
            }
            String body    = readBody(ex);
            String voterId = jsonField(body, "voter_id");
            if (voterId == null) { sendError(ex, 400, "Missing voter_id"); return; }
            boolean ok = SYSTEM.markFaceRegistered(voterId);
            if (ok) sendOk(ex, "{\"success\":true,\"message\":\"Face registration noted\"}");
            else    sendError(ex, 404, "Voter not found");
        }
    }

    // ── POST /vote ─────────────────────────────────────
    // Body: {"voter_id":"...","candidate_id":"..."}
    // Face verification must have already been done by the Python service.
    static class VoteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            addCORS(ex);
            if (handlePreflight(ex)) return;
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendError(ex, 405, "POST required"); return;
            }
            String body        = readBody(ex);
            String voterId     = jsonField(body, "voter_id");
            String candidateId = jsonField(body, "candidate_id");

            if (voterId == null || candidateId == null) {
                sendError(ex, 400, "Required: voter_id, candidate_id"); return;
            }

            // Check face is registered
            if (!SYSTEM.isFaceRegistered(voterId)) {
                sendError(ex, 403, "Face not registered. Complete face registration first.");
                return;
            }
            // Check not already voted
            if (SYSTEM.hasVoted(voterId)) {
                sendError(ex, 409, "Voter has already cast a vote.");
                return;
            }

            Block block = SYSTEM.addVote(voterId, candidateId);
            if (block == null) {
                sendError(ex, 500, "Vote failed — see server logs.");
                return;
            }
            sendOk(ex, String.format(
                "{\"success\":true,\"message\":\"Vote cast successfully\"," +
                "\"block_index\":%d,\"block_hash\":%s,\"candidate\":%s}",
                block.getIndex(),
                jsonString(block.getBlockHash()),
                jsonString(candidateId)
            ));
        }
    }

    // ── POST /verify-vote-status ───────────────────────
    // Body: {"voter_id":"..."}
    static class VoteStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            addCORS(ex);
            if (handlePreflight(ex)) return;
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendError(ex, 405, "POST required"); return;
            }
            String body    = readBody(ex);
            String voterId = jsonField(body, "voter_id");
            if (voterId == null) { sendError(ex, 400, "Missing voter_id"); return; }
            Map<String, Object> info = SYSTEM.getVoterInfo(voterId);
            if (info == null) { sendError(ex, 404, "Voter not found"); return; }
            boolean hasVoted = Boolean.TRUE.equals(info.get("has_voted"));
            sendOk(ex, String.format(
                "{\"success\":true,\"voter_id\":%s,\"has_voted\":%b," +
                "\"face_registered\":%b}",
                jsonString(voterId), hasVoted,
                Boolean.TRUE.equals(info.get("face_registered"))
            ));
        }
    }
}
