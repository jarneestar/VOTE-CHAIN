import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * SHA-256 Hashing Utility
 * Used for block hashing and voter ID anonymization.
 */
public class SHA {

    private SHA() {}

    /**
     * Produces a lowercase hex SHA-256 digest of the input string.
     */
    public static String hash(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Computes Merkle root of two hashes (used per block).
     */
    public static String merkleRoot(String left, String right) {
        return hash(left + right);
    }

    /**
     * Proof-of-Work: hash the block data until the result starts with
     * `difficulty` zero characters.
     */
    public static MineResult mine(String blockData, int difficulty) {
        String target = "0".repeat(difficulty);
        int nonce = 0;
        String result;
        do {
            result = hash(blockData + nonce);
            nonce++;
        } while (!result.startsWith(target));
        return new MineResult(nonce - 1, result);
    }

    public static class MineResult {
        public final int    nonce;
        public final String hash;
        MineResult(int nonce, String hash) {
            this.nonce = nonce;
            this.hash  = hash;
        }
    }
}
