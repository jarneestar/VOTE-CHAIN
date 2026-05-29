import base64, logging, os, time
from pathlib import Path
from typing import Optional
import cv2, face_recognition, numpy as np
from flask import Flask, jsonify, request
from flask_cors import CORS

FACES_DIR      = Path(os.environ.get("FACES_DIR", "faces"))
TOLERANCE      = float(os.environ.get("FACE_TOLERANCE", "0.50"))
MIN_BRIGHTNESS = float(os.environ.get("MIN_BRIGHTNESS", "15.0"))
MAX_BRIGHTNESS = float(os.environ.get("MAX_BRIGHTNESS", "240.0"))
FACES_DIR.mkdir(parents=True, exist_ok=True)

logging.basicConfig(level=logging.INFO, format="[%(asctime)s] %(levelname)s %(message)s", datefmt="%H:%M:%S")
log = logging.getLogger("FaceAPI")
app = Flask(__name__)
CORS(app)

def decode_image(data_url):
    try:
        if "," in data_url: data_url = data_url.split(",", 1)[1]
        np_arr = np.frombuffer(base64.b64decode(data_url), dtype=np.uint8)
        bgr = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)
        return cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB) if bgr is not None else None
    except: return None

def brightness(rgb):
    return float(np.mean(cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)))

def encoding_path(voter_id):
    safe = "".join(c for c in voter_id if c.isalnum() or c in "-_")
    return FACES_DIR / f"{safe}.npy"

def load_encoding(voter_id):
    p = encoding_path(voter_id)
    return np.load(str(p)) if p.exists() else None

def save_encoding(voter_id, enc):
    np.save(str(encoding_path(voter_id)), enc)

def detect_and_encode(rgb):
    locs = face_recognition.face_locations(rgb, number_of_times_to_upsample=2, model="hog")
    if not locs: raise ValueError("No face detected. Look directly at camera in good lighting.")
    if len(locs) > 1: raise ValueError(f"{len(locs)} faces detected. Only one person allowed.")
    encs = face_recognition.face_encodings(rgb, locs)
    if not encs: raise ValueError("Encoding failed. Try better lighting.")
    return locs[0], encs[0]

def find_matching_voter(new_enc, exclude_voter_id=None):
    all_npy = list(FACES_DIR.glob("*.npy"))
    if not all_npy: return None
    encs, vids = [], []
    for f in all_npy:
        vid = f.stem
        if exclude_voter_id and vid == exclude_voter_id: continue
        try: encs.append(np.load(str(f))); vids.append(vid)
        except: continue
    if not encs: return None
    matches = face_recognition.compare_faces(encs, new_enc, tolerance=TOLERANCE)
    for i, m in enumerate(matches):
        if m:
            log.warning("Duplicate face: matches voter=%s", vids[i])
            return vids[i]
    return None

@app.route("/", methods=["GET"])
def health():
    return jsonify({"status":"ok","registered_voters":len(list(FACES_DIR.glob("*.npy"))),"tolerance":TOLERANCE,"timestamp":time.time()})

@app.route("/register", methods=["POST"])
def register():
    data = request.get_json(force=True, silent=True) or {}
    voter_id = data.get("voter_id","").strip()
    image_b64 = data.get("image","")
    if not voter_id: return jsonify({"success":False,"message":"voter_id required"}),400
    if not image_b64: return jsonify({"success":False,"message":"image required"}),400
    rgb = decode_image(image_b64)
    if rgb is None: return jsonify({"success":False,"message":"Cannot decode image"}),400
    bri = brightness(rgb)
    if bri < MIN_BRIGHTNESS: return jsonify({"success":False,"message":f"Too dark (brightness={bri:.0f})"}),400
    if bri > MAX_BRIGHTNESS: return jsonify({"success":False,"message":f"Overexposed (brightness={bri:.0f})"}),400
    try: _loc, enc = detect_and_encode(rgb)
    except ValueError as e: return jsonify({"success":False,"message":str(e)}),400
    dup = find_matching_voter(enc, exclude_voter_id=voter_id)
    if dup:
        log.warning("BLOCKED: voter=%s face matches voter=%s", voter_id, dup)
        return jsonify({"success":False,"message":"This face is already registered under another Voter ID. Duplicate not allowed."}),400
    already = encoding_path(voter_id).exists()
    save_encoding(voter_id, enc)
    log.info("Registered voter=%s re=%s", voter_id, already)
    return jsonify({"success":True,"message":"Face registered successfully" if not already else "Face re-registered","voter_id":voter_id,"brightness":round(bri,1)})

@app.route("/verify", methods=["POST"])
def verify():
    data = request.get_json(force=True, silent=True) or {}
    voter_id = data.get("voter_id","").strip()
    image_b64 = data.get("image","")
    if not voter_id: return jsonify({"success":False,"message":"voter_id required"}),400
    if not image_b64: return jsonify({"success":False,"message":"image required"}),400
    saved_enc = load_encoding(voter_id)
    if saved_enc is None: return jsonify({"success":False,"message":f"No face registered for '{voter_id}'"}),404
    rgb = decode_image(image_b64)
    if rgb is None: return jsonify({"success":False,"message":"Cannot decode image"}),400
    bri = brightness(rgb)
    if bri < MIN_BRIGHTNESS: return jsonify({"success":False,"message":f"Too dark (brightness={bri:.0f})"}),400
    try: _loc, cur_enc = detect_and_encode(rgb)
    except ValueError as e: return jsonify({"success":False,"message":str(e)}),400
    actual = find_matching_voter(cur_enc, exclude_voter_id=None)
    if actual is None:
        return jsonify({"success":False,"message":"Face not recognized.","confidence":0.0})
    if actual != voter_id:
        log.warning("FRAUD: voter=%s using face of voter=%s", voter_id, actual)
        return jsonify({"success":False,"message":"This face belongs to a different voter. Access denied.","confidence":0.0}),403
    distance = face_recognition.face_distance([saved_enc], cur_enc)[0]
    confidence = round(max(0.0, 1.0 - float(distance)), 4)
    log.info("Verified voter=%s confidence=%.4f", voter_id, confidence)
    return jsonify({"success":True,"message":"Face verified successfully","voter_id":voter_id,"confidence":confidence})

@app.route("/delete", methods=["POST"])
def delete_face():
    data = request.get_json(force=True, silent=True) or {}
    voter_id = data.get("voter_id","").strip()
    if not voter_id: return jsonify({"success":False,"message":"voter_id required"}),400
    p = encoding_path(voter_id)
    if not p.exists(): return jsonify({"success":False,"message":"No encoding found"}),404
    p.unlink()
    return jsonify({"success":True,"message":"Deleted"})

@app.route("/faces", methods=["GET"])
def list_faces():
    ids = sorted(p.stem for p in FACES_DIR.glob("*.npy"))
    return jsonify({"success":True,"count":len(ids),"voter_ids":ids})

@app.route("/check-liveness", methods=["POST"])
def check_liveness():
    data = request.get_json(force=True, silent=True) or {}
    image_b64 = data.get("image","")
    if not image_b64: return jsonify({"success":False,"message":"image required"}),400
    rgb = decode_image(image_b64)
    if rgb is None: return jsonify({"success":False,"message":"Cannot decode image"}),400
    try: loc, _enc = detect_and_encode(rgb)
    except ValueError as e: return jsonify({"success":False,"message":str(e)}),400
    return jsonify({"success":True,"message":"Liveness check passed","brightness":round(brightness(rgb),1)})

@app.errorhandler(404)
def not_found(e): return jsonify({"success":False,"message":"Not found"}),404

@app.errorhandler(500)
def internal(e): return jsonify({"success":False,"message":"Internal error"}),500

if __name__ == "__main__":
    log.info("Starting VoteChain Face Recognition Service on port 5000")
    log.info("Faces directory: %s", FACES_DIR.resolve())
    log.info("Tolerance: %.2f  |  Min brightness: %.0f  |  Max brightness: %.0f", TOLERANCE, MIN_BRIGHTNESS, MAX_BRIGHTNESS)
    app.run(host="0.0.0.0", port=5000, debug=False, threaded=True)
