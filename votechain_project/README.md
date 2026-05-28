# VoteChain — Blockchain Voting System with Face Recognition

Live demo: https://pbl-lh5f.vercel.app/login.html

## Project Structure

```
votechain_project/
├── VotingApi.java          # REST API server (updated: /register-face, /verify-face)
├── VotingSystem.java       # Blockchain logic (updated: face registration & verification)
├── DatabaseConnection.java # MySQL connection
├── SHA.java                # SHA-256 utility
├── Dockerfile              # Container config
├── register.html           # Face registration page (NEW)
└── vote.html               # Voting page with face verification (NEW)
```

## Face Recognition Flow

### Registration (register.html)
1. Voter enters their Voter ID
2. Webcam opens — face-api.js detects face in browser
3. 128-float face descriptor captured and sent to `/register-face`
4. Descriptor stored in `voter_faces` MySQL table

### Voting (vote.html)
1. Voter enters their Voter ID
2. Live face scan → descriptor sent to `/verify-face`
3. Backend computes Euclidean distance vs stored descriptor
4. If distance < 0.55 → identity confirmed → ballot unlocks
5. Voter picks candidate → `/vote` seals it in blockchain

## New API Endpoints

| Method | Path             | Body                                  | Response                        |
|--------|------------------|---------------------------------------|---------------------------------|
| POST   | /register-face   | `{ voterId, descriptor: [128 floats] }` | `{ status, message }`         |
| POST   | /verify-face     | `{ voterId, descriptor: [128 floats] }` | `{ verified: true/false }`    |

## Database Tables (auto-created on startup)

```sql
CREATE TABLE voter_faces (
  voter_id        VARCHAR(255) PRIMARY KEY,
  face_descriptor TEXT NOT NULL,
  registered_at   BIGINT
);
```

## Deployment

### Step 1 — Update API URL in HTML files
In both `register.html` and `vote.html`, change line 1 of the script:
```js
const API_BASE = 'http://localhost:4567';
// change to your Railway backend URL:
const API_BASE = 'https://your-app.railway.app';
```

### Step 2 — Deploy backend (Railway)
Replace VotingApi.java and VotingSystem.java, rebuild Docker image.
The `voter_faces` table is created automatically on first start.

### Step 3 — Deploy frontend (Vercel)
Upload `register.html` and `vote.html` alongside `login.html`.

## Dependencies
- face-api.js 0.22.2 (loaded from CDN — no npm install needed)
- MySQL connector (already in your Docker setup)
- All face recognition runs in the browser — no Python or extra backend needed
