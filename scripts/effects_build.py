#!/usr/bin/env python3
"""
Effect library pipeline for Nepenthe Journal.

Builds the bundled `effects.json`: one readable article per subjective effect,
so the app can explain an effect without a network call.

Sources, in priority order:
  1. DoseWiki EffectIndex open data (https://dose.wiki/open-data/EffectIndex.json,
     CC0 text). Names come from the same source as `dosewiki_slim.py`.
  2. PsychonautWiki page extracts (CC BY-SA 4.0) for every effect name the app
     knows but DoseWiki has no article for.

Usage:
    python scripts/effects_build.py

Output (all four copies are written with identical bytes):
    composeApp/src/desktopMain/resources/effects.json
    composeApp/src/jvmMain/resources/effects.json
    composeApp/src/iosMain/resources/effects.json
    composeApp/src/androidMain/assets/effects.json

Downloads are cached under scripts/cache/ (regenerable, gitignored):
    EffectIndex.json, pw_effects.json
"""

import html
import json
import os
import re
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.normpath(os.path.join(HERE, ".."))
CACHE = os.path.join(HERE, "cache")

SLIM_PATH = os.path.join(PROJECT, "composeApp", "src", "androidMain", "assets", "dosewiki_slim.json")
EFFECT_INDEX_URL = "https://dose.wiki/open-data/EffectIndex.json"
PW_API = "https://psychonautwiki.org/w/api.php"
PW_USER_AGENT = "NepentheJournal/1.0 (offline effect bundle; +https://github.com/Wolren/NepentheJournal)"

OUTPUT_PATHS = [
    os.path.join(PROJECT, "composeApp", "src", "desktopMain", "resources", "effects.json"),
    os.path.join(PROJECT, "composeApp", "src", "jvmMain", "resources", "effects.json"),
    os.path.join(PROJECT, "composeApp", "src", "iosMain", "resources", "effects.json"),
    os.path.join(PROJECT, "composeApp", "src", "androidMain", "assets", "effects.json"),
]

# Category order the app renders; anything else falls back to "other".
CATEGORY_ORDER = ["cognitive", "physical", "sensory"]
SENSORY_SLOTS = {
    "auditory", "gustatory", "tactile", "visual", "olfactory",
    "multisensory", "multisensory_category",
}
BODY_LIMIT = 4000


def norm(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", name.lower()).strip()


def fetch_json(url: str, headers: dict | None = None):
    req = urllib.request.Request(url, headers=headers or {"User-Agent": PW_USER_AGENT})
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read().decode("utf-8"))


def collect_app_effects(slim: list) -> dict:
    """Map normalised effect name -> {name, categories} from each substance's subjective_effects."""
    found: dict[str, dict] = {}

    def visit(node, slot: str | None, sub: str | None) -> None:
        if isinstance(node, dict):
            effects = node.get("effects")
            if isinstance(effects, list):
                for entry in effects:
                    if not isinstance(entry, dict):
                        continue
                    name = (entry.get("name") or "").strip()
                    if not name:
                        continue
                    key = norm(name)
                    record = found.setdefault(key, {"name": name, "categories": set()})
                    if slot in CATEGORY_ORDER:
                        record["categories"].add(slot)
                    if slot == "sensory" and sub in SENSORY_SLOTS:
                        record["categories"].add(sub)
            for key, value in node.items():
                if key == "effects":
                    continue
                if key in CATEGORY_ORDER:
                    visit(value, key, None)
                elif slot == "sensory" and key in SENSORY_SLOTS:
                    visit(value, slot, key)
                else:
                    visit(value, slot, sub)
        elif isinstance(node, list):
            for value in node:
                visit(value, slot, sub)

    for substance in slim:
        visit(substance.get("subjective_effects"), None, None)
    return found


def download_effect_index() -> list:
    path = os.path.join(CACHE, "EffectIndex.json")
    if os.path.exists(path):
        print(f"Using cached {path}")
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    else:
        print(f"Downloading {EFFECT_INDEX_URL}...")
        data = fetch_json(EFFECT_INDEX_URL, {"User-Agent": PW_USER_AGENT, "Accept": "application/json"})
        os.makedirs(CACHE, exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(data, handle, ensure_ascii=False)
    items = data.get("items") if isinstance(data, dict) else None
    if not isinstance(items, list):
        raise RuntimeError("Unexpected EffectIndex shape")
    return items


def attr(tag: str, name: str) -> str:
    match = re.search(name + r'="([^"]*)"', tag)
    return match.group(1).strip() if match else ""


def clean_markup(text: str) -> str:
    """DoseWiki rich text to plain paragraphs."""
    if not text:
        return ""
    text = html.unescape(text)
    # Boxed blocks carry their title in an attribute; the title is the content that matters.
    text = re.sub(
        r"\[headered-textbox[^\]]*\]",
        lambda m: "\n\n## " + (attr(m.group(0), "header") or attr(m.group(0), "label")) + "\n\n",
        text,
    )
    text = re.sub(r"\[/headered-textbox\]", "\n", text)
    text = re.sub(
        r"\[captioned-image[^\]]*\]",
        lambda m: "\n\n" + attr(m.group(0), "caption") + "\n\n",
        text,
    )
    text = re.sub(r"\[(subarticle|toc)[^\]]*\]", "", text)
    text = re.sub(r"\[/subarticle\]", "", text)
    text = re.sub(
        r"\[markdown[^\]]*\]",
        lambda m: "\n\n## " + attr(m.group(0), "text").lstrip("# ").strip() + "\n\n",
        text,
    )
    text = re.sub(r"\[panel[^\]]*\]", lambda m: "\n\n## " + attr(m.group(0), "title") + "\n\n", text)
    # Section headings keep a marker so the reader can render them as headings.
    text = re.sub(r"\[h[1-6][^\]]*\]", "\n\n## ", text)
    text = re.sub(r"\[/h[1-6]\]", "\n\n", text)
    text = re.sub(r"\[/panel\]|\[hr\s*/?\]", "\n\n", text)
    text = re.sub(r"\[li[^\]]*\]", "\n- ", text)
    text = re.sub(r"\[/li\]", "\n", text)
    text = re.sub(r"\[/?list[^\]]*\]", "\n\n", text)
    # Any site tag that survived the specific passes is presentation, not content.
    text = re.sub(r"\[/?[A-Za-z][^\]]*\]", "", text)
    # Citation markers and inline cross-links carry site-specific tags; keep the
    # visible text of a cross-link, drop a citation marker whole.
    text = re.sub(r"\[ref[^\]]*\].*?\[/ref\]", "", text, flags=re.S)
    text = re.sub(r"\[/?ref[^\]]*\]", "", text)
    text = re.sub(r"\[(int|ext)-link[^\]]*\]", "", text)
    text = re.sub(r"\[/(int|ext)-link\]", "", text)
    text = re.sub(r"\[url=[^\]]*\]", "", text)
    text = re.sub(r"\[/?url\]", "", text)
    text = re.sub(r"\[/?(p|br|h\d|quote|center|hr|img|table|tr|td|th|math|code|youtube|video|embed)(=[^\]]*)?\]", "\n\n", text)
    text = re.sub(r"\[/?li\]", "\n", text)
    text = re.sub(r"\[/?list\]", "\n", text)
    text = re.sub(r"\[/?(b|i|u|s|em|strong)\]", "", text)
    text = re.sub(r"\[[a-z]+(=[^\]]*)?\]", "", text)
    text = re.sub(r"\[/[a-z]+\]", "", text)
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return "\n".join(line.strip() for line in text.split("\n")).strip()


def clamp_body(text: str) -> str:
    if len(text) <= BODY_LIMIT:
        return text
    cut = text.rfind(". ", 0, BODY_LIMIT)
    if cut < BODY_LIMIT // 2:
        cut = BODY_LIMIT
    return text[: cut + 1].rstrip() + " ..."


def pw_extracts(names: list[str]) -> dict:
    """Plain-text PsychonautWiki extracts for the given titles, cached."""
    path = os.path.join(CACHE, "pw_effects.json")
    cache = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as handle:
            cache = json.load(handle)
    pending = [name for name in names if name not in cache]
    if pending:
        print(f"Fetching {len(pending)} PsychonautWiki effect pages...")
        for start in range(0, len(pending), 20):
            chunk = pending[start : start + 20]
            # exintro is required for batched extracts: a whole-article request
            # is silently capped at one page per call.
            base_params = {
                "action": "query",
                "prop": "extracts",
                "explaintext": "1",
                "exintro": "1",
                "exlimit": "20",
                "redirects": "1",
                "format": "json",
                "titles": "|".join(chunk),
            }
            by_title = {}
            aliases = {}
            continuation = {}
            for _ in range(10):
                params = dict(base_params, **continuation)
                payload = fetch_json(f"{PW_API}?{urllib.parse.urlencode(params)}")
                query = payload.get("query", {})
                pages = query.get("pages", {})
                for page in (pages if isinstance(pages, list) else pages.values()):
                    title = page.get("title") or ""
                    extract = page.get("extract") or ""
                    if extract:
                        by_title[norm(title)] = {"title": title, "extract": extract}
                for key in ("redirects", "normalized"):
                    for entry in query.get(key, []):
                        aliases[norm(entry.get("from", ""))] = norm(entry.get("to", ""))
                continuation = payload.get("continue") or {}
                if not continuation:
                    break
            for name in chunk:
                key = norm(name)
                record = by_title.get(aliases.get(key, key))
                cache[name] = record or {"title": name, "extract": ""}
            print(f"  {min(start + 20, len(pending))}/{len(pending)}")
        os.makedirs(CACHE, exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(cache, handle, ensure_ascii=False)
    return cache


def first_sentence(text: str) -> str:
    text = text.strip().replace("\n", " ")
    match = re.search(r"(?<=[.!?])\s", text)
    return text[: match.start() + 1] if match else text[:240]


def main() -> None:
    print("=== Effect Library Pipeline ===")
    with open(SLIM_PATH, encoding="utf-8") as handle:
        slim = json.load(handle)
    app_effects = collect_app_effects(slim)
    print(f"Effect names in the substance library: {len(app_effects)}")

    index = download_effect_index()
    dosewiki = {}
    for item in index:
        name = (item.get("name") or "").strip()
        if not name:
            continue
        dosewiki[norm(name)] = item
    print(f"DoseWiki effect articles: {len(dosewiki)}")

    missing = [record["name"] for key, record in app_effects.items() if key not in dosewiki]
    pw = pw_extracts(sorted(set(missing))) if missing else {}
    print(f"Effect names without a DoseWiki article: {len(missing)}")

    articles = []
    for key, record in sorted(app_effects.items(), key=lambda kv: kv[1]["name"].lower()):
        article = build_article(record, dosewiki.get(key), pw.get(record["name"]))
        if article:
            articles.append(article)
    # DoseWiki articles the substance library never names are still worth browsing.
    for key, item in dosewiki.items():
        if key in app_effects:
            continue
        article = build_article({"name": item.get("name", ""), "categories": set()}, item, None)
        if article:
            articles.append(article)

    # A label the app knows can be a plural or a compound of a canonical effect;
    # reuse that article and record which one it is.
    by_key = {norm(article["name"]): article for article in articles}
    covered = {norm(article["name"]) for article in articles}
    for key, record in sorted(app_effects.items(), key=lambda kv: kv[1]["name"].lower()):
        if key in covered:
            continue
        for variant in name_variants(record["name"]):
            source = by_key.get(variant)
            if source:
                articles.append(dict(source, name=record["name"], matchedName=source["name"]))
                break

    articles.sort(key=lambda a: a["name"].lower())
    by_source = {}
    for article in articles:
        by_source[article["source"]] = by_source.get(article["source"], 0) + 1
    print(f"Articles: {len(articles)} {by_source}")

    payload = {
        "generatedAt": __import__("datetime").date.today().isoformat(),
        "licenses": {
            "dosewiki": "CC0 1.0 (https://dose.wiki/docs/license)",
            "psychonautwiki": "CC BY-SA 4.0 (https://psychonautwiki.org)",
        },
        "articles": articles,
    }
    text = json.dumps(payload, separators=(",", ":"), ensure_ascii=False)
    for path in OUTPUT_PATHS:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        print(f"  -> {path} ({os.path.getsize(path) / 1024:.0f} KB)")
    print("Done.")


def name_variants(name: str) -> list[str]:
    """Plural and conjunction fallbacks for labels the app uses but the sources do not name."""
    base = norm(name)
    out = []
    words = base.split()
    if words:
        last = words[-1]
        if last.endswith("ies") and len(last) > 4:
            last = last[:-3] + "y"
        elif last.endswith(("ses", "xes")):
            last = last[:-2]
        elif last.endswith("s") and not last.endswith("ss") and len(last) > 3:
            last = last[:-1]
        singular = " ".join(words[:-1] + [last])
        if singular != base:
            out.append(singular)
    for separator in (" and ", " or ", " & ", ", "):
        if separator in name.lower():
            out += [norm(part) for part in re.split(separator, name.lower()) if part.strip()]
    return out


def build_article(record: dict, dosewiki_item: dict | None, pw_record: dict | None) -> dict | None:
    name = (record.get("name") or "").strip()
    categories = [slot for slot in CATEGORY_ORDER if slot in record.get("categories", set())]
    categories += sorted(
        slot for slot in record.get("categories", set()) if slot not in CATEGORY_ORDER
    )
    if dosewiki_item:
        body = clean_markup(dosewiki_item.get("description_raw") or "")
        summary = clean_markup(dosewiki_item.get("summary") or "")
        if not body:
            body = summary
        if not summary:
            summary = first_sentence(body)
        slug = dosewiki_item.get("slug") or norm(name).replace(" ", "-")
        return {
            "name": name,
            "slug": slug,
            "categories": categories,
            "summary": summary,
            "body": clamp_body(body),
            "source": "dosewiki",
            "url": f"https://dose.wiki/effects/{slug}",
        }
    if pw_record and (pw_record.get("extract") or "").strip():
        body = clamp_body(clean_markup(pw_record["extract"]))
        return {
            "name": name,
            "slug": norm(name).replace(" ", "-"),
            "categories": categories,
            "summary": first_sentence(body),
            "body": body,
            "source": "psychonautwiki",
            "url": "https://psychonautwiki.org/wiki/" + urllib.parse.quote(name.replace(" ", "_")),
        }
    return None


if __name__ == "__main__":
    main()
