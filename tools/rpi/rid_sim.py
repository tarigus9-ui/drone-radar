#!/usr/bin/env python3
"""
DroneRadar — Remote ID test transmitter for Raspberry Pi 5 (Bluetooth LE)
==========================================================================
Broadcasts a SIMULATED drone (ASTM F3411 / Open Drone ID, Bluetooth 4 legacy advertising)
circling over Dubai Silicon Oasis, so the DroneRadar app can be tested without a real drone.

Simple web page with Start / Stop:  http://localhost:8080   (also reachable from a phone on the same network)

Run:   sudo python3 rid_sim.py
Needs: Raspberry Pi OS (Bookworm), built-in Bluetooth. Python standard library only — nothing to install.

⚠️ For testing only. Short range (tens of metres). Do not run near airports, and stop it when not testing.
   The serial number starts with "TST" so it is obviously a test drone.
"""

import fcntl
import json
import math
import socket
import struct
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ----------------------------------------------------------------- settings
AREA_NAME = "واحة دبي للسيليكون"
OP_LAT, OP_LON = 25.1215, 55.3825      # operator (pilot) position — Dubai Silicon Oasis
RADIUS_M = 300.0                        # drone circles the operator at this distance
HEIGHT_M = 60.0                         # height above take-off
SPEED_MS = 10.0                         # ground speed
SERIAL = "TST0ADRNE000001"              # follows ANSI/CTA-2063 format (4 mfr + length 'A'=10 + 10 chars)
OPERATOR_ID = "ARE-TEST-OP-0001"
SELF_DESC = "DroneRadar RPi test"
HTTP_PORT = 8080
HCI_DEV = 0                             # hci0

MSG = 25

# ----------------------------------------------------------------- Open Drone ID encoding
def _enc_alt(m):  # 0.5 m steps, offset -1000 m
    return max(0, min(65535, int(round((m + 1000.0) / 0.5))))

def _ascii(s, n):
    b = s.encode("ascii", "ignore")[:n]
    return b + bytes(n - len(b))

def msg_basic_id():
    m = bytearray(MSG)
    m[0] = 0x02                         # type 0 Basic ID, protocol v2
    m[1] = (1 << 4) | 2                 # ID type 1 = serial number, UA type 2 = multirotor
    m[2:22] = _ascii(SERIAL, 20)
    return bytes(m)

def msg_self_id():
    m = bytearray(MSG)
    m[0] = 0x32; m[1] = 0
    m[2:25] = _ascii(SELF_DESC, 23)
    return bytes(m)

def msg_operator_id():
    m = bytearray(MSG)
    m[0] = 0x52; m[1] = 0
    m[2:22] = _ascii(OPERATOR_ID, 20)
    return bytes(m)

def msg_system(op_lat, op_lon):
    m = bytearray(MSG)
    m[0] = 0x42
    m[1] = 0x01                         # operator location = live GNSS
    struct.pack_into("<ii", m, 2, int(round(op_lat * 1e7)), int(round(op_lon * 1e7)))
    struct.pack_into("<H", m, 10, 1)    # area count
    m[12] = 0
    struct.pack_into("<HH", m, 13, _enc_alt(120), _enc_alt(0))
    m[17] = 0
    struct.pack_into("<H", m, 18, _enc_alt(10))
    struct.pack_into("<I", m, 20, int(time.time()) - 1546300800)   # seconds since 2019-01-01
    return bytes(m)

def msg_location(lat, lon, heading_deg, speed_ms, height_m):
    m = bytearray(MSG)
    m[0] = 0x12
    hd = int(round(heading_deg)) % 360
    flags = (2 << 4)                    # status 2 = airborne
    if hd >= 180:
        flags |= 0x02; hd -= 180       # E/W direction bit
    if speed_ms > 63.75:
        flags |= 0x01                   # speed multiplier
        sp = int(round((speed_ms - 63.75) / 0.75))
    else:
        sp = int(round(speed_ms / 0.25))
    m[1] = flags
    m[2] = hd
    m[3] = max(0, min(254, sp))
    m[4] = 0
    struct.pack_into("<ii", m, 5, int(round(lat * 1e7)), int(round(lon * 1e7)))
    struct.pack_into("<HHH", m, 13, _enc_alt(height_m + 10), _enc_alt(height_m + 10), _enc_alt(height_m))
    m[19] = (4 << 4) | 10
    m[20] = (3 << 4) | 3
    struct.pack_into("<H", m, 21, int((time.time() % 3600) * 10))
    m[23] = 1
    return bytes(m)

def ble_adv_payload(msg, counter):
    # 31 bytes: len, Service Data 16-bit, UUID 0xFFFA, app code 0x0D, counter, 25-byte message
    return bytes([30, 0x16, 0xFA, 0xFF, 0x0D, counter & 0xFF]) + msg

# ----------------------------------------------------------------- Bluetooth (raw HCI, needs root)
HCIDEVUP = 0x400448C9

class Hci:
    def __init__(self, dev=HCI_DEV):
        self.s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_RAW, socket.BTPROTO_HCI)
        try:
            fcntl.ioctl(self.s.fileno(), HCIDEVUP, dev)
        except OSError as e:
            if e.errno != 114:          # EALREADY = already up
                pass
        self.s.bind((dev,))

    def cmd(self, ogf, ocf, params=b""):
        opcode = (ogf << 10) | ocf
        self.s.send(struct.pack("<BHB", 0x01, opcode, len(params)) + params)
        time.sleep(0.01)

    def adv_enable(self, on):
        self.cmd(0x08, 0x000A, bytes([1 if on else 0]))

    def adv_params(self, interval_ms=100):
        iv = int(interval_ms / 0.625)
        p = struct.pack("<HHBBB6sBB", iv, iv, 0x03, 0x00, 0x00, bytes(6), 0x07, 0x00)  # ADV_NONCONN_IND
        self.cmd(0x08, 0x0006, p)

    def adv_data(self, data31):
        self.cmd(0x08, 0x0008, bytes([len(data31)]) + data31.ljust(31, b"\x00"))

# ----------------------------------------------------------------- simulator
class Sim:
    def __init__(self):
        self.running = False
        self.scenario = "normal"
        self.lock = threading.Lock()
        self.sent = 0
        self.lat = OP_LAT; self.lon = OP_LON
        self.op_lat = OP_LAT; self.op_lon = OP_LON
        self.error = ""
        self.thread = None
        self.hci = None

    def start(self, scenario):
        with self.lock:
            self.scenario = scenario if scenario in ("normal", "spoof") else "normal"
            if self.running:
                return
            try:
                self.hci = self.hci or Hci()
                self.hci.adv_enable(False)
                self.hci.adv_params(100)
                self.hci.adv_enable(True)
                self.error = ""
            except PermissionError:
                self.error = "يجب التشغيل بصلاحية الجذر: sudo python3 rid_sim.py"; return
            except Exception as e:   # no Bluetooth adapter / Python without Bluetooth support
                self.error = f"تعذّر الوصول إلى البلوتوث: {e}"; return
            self.running = True
            self.t0 = time.time()
            self.thread = threading.Thread(target=self.loop, daemon=True)
            self.thread.start()

    def stop(self):
        with self.lock:
            self.running = False
        try:
            if self.hci: self.hci.adv_enable(False)
        except OSError:
            pass

    def position(self, t):
        omega = SPEED_MS / RADIUS_M
        a = omega * t
        dn, de = RADIUS_M * math.cos(a), RADIUS_M * math.sin(a)
        lat = OP_LAT + dn / 111320.0
        lon = OP_LON + de / (111320.0 * math.cos(math.radians(OP_LAT)))
        heading = (math.degrees(a) + 90.0) % 360
        speed, op_lat, op_lon = SPEED_MS, OP_LAT, OP_LON
        if self.scenario == "spoof":
            # forged data: teleports 3 km every 10 s, claims 90 m/s, operator "20 km away"
            if int(t / 10) % 2 == 1:
                lat += 3000 / 111320.0
            speed = 90.0
            op_lat = OP_LAT + 20000 / 111320.0
        return lat, lon, heading, speed, op_lat, op_lon

    def loop(self):
        counter = 0
        order = ["loc", "basic", "loc", "system", "loc", "operator", "loc", "self"]
        i = 0
        while True:
            with self.lock:
                if not self.running:
                    break
            t = time.time() - self.t0
            lat, lon, hd, sp, op_lat, op_lon = self.position(t)
            self.lat, self.lon, self.op_lat, self.op_lon = lat, lon, op_lat, op_lon
            kind = order[i % len(order)]; i += 1
            msg = {
                "loc": lambda: msg_location(lat, lon, hd, sp, HEIGHT_M),
                "basic": msg_basic_id,
                "system": lambda: msg_system(op_lat, op_lon),
                "operator": msg_operator_id,
                "self": msg_self_id,
            }[kind]()
            try:
                self.hci.adv_data(ble_adv_payload(msg, counter))
                counter += 1; self.sent += 1
            except OSError as e:
                self.error = f"خطأ بلوتوث: {e}"
            time.sleep(0.15)

    def status(self):
        return {
            "running": self.running, "scenario": self.scenario, "sent": self.sent,
            "lat": round(self.lat, 6), "lon": round(self.lon, 6),
            "op_lat": round(self.op_lat, 6), "op_lon": round(self.op_lon, 6),
            "serial": SERIAL, "area": AREA_NAME, "error": self.error,
        }

SIM = Sim()

# ----------------------------------------------------------------- web page
PAGE = """<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>بث درون تجريبي</title>
<style>
 body{font-family:system-ui,'Noto Sans Arabic',sans-serif;background:#10213d;color:#fff;margin:0;padding:24px;text-align:center}
 .card{max-width:520px;margin:auto;background:#1b3258;border-radius:16px;padding:24px}
 h1{font-size:24px;margin:0 0 6px} .area{color:#9fc3ff;margin-bottom:18px}
 .dot{display:inline-block;width:14px;height:14px;border-radius:50%;background:#777;margin-left:8px;vertical-align:middle}
 .on .dot{background:#2ecc71;box-shadow:0 0 12px #2ecc71}
 #state{font-size:20px;margin:14px 0}
 button{font-size:22px;border:0;border-radius:12px;padding:16px 0;width:100%;margin:8px 0;color:#fff;cursor:pointer}
 #start{background:#2e7d32} #stop{background:#c62828}
 label{display:block;text-align:right;margin:10px 4px;font-size:16px}
 .info{font-size:14px;color:#c8d6ee;line-height:1.9;text-align:right;margin-top:14px}
 .warn{font-size:12px;color:#ffcc80;margin-top:16px}
 .err{color:#ff8a80;font-size:15px}
</style></head><body><div class="card" id="card">
<h1>🛸 بث درون تجريبي (Remote ID)</h1>
<div class="area">📍 المنطقة: __AREA__</div>
<div id="state"><span class="dot"></span><span id="stext">متوقف</span></div>
<label><input type="radio" name="sc" value="normal" checked> درون طبيعي يدور فوق المنطقة</label>
<label><input type="radio" name="sc" value="spoof"> درون ببيانات مزيفة (لاختبار كشف التزييف ⚠️)</label>
<button id="start" onclick="go('start')">▶ تشغيل</button>
<button id="stop" onclick="go('stop')">■ إيقاف</button>
<div class="info" id="info"></div>
<div class="err" id="err"></div>
<div class="warn">للاختبار فقط — مدى قصير (عشرات الأمتار). أوقف البث بعد الانتهاء، ولا تشغّله قرب المطارات.</div>
</div><script>
function sc(){return document.querySelector('input[name=sc]:checked').value}
async function go(a){await fetch('/'+a+'?scenario='+sc(),{method:'POST'});refresh()}
async function refresh(){
 const s=await (await fetch('/status')).json();
 document.getElementById('card').className='card'+(s.running?' on':'');
 document.getElementById('stext').textContent=s.running?(s.scenario=='spoof'?'يبث — بيانات مزيفة ⚠️':'يبث الآن ✅'):'متوقف';
 document.getElementById('info').innerHTML=
  'الرقم التسلسلي: '+s.serial+'<br>موقع الدرون: '+s.lat+', '+s.lon+
  '<br>موقع المشغل: '+s.op_lat+', '+s.op_lon+'<br>رسائل مُرسلة: '+s.sent;
 document.getElementById('err').textContent=s.error||'';
}
setInterval(refresh,1000);refresh();
</script></body></html>"""

class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype):
        b = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path.startswith("/status"):
            self._send(200, json.dumps(SIM.status(), ensure_ascii=False), "application/json; charset=utf-8")
        else:
            self._send(200, PAGE.replace("__AREA__", AREA_NAME), "text/html; charset=utf-8")

    def do_POST(self):
        sc = "spoof" if "scenario=spoof" in self.path else "normal"
        if self.path.startswith("/start"):
            SIM.stop() if SIM.running and SIM.scenario != sc else None
            SIM.start(sc)
        elif self.path.startswith("/stop"):
            SIM.stop()
        self._send(200, json.dumps(SIM.status(), ensure_ascii=False), "application/json; charset=utf-8")

    def log_message(self, *a):
        pass

# ----------------------------------------------------------------- self-test (no Bluetooth needed)
def selftest():
    m = msg_location(25.1215, 55.3825, 270, 10, 60)
    lat, lon = struct.unpack_from("<ii", m, 5)
    assert abs(lat / 1e7 - 25.1215) < 1e-6 and abs(lon / 1e7 - 55.3825) < 1e-6
    assert m[0] >> 4 == 1 and m[2] == 90 and m[1] & 0x02          # 270° = 90 + E/W bit
    assert len(ble_adv_payload(m, 7)) == 31
    b = msg_basic_id(); assert b[0] >> 4 == 0 and b[2:2 + len(SERIAL)] == SERIAL.encode()
    s = msg_system(25.1, 55.3); assert s[0] >> 4 == 4
    # serial format check identical to the app (ANSI/CTA-2063)
    import re
    assert re.fullmatch(r"[0-9A-HJ-NP-Z]{4}[1-9A-F][0-9A-HJ-NP-Z]{1,15}", SERIAL)
    assert len(SERIAL) - 5 == "0123456789ABCDEF".index(SERIAL[4])
    print("selftest OK")

if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest(); sys.exit(0)
    srv = ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), Handler)
    print(f"افتح المتصفح على: http://localhost:{HTTP_PORT}   (Ctrl+C للخروج)")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        SIM.stop()
