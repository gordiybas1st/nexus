import os
import sys
import json
from html.parser import HTMLParser
from urllib.parse import urlparse

import requests
import google.generativeai as genai
from google.api_core import exceptions

DEFAULT_MODEL_NAME = os.environ.get("GENAI_MODEL", "gemini-1.5-flash-002")
MAX_PAGE_CHARS = 30000
REQUEST_TIMEOUT = 15


class NexusError(Exception):
    pass


def get_api_key():
    api_key = os.environ.get("GOOGLE_API_KEY")
    if not api_key:
        raise NexusError("API-ключ не задан. Установите переменную окружения GOOGLE_API_KEY.")
    return api_key


def configure_model():
    genai.configure(api_key=get_api_key())
    return genai.GenerativeModel(
        DEFAULT_MODEL_NAME,
        generation_config={"response_mime_type": "application/json"},
    )


class _TextExtractor(HTMLParser):
    SKIP = {"script", "style", "noscript", "svg", "head"}

    def __init__(self):
        super().__init__()
        self.parts = []
        self._skip_depth = 0

    def handle_starttag(self, tag, attrs):
        if tag in self.SKIP:
            self._skip_depth += 1

    def handle_endtag(self, tag):
        if tag in self.SKIP and self._skip_depth:
            self._skip_depth -= 1

    def handle_data(self, data):
        if not self._skip_depth:
            data = data.strip()
            if data:
                self.parts.append(data)


def fetch_page_text(url):
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        raise NexusError(f"Некорректный URL: {url}")

    try:
        resp = requests.get(
            url,
            timeout=REQUEST_TIMEOUT,
            headers={"User-Agent": "Mozilla/5.0 (Nexus parser)"},
        )
        resp.raise_for_status()
    except requests.RequestException as exc:
        raise NexusError(f"Не удалось загрузить страницу: {exc}") from exc

    extractor = _TextExtractor()
    extractor.feed(resp.text)
    text = "\n".join(extractor.parts)
    if not text:
        raise NexusError("На странице не найден текст (возможно, она рисуется через JavaScript).")
    return text[:MAX_PAGE_CHARS]


def nexus_parser(url):
    """Возвращает dict с полями title, deadline (ГГГГ-ММ-ДД или null), tasks (список)."""
    page_text = fetch_page_text(url)
    model = configure_model()

    prompt = f"""
Ниже текст веб-страницы. Используй ТОЛЬКО его, ничего не выдумывай.
Если какого-то данного на странице нет, ставь null (для tasks — пустой список).

Верни JSON с полями:
  "title": название мероприятия/задачи (строка),
  "deadline": дата дедлайна в формате ГГГГ-ММ-ДД (строка),
  "tasks": краткий список того, что нужно сделать (массив строк).

URL: {url}

ТЕКСТ СТРАНИЦЫ:
{page_text}
"""

    try:
        response = model.generate_content(prompt)
        return json.loads(response.text)
    except json.JSONDecodeError as exc:
        raise NexusError(f"Модель вернула не-JSON: {exc}") from exc
    except exceptions.GoogleAPICallError as exc:
        raise NexusError(f"Ошибка Google API: {exc}") from exc
    except ValueError as exc:
        # response.text бросает ValueError, если ответ заблокирован фильтрами
        raise NexusError(f"Модель не вернула ответ: {exc}") from exc


if __name__ == "__main__":
    link = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("NEXUS_LINK")
    if not link:
        sys.exit("Использование: python Nexus_Logic.py <URL>")
    try:
        print(json.dumps(nexus_parser(link), ensure_ascii=False, indent=2))
    except NexusError as exc:
        sys.exit(f"Ошибка: {exc}")
