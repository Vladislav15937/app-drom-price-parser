# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## О проекте

**drom-price-parser** — Spring Boot приложение для анализа конкурентных цен на автозапчасти с baza.drom.ru. Парсит объявления по OEM-номеру через Playwright, классифицирует конкурентов через DeepSeek LLM и рекомендует конкурентную цену.

## Сборка и запуск

```bash
mvn compile
mvn spring-boot:run
```

- Swagger UI: http://localhost:8081/swagger-ui.html
- Порт: 8081
- Требуется Java 22

## Архитектура

```
PriceAggregatorController  (REST)
        ↓
PriceAnalyzer              (оркестрация + статистика)
    ├─ DromParser           (парсинг через Playwright)
    └─ AIPriceAdvisor       (vision-агент + классификатор + математика цен)
```

### DromParser
- Headless Chromium через Microsoft Playwright
- Один браузер на всё приложение (`@PostConstruct`/`@PreDestroy`), отдельный `BrowserContext` на каждый запрос
- Антибот: фиксированный User-Agent на весь сеанс (выбирается случайно из 5 при запуске), скрытие `navigator.webdriver`, задержки
- `parseMyListing(url)` → `MyListingInfo` (моё объявление: описание, дата, фото, цена)
- `parseParts(oem, region)` → до 10 объявлений конкурентов в регионе
- `findMyListingUrl(oem, region, company)` → URL моего объявления по имени компании на странице поиска (без захода на детальные страницы)
- `parseCityWithMyListing(oem, region, limit, company)` → `CityParseResult(myListingUrl, competitors)` за ОДНУ загрузку страницы поиска (используется в `analyzeFromCatalog` вместо `findMyListingUrl`+`parseParts`). Общие шаги вынесены в `loadSearchEntries`/`processSearchEntries`.
- Ускорение: контекст блокирует загрузку картинок/CSS/шрифтов/медиа (`ctx.route`); фото берём из HTML-атрибутов
- Извлечение фото: `data-image-info` JSON атрибут → `static.baza.drom.ru` img → og:image. Фото на baza.drom.ru **без расширений** в URL — не фильтровать по `*.jpg`.
- Дата публикации: селектор `.viewbull-actual-date`
- При CAPTCHA в headless-режиме возвращает пустой список; в GUI-режиме ждёт ручного решения 120 с
- **Предохранитель капчи**: после `CAPTCHA_BLOCK_THRESHOLD` (3) нерешённых капч подряд `isCaptchaBlocked()` → `true`, и батч в контроллере останавливается с сообщением «drom показывает нерешаемую капчу». Любая успешно загруженная страница (`proxyOk()`) сбрасывает счётчик. Когда блок признан, `waitForManualCaptcha` больше не ждёт 120 с впустую.
- **Навигация**: `navigateWithRetry` — тайм-аут `NAV_TIMEOUT_MS` (30 с) вместо 90 с. `ERR_TUNNEL_CONNECTION_FAILED` (мобильный прокси «мигает») повторяется до 3 раз с паузой 1.5/3 с — поглощает кратковременные провалы IP; дорогой тайм-аут повторяется 1 раз. Долгий сбой прокси взведёт предохранитель.

### AIPriceAdvisor
Текстовый агент: конфиг `deepseek.api.base-url`, модель — `deepseek.text.model`.

**Vision-оценка фото** — активна при непустом `gemini.api.key`. Использует модель `gemini.vision.model` через `gemini.api.base-url`. При пустом ключе всегда возвращает нейтральный коэф. 0.80 без обращения к API.

**Классификатор** (`classifyCompetitors`): сравнивает конкурентов с моим товаром, делит на `similar/better/worse`. Принимает только узлы в сборе — ремкомплекты и компоненты уже отфильтрованы в `PriceAnalyzer.filterAssemblies()`.

**Математика** (`computeCompetitivePrice` → `strategyForMarket`). Единый рынок = 5 дешёвых Барнаул + 5 дешёвых НСК. Цель — попасть в рынок и продать (без надбавок над рынком):
- `similar` есть, мы лучше большинства (`worse > better`) → **медиана аналогов** (НА рынке, без надбавки)
- `similar` есть, иначе → **медиана аналогов × 0.95** (−5%, уклон в быструю продажу), не ниже `min(similar)`
- Только `worse` (аналогов нет) → `min(worse) × 1.20`
- Только `better` (аналогов нет) → `min(better) × 0.80`
- Аналогов/конкурентов нет → цена не меняется
- Смешанный рынок (есть better и worse, нет similar) → `медиана × (1 ± 0.15·net)`
- Границы (`applyBounds`): потолок = `max` рынка; если мы уже на минимуме — не подрезаем себя ниже; пол = `min × 0.80`
- Тонкие данные: если сопоставимых (`priceBasisCount`) < `MIN_RELIABLE_COMPETITORS` (3) → движение ≤ ±10% от текущей, уверенность «низкая»
- Фото-дисконт: если коэф. моего фото < 0.70 → масштабируем цену пропорционально
- Кэш классификации по набору конкурентов (TTL 30 мин) → один и тот же набор даёт одну цену (детерминизм)

### Ключевые DTO
- `MyListingInfo` — моё объявление (title, description, condition, manufacturer, oem, city, publishedDate, photoUrls, price)
- `PartPrice` — объявление конкурента (price, url, location, dealer, publishedDate, photoUrls, description)
- `AggregationResult` — итог: статистика (min/max/avg/median), cityCompetitorCount, siberiaCompetitorCount, recommendedPrice, aiReason, photoNote
- `DromListingItem`, `RunActorRequest`, `RunResponse` — DTO для Apify-интеграции (`apify.enabled: false` по умолчанию)

### Логика Сибири (PriceAnalyzer)
- `PriceAnalyzer.CITY_ANALOG_THRESHOLD = 10` — если в городе меньше → ЗАПРАШИВАЕМ данные по НСК
- `AIPriceAdvisor.MIN_CITY_COMPETITORS = 3` — если в городе меньше → ИСПОЛЬЗУЕМ НСК как ценовой ориентир
- Фоллбэк-регион: `novosibirsk` (`FALLBACK_REGION`)
- `isOlderThan6Months()` — парсит русские форматы дат: "вчера"/"сегодня"/"назад" → false; "15 ноября 2024" и "dd.MM.yyyy" → сравниваем с порогом

## Конфигурация (`application.yml`)

| Параметр | Назначение |
|---|---|
| `drom.headless` | `false` — видимый браузер для отладки, `true` для продакшена |
| `deepseek.api.token` | Ключ DeepSeek API |
| `deepseek.api.base-url` | URL API (default: `https://api.deepseek.com/v1/chat/completions`) |
| `deepseek.text.model` | Текстовая модель (default: `deepseek-v4-flash`) |
| `gemini.api.key` | Ключ Gemini для vision-оценки фото (если пусто — vision отключён) |
| `gemini.api.base-url` | URL Gemini API |
| `gemini.vision.model` | Модель для vision (default: `gemini-2.0-flash`) |
| `drom.proxy.url` | Прокси для парсера (опционально, `http://` или `socks5://`) |
| `twocaptcha.api-key` | Ключ 2captcha для автоматического решения капчи |
| `twocaptcha.enabled` | Включить автоматическое решение капчи |
| `apify.enabled` | Использовать Apify вместо Playwright (default: `false`) |

## Точки расширения

- **Порог мало конкурентов** → `PriceAnalyzer.CITY_ANALOG_THRESHOLD`
- **Фильтр нецелевых товаров** → `PriceAnalyzer.NON_ASSEMBLY_KEYWORDS`
- **Процент подрезания** → `0.97` в `AIPriceAdvisor.strategyForMarket()`
- **Лимит объявлений на регион** → `parseParts(oemNumber, region, 10)` в `parseParts()`
- **CSS-селекторы парсера** → `DromParser.extractPartFromPage()` и `parseMyListing()`
