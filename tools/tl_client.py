#!/usr/bin/env python3
"""Talk to the Terraduo TL Pro mod from the PC (read-only helper for development).

  adb forward tcp:38656 tcp:38656
  python tools/tl_client.py state [inventory|equipment|craft]
  python tools/tl_client.py raw '{"op":"sprite","kind":"item","id":1}'

The mod answers exactly one JSON line per connection.
"""
import json
import socket
import sys


def ask(request, timeout=8.0):
    s = socket.create_connection(("127.0.0.1", 38656), timeout=timeout)
    s.sendall((json.dumps(request) + "\n").encode())
    data = b""
    while not data.endswith(b"\n"):
        chunk = s.recv(65536)
        if not chunk:
            break
        data += chunk
    s.close()
    return json.loads(data.decode("utf-8"))


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "state"
    if cmd == "state":
        pane = sys.argv[2] if len(sys.argv) > 2 else "inventory"
        r = ask({"op": "state", "pane": pane, "page": 0})
    else:
        r = ask(json.loads(sys.argv[2]))
    text = json.dumps(r, ensure_ascii=False)
    print(text if len(text) < 6000 else text[:6000] + " ...[%d bytes]" % len(text))
