import os
import google.generativeai as genai
from google.api_core.exceptions import GoogleAPICallError

def get_api_key():
    return os.environ.get("GOOGLE_API_KEY")


def main() -> int:
    api_key = get_api_key()
    if not api_key:
        print("Ошибка: API-ключ не задан. Установите переменную окружения GOOGLE_API_KEY.")
        return 1

    genai.configure(api_key=api_key)

    try:
        models_found = False
        for m in genai.list_models():
            methods = getattr(m, "supported_generation_methods", None)
            if methods and "generateContent" in methods:
                print(getattr(m, "name", "<unknown model>"))
                models_found = True

        if not models_found:
            print("Не найдено моделей с поддержкой generateContent.")

    except GoogleAPICallError as exc:
        print("Ошибка Google API:", exc)
        return 1
    except Exception as exc:
        print("Неожиданная ошибка:", exc)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())