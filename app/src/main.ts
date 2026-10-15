import "./style.css";
import type { Item, Settings } from "./types";
import { loadItems, saveItems, loadSettings, saveSettings, DEFAULT_MODEL } from "./store";
import { parseLink, NexusError, today } from "./gemini";
import { syncReminders, requestPermission } from "./notify";
import { listenForShares, findUrl } from "./share";

let items: Item[] = loadItems().map((i) =>
  i.state === "loading" ? { ...i, state: "error" as const, error: "Прервано" } : i,
);
let settings: Settings = loadSettings();
let view: "list" | "settings" = settings.apiKey ? "list" : "settings";
let message = "";

const root = document.getElementById("app")!;

const ESC: Record<string, string> = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ESC[c]);

function daysLeft(deadline: string): number {
  const [y, m, d] = deadline.split("-").map(Number);
  const [ty, tm, td] = today().split("-").map(Number);
  return Math.round((Date.UTC(y, m - 1, d) - Date.UTC(ty, tm - 1, td)) / 86400000);
}

function badge(deadline: string | null): { text: string; cls: string } {
  if (!deadline) return { text: "без даты", cls: "none" };
  const n = daysLeft(deadline);
  if (n < 0) return { text: `просрочено ${-n} дн.`, cls: "past" };
  if (n === 0) return { text: "сегодня", cls: "hot" };
  return { text: `${n} дн.`, cls: n <= 3 ? "hot" : n <= 7 ? "warn" : "ok" };
}

function sorted(): Item[] {
  const group = (i: Item) => {
    const finished = i.tasks.length > 0 && i.tasks.every((t) => t.done);
    const past = i.deadline ? daysLeft(i.deadline) < 0 : false;
    return finished || past ? 2 : i.deadline ? 0 : 1;
  };
  return [...items].sort(
    (a, b) =>
      group(a) - group(b) ||
      (a.deadline ?? "9999-99-99").localeCompare(b.deadline ?? "9999-99-99") ||
      b.addedAt - a.addedAt,
  );
}

function persist(): void {
  saveItems(items);
  void syncReminders(items);
}

function renderItem(it: Item): string {
  if (it.state === "loading") {
    return `<article class="card"><p class="muted">Читаю страницу…</p><p class="url">${esc(it.url)}</p></article>`;
  }
  if (it.state === "error") {
    return `<article class="card err">
      <p>${esc(it.error ?? "Ошибка")}</p><p class="url">${esc(it.url)}</p>
      <div class="row"><button data-act="retry" data-id="${it.id}">Повторить</button>
      <button class="ghost" data-act="del" data-id="${it.id}">Удалить</button></div></article>`;
  }
  const b = badge(it.deadline);
  const done = it.tasks.filter((t) => t.done).length;
  const pct = it.tasks.length ? Math.round((done / it.tasks.length) * 100) : 0;
  const tasks = it.tasks
    .map(
      (t, idx) =>
        `<label class="task"><input type="checkbox" data-act="toggle" data-id="${it.id}" data-idx="${idx}" ${t.done ? "checked" : ""}/><span class="${t.done ? "done" : ""}">${esc(t.text)}</span></label>`,
    )
    .join("");
  return `<article class="card">
    <header><h3>${esc(it.title)}</h3><span class="badge ${b.cls}">${b.text}</span></header>
    <p class="muted">${it.deadline ? "до " + it.deadline : "дедлайн не найден"}</p>
    ${
      it.tasks.length
        ? `<div class="bar"><i style="width:${pct}%"></i></div><div class="tasks">${tasks}</div>`
        : `<p class="muted">Шагов на странице нет</p>`
    }
    <div class="row"><a href="${esc(it.url)}" target="_blank" rel="noopener">Открыть страницу</a>
    <button class="ghost" data-act="retry" data-id="${it.id}">Обновить</button>
    <button class="ghost" data-act="del" data-id="${it.id}">Удалить</button></div></article>`;
}

function render(): void {
  if (view === "settings") {
    root.innerHTML = `<main>
      <h1>Настройки</h1>
      <p class="muted">Ключ хранится только на этом устройстве. Бесплатный ключ: aistudio.google.com/apikey</p>
      <label>API-ключ Gemini<input id="key" type="password" autocomplete="off" value="${esc(settings.apiKey)}"/></label>
      <label>Модель<input id="model" value="${esc(settings.model)}"/></label>
      <div class="row"><button id="save">Сохранить</button>${settings.apiKey ? `<button class="ghost" id="back">Назад</button>` : ""}</div>
      <p class="msg">${esc(message)}</p></main>`;
    return;
  }
  const list = sorted();
  root.innerHTML = `<main>
    <header class="top"><h1>Nexus</h1><button class="ghost" id="gear" aria-label="Настройки">⚙</button></header>
    <form id="add"><input id="url" type="url" inputmode="url" placeholder="Вставьте ссылку" required/><button>Добавить</button></form>
    <p class="msg">${esc(message)}</p>
    ${
      list.length
        ? list.map(renderItem).join("")
        : `<p class="muted center">Пока пусто. Вставьте ссылку на грант или конкурс либо нажмите «Поделиться» в браузере и выберите Nexus.</p>`
    }
  </main>`;
}

async function process(it: Item): Promise<void> {
  it.state = "loading";
  it.error = undefined;
  render();
  try {
    const p = await parseLink(it.url, settings.apiKey, settings.model || DEFAULT_MODEL);
    const prevDone = new Set(it.tasks.filter((t) => t.done).map((t) => t.text));
    it.title = p.title;
    it.deadline = p.deadline;
    it.tasks = p.tasks.map((t) => ({ text: t.text, done: prevDone.has(t.text) }));
    it.state = "ok";
  } catch (e) {
    it.state = "error";
    it.error = e instanceof NexusError ? e.message : "Неожиданная ошибка";
  }
  persist();
  render();
}

function addUrl(raw: string): void {
  const url = findUrl(raw) ?? raw.trim();
  if (!/^https?:\/\//i.test(url)) {
    message = "Нужна ссылка, начинающаяся с http:// или https://";
    render();
    return;
  }
  message = "";
  view = "list";
  const existing = items.find((i) => i.url === url);
  if (existing) {
    void process(existing);
    return;
  }
  const it: Item = {
    id: crypto.randomUUID(),
    url,
    title: url,
    deadline: null,
    tasks: [],
    addedAt: Date.now(),
    state: "loading",
  };
  items.unshift(it);
  void process(it);
}

root.addEventListener("submit", (e) => {
  if ((e.target as HTMLElement).id !== "add") return;
  e.preventDefault();
  addUrl((document.getElementById("url") as HTMLInputElement).value);
});

root.addEventListener("click", (e) => {
  const el = (e.target as HTMLElement).closest<HTMLElement>("[id],[data-act]");
  if (!el) return;
  const { act, id } = el.dataset;
  if (el.id === "gear") {
    view = "settings";
    message = "";
    render();
    return;
  }
  if (el.id === "back") {
    view = "list";
    message = "";
    render();
    return;
  }
  if (el.id === "save") {
    settings = {
      apiKey: (document.getElementById("key") as HTMLInputElement).value.trim(),
      model: (document.getElementById("model") as HTMLInputElement).value.trim() || DEFAULT_MODEL,
    };
    saveSettings(settings);
    if (!settings.apiKey) {
      message = "Введите ключ";
      render();
      return;
    }
    view = "list";
    message = "";
    void requestPermission();
    render();
    return;
  }
  const it = items.find((i) => i.id === id);
  if (!it) return;
  if (act === "del") {
    items = items.filter((i) => i !== it);
    persist();
    render();
  }
  if (act === "retry") void process(it);
});

root.addEventListener("change", (e) => {
  const el = e.target as HTMLInputElement;
  if (el.dataset.act !== "toggle") return;
  const t = items.find((i) => i.id === el.dataset.id)?.tasks[Number(el.dataset.idx)];
  if (!t) return;
  t.done = el.checked;
  persist();
  render();
});

render();
void syncReminders(items);
listenForShares(addUrl);
