import java.sql.*;
import java.util.*;

/**
 * Core blockchain engine for VoteChain.
 *
 * Responsibilities:
 *  - Maintain an in-memory copy of the chain (loaded from DB at startup)
 *  - Add new vote blocks with Proof-of-Work
 *  - Persist every block to MySQL
 *  - Validate chain integrity
 *  - Provide tallies, voter queries, and chain dump
 */
public class VotingSystem {

    // ──────────────────────────────────────────────────
    // Constants
    // ──────────────────────────────────────────────────
    private static final int DEFAULT_DIFFICULTY = 2;   // leading zeros required
    private static final String GENESIS_HASH =
        "0acd5b3e7f10ba6e5e2d5a9f4c7d8e1b2f3a4c5d6e7f8a9b0c1d2e3f4a5b6c7";

    // ──────────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────────
    private final List<Block> chain = new ArrayList<>();
    private int difficulty;

    // ──────────────────────────────────────────────────
    // Bootstrap
    // ──────────────────────────────────────────────────
    public VotingSystem() {
        this.difficulty = loadDifficulty();
        loadChainFromDB();
        if (chain.isEmpty()) {
            initGenesisBlock();
        }
        System.out.printf("[VoteChain] Loaded %d block(s). Difficulty = %d%n",
                          chain.size(), difficulty);
    }

    private int loadDifficulty() {
        try (Connection c = DatabaseConnection.getConnection();
             Statement  s = c.createStatement();
             ResultSet  r = s.executeQuery(
                 "SELECT config_value FROM election_config WHERE config_key='difficulty'")) {
            if (r.next()) return Integer.parseInt(r.getString(1));
        } catch (Exception e) {
            System.err.println("[VoteChain] Could not load difficulty: " + e.getMessage());
        }
        return DEFAULT_DIFFICULTY;
    }

    private void loadChainFromDB() {
        String sql = "SELECT block_index, timestamp, voter_id_hash, candidate_id, " +
                     "previous_hash, block_hash, nonce, merkle_root " +
                     "FROM blockchain ORDER BY block_index ASC";
        try (Connection c = DatabaseConnection.getConnection();
             Statement  s = c.createStatement();
             ResultSet  r = s.executeQuery(sql)) {

            while (r.next()) {
                chain.add(new Block(
                    r.getInt("block_index"),
                    r.getLong("timestamp"),
                    r.getString("voter_id_hash"),
                    r.getString("candidate_id"),
                    r.getString("previous_hash"),
                    r.getString("block_hash"),
                    r.getInt("nonce"),
                    r.getString("merkle_root")
                ));
            }
        } catch (SQLException e) {
            System.err.println("[VoteChain] Error loading chain: " + e.getMessage());
        }
    }

    private void initGenesisBlock() {
        Block genesis = new Block(0, 0L,
            "0000000000000000000000000000000000000000000000000000000000000000",
            "GENESIS",
            "0000000000000000000000000000000000000000000000000000000000000000",
            GENESIS_HASH,
            0,
            "0000000000000000000000000000000000000000000000000000000000000000");
        chain.add(genesis);
        persistBlock(genesis);
        System.out.println("[VoteChain] Genesis block created.");
    }

    // ──────────────────────────────────────────────────
    // Vote submission
    // ──────────────────────────────────────────────────

    /**
     * Adds a vote to the blockchain.
     *
     * @param voterId     The authenticated voter's ID
     * @param candidateId The chosen candidate's ID
     * @return The newly mined block, or null on failure
     */
    public synchronized Block addVote(String voterId, String candidateId) {

        // 1. Verify voter exists and hasn't voted
        VoterStatus status = getVoterStatus(voterId);
        if (status == VoterStatus.NOT_FOUND) {
            System.err.println("[VoteChain] Unknown voter: " + voterId);
            return null;
        }
        if (status == VoterStatus.ALREADY_VOTED) {
            System.err.println("[VoteChain] Duplicate vote attempt: " + voterId);
            return null;
        }

        // 2. Verify candidate exists
        if (!candidateExists(candidateId)) {
            System.err.println("[VoteChain] Unknown candidate: " + candidateId);
            return null;
        }

        // 3. Mine the new block
        Block prev = chain.get(chain.size() - 1);
        Block newBlock = new Block(
            chain.size(),
            voterId,
            candidateId,
            prev.getBlockHash(),
            difficulty
        );

        // 4. Persist block, mark voter, update candidate tally — atomically
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                insertBlock(c, newBlock);
                markVoterAsVoted(c, voterId);
                incrementCandidateVote(c, candidateId);
                logAudit(c, "VOTE_CAST", voterId, null,
                         "Block " + newBlock.getIndex() + " → " + candidateId);
                c.commit();
                chain.add(newBlock);
                System.out.println("[VoteChain] " + newBlock);
                return newBlock;
            } catch (SQLException ex) {
                c.rollback();
                System.err.println("[VoteChain] Transaction rolled back: " + ex.getMessage());
                return null;
            }
        } catch (SQLException e) {
            System.err.println("[VoteChain] DB error: " + e.getMessage());
            return null;
        }
    }

    // ──────────────────────────────────────────────────
    // Chain validation
    // ──────────────────────────────────────────────────

    /**
     * Walks the entire chain verifying:
     *   1. Each block's stored hash matches its recomputed hash
     *   2. Each block's previousHash matches the preceding block's hash
     */
    public boolean isChainValid() {
        for (int i = 1; i < chain.size(); i++) {
            Block current  = chain.get(i);
            Block previous = chain.get(i - 1);

            if (!current.isHashValid()) {
                System.err.printf("[Validate] Block %d: hash mismatch!%n", i);
                return false;
            }
            if (!current.getPreviousHash().equals(previous.getBlockHash())) {
                System.err.printf("[Validate] Block %d: broken chain link!%n", i);
                return false;
            }
        }
        return true;
    }

    // ──────────────────────────────────────────────────
    // Results & queries
    // ──────────────────────────────────────────────────

    /** Returns live vote tallies from the candidates table. */
    public List<Map<String, Object>> getResults() {
        List<Map<String, Object>> results = new ArrayList<>();
        String sql = "SELECT candidate_id, full_name, party, symbol, vote_count " +
                     "FROM candidates ORDER BY vote_count DESC";
        try (Connection c = DatabaseConnection.getConnection();
             Statement  s = c.createStatement();
             ResultSet  r = s.executeQuery(sql)) {
            while (r.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("candidate_id", r.getString("candidate_id"));
                row.put("full_name",    r.getString("full_name"));
                row.put("party",        r.getString("party"));
                row.put("symbol",       r.getString("symbol"));
                row.put("vote_count",   r.getInt("vote_count"));
                results.add(row);
            }
        } catch (SQLException e) {
            System.err.println("[VoteChain] getResults error: " + e.getMessage());
        }
        return results;
    }

    /** Returns the total number of votes cast (chain length − 1 for genesis). */
    public int getTotalVotes() {
        return Math.max(0, chain.size() - 1);
    }

    /** Returns the total registered voters. */
    public int getTotalRegisteredVoters() {
        String sql = "SELECT COUNT(*) FROM voters";
        try (Connection c = DatabaseConnection.getConnection();
             Statement  s = c.createStatement();
             ResultSet  r = s.executeQuery(sql)) {
            if (r.next()) return r.getInt(1);
        } catch (SQLException e) {
            System.err.println("[VoteChain] Count error: " + e.getMessage());
        }
        return 0;
    }

    /** Returns a safe copy of the chain for API responses. */
    public List<Block> getChain() {
        return Collections.unmodifiableList(chain);
    }

    /** Checks if a voter has already voted. */
    public boolean hasVoted(String voterId) {
        return getVoterStatus(voterId) == VoterStatus.ALREADY_VOTED;
    }

    /** Looks up full voter record (for the admin panel). */
    public Map<String, Object> getVoterInfo(String voterId) {
        String sql = "SELECT voter_id, full_name, email, face_registered, has_voted, registered_at " +
                     "FROM voters WHERE voter_id = ?";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, voterId);
            try (ResultSet r = ps.executeQuery()) {
                if (r.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("voter_id",        r.getString("voter_id"));
                    m.put("full_name",       r.getString("full_name"));
                    m.put("email",           r.getString("email"));
                    m.put("face_registered", r.getBoolean("face_registered"));
                    m.put("has_voted",       r.getBoolean("has_voted"));
                    m.put("registered_at",   r.getString("registered_at"));
                    return m;
                }
            }
        } catch (SQLException e) {
            System.err.println("[VoteChain] getVoterInfo error: " + e.getMessage());
        }
        return null;
    }

    /** Returns all candidates. */
    public List<Map<String, Object>> getCandidates() {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT candidate_id, full_name, party, symbol, manifesto, vote_count " +
                     "FROM candidates WHERE candidate_id != 'GENESIS' ORDER BY candidate_id";
        try (Connection c = DatabaseConnection.getConnection();
             Statement  s = c.createStatement();
             ResultSet  r = s.executeQuery(sql)) {
            while (r.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("candidate_id", r.getString("candidate_id"));
                m.put("full_name",    r.getString("full_name"));
                m.put("party",        r.getString("party"));
                m.put("symbol",       r.getString("symbol"));
                m.put("manifesto",    r.getString("manifesto"));
                m.put("vote_count",   r.getInt("vote_count"));
                list.add(m);
            }
        } catch (SQLException e) {
            System.err.println("[VoteChain] getCandidates error: " + e.getMessage());
        }
        return list;
    }

    // ──────────────────────────────────────────────────
    // Voter registration (called by the API)
    // ──────────────────────────────────────────────────

    public boolean registerVoter(String voterId, String fullName,
                                  String email, String aadharHash) {
        String sql = "INSERT INTO voters (voter_id, full_name, email, aadhar_hash) " +
                     "VALUES (?, ?, ?, ?)";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, voterId);
            ps.setString(2, fullName);
            ps.setString(3, email);
            ps.setString(4, aadharHash);
            ps.executeUpdate();
            logAudit(c, "VOTER_REGISTERED", voterId, null, "New voter: " + fullName);
            return true;
        } catch (SQLException e) {
            System.err.println("[VoteChain] registerVoter error: " + e.getMessage());
            return false;
        }
    }

    public boolean markFaceRegistered(String voterId) {
        String sql = "UPDATE voters SET face_registered = 1 WHERE voter_id = ?";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, voterId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[VoteChain] markFaceRegistered error: " + e.getMessage());
            return false;
        }
    }

    public boolean isFaceRegistered(String voterId) {
        String sql = "SELECT face_registered FROM voters WHERE voter_id = ?";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, voterId);
            try (ResultSet r = ps.executeQuery()) {
                return r.next() && r.getBoolean("face_registered");
            }
        } catch (SQLException e) {
            return false;
        }
    }

    // ──────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────

    private enum VoterStatus { NOT_FOUND, ELIGIBLE, ALREADY_VOTED }

    private VoterStatus getVoterStatus(String voterId) {
        String sql = "SELECT has_voted FROM voters WHERE voter_id = ?";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, voterId);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) return VoterStatus.NOT_FOUND;
                return r.getBoolean("has_voted")
                       ? VoterStatus.ALREADY_VOTED
                       : VoterStatus.ELIGIBLE;
            }
        } catch (SQLException e) {
            return VoterStatus.NOT_FOUND;
        }
    }

    private boolean candidateExists(String candidateId) {
        String sql = "SELECT 1 FROM candidates WHERE candidate_id = ?";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, candidateId);
            try (ResultSet r = ps.executeQuery()) { return r.next(); }
        } catch (SQLException e) { return false; }
    }

    private void insertBlock(Connection c, Block b) throws SQLException {
        String sql = "INSERT INTO blockchain " +
                     "(block_index, timestamp, voter_id_hash, candidate_id, " +
                     " previous_hash, block_hash, nonce, merkle_root) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1,    b.getIndex());
            ps.setLong(2,   b.getTimestamp());
            ps.setString(3, b.getVoterIdHash());
            ps.setString(4, b.getCandidateId());
            ps.setString(5, b.getPreviousHash());
            ps.setString(6, b.getBlockHash());
            ps.setInt(7,    b.getNonce());
            ps.setString(8, b.getMerkleRoot());
            ps.executeUpdate();
        }
    }

    /** Convenience overload used during genesis. */
    private void persistBlock(Block b) {
        try (Connection c = DatabaseConnection.getConnection()) {
            insertBlock(c, b);
        } catch (SQLException e) {
            System.err.println("[VoteChain] persistBlock error: " + e.getMessage());
        }
    }

    private void markVoterAsVoted(Connection c, String voterId) throws SQLException {
        String sql = "UPDATE voters SET has_voted = 1 WHERE voter_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, voterId);
            ps.executeUpdate();
        }
    }

    private void incrementCandidateVote(Connection c, String candidateId) throws SQLException {
        String sql = "UPDATE candidates SET vote_count = vote_count + 1 WHERE candidate_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, candidateId);
            ps.executeUpdate();
        }
    }

    private void logAudit(Connection c, String eventType, String voterId,
                           String ip, String details) {
        String sql = "INSERT INTO audit_log (event_type, voter_id, ip_address, details) " +
                     "VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, eventType);
            ps.setString(2, voterId);
            ps.setString(3, ip);
            ps.setString(4, details);
            ps.executeUpdate();
        } catch (SQLException ignored) { /* audit failures shouldn't break voting */ }
    }
}
