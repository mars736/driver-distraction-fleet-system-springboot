"""Local-only inference stub. No model is loaded and predictions are synthetic."""

import argparse
import json
from email import policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def main():
    parser = argparse.ArgumentParser(description="Synthetic /predict service for local integration")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--pred", type=int, choices=range(10), default=1)
    parser.add_argument("--ood", action="store_true")
    args = parser.parse_args()

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path != "/predict":
                self.send_error(404)
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 64 * 1024 * 1024:
                    self.send_error(413)
                    return
                content_type = self.headers.get("Content-Type", "")
                if not content_type.lower().startswith("multipart/form-data;"):
                    self.send_error(415)
                    return
                payload = self.rfile.read(length)
                message = BytesParser(policy=policy.default).parsebytes(
                    b"Content-Type: " + content_type.encode("ascii")
                    + b"\r\nMIME-Version: 1.0\r\n\r\n" + payload
                )
                if not message.is_multipart():
                    self.send_error(400)
                    return
                count = sum(part.get_param("name", header="content-disposition") == "files"
                            for part in message.iter_parts())
                if not 0 < count <= 100:
                    self.send_error(400)
                    return
            except (ValueError, UnicodeError):
                self.send_error(400)
                return
            result = {"pred": args.pred, "class_name": f"c{args.pred}", "max_p": 0.99,
                      "energy": -3.0, "knn_dist": 0.1, "is_ood": args.ood}
            body = json.dumps({"results": [result for _ in range(count)]}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"SYNTHETIC inference only: http://127.0.0.1:{args.port}/predict; "
          f"pred={args.pred}; ood={args.ood}; no real model", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
