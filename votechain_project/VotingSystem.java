import java.security.MessageDigest;
import java.util.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Core blockchain voting logic with face-recognition support.
 * Face descriptors (128-float arrays from face-api.js) are stored in MySQL
 * and verified using Euclidean distance (threshold: 0.55).
 */
public class VotingSystem {

    // ── Blockchain storage ────────────────────────────────────────────────────

    static final List<Block> blockchain = new ArrayList<>();
    static final Set<String>         votedUsers = new HashSet<>();
    static final Map<String, Integer> results    = new HashMap<>();
    static final List<String>         candidates = new ArrayList<>();
    static int totalVotes = 0;

    // ── Block inner class ─────────────────────────────────────────────────────

    static class Block {
        String voterId;
        String candidate;
        String previousHash;
        String hash;
        long   timeStamp;

        Block(String voterId, String candidate, String previousHash) {
            this.voterId      = voterId;
            this.candidate    = candidate;
            this.previousHash = previousHash;
            this.timeStamp    = System.currentTimeMillis();
            this.hash         = calculateHash(voterId, candidate, previousHash, timeStamp);
        }

        static String calculateHash(String voterId, String candidate, String previousHash, long timeStamp) {
            return applySHA256(voterId + "|" + candidate + "|" + previousHash + "|" + timeStamp);
        }

        String calculateHash() {
            return calculateHash(voterId, candidate, previousHash, timeStamp);
        }

        static String applySHA256(String input) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] hashBytes = md.digest(input.getBytes("UTF-8"));
                StringBuilder hex = new StringBuilder();
                for (byte b : hashBytes) {
                    String h = Integer.toHexString(0xff & b);
                    if (h.length() == 1) hex.append('0');
                    hex.append(h);
                }
                return hex.toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ── Database init ─────────────────────────────────────────────────────────

    /**
     * Creates both required tables if they don't exist.
     * Called once at startup from VotingApi.main().
     */
    public static void initDatabase() {
        try {
            Connection con = DatabaseConnection.getConnection();

            con.createStatement().execute(
                "CREATE TABLE IF NOT EXISTS votes (" +
                "  id            INT AUTO_INCREMENT PRIMARY KEY," +
                "  voter_id      VARCHAR(255)," +
                "  candidate     VARCHAR(100)," +
                "  previous_hash VARCHAR(64)," +
                "  hash          VARCHAR(64)," +
                "  timestamp     BIGINT" +
                ")");

            con.createStatement().execute(
                "CREATE TABLE IF NOT EXISTS voter_faces (" +
                "  voter_id        VARCHAR(255) PRIMARY KEY," +
                "  face_descriptor TEXT        NOT NULL," +
                "  registered_at   BIGINT" +
                ")");

            con.close();
            System.out.println("Database tables initialised.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ── Candidate management ──────────────────────────────────────────────────

    public static synchronized void initCandidates() {
        if (!candidates.isEmpty()) return;
        candidates.add("A");
        candidates.add("B");
        candidates.add("C");
    }

    public static synchronized void createGenesisBlock() {
        if (!blockchain.isEmpty()) return;
        Block genesis = new Block("0", "GENESIS", "0");
        genesis.hash = "0";
        blockchain.add(genesis);
    }

    private static synchronized void ensureAllCandidatesPresent() {
        for (String c : candidates) results.putIfAbsent(c, 0);
    }

    public static synchronized List<String> getCandidatesApi() {
        initCandidates();
        ensureAllCandidatesPresent();
        return new ArrayList<>(candidates);
    }

    // ── Voting ────────────────────────────────────────────────────────────────

    public static synchronized String castVoteApi(String voterId, String candidate) {
        initCandidates();
        createGenesisBlock();
        ensureAllCandidatesPresent();

        if (voterId == null || voterId.trim().isEmpty()) return "Voter ID is required";
        if (candidate == null || !candidates.contains(candidate))  return "Invalid candidate";
        if (votedUsers.contains(voterId))                          return "Voter has already voted";

        String prevHash  = blockchain.get(blockchain.size() - 1).hash;
        Block  newBlock  = new Block(voterId, candidate, prevHash);
        blockchain.add(newBlock);
        saveVote(newBlock);
        votedUsers.add(voterId);
        results.put(candidate, results.getOrDefault(candidate, 0) + 1);
        totalVotes++;
        return "Vote recorded";
    }

    public static synchronized boolean hasVoteApi(String voterId) {
        return votedUsers.contains(voterId);
    }

    // ── DB persistence ────────────────────────────────────────────────────────

    public static void saveVote(Block block) {
        try {
            Connection con = DatabaseConnection.getConnection();
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO votes(voter_id, candidate, previous_hash, hash, timestamp) " +
                "VALUES(?,?,?,?,?)");
            ps.setString(1, block.voterId);
            ps.setString(2, block.candidate);
            ps.setString(3, block.previousHash);
            ps.setString(4, block.hash);
            ps.setLong  (5, block.timeStamp);
            ps.executeUpdate();
            con.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void loadBlockchainFromDatabase() {
        try {
            Connection con = DatabaseConnection.getConnection();
            PreparedStatement ps = con.prepareStatement("SELECT * FROM votes ORDER BY id ASC");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Block block = new Block(
                    rs.getString("voter_id"),
                    rs.getString("candidate"),
                    rs.getString("previous_hash")
                );
                block.hash      = rs.getString("hash");
                block.timeStamp = rs.getLong("timestamp");
                blockchain.add(block);
                votedUsers.add(block.voterId);
                results.put(block.candidate, results.getOrDefault(block.candidate, 0) + 1);
                totalVotes++;
            }
            con.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ── Results & chain ───────────────────────────────────────────────────────

    public static synchronized Map<String, Integer> getResultsApi() {
        initCandidates();
        ensureAllCandidatesPresent();
        return new HashMap<>(results);
    }

    public static synchronized int getTotalVotesApi()          { return totalVotes; }
    public static synchronized List<Block> getBlockchainApi()  { return new ArrayList<>(blockchain); }

    public static synchronized boolean validateBlockchainApi() {
        for (int i = 1; i < blockchain.size(); i++) {
            Block cur  = blockchain.get(i);
            Block prev = blockchain.get(i - 1);
            if (!Objects.equals(cur.previousHash, prev.hash))    return false;
            if (!Objects.equals(cur.hash, cur.calculateHash()))  return false;
        }
        return true;
    }

    public static synchronized void tamperBlock() {
        if (blockchain.size() <= 1) return;
        blockchain.get(1).candidate = "HACKED";
    }

    // ── Face recognition ──────────────────────────────────────────────────────

    /**
     * Stores a voter's face descriptor (JSON float array from face-api.js).
     * Returns "Face registered" on success, or an error message.
     */
    public static synchronized String registerFace(String voterId, String descriptorJson) {
        if (voterId == null || voterId.trim().isEmpty()) return "Voter ID is required";
        if (descriptorJson == null || descriptorJson.isEmpty())  return "Face descriptor is required";

        double[] incoming = parseDescriptor(descriptorJson);
        if (incoming == null || incoming.length != 128)
            return "Invalid face descriptor (expected 128 values)";

        try {
            Connection con = DatabaseConnection.getConnection();

            // Check duplicate
            PreparedStatement check = con.prepareStatement(
                "SELECT voter_id FROM voter_faces WHERE voter_id = ?");
            check.setString(1, voterId);
            ResultSet rs = check.executeQuery();
            if (rs.next()) { con.close(); return "Face already registered for this voter ID"; }

            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO voter_faces(voter_id, face_descriptor, registered_at) VALUES(?,?,?)");
            ps.setString(1, voterId);
            ps.setString(2, descriptorJson);
            ps.setLong  (3, System.currentTimeMillis());
            ps.executeUpdate();
            con.close();
            return "Face registered";
        } catch (Exception e) {
            e.printStackTrace();
            return "Database error: " + e.getMessage();
        }
    }

    /**
     * Compares the provided descriptor with the one stored for voterId.
     * Returns true if Euclidean distance < 0.55 (strict threshold).
     */
    public static boolean verifyFace(String voterId, String descriptorJson) {
        if (voterId == null || descriptorJson == null) return false;

        try {
            Connection con = DatabaseConnection.getConnection();
            PreparedStatement ps = con.prepareStatement(
                "SELECT face_descriptor FROM voter_faces WHERE voter_id = ?");
            ps.setString(1, voterId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) { con.close(); return false; }

            String storedJson = rs.getString("face_descriptor");
            con.close();

            double[] stored   = parseDescriptor(storedJson);
            double[] incoming = parseDescriptor(descriptorJson);
            if (stored == null || incoming == null || stored.length != incoming.length) return false;

            double dist = euclideanDistance(stored, incoming);
            System.out.printf("Face verification for %s: distance=%.4f%n", voterId, dist);
            return dist < 0.55;   // strict threshold (0.6 is common; 0.55 reduces false-positives)
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // ── Face math helpers ─────────────────────────────────────────────────────

    /** Parses a JSON array string "[0.1, -0.2, ...]" into a double[]. */
    private static double[] parseDescriptor(String json) {
        if (json == null) return null;
        String trimmed = json.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null;
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty()) return null;
        String[] parts = inner.split(",");
        double[] result = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Double.parseDouble(parts[i].trim());
        }
        return result;
    }

    /** Standard Euclidean distance between two equal-length vectors. */
    private static double euclideanDistance(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) { double d = a[i] - b[i]; sum += d * d; }
        return Math.sqrt(sum);
    }
}
