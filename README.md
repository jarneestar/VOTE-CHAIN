# VOTE-CHAIN
# VoteChain — Blockchain Voting System

Secure, tamper-evident voting using SHA-256 blockchain + face recognition + MySQL.

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  Browser  ← frontend/index.html (pure HTML/JS)      │
└──────────────────┬────────────────┬─────────────────┘
                   │                │
         HTTP :4567│                │HTTP :5000
                   ▼                ▼
        ┌──────────────┐   ┌───────────────────┐
        │  Java API    │   │  Python Face API  │
        │  VotingApi   │   │  face_server.py   │
        │  (HttpServer)│   │  (Flask)          │
        └──────┬───────┘   └────────┬──────────┘
               │ JDBC               │ .npy files
               ▼                    ▼
        ┌──────────────┐    ┌──────────────────┐
        │  MySQL       │    │  faces/ directory │
        │  votechain DB│    │  voter_id.npy     │
        └──────────────┘    └──────────────────┘
```

## File Structure

```
votingchain/
├── java/
│   ├── SHA.java              # SHA-256 + Proof-of-Work
│   ├── DatabaseConnection.java
│   ├── Block.java            # Blockchain block
│   ├── VotingSystem.java     # Core engine + DB logic
│   └── VotingApi.java        # HTTP REST server (port 4567)
├── python/
│   └── face_server.py        # Flask face recognition (port 5000)
├── frontend/
│   └── index.html            # Complete UI (no framework needed)
├── db/
│   └── schema.sql            # MySQL DDL + seed data
├── lib/                      # Put mysql-connector-j.jar here
├── out/                      # Compiled Java classes (auto-created)
├── faces/                    # Face encodings (auto-created)
├── logs/                     # Server logs (auto-created)
├── build.sh                  # Compile Java
└── start.sh                  # Start everything
```

---

## Prerequisites

| Component | Requirement |
|-----------|-------------|
| Java      | JDK 17+     |
| Python    | 3.9+        |
| MySQL     | 8.0+        |
| MySQL Connector/J | 9.x — place in `lib/` |

### Python packages
```bash
pip install flask flask-cors face_recognition opencv-python numpy
```
> `face_recognition` requires `cmake` and `dlib`. On Ubuntu/Debian:
> ```bash
> sudo apt-get install -y cmake build-essential libopenblas-dev liblapack-dev libx11-dev
> pip install dlib face_recognition
> ```

---

## Setup

### 1. MySQL
```sql
-- Run as root
source votingchain/db/schema.sql;
```
This creates the `votechain` database with all tables and seed candidates.

Default credentials (edit `DatabaseConnection.java` or set env vars):
```
DB_URL  = jdbc:mysql://localhost:3306/votechain
DB_USER = root
DB_PASS = root369
```

### 2. Place MySQL connector JAR
```
lib/mysql-connector-j-9.x.x.jar
```

### 3. Build Java
```bash
chmod +x build.sh start.sh
bash build.sh
```

### 4. Start everything
```bash
bash start.sh
```

Then open `frontend/index.html` in your browser.

---

## REST API Reference

### Java API (port 4567)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/health` | System health + chain stats |
| GET | `/candidates` | List all candidates |
| GET | `/results` | Live vote tallies |
| GET | `/chain` | Full blockchain dump |
| GET | `/validate` | Validate chain integrity |
| GET | `/voter?id=VOT001` | Voter info |
| POST | `/register-voter` | Register new voter |
| POST | `/mark-face` | Mark face as registered |
| POST | `/vote` | Cast a vote |
| POST | `/verify-vote-status` | Check if voted |

### Python API (port 5000)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/` | Health check |
| POST | `/register` | Register face encoding |
| POST | `/verify` | Verify face |
| POST | `/check-liveness` | Anti-spoofing check |
| POST | `/delete` | Delete face encoding |
| GET | `/faces` | List registered voter IDs |

---

## How a Vote Is Cast

1. **Login** — voter enters their Voter ID
2. **Face verify** — Python API compares live webcam frame to stored encoding (tolerance = 0.45)
3. **Select candidate** — clicks on candidate card
4. **Confirm** — modal popup, then Java API mines a new block:
   - SHA-256 of `(index + timestamp + SHA(voterId) + candidateId + prevHash + merkleRoot) + nonce`
   - Proof-of-Work: hash must start with `difficulty` zeros
   - Block persisted to MySQL, voter marked as voted, candidate tally incremented — all in one transaction

---

## Blockchain Details

- **Algorithm**: SHA-256 (java.security.MessageDigest)
- **Proof-of-Work**: hashcash-style, configurable difficulty (default: 2 leading zeros)
- **Voter anonymity**: only `SHA-256(voter_id)` is stored in the block — not the raw ID
- **Merkle root**: `SHA-256(voterIdHash + candidateId)` per block
- **Chain validation**: re-computes every block hash and checks the prev-hash link

---

## Candidates (Seed Data)

| ID | Name | Party | Symbol |
|----|------|-------|--------|
| C001 | Arjun Sharma | National Development Party | 🪷 Lotus |
| C002 | Priya Gupta | Progressive Alliance | ✋ Hand |
| C003 | Ramesh Verma | United People Front | 🚲 Bicycle |
| C004 | Sunita Patel | Green India Party | 🌳 Tree |
| NOTA | None Of Above | NOTA | ❌ |

---

## Security Features

- **Anti-double-vote**: `has_voted` flag + checked before every vote
- **Tamper detection**: chain validation re-hashes every block
- **Anonymous votes**: voter IDs are SHA-256 hashed in the chain
- **Aadhar privacy**: stored as SHA-256 hash only
- **Liveness check**: face landmark analysis before registration
- **Strict face tolerance**: 0.45 (lower = stricter than the default 0.6)
- **Atomic DB transactions**: vote + mark-voted + tally update in one SQL transaction

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_URL` | jdbc:mysql://localhost:3306/votechain | JDBC URL |
| `DB_USER` | root | MySQL user |
| `DB_PASS` | root369 | MySQL password |
| `FACES_DIR` | faces/ | Directory for face .npy files |
| `FACE_TOLERANCE` | 0.45 | Face match threshold (lower = stricter) |
| `MIN_BRIGHTNESS` | 30 | Min image brightness |
| `MAX_BRIGHTNESS` | 240 | Max image brightness |
