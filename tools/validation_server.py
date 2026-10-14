"""Isolated loopback validation service; keep the user's running preview untouched."""
import sys
import threading
from pathlib import Path
from http.server import ThreadingHTTPServer

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'voice'))
import polish
polish.PORT = 18746
import server
server.PORT = 18745

if __name__=='__main__':
    http = ThreadingHTTPServer(('127.0.0.1',server.PORT),server.Handler)
    threading.Thread(target=server.engine.load,daemon=True).start()
    threading.Thread(target=server.expire_sessions,daemon=True).start()
    print('ISOLATED_VALIDATION_SERVER http://127.0.0.1:18745',flush=True)
    try:
        http.serve_forever()
    finally:
        server.polisher.close();http.server_close()
