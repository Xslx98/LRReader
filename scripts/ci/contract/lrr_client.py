"""Minimal LANraragi HTTP client for the contract scripts (stdlib only)."""
import base64
import json
import urllib.error
import urllib.parse
import urllib.request
import uuid


class ApiError(RuntimeError):
    """A response the caller did not expect."""


class LrrClient:
    def __init__(self, base_url, api_key=None):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key

    def request(self, method, path, query=None, form=None, json_body=None, multipart=None, timeout=120):
        """Returns (status, body bytes). Non-2xx is a result here, not an exception."""
        url = self.base_url + path
        if query:
            url += "?" + urllib.parse.urlencode(query)
        headers = {}
        data = None
        if form is not None:
            data = urllib.parse.urlencode(form).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        elif json_body is not None:
            data = json.dumps(json_body).encode()
            headers["Content-Type"] = "application/json"
        elif multipart is not None:
            data, headers["Content-Type"] = _encode_multipart(*multipart)
        elif method in ("PUT", "POST"):
            data = b""
        if self.api_key:
            token = base64.b64encode(self.api_key.encode()).decode()
            headers["Authorization"] = f"Bearer {token}"
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as err:
            return err.code, err.read()

    def json(self, method, path, **kwargs):
        """Like request, but any non-2xx or non-JSON answer raises ApiError."""
        status, body = self.request(method, path, **kwargs)
        if not 200 <= status < 300:
            raise ApiError(f"{method} {path}: HTTP {status}: {body[:500]!r}")
        try:
            return json.loads(body)
        except ValueError as err:
            raise ApiError(f"{method} {path}: not JSON: {body[:500]!r}") from err


def _encode_multipart(fields, file_field, file_name, file_bytes):
    boundary = uuid.uuid4().hex
    parts = []
    for name, value in fields.items():
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode()
        )
    parts.append(
        (
            f'--{boundary}\r\nContent-Disposition: form-data; name="{file_field}"; '
            f'filename="{file_name}"\r\nContent-Type: application/octet-stream\r\n\r\n'
        ).encode()
    )
    parts.append(file_bytes)
    parts.append(f"\r\n--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"
