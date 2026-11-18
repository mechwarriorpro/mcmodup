"""Small local workbench; clients can select IDs, not arbitrary filesystem paths."""

from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import secrets
from threading import Lock
from urllib.parse import urlsplit

from .inputs import InputError
from .native_pack import REPORT_NAME, UpdateInventory, inventory, plan, stage


ASSETS = Path(__file__).with_name("templates") / "pack-ui"
STATIC = {"/": ("index.html", "text/html"), "/app.js": ("app.js", "text/javascript"),
          "/style.css": ("style.css", "text/css")}


def handler(state: UpdateInventory, output: Path | None) -> type[BaseHTTPRequestHandler]:
    token = secrets.token_urlsafe(32)
    build_lock = Lock()

    class Handler(BaseHTTPRequestHandler):
        def _send(self, status: int, media: str, content: bytes) -> None:
            self.send_response(status)
            self.send_header("Content-Type", media + "; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Content-Security-Policy", "default-src 'self'; base-uri 'none'; object-src 'none'; form-action 'none'")
            self.end_headers()
            self.wfile.write(content)

        def _json(self, status: int, payload: dict) -> None:
            self._send(status, "application/json", json.dumps(payload, ensure_ascii=True).encode("utf-8"))

        def _allowed_host(self) -> bool:
            if output is None:
                return True  # Read-only demos can be placed behind a preview proxy.
            port = self.server.server_port
            host = self.headers.get("Host", "").casefold()
            return host in {"localhost", "127.0.0.1", f"localhost:{port}", f"127.0.0.1:{port}"}

        def do_GET(self) -> None:
            if not self._allowed_host():
                self._json(HTTPStatus.FORBIDDEN, {"error": "Use the local workbench URL shown in your terminal."})
                return
            route = urlsplit(self.path).path
            if route in STATIC:
                name, media = STATIC[route]
                self._send(HTTPStatus.OK, media, (ASSETS / name).read_bytes())
            elif route == "/api/catalog":
                self._json(HTTPStatus.OK, {**state.catalog(), "token": token,
                                          "can_stage": output is not None and not (output.exists() or output.is_symlink())})
            else:
                self._json(HTTPStatus.NOT_FOUND, {"error": "Not found"})

        def do_POST(self) -> None:
            route = urlsplit(self.path).path
            if route not in {"/api/plan", "/api/stage"}:
                self._json(HTTPStatus.NOT_FOUND, {"error": "Not found"})
                return
            if (not self._allowed_host() or self.headers.get_content_type() != "application/json" or
                    not secrets.compare_digest(self.headers.get("X-Mcmodup-Token", "").encode("utf-8"), token.encode("ascii"))):
                self._json(HTTPStatus.FORBIDDEN, {"error": "Open the workbench page and retry."})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 1 <= length <= 256 * 1024:
                    raise InputError("Invalid selection request size.")
                doc = json.loads(self.rfile.read(length))
                selections = doc.get("selected") if isinstance(doc, dict) else None
                if not isinstance(selections, list) or len(selections) > len(state.entries) or not all(isinstance(s, str) for s in selections):
                    raise InputError("Supply a list of archive IDs from the checkbox catalog.")
                if route == "/api/plan":
                    self._json(HTTPStatus.OK, plan(state, selections).report)
                    return
                if output is None:
                    raise InputError("This workbench is read-only. Restart with -o NEW_DIR to stage an update.")
                with build_lock:
                    # Re-read local files before writing, so a stale browser plan cannot stage changed inputs.
                    refreshed = inventory(state.source, state.target, state.build_options, approved_sources=state.approved_sources)
                    update = plan(refreshed, selections)
                    if update.problems:
                        self._json(HTTPStatus.CONFLICT, {"error": "The refreshed plan has blockers.", "plan": update.report})
                        return
                    result = stage(update, output)
                report = json.loads((result / REPORT_NAME).read_text(encoding="utf-8"))
                self._json(HTTPStatus.OK, {"output": str(result), "plan": report})
            except (ValueError, RecursionError, OSError) as exc:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})

    return Handler


def serve(state: UpdateInventory, output: Path | None, host: str, port: int) -> None:
    if not 1 <= port <= 65535:
        raise InputError("Use an HTTP port between 1 and 65535.")
    if output is not None:
        if host not in {"127.0.0.1", "localhost"}:
            raise InputError("Use --host 127.0.0.1 for a writable workbench; network previews are read-only.")
        output = output.expanduser()
        if output.exists() or output.is_symlink() or any(output.resolve().is_relative_to(root) for root in (state.source, state.target)):
            raise InputError("Choose a new output directory outside both modpacks.")
        output = output.resolve()
    with ThreadingHTTPServer((host, port), handler(state, output)) as server:
        print(f"Native modpack updater: http://{host}:{port}/ — {'read-only' if output is None else f'stage to {output}'}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
