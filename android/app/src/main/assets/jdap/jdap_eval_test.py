#!/usr/bin/env python3
"""F6-b eval engine test — same logic runs in the spike sandbox and on the
device inside the container. Scenarios:
 1. plain line breakpoint stops (verified + correct reason/line)
 2. conditional breakpoint via the javac engine (n % 7 == 0 && n > 8000)
 3. conditional breakpoint via the fast JDI path (n > 8500)
 4. negative condition never stops (n > 99999)
 5. evaluate expressions in the paused session (n, n * 2 + 1, Hello.compute(5))
"""
import json, subprocess, sys, time, os

LOGDIR = os.environ.get("JDAP_LOGDIR", os.environ.get("JDAP_BASE", "/tmp"))
os.makedirs(LOGDIR, exist_ok=True)

JAVA = os.environ.get("JDAP_JAVA", "java")
JAVA_HOME = os.environ.get("JDAP_JAVA_HOME", "/tmp/f6/jdk-17.0.20.1+1")
if os.path.isdir(JAVA_HOME):
    JAVA = JAVA_HOME + "/bin/java"
BASE = os.environ.get("JDAP_BASE", "/tmp/f6")
DRIVER_CP = os.environ.get("JDAP_DRIVER_CP", "/tmp/f6/f6b/DapDriver.jar")
JARS_CP = os.environ.get("JDAP_JARS_CP", "/tmp/f6/com.microsoft.java.debug.core-0.53.1.jar:/tmp/f6/gson-2.8.9.jar:/tmp/f6/commons-lang3-3.6.jar:/tmp/f6/commons-io-2.14.0.jar:/tmp/f6/rxjava-2.2.21.jar:/tmp/f6/reactive-streams-1.0.4.jar")
EVALHOST = os.environ.get("JDAP_EVALHOST", "/tmp/f6/f6b/jdap-evalhost.jar")
PORT = int(os.environ.get("JDAP_PORT", "5010"))
RESULTS = []


class Client:
    def __init__(self):
        self.p = subprocess.Popen([JAVA, "-cp", DRIVER_CP + ":" + JARS_CP, "DapDriver"],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=open(os.path.join(LOGDIR, 'driver-stderr-%d.log' % os.getpid()), 'ab'))
        self.buf = bytearray()
        self.seq = 0

    def send(self, cmd, arguments=None):
        self.seq += 1
        msg = {"seq": self.seq, "type": "request", "command": cmd}
        if arguments is not None:
            msg["arguments"] = arguments
        data = json.dumps(msg).encode()
        self.p.stdin.write(b"Content-Length: %d\r\n\r\n%s" % (len(data), data))
        self.p.stdin.flush()
        return self.seq

    def read_msg(self):
        while True:
            while b"\r\n\r\n" in self.buf:
                head, rest = bytes(self.buf).split(b"\r\n\r\n", 1)
                n = int(dict(l.split(": ") for l in head.decode().split("\r\n"))["Content-Length"])
                if len(rest) >= n:
                    self.buf = bytearray(rest[n:])
                    return json.loads(rest[:n])
            chunk = self.p.stdout.read1(65536)
            if not chunk:
                return None
            self.buf.extend(chunk)

    def wait(self, req_seq, timeout=30):
        events = []
        deadline = time.time() + timeout
        while time.time() < deadline:
            m = self.read_msg()
            if m is None:
                return None, events
            if m.get("type") == "response" and m.get("request_seq") == req_seq:
                return m, events
            if m.get("type") == "event":
                events.append(m)
        return "TIMEOUT", events

    def close(self):
        try:
            self.p.terminate()
        except Exception:
            pass


def start_debuggee(port, extra_cp=""):
    cp = BASE + (":" + extra_cp if extra_cp else "")
    return subprocess.Popen([JAVA, "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=%d" % port,
                             "-cp", cp, "Hello"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def attach_and_break(c, port, condition=None, line=10):
    s = c.send("initialize", {"adapterID": "java", "linesStartAt1": True, "columnsStartAt1": True, "pathFormat": "path"})
    r, _ = c.wait(s)
    assert r and r.get("success"), "initialize failed"
    s = c.send("attach", {"request": "attach", "port": port, "hostName": "127.0.0.1", "projectName": "f6b"})
    r, _ = c.wait(s)
    assert r and r.get("success"), "attach failed"
    bp = {"line": line}
    if condition:
        bp["condition"] = condition
    s = c.send("setBreakpoints", {"source": {"name": "Hello.java", "path": BASE + "/Hello.java"},
                                  "lines": [line], "breakpoints": [bp]})
    r, _ = c.wait(s)
    assert r and r.get("success"), "setBreakpoints failed"
    verified = r["body"]["breakpoints"][0]["verified"]
    s = c.send("configurationDone")
    r, _ = c.wait(s)
    assert r and r.get("success"), "configurationDone failed"
    return verified


def wait_stopped_or_end(c, timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        m = c.read_msg()
        if m is None:
            return None
        if m.get("type") == "event" and m.get("event") == "stopped":
            return m["body"]
        if m.get("type") == "event" and m.get("event") in ("terminated", "exited"):
            return m
    return "TIMEOUT"


def evaluate(c, thread_id, expr):
    s = c.send("evaluate", {"expression": expr, "frameId": 1, "context": "repl"})
    r, _ = c.wait(s)
    return r


def record(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + (" — " + detail if detail else ""))


def scenario_line_bp(port):
    dbg = start_debuggee(port)
    time.sleep(3)
    c = Client()
    try:
        verified = attach_and_break(c, port)
        body = wait_stopped_or_end(c)
        ok = body is not None and body.get("reason") == "breakpoint"
        record("1. line breakpoint stops", ok,
               "reason=%s verified=%s" % (body and body.get("reason"), verified))
        if body and body.get("reason") == "breakpoint":
            s = c.send("stackTrace", {"threadId": body["threadId"], "levels": 4})
            r, _ = c.wait(s)
            frame = r["body"]["stackFrames"][0]
            record("1a. stopped at requested line", frame["line"] == 10,
                   "line=%s name=%s" % (frame["line"], frame["name"]))
    finally:
        c.close()
        dbg.terminate()


def scenario_cond_bp(port, condition, expect_stop, name, check_eval=None):
    dbg = start_debuggee(port, extra_cp=EVALHOST)
    time.sleep(3)
    c = Client()
    try:
        attach_and_break(c, port, condition=condition)
        body = wait_stopped_or_end(c, timeout=150)
        if expect_stop:
            ok = body is not None and body.get("reason") == "breakpoint"
            detail = "reason=%s" % (body and body.get("reason"))
            if ok and check_eval:
                s = c.send("stackTrace", {"threadId": body["threadId"], "levels": 4})
                st, _ = c.wait(s)
                fid = st["body"]["stackFrames"][0]["id"]
                s = c.send("evaluate", {"expression": check_eval, "frameId": fid, "context": "repl"})
                r, _ = c.wait(s)
                if r and r.get("success"):
                    detail += " eval[%s]=%s" % (check_eval, r["body"]["result"])
                else:
                    ok = False
                    detail += " eval failed: " + json.dumps(r)[:200]
            record(name, ok, detail)
        else:
            ok = body is not None and body.get("event") in ("terminated", "exited")
            record(name, ok, "end=%s (no stop wanted) body=%s" % (body and body.get("event"), json.dumps(body)[:80] if body else None))
    finally:
        c.close()
        dbg.terminate()


def scenario_eval(port):
    dbg = start_debuggee(port, extra_cp=EVALHOST)
    time.sleep(3)
    c = Client()
    try:
        attach_and_break(c, port, condition="n > 8000")
        body = wait_stopped_or_end(c, timeout=150)
        if not (body and body.get("reason") == "breakpoint"):
            record("5. evaluate in paused session", False, "no stop: " + json.dumps(body)[:120])
            return
        s = c.send("stackTrace", {"threadId": body["threadId"], "levels": 4})
        st, _ = c.wait(s)
        fid = st["body"]["stackFrames"][0]["id"]
        def ev(expr):
            rr = c.wait(c.send("evaluate", {"expression": expr, "frameId": fid, "context": "repl"}))[0]
            return rr
        n = None
        r = ev("n")
        if r and r.get("success"):
            n = int(r["body"]["result"])
        r2 = ev("n * 2 + 1")
        v2 = int(r2["body"]["result"]) if r2 and r2.get("success") else None
        r3 = ev("Hello.compute(5)")
        v3 = int(r3["body"]["result"]) if r3 and r3.get("success") else None
        ok = n is not None and v2 == n * 2 + 1 and v3 == 11
        record("5. evaluate in paused session", ok,
               "n=%s n*2+1=%s (want %s) compute(5)=%s (want 11)" % (n, v2, (n * 2 + 1) if n is not None else "?", v3))
        if n is not None:
            record("5a. condition truly gated (n > 8000)", n > 8000, "n=%d" % n)
    finally:
        c.close()
        dbg.terminate()


print("== jdap F6-b eval test ==")
scen = os.environ.get("SCEN", "1,2,3,4,5").split(",")
if "1" in scen:
    scenario_line_bp(PORT)
if "2" in scen:
    scenario_cond_bp(PORT + 1, "n % 7 == 0 && n > 8000", True, "2. conditional bp (javac path) stops only when true", check_eval="n")
if "3" in scen:
    scenario_cond_bp(PORT + 2, "n > 8500", True, "3. conditional bp (fast JDI path) stops only when true", check_eval="n")
if "4" in scen:
    scenario_cond_bp(PORT + 3, "n > 99999", False, "4. negative condition never stops")
if "5" in scen:
    scenario_eval(PORT + 4)
fails = [r for r in RESULTS if not r[1]]
print("== %d/%d checks passed ==" % (len(RESULTS) - len(fails), len(RESULTS)))
sys.exit(1 if fails else 0)
