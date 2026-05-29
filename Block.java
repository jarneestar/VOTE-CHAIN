/**
 * Represents a single block in the VoteChain blockchain.
 *
 * Each vote produces exactly one block.  The block hash is computed
 * with a simple Proof-of-Work so the chain is tamper-evident.
 *
 * Block data string (used for hashing):
 *   index + timestamp + voterIdHash + candidateId + previousHash + merkleRoot
 */
public class Block {

    // ──────────────────────────────────────────────────
    // Fields (stored in DB)
    // ──────────────────────────────────────────────────
    private final int    index;
    private final long   timestamp;
    private final String voterIdHash;   // SHA-256(voter_id)  — keeps vote anonymous
    private final String candidateId;
    private final String previousHash;
    private       String blockHash;
    private       int    nonce;
    private final String merkleRoot;    // SHA-256(voterIdHash + candidateId)

    // ──────────────────────────────────────────────────
    // Constructor — mines the block immediately
    // ──────────────────────────────────────────────────
    public Block(int    index,
                 String voterId,
                 String candidateId,
                 String previousHash,
                 int    difficulty) {

        this.index        = index;
        this.timestamp    = System.currentTimeMillis();
        this.voterIdHash  = SHA.hash(voterId);
        this.candidateId  = candidateId;
        this.previousHash = previousHash;
        this.merkleRoot   = SHA.merkleRoot(this.voterIdHash, candidateId);

        // Proof-of-Work
        SHA.MineResult result = SHA.mine(getRawData(), difficulty);
        this.nonce     = result.nonce;
        this.blockHash = result.hash;
    }

    /** Constructor used when loading a block from the database. */
    public Block(int    index,
                 long   timestamp,
                 String voterIdHash,
                 String candidateId,
                 String previousHash,
                 String blockHash,
                 int    nonce,
                 String merkleRoot) {

        this.index        = index;
        this.timestamp    = timestamp;
        this.voterIdHash  = voterIdHash;
        this.candidateId  = candidateId;
        this.previousHash = previousHash;
        this.blockHash    = blockHash;
        this.nonce        = nonce;
        this.merkleRoot   = merkleRoot;
    }

    // ──────────────────────────────────────────────────
    // Integrity check
    // ──────────────────────────────────────────────────

    /**
     * Recomputes the hash and confirms it matches the stored hash.
     * Used during chain validation.
     */
    public boolean isHashValid() {
        String recomputed = SHA.hash(getRawData() + nonce);
        return recomputed.equals(blockHash);
    }

    /** The canonical string that is hashed (without nonce). */
    public String getRawData() {
        return index + timestamp + voterIdHash + candidateId + previousHash + merkleRoot;
    }

    // ──────────────────────────────────────────────────
    // Getters
    // ──────────────────────────────────────────────────
    public int    getIndex()        { return index;        }
    public long   getTimestamp()    { return timestamp;    }
    public String getVoterIdHash()  { return voterIdHash;  }
    public String getCandidateId()  { return candidateId;  }
    public String getPreviousHash() { return previousHash; }
    public String getBlockHash()    { return blockHash;    }
    public int    getNonce()        { return nonce;        }
    public String getMerkleRoot()   { return merkleRoot;   }

    @Override
    public String toString() {
        return String.format(
            "Block{index=%d, candidate='%s', hash='%s...', prev='%s...', nonce=%d}",
            index, candidateId,
            blockHash.substring(0, 12), previousHash.substring(0, 12),
            nonce);
    }
}
