"""Builds assets/index.html from index.template.html by embedding the fonts (keeps the app a single offline file)."""
import base64, pathlib
here = pathlib.Path(__file__).parent
html = (here / "index.template.html").read_text(encoding="utf-8")
for key, f in (("__INTER__", "inter.woff2"), ("__GROTESK__", "space-grotesk.woff2")):
    html = html.replace(key, base64.b64encode((here / "fonts" / f).read_bytes()).decode())
(here.parent / "assets" / "index.html").write_text(html, encoding="utf-8")
print("assets/index.html", len(html), "bytes")
