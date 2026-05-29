-- ============================================================
-- VoteChain - Blockchain Voting System - Database Schema
-- MySQL 8.0+
-- ============================================================

CREATE DATABASE IF NOT EXISTS votechain;
USE votechain;

-- ============================================================
-- VOTERS TABLE
-- ============================================================
CREATE TABLE IF NOT EXISTS voters (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    voter_id        VARCHAR(50)  NOT NULL UNIQUE,
    full_name       VARCHAR(100) NOT NULL,
    email           VARCHAR(100) NOT NULL UNIQUE,
    aadhar_hash     VARCHAR(64)  NOT NULL UNIQUE,  -- SHA-256 of Aadhar number
    face_registered TINYINT(1)   NOT NULL DEFAULT 0,
    has_voted       TINYINT(1)   NOT NULL DEFAULT 0,
    registered_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_voter_id (voter_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- CANDIDATES TABLE
-- ============================================================
CREATE TABLE IF NOT EXISTS candidates (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    candidate_id    VARCHAR(50)  NOT NULL UNIQUE,
    full_name       VARCHAR(100) NOT NULL,
    party           VARCHAR(100) NOT NULL,
    symbol          VARCHAR(50)  NOT NULL,
    manifesto       TEXT,
    vote_count      INT          NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- BLOCKCHAIN TABLE
-- ============================================================
CREATE TABLE IF NOT EXISTS blockchain (
    block_index     INT          NOT NULL PRIMARY KEY,
    timestamp       BIGINT       NOT NULL,
    voter_id_hash   VARCHAR(64)  NOT NULL,   -- SHA-256 of voter_id (anonymous)
    candidate_id    VARCHAR(50)  NOT NULL,
    previous_hash   VARCHAR(64)  NOT NULL,
    block_hash      VARCHAR(64)  NOT NULL UNIQUE,
    nonce           INT          NOT NULL DEFAULT 0,
    merkle_root     VARCHAR(64)  NOT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_block_hash (block_hash),
    INDEX idx_candidate (candidate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- AUDIT LOG TABLE
-- ============================================================
CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type  VARCHAR(50)  NOT NULL,
    voter_id    VARCHAR(50),
    ip_address  VARCHAR(45),
    details     TEXT,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_event_type (event_type),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- ELECTION CONFIG TABLE
-- ============================================================
CREATE TABLE IF NOT EXISTS election_config (
    config_key   VARCHAR(50)  NOT NULL PRIMARY KEY,
    config_value TEXT         NOT NULL,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- SEED DATA
-- ============================================================

-- Election config
INSERT INTO election_config (config_key, config_value) VALUES
('election_name',   'General Election 2025'),
('election_active', '0'),
('start_time',      '2025-01-01 00:00:00'),
('end_time',        '2025-12-31 23:59:59'),
('difficulty',      '2')
ON DUPLICATE KEY UPDATE config_value = VALUES(config_value);

-- Sample candidates
INSERT INTO candidates (candidate_id, full_name, party, symbol, manifesto) VALUES
('C001', 'Arjun Sharma',  'National Development Party', 'Lotus',      'Economic growth, infrastructure, digital India.'),
('C002', 'Priya Gupta',   'Progressive Alliance',       'Hand',        'Education, healthcare, women empowerment.'),
('C003', 'Ramesh Verma',  'United People Front',        'Bicycle',     'Farmer welfare, rural development, employment.'),
('C004', 'Sunita Patel',  'Green India Party',          'Tree',        'Environment, sustainability, clean energy.'),
('NOTA', 'None Of Above', 'NOTA',                       'Cross',       'None of the above candidates.')
ON DUPLICATE KEY UPDATE full_name = VALUES(full_name);

-- Genesis block (block 0)
INSERT IGNORE INTO blockchain
    (block_index, timestamp, voter_id_hash, candidate_id, previous_hash, block_hash, nonce, merkle_root)
VALUES
    (0, 0, '0000000000000000000000000000000000000000000000000000000000000000',
     'GENESIS',
     '0000000000000000000000000000000000000000000000000000000000000000',
     '0acd5b3e7f10ba6e5e2d5a9f4c7d8e1b2f3a4c5d6e7f8a9b0c1d2e3f4a5b6c7',
     0,
     '0000000000000000000000000000000000000000000000000000000000000000');

COMMIT;
