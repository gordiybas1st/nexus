import type { Task } from "./types";

export interface Parsed {
  title: string;
  deadline: string | null;
  tasks: Task[];
}

export class NexusError extends Error {}

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

export function today(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function buildPrompt(url: string): string {
  return `Открой страницу по ссылке и найди на ней информацию о мероприятии, гранте, конкурсе или задаче.
Используй ТОЛЬКО то, что реально написано на странице, ничего не выдумывай.
Сегодня ${today()}. Если год в дате не указан, выбери ближайшую будущую дату.

Верни только JSON без пояснений и без markdown:
{
  "title": "название (строка)",
  "deadline": "дедлайн в формате ГГГГ-ММ-ДД или null, если на странице его нет",
  "tasks": ["краткий шаг, что нужно сделать", "..."]
}
Если шагов на странице нет, верни пустой массив.

Ссылка: ${url}`;
}

export function extractJson(text: string): unknown {
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start === -1 || end <= start) throw new NexusError("Модель вернула ответ без JSON.");
  try {
    return JSON.parse(text.slice(start, end + 1));
  } catch {
    throw new NexusError("Не удалось разобрать JSON из ответа модели.");
  }
}

export function normalize(raw: unknown): Parsed {
  if (typeof raw !== "object" || raw === null) throw new NexusError("Пустой ответ модели.");
  const r = raw as Record<string, unknown>;

  const title = typeof r.title === "string" ? r.title.trim() : "";
  if (!title) throw new NexusError("Модель не нашла название на странице.");

  const deadline =
    typeof r.deadline === "string" && DATE_RE.test(r.deadline.trim()) ? r.deadline.trim() : null;

  const tasks = Array.isArray(r.tasks)
    ? r.tasks
        .filter((t): t is string => typeof t === "string" && t.trim() !== "")
        .map((t) => ({ text: t.trim(), done: false }))
    : [];

  return { title, deadline, tasks };
}

export async function parseLink(url: string, apiKey: string, model: string): Promise<Parsed> {
  if (!apiKey) throw new NexusError("Не задан API-ключ Gemini. Откройте настройки.");

  let res: Response;
  try {
    res = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-goog-api-key": apiKey },
        body: JSON.stringify({
          contents: [{ parts: [{ text: buildPrompt(url) }] }],
          tools: [{ url_context: {} }],
        }),
      },
    );
  } catch {
    throw new NexusError("Нет соединения с интернетом.");
  }

  if (!res.ok) {
    let detail = "";
    try {
      detail = (await res.json())?.error?.message ?? "";
    } catch {
      /* тело не JSON */
    }
    if (res.status === 400 && /api key/i.test(detail)) throw new NexusError("Неверный API-ключ.");
    if (res.status === 429) throw new NexusError("Превышен лимит запросов Gemini, попробуйте позже.");
    throw new NexusError(`Ошибка Gemini (${res.status}). ${detail}`.trim());
  }

  const data = await res.json();
  const parts: { text?: string }[] = data?.candidates?.[0]?.content?.parts ?? [];
  const text = parts.map((p) => p.text ?? "").join("");
  if (!text) throw new NexusError("Модель не вернула ответ (возможно, страница недоступна).");

  return normalize(extractJson(text));
}
